package io.github.nicochanyo.sonypods;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

/**
 * Hardware-accelerated progressive Gaussian blur + vertical surface scrim overlay
 * for the collapsing top header. Captures the scrolling content beneath it into a
 * RenderNode, renders dual soft/strong RenderEffect Gaussian bands with alpha gradient
 * masks (API 31+), and falls back to downsampled software blur on older APIs.
 */
public class TopGradientBlurView extends View {
    private static final float SOFT_BLUR_DP = 4.5f;
    private static final float STRONG_BLUR_DP = 11.5f;
    private static final float BLUR_PAD_DP = 20f;
    private static final int FALLBACK_DOWNSAMPLE = 8;

    private View targetView;
    private boolean isCapturing;
    private int statusBarTopPx;
    private int bgColor;
    private boolean translucentBackdrop = false;
    private boolean dynamicBackdrop = false;

    // Cached geometry/theme signature for RenderEffects and Shaders
    private int lastW = -1;
    private int lastH = -1;
    private int lastStatusTop = -1;
    private int lastBgColor;
    private boolean lastTranslucent = false;
    private boolean lastDynamicBackdrop = false;

    // API 31+ GPU RenderNodes
    private RenderNode sourceNode;
    private RenderNode softBlurNode;
    private RenderNode strongBlurNode;
    private RenderEffect softMaskedEffect;
    private RenderEffect strongMaskedEffect;

    // Top surface gradient scrim & software fallback paints
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallbackBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fallbackMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap fallbackCaptureBmp;
    private Canvas fallbackCaptureCanvas;

    public TopGradientBlurView(Context context) {
        super(context);
        init();
    }

