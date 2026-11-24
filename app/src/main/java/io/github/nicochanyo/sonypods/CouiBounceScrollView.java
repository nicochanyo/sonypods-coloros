package io.github.nicochanyo.sonypods;

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.OverScroller;
import android.widget.ScrollView;

/**
 * ColorOS-style {@link ScrollView} featuring smooth rubber-band top/bottom overscroll
 * translation without Android 12+ stretch distortion, plus critically-damped analytical
 * spring-back and boundary impact bounce.
 */
public class CouiBounceScrollView extends ScrollView {
    private static final float RUBBER_DAMP_COEFF = 0.42f;
    private static final float MAX_STRETCH_RATIO = 0.34f;
    private static final float SPRING_OMEGA = 16.5f; // rad/s (critically damped, ~370ms ColorOS settle)
    private static final float WALL_IMPACT_VEL_SCALE = 0.32f;
    private static final float SETTLE_THRESHOLD_PX = 0.4f;

    public interface OnOverscrollListener {
        void onOverscrollChanged(float translationY);
    }

    private final int touchSlop;
    private final int minFlingVel;
    private final int maxFlingVel;
    private final OverScroller scroller;

    private OnOverscrollListener overscrollListener;
    private VelocityTracker velocityTracker;

    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private float downX;
    private float downY;
    private float lastY;
    private float scrollFraction;
    private boolean isDragging;
    private boolean disallowParentOnDown;

    private float overscrollRawY;
    private float overscrollVisualY;
    private boolean overscrollHapticArmed = true;

    // Analytical critically damped spring state: x(t) = exp(-w*t) * (x0 + (v0 + w*x0)*t)
    private boolean springRunning;
    private long springStartMs;
    private float springX0;
    private float springV0;

    private boolean flingRunning;
    private int lastFlingY;

    private final Runnable springFrameRunnable = this::stepSpringFrame;
    private final Runnable flingFrameRunnable = this::stepFlingFrame;

    public CouiBounceScrollView(Context context) {
        this(context, null);
    }

