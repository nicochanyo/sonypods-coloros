package io.github.nicochanyo.sonypods;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.util.AttributeSet;
import android.widget.SeekBar;

/**
 * ColorOS 17 styled thick capsule SeekBar with rounded active pill and inner thumb dot.
 */
public class CouiPillSeekBar extends SeekBar {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public CouiPillSeekBar(Context context) {
        super(context);
        init();
    }

    public CouiPillSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CouiPillSeekBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setThumb(new ColorDrawable(Color.TRANSPARENT));
        setProgressDrawable(new ColorDrawable(Color.TRANSPARENT));
        setBackground(null);
        setSplitTrack(false);
        int rPx = Math.round(10f * getResources().getDisplayMetrics().density);
        setPadding(rPx, 0, rPx, 0);
    }

    @Override
    protected synchronized void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredH = Math.round(34f * getResources().getDisplayMetrics().density);
        setMeasuredDimension(
                resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                resolveSize(desiredH, heightMeasureSpec));
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        float d = getResources().getDisplayMetrics().density;
        float r = Math.min(10f * d, Math.min(h * 0.5f, w * 0.25f));
        float cy = h * 0.5f;
        float top = cy - r;
        float bottom = cy + r;

        int max = getMax();
        float frac = max > 0 ? Math.max(0f, Math.min(1f, getProgress() / (float) max)) : 0f;

        Context ctx = getContext();
        int trackColor = ConfigStore.resolveThemeColor(ctx, R.attr.couiSeekbarTrack, R.color.coui_seekbar_track);
        int accentColor = DynamicThemeHelper.getAccent(ctx);
        int dotColor = DynamicThemeHelper.getOnAccent(ctx);
        if (!isEnabled()) {
            accentColor = (Math.round(Color.alpha(accentColor) * 0.42f) << 24)
                    | (accentColor & 0x00FFFFFF);
        }

        paint.setStyle(Paint.Style.FILL);

        // Background capsule track
        paint.setColor(trackColor);
        rect.set(0f, top, w, bottom);
        canvas.drawRoundRect(rect, r, r, paint);

        // Active accent capsule + circular thumb dot
        float travel = Math.max(0f, (w - r) - r);
        float cx;
        if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) {
            cx = (w - r) - frac * travel;
            rect.set(cx - r, top, w, bottom);
        } else {
            cx = r + frac * travel;
            rect.set(0f, top, cx + r, bottom);
        }

        paint.setColor(accentColor);
        canvas.drawRoundRect(rect, r, r, paint);

        paint.setColor(dotColor);
        canvas.drawCircle(cx, cy, r * 0.55f, paint);
    }
}