    public TopGradientBlurView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TopGradientBlurView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        fallbackMaskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        refreshThemeSurfaceColor();
    }

    public void setBlurTargetView(View target) {
        this.targetView = target;
        invalidate();
    }

    public void setStatusBarTopPx(int topPx) {
        int clamped = Math.max(0, topPx);
        if (this.statusBarTopPx != clamped) {
            this.statusBarTopPx = clamped;
            lastW = -1;
            invalidate();
        }
    }

    public void refreshThemeSurfaceColor() {
        try {
            int resolved = ConfigStore.resolveThemeColor(getContext(), R.attr.couiBg, R.color.coui_bg);
            if (this.bgColor != resolved || this.translucentBackdrop) {
                this.bgColor = resolved;
                this.translucentBackdrop = false;
                lastW = -1;
                invalidate();
            }
        } catch (Throwable ignored) {
        }
    }

    public void setSurfaceColor(int color) {
        boolean isTranslucent = Color.alpha(color) == 0;
        int nextBg = isTranslucent ? (color & 0x00FFFFFF) : color;
        if (this.translucentBackdrop != isTranslucent || this.bgColor != nextBg) {
            this.translucentBackdrop = isTranslucent;
            this.bgColor = nextBg;
            lastW = -1;
            invalidate();
        }
    }

    public void setDynamicBackdrop(boolean dynamic) {
        if (this.dynamicBackdrop != dynamic) {
            this.dynamicBackdrop = dynamic;
            lastW = -1;
            invalidate();
        }
    }

    public boolean isCapturingTarget() {
        return isCapturing;
    }

    public void notifyTargetInvalidated() {
        if (!isCapturing) {
            invalidate();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (fallbackCaptureBmp != null && !fallbackCaptureBmp.isRecycled()) {
            fallbackCaptureBmp.recycle();
        }
        fallbackCaptureBmp = null;
        fallbackCaptureCanvas = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (isCapturing || targetView == null) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        ensureShadersAndEffects(w, h);

        if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated()) {
            drawHardwareProgressiveBlur(canvas, w, h);
        } else {
            drawSoftwareFallbackBlur(canvas, w, h);
        }
    }

    private void ensureShadersAndEffects(int w, int h) {
        if (w == lastW && h == lastH && statusBarTopPx == lastStatusTop
                && bgColor == lastBgColor && translucentBackdrop == lastTranslucent
                && dynamicBackdrop == lastDynamicBackdrop) {
            return;
        }
        lastW = w;
        lastH = h;
        lastStatusTop = statusBarTopPx;
        lastBgColor = bgColor;
        lastTranslucent = translucentBackdrop;
        lastDynamicBackdrop = dynamicBackdrop;

        float d = getResources().getDisplayMetrics().density;
        boolean compactEmbedded = (statusBarTopPx <= 0);
        float topAnchor = Math.min(h * 0.68f, statusBarTopPx + 34f * d);
        float fh = h;

        if (dynamicBackdrop) {
            scrimPaint.setShader(null);
            scrimPaint.setColor(Color.TRANSPARENT);
        } else {
            float[] scrimStops = compactEmbedded
                    ? new float[] { 0f, 0.48f, 0.64f, 0.78f, 0.90f, 1f }
                    : new float[] {
                            0f,
                            clampPos(statusBarTopPx / fh),
                            clampPos(topAnchor / fh),
                            clampPos((topAnchor + 20f * d) / fh),
                            clampPos((topAnchor + 38f * d) / fh),
                            1f
                    };
            int rgb = bgColor & 0x00FFFFFF;
            int[] scrimColors;
            if (compactEmbedded) {
                scrimColors = translucentBackdrop
                        ? new int[] {
                                (0xCC << 24) | rgb,
                                (0xB8 << 24) | rgb,
                                (0x80 << 24) | rgb,
                                (0x40 << 24) | rgb,
                                (0x12 << 24) | rgb,
                                rgb
                        }
                        : new int[] {
                                (0xFF << 24) | rgb,
                                (0xF7 << 24) | rgb,
                                (0xCC << 24) | rgb,
                                (0x7A << 24) | rgb,
                                (0x24 << 24) | rgb,
                                rgb
                        };
            } else {
                scrimColors = translucentBackdrop
                        ? new int[] {
                                (0x72 << 24) | rgb,
                                (0x56 << 24) | rgb,
                                (0x30 << 24) | rgb,
                                (0x16 << 24) | rgb,
                                (0x06 << 24) | rgb,
                                rgb
                        }
                        : new int[] {
                                (0x9E << 24) | rgb,
                                (0x82 << 24) | rgb,
                                (0x52 << 24) | rgb,
                                (0x2B << 24) | rgb,
                                (0x0C << 24) | rgb,
                                rgb
                        };
            }
            scrimPaint.setColor(Color.BLACK);
            scrimPaint.setShader(new LinearGradient(0f, 0f, 0f, fh, scrimColors, scrimStops, Shader.TileMode.CLAMP));
        }

        if (Build.VERSION.SDK_INT >= 31) {
            float rSoft = Math.max(1f, SOFT_BLUR_DP * d);
            float rStrong = Math.max(1f, STRONG_BLUR_DP * d);

            float[] softStops;
            int[] softMaskColors;
            float[] strongStops;
            int[] strongMaskColors;
            if (dynamicBackdrop) {
                softStops = new float[] { 0f, 0.46f, 0.64f, 0.82f, 0.94f, 1f };
                softMaskColors = new int[] {
                        0x00FFFFFF, 0x00FFFFFF, 0x78FFFFFF, 0xFFFFFFFF, 0x70FFFFFF, 0x00FFFFFF
                };
                strongStops = new float[] { 0f, 0.36f, 0.54f, 0.72f, 0.88f, 1f };
                strongMaskColors = new int[] {
                        0x00FFFFFF, 0x00FFFFFF, 0xA8FFFFFF, 0xFFFFFFFF, 0x40FFFFFF, 0x00FFFFFF
                };
            } else {
                // Keep soft blur 100% opaque across the upper transition so sharp edges never ghost beneath strongBlur
                softStops = compactEmbedded
                        ? new float[] { 0f, 0.72f, 0.88f, 1f }
                        : new float[] {
                                0f,
                                clampPos((topAnchor + 26f * d) / fh),
                                clampPos((topAnchor + 40f * d) / fh),
                                1f
                        };
                softMaskColors = new int[] {
                        0xFFFFFFFF, 0xFFFFFFFF, 0x5CFFFFFF, 0x00FFFFFF
                };
                strongStops = compactEmbedded
                        ? new float[] { 0f, 0.56f, 0.74f, 0.90f, 1f }
                        : new float[] {
                                0f,
                                clampPos((topAnchor + 4f * d) / fh),
                                clampPos((topAnchor + 20f * d) / fh),
                                clampPos((topAnchor + 36f * d) / fh),
                                1f
                        };
                strongMaskColors = new int[] {
                        0xFFFFFFFF, 0xFFFFFFFF, 0x78FFFFFF, 0x00FFFFFF, 0x00FFFFFF
                };
            }
            LinearGradient softGrad = new LinearGradient(
                    0f, 0f, 0f, fh, softMaskColors, softStops, Shader.TileMode.CLAMP);
            softMaskedEffect = RenderEffect.createBlendModeEffect(
                    RenderEffect.createBlurEffect(rSoft, rSoft, Shader.TileMode.CLAMP),
                    RenderEffect.createShaderEffect(softGrad),
                    BlendMode.DST_IN);

            LinearGradient strongGrad = new LinearGradient(
                    0f, 0f, 0f, fh, strongMaskColors, strongStops, Shader.TileMode.CLAMP);
            strongMaskedEffect = RenderEffect.createBlendModeEffect(
                    RenderEffect.createBlurEffect(rStrong, rStrong, Shader.TileMode.CLAMP),
                    RenderEffect.createShaderEffect(strongGrad),
                    BlendMode.DST_IN);

            if (softBlurNode != null) {
                softBlurNode.setRenderEffect(softMaskedEffect);
            }
            if (strongBlurNode != null) {
                strongBlurNode.setRenderEffect(strongMaskedEffect);
            }
        } else {
            float[] fbStops = dynamicBackdrop
                    ? new float[] { 0f, 0.38f, 0.68f, 0.88f, 1f }
                    : (compactEmbedded
                            ? new float[] { 0f, 0.56f, 0.84f, 1f }
                            : new float[] { 0f, clampPos(topAnchor / fh), clampPos((topAnchor + 28f * d) / fh), 1f });
            int[] fbColors = dynamicBackdrop
                    ? new int[] { 0x00FFFFFF, 0x00FFFFFF, 0xD8FFFFFF, 0x60FFFFFF, 0x00FFFFFF }
                    : new int[] { 0xFFFFFFFF, 0xE6FFFFFF, 0x55FFFFFF, 0x00FFFFFF };
            fallbackMaskPaint.setShader(new LinearGradient(0f, 0f, 0f, fh, fbColors, fbStops, Shader.TileMode.CLAMP));
        }
    }

    private void drawHardwareProgressiveBlur(Canvas canvas, int w, int h) {
        if (Build.VERSION.SDK_INT < 31) {
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        int captureH = h + Math.round(BLUR_PAD_DP * d);

        if (sourceNode == null) {
            sourceNode = new RenderNode("SonyPodsTopBlurSource");
            softBlurNode = new RenderNode("SonyPodsTopBlurSoft");
            strongBlurNode = new RenderNode("SonyPodsTopBlurStrong");
            softBlurNode.setRenderEffect(softMaskedEffect);
            strongBlurNode.setRenderEffect(strongMaskedEffect);
        }

        sourceNode.setPosition(0, 0, w, captureH);
        sourceNode.setClipToBounds(true);

        boolean clearTransparent = dynamicBackdrop || translucentBackdrop;
        isCapturing = true;
        try {
            RecordingCanvas srcRc = sourceNode.beginRecording(w, captureH);
            if (clearTransparent) {
                srcRc.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            } else {
                srcRc.drawColor(bgColor);
            }
            float dx = targetView.getLeft() - getLeft() + targetView.getTranslationX() - targetView.getScrollX();
            float dy = targetView.getTop() - getTop() + targetView.getTranslationY() - targetView.getScrollY();
            if (dx != 0f || dy != 0f) {
                srcRc.translate(dx, dy);
            }
            targetView.draw(srcRc);
            sourceNode.endRecording();
        } catch (Throwable ignored) {
            isCapturing = false;
            if (!dynamicBackdrop) {
                canvas.drawRect(0f, 0f, w, h, scrimPaint);
            }
            return;
        } finally {
            isCapturing = false;
        }

        softBlurNode.setPosition(0, 0, w, captureH);
        softBlurNode.setClipToBounds(true);
        RecordingCanvas softRc = softBlurNode.beginRecording(w, captureH);
        softRc.drawRenderNode(sourceNode);
        softBlurNode.endRecording();

        strongBlurNode.setPosition(0, 0, w, captureH);
        strongBlurNode.setClipToBounds(true);
        RecordingCanvas strongRc = strongBlurNode.beginRecording(w, captureH);
        strongRc.drawRenderNode(sourceNode);
        strongBlurNode.endRecording();

        int sc = canvas.save();
        canvas.clipRect(0, 0, w, h);
        canvas.drawRenderNode(softBlurNode);
        canvas.drawRenderNode(strongBlurNode);
        if (!dynamicBackdrop) {
            canvas.drawRect(0f, 0f, w, h, scrimPaint);
        }
        canvas.restoreToCount(sc);
    }

    private void drawSoftwareFallbackBlur(Canvas canvas, int w, int h) {
        int sw = Math.max(1, w / FALLBACK_DOWNSAMPLE);
        int sh = Math.max(1, h / FALLBACK_DOWNSAMPLE);
        if (fallbackCaptureBmp == null || fallbackCaptureBmp.getWidth() != sw || fallbackCaptureBmp.getHeight() != sh) {
            if (fallbackCaptureBmp != null && !fallbackCaptureBmp.isRecycled()) {
                fallbackCaptureBmp.recycle();
            }
            fallbackCaptureBmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            fallbackCaptureCanvas = new Canvas(fallbackCaptureBmp);
        }

        boolean clearTransparent = dynamicBackdrop || translucentBackdrop;
        isCapturing = true;
        try {
            fallbackCaptureCanvas.save();
            fallbackCaptureCanvas.scale(1f / FALLBACK_DOWNSAMPLE, 1f / FALLBACK_DOWNSAMPLE);
            if (clearTransparent) {
                fallbackCaptureCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            } else {
                fallbackCaptureCanvas.drawColor(bgColor);
            }
            float dx = targetView.getLeft() - getLeft() + targetView.getTranslationX() - targetView.getScrollX();
            float dy = targetView.getTop() - getTop() + targetView.getTranslationY() - targetView.getScrollY();
            if (dx != 0f || dy != 0f) {
                fallbackCaptureCanvas.translate(dx, dy);
            }
            targetView.draw(fallbackCaptureCanvas);
            fallbackCaptureCanvas.restore();
            boxBlurInPlace(fallbackCaptureBmp, 4);
        } catch (Throwable ignored) {
            isCapturing = false;
            if (!dynamicBackdrop) {
                canvas.drawRect(0f, 0f, w, h, scrimPaint);
            }
            return;
        } finally {
            isCapturing = false;
        }

        int layer = canvas.saveLayer(0f, 0f, w, h, null);
        canvas.save();
        canvas.scale((float) w / sw, (float) h / sh);
        canvas.drawBitmap(fallbackCaptureBmp, 0f, 0f, fallbackBitmapPaint);
        canvas.restore();
        canvas.drawRect(0f, 0f, w, h, fallbackMaskPaint);
        canvas.restoreToCount(layer);
        if (!dynamicBackdrop) {
            canvas.drawRect(0f, 0f, w, h, scrimPaint);
        }
    }

    private static void boxBlurInPlace(Bitmap bmp, int radius) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w <= 1 || h <= 1) return;
        int[] src = new int[w * h];
        int[] tmp = new int[w * h];
        bmp.getPixels(src, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < h; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    int r = 0, g = 0, b = 0, count = 0;
                    int x0 = Math.max(0, x - radius);
                    int x1 = Math.min(w - 1, x + radius);
                    for (int xi = x0; xi <= x1; xi++) {
                        int c = src[row + xi];
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        count++;
                    }
                    tmp[row + x] = 0xFF000000 | ((r / count) << 16) | ((g / count) << 8) | (b / count);
                }
            }
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    int r = 0, g = 0, b = 0, count = 0;
                    int y0 = Math.max(0, y - radius);
                    int y1 = Math.min(h - 1, y + radius);
                    for (int yi = y0; yi <= y1; yi++) {
                        int c = tmp[yi * w + x];
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        count++;
                    }
                    src[y * w + x] = 0xFF000000 | ((r / count) << 16) | ((g / count) << 8) | (b / count);
                }
            }
        }
        bmp.setPixels(src, 0, w, 0, 0, w, h);
    }

    private static float clampPos(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
