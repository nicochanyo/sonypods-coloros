package io.github.nicochanyo.sonypods;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Root container that notifies a sibling {@link TopGradientBlurView} whenever
 * the underlying scrollable content invalidates, without triggering draw loops.
 */
public class ProgressiveBlurFrameLayout extends FrameLayout {
    private int blurTargetId = View.NO_ID;
    private int blurOverlayId = View.NO_ID;
    private View cachedTarget;
    private TopGradientBlurView cachedOverlay;

    public ProgressiveBlurFrameLayout(Context context) {
        super(context);
    }

    public ProgressiveBlurFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ProgressiveBlurFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void bindProgressiveBlur(int targetViewId, int overlayViewId) {
        this.blurTargetId = targetViewId;
        this.blurOverlayId = overlayViewId;
        this.cachedTarget = findViewById(targetViewId);
        View overlay = findViewById(overlayViewId);
        this.cachedOverlay = overlay instanceof TopGradientBlurView ? (TopGradientBlurView) overlay : null;
        if (cachedOverlay != null && cachedTarget != null) {
            cachedOverlay.setBlurTargetView(cachedTarget);
        }
    }

    public void bindProgressiveBlur(View target, TopGradientBlurView overlay) {
        this.blurTargetId = View.NO_ID;
        this.blurOverlayId = View.NO_ID;
        this.cachedTarget = target;
        this.cachedOverlay = overlay;
        if (overlay != null && target != null) {
            overlay.setBlurTargetView(target);
        }
    }

    @Override
    public void onDescendantInvalidated(View child, View target) {
        super.onDescendantInvalidated(child, target);
        if (cachedTarget == null && blurTargetId == View.NO_ID) {
            return;
        }
        if (cachedTarget == null && blurTargetId != View.NO_ID) {
            cachedTarget = findViewById(blurTargetId);
        }
        if (cachedOverlay == null && blurOverlayId != View.NO_ID) {
            View overlay = findViewById(blurOverlayId);
            cachedOverlay = overlay instanceof TopGradientBlurView ? (TopGradientBlurView) overlay : null;
        }
        if (cachedTarget != null && cachedOverlay != null
                && child == cachedTarget
                && cachedOverlay.getVisibility() == View.VISIBLE) {
            cachedOverlay.notifyTargetInvalidated();
        }
    }
}