    public CouiBounceScrollView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CouiBounceScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOverScrollMode(OVER_SCROLL_NEVER);
        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        minFlingVel = vc.getScaledMinimumFlingVelocity();
        maxFlingVel = vc.getScaledMaximumFlingVelocity();
        scroller = new OverScroller(context);
    }

    public void setOnOverscrollListener(OnOverscrollListener listener) {
        this.overscrollListener = listener;
    }

    public void setDisallowParentOnDown(boolean disallow) {
        this.disallowParentOnDown = disallow;
    }

    private void requestDisallowAllParents(boolean disallow) {
        ViewParent p = getParent();
        while (p != null) {
            p.requestDisallowInterceptTouchEvent(disallow);
            p = p.getParent();
        }
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
        if (!disallowIntercept && disallowParentOnDown) {
            requestDisallowAllParents(true);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimations();
        recycleVelocityTracker();
        super.onDetachedFromWindow();
    }

    @Override
    public void fling(int velocityY) {
        startFling(velocityY);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        final int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE && isDragging) {
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                activePointerId = ev.getPointerId(0);
                downX = ev.getX();
                downY = ev.getY();
                lastY = downY;
                scrollFraction = 0f;
                initOrResetVelocityTracker();
                velocityTracker.addMovement(ev);
                if (disallowParentOnDown) {
                    requestDisallowAllParents(true);
                }

                boolean wasBouncing = springRunning && Math.abs(overscrollVisualY) > 1.0f;
                stopAnimations();
                if (wasBouncing) {
                    overscrollRawY = inverseRubberBand(overscrollVisualY);
                    isDragging = true;
                    return true;
                }
                isDragging = false;
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
                    return false;
                }
                int idx = ev.findPointerIndex(activePointerId);
                if (idx < 0) {
                    return false;
                }
                float x = ev.getX(idx);
                float y = ev.getY(idx);
                float dy = Math.abs(y - downY);
                float dx = Math.abs(x - downX);
                if (dy > touchSlop && dy > dx * 0.55f) {
                    isDragging = true;
                    lastY = y;
                    initVelocityTrackerIfNotExists();
                    velocityTracker.addMovement(ev);
                    requestDisallowAllParents(true);
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                scrollFraction = 0f;
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                recycleVelocityTracker();
                if (Math.abs(overscrollVisualY) > SETTLE_THRESHOLD_PX) {
                    startSpring(overscrollVisualY, 0f);
                }
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        initVelocityTrackerIfNotExists();
        velocityTracker.addMovement(ev);

        final int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                if (getChildCount() == 0) {
                    return false;
                }
                activePointerId = ev.getPointerId(0);
                downX = ev.getX();
                downY = ev.getY();
                lastY = downY;
                scrollFraction = 0f;
                if (disallowParentOnDown) {
                    requestDisallowAllParents(true);
                }
                stopAnimations();
                overscrollRawY = Math.abs(overscrollVisualY) > SETTLE_THRESHOLD_PX
                        ? inverseRubberBand(overscrollVisualY) : 0f;
                isDragging = Math.abs(overscrollVisualY) > SETTLE_THRESHOLD_PX;
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = ev.getActionIndex();
                activePointerId = ev.getPointerId(index);
                downX = ev.getX(index);
                downY = ev.getY(index);
                lastY = downY;
                scrollFraction = 0f;
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                int idx = ev.findPointerIndex(activePointerId);
                if (idx < 0) {
                    idx = 0;
                    activePointerId = ev.getPointerId(0);
                }
                float x = ev.getX(idx);
                float y = ev.getY(idx);
                if (!isDragging) {
                    float dy = Math.abs(y - downY);
                    float dx = Math.abs(x - downX);
                    if (dy > touchSlop && dy > dx * 0.55f) {
                        isDragging = true;
                        lastY = y;
                        requestDisallowAllParents(true);
                    }
                    return true;
                }
                float deltaY = lastY - y;
                lastY = y;
                applyVerticalDragDelta(deltaY);
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                onSecondaryPointerUp(ev);
                int idx = ev.findPointerIndex(activePointerId);
                if (idx >= 0) {
                    lastY = ev.getY(idx);
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                float vy = 0f;
                if (velocityTracker != null && activePointerId != MotionEvent.INVALID_POINTER_ID) {
                    velocityTracker.computeCurrentVelocity(1000, maxFlingVel);
                    vy = velocityTracker.getYVelocity(activePointerId);
                }
                recycleVelocityTracker();
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                boolean wasDragging = isDragging;
                isDragging = false;

                if (Math.abs(overscrollVisualY) > SETTLE_THRESHOLD_PX) {
                    float maxInitVel = 2400f * getResources().getDisplayMetrics().density;
                    float springVel = Math.max(-maxInitVel, Math.min(maxInitVel, vy * 0.45f));
                    if (overscrollVisualY > 0f && springVel < 0f) {
                        springVel = Math.max(springVel, -SPRING_OMEGA * overscrollVisualY * 0.75f);
                    } else if (overscrollVisualY < 0f && springVel > 0f) {
                        springVel = Math.min(springVel, -SPRING_OMEGA * overscrollVisualY * 0.75f);
                    }
                    startSpring(overscrollVisualY, springVel);
                } else if (wasDragging && Math.abs(vy) >= minFlingVel) {
                    startFling(-Math.round(vy));
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                recycleVelocityTracker();
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                isDragging = false;
                if (Math.abs(overscrollVisualY) > SETTLE_THRESHOLD_PX) {
                    startSpring(overscrollVisualY, 0f);
                }
                return true;
            }
        }
        return true;
    }

    private void onSecondaryPointerUp(MotionEvent ev) {
        int pointerIndex = ev.getActionIndex();
        int pointerId = ev.getPointerId(pointerIndex);
        if (pointerId == activePointerId) {
            int newPointerIndex = pointerIndex == 0 ? 1 : 0;
            activePointerId = ev.getPointerId(newPointerIndex);
            lastY = ev.getY(newPointerIndex);
            downY = lastY;
            downX = ev.getX(newPointerIndex);
            if (velocityTracker != null) {
                velocityTracker.clear();
            }
        }
    }

    private void applyVerticalDragDelta(float deltaY) {
        float rem = deltaY; // >0 = move content up (scroll down / bottom pull), <0 = move content down (scroll up / top pull)
        if (overscrollVisualY > 0f) {
            if (rem < 0f) {
                overscrollRawY += (-rem);
                rem = 0f;
            } else if (rem <= overscrollRawY) {
                overscrollRawY -= rem;
                rem = 0f;
            } else {
                rem -= overscrollRawY;
                overscrollRawY = 0f;
            }
            setOverscrollVisualY(overscrollRawY > 0f ? rubberBand(overscrollRawY) : 0f);
        } else if (overscrollVisualY < 0f) {
            if (rem > 0f) {
                overscrollRawY -= rem;
                rem = 0f;
            } else {
                float absRem = -rem;
                float absRaw = -overscrollRawY;
                if (absRem <= absRaw) {
                    overscrollRawY += absRem;
                    rem = 0f;
                } else {
                    rem = -(absRem - absRaw);
                    overscrollRawY = 0f;
                }
            }
            setOverscrollVisualY(overscrollRawY < 0f ? -rubberBand(-overscrollRawY) : 0f);
        }

        if (overscrollVisualY == 0f && rem != 0f) {
            int curY = getScrollY();
            int maxY = getMaxScrollY();
            if (curY <= 0 && rem < 0f) {
                scrollFraction = 0f;
                overscrollRawY = -rem;
                setOverscrollVisualY(rubberBand(overscrollRawY));
                return;
            }
            if (curY >= maxY && rem > 0f) {
                scrollFraction = 0f;
                overscrollRawY = -rem;
                setOverscrollVisualY(-rubberBand(-overscrollRawY));
                return;
            }
            scrollFraction += rem;
            int intDelta = (int) scrollFraction;
            if (intDelta == 0) {
                return;
            }
            scrollFraction -= intDelta;
            int targetY = curY + intDelta;
            if (targetY < 0) {
                if (curY != 0) {
                    scrollTo(getScrollX(), 0);
                }
                scrollFraction = 0f;
                overscrollRawY = -targetY;
                setOverscrollVisualY(rubberBand(overscrollRawY));
            } else if (targetY > maxY) {
                if (curY != maxY) {
                    scrollTo(getScrollX(), maxY);
                }
                scrollFraction = 0f;
                overscrollRawY = -(targetY - maxY);
                setOverscrollVisualY(-rubberBand(-overscrollRawY));
            } else {
                scrollTo(getScrollX(), targetY);
            }
        }
    }

    private void startFling(int velocityY) {
        stopAnimations();
        if (getChildCount() == 0) {
            return;
        }
        int curY = getScrollY();
        int maxY = getMaxScrollY();
        if ((curY <= 0 && velocityY < 0) || (curY >= maxY && velocityY > 0)) {
            // Already at wall: immediately launch wall-impact bounce
            float maxVel = 2400f * getResources().getDisplayMetrics().density;
            float v0 = Math.max(-maxVel, Math.min(maxVel, -velocityY * WALL_IMPACT_VEL_SCALE));
            startSpring(0f, v0);
            return;
        }
        int unboundedSpan = Math.round(18000f * getResources().getDisplayMetrics().density);
        lastFlingY = curY;
        flingRunning = true;
        scroller.fling(getScrollX(), curY, 0, velocityY,
                0, 0, -unboundedSpan, maxY + unboundedSpan);
        postOnAnimation(flingFrameRunnable);
    }

    private void stepFlingFrame() {
        if (!flingRunning) {
            return;
        }
        if (!scroller.computeScrollOffset()) {
            flingRunning = false;
            return;
        }
        int newY = scroller.getCurrY();
        int maxY = getMaxScrollY();
        if (newY < 0) {
            float speed = Math.abs(scroller.getCurrVelocity());
            scrollTo(getScrollX(), 0);
            scroller.abortAnimation();
            flingRunning = false;
            float maxVel = 2400f * getResources().getDisplayMetrics().density;
            float v0 = Math.min(maxVel, speed * WALL_IMPACT_VEL_SCALE);
            if (v0 > 40f) {
                startSpring(0f, v0);
            }
            return;
        }
        if (newY > maxY) {
            float speed = Math.abs(scroller.getCurrVelocity());
            scrollTo(getScrollX(), maxY);
            scroller.abortAnimation();
            flingRunning = false;
            float maxVel = 2400f * getResources().getDisplayMetrics().density;
            float v0 = -Math.min(maxVel, speed * WALL_IMPACT_VEL_SCALE);
            if (Math.abs(v0) > 40f) {
                startSpring(0f, v0);
            }
            return;
        }
        if (newY != lastFlingY) {
            lastFlingY = newY;
            scrollTo(getScrollX(), newY);
        }
        if (!scroller.isFinished()) {
            postOnAnimation(flingFrameRunnable);
        } else {
            flingRunning = false;
        }
    }

    private void startSpring(float x0, float v0) {
        stopAnimations();
        if (Math.abs(x0) <= SETTLE_THRESHOLD_PX && Math.abs(v0) <= 40f) {
            overscrollRawY = 0f;
            setOverscrollVisualY(0f);
            return;
        }
        springX0 = x0;
        springV0 = v0;
        springStartMs = SystemClock.uptimeMillis();
        springRunning = true;
        postOnAnimation(springFrameRunnable);
    }

    private void stepSpringFrame() {
        if (!springRunning) {
            return;
        }
        float t = Math.max(0f, (SystemClock.uptimeMillis() - springStartMs) / 1000f);
        float decay = (float) Math.exp(-SPRING_OMEGA * t);
        float x = decay * (springX0 + (springV0 + SPRING_OMEGA * springX0) * t);
        float v = decay * (springV0 - SPRING_OMEGA * (springV0 + SPRING_OMEGA * springX0) * t);

        if ((t > 0.04f && Math.abs(x) <= SETTLE_THRESHOLD_PX && Math.abs(v) <= 25f) || t > 1.2f) {
            springRunning = false;
            overscrollRawY = 0f;
            setOverscrollVisualY(0f);
            return;
        }
        setOverscrollVisualY(x);
        postOnAnimation(springFrameRunnable);
    }

    private void stopAnimations() {
        removeCallbacks(springFrameRunnable);
        removeCallbacks(flingFrameRunnable);
        springRunning = false;
        if (flingRunning) {
            flingRunning = false;
            scroller.abortAnimation();
        }
    }

    private void setOverscrollVisualY(float visualY) {
        overscrollVisualY = visualY;
        float d = getResources().getDisplayMetrics().density;
        float absY = Math.abs(visualY);
        if (absY <= 2f * d) {
            overscrollHapticArmed = true;
        } else if (isDragging && overscrollHapticArmed && absY > 6f * d
                && ConfigStore.isLivingFlowUi(getContext())) {
            overscrollHapticArmed = false;
            ColorOsLivingFlowEngine.performHaptic(this, ColorOsLivingFlowEngine.HapticKind.TICK);
        }
        View child = getChildCount() > 0 ? getChildAt(0) : null;
        if (child != null) {
            child.setTranslationY(visualY);
        }
        if (overscrollListener != null) {
            overscrollListener.onOverscrollChanged(visualY);
        }
    }

    private int getMaxScrollY() {
        View child = getChildCount() > 0 ? getChildAt(0) : null;
        if (child == null) {
            return 0;
        }
        int avail = Math.max(0, getHeight() - getPaddingTop() - getPaddingBottom());
        return Math.max(0, child.getHeight() - avail);
    }

    private float getMaxStretchPx() {
        float minCap = 160f * getResources().getDisplayMetrics().density;
        return Math.max(minCap, getHeight() * MAX_STRETCH_RATIO);
    }

    private float rubberBand(float absRaw) {
        if (absRaw <= 0f) return 0f;
        float max = getMaxStretchPx();
        return (absRaw * max * RUBBER_DAMP_COEFF) / (absRaw * RUBBER_DAMP_COEFF + max);
    }

    private float inverseRubberBand(float visual) {
        float absV = Math.abs(visual);
        if (absV <= 0f) return 0f;
        float max = getMaxStretchPx();
        float clamped = Math.min(absV, max * 0.96f);
        float absRaw = (clamped * max) / (RUBBER_DAMP_COEFF * (max - clamped));
        return visual >= 0f ? absRaw : -absRaw;
    }

    private void initOrResetVelocityTracker() {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        } else {
            velocityTracker.clear();
        }
    }

    private void initVelocityTrackerIfNotExists() {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
    }

    private void recycleVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }
}
