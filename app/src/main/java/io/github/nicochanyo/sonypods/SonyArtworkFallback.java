package io.github.nicochanyo.sonypods;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * High-resolution transparent-background fallback artwork renderer for Sony TWS earbuds
 * (e.g. WF-1000XM4/XM5) and over-ear headphones (e.g. WH-1000XM4/XM5/XM6).
 * Tinted gently according to Sony SERIES_AND_COLOR color codes.
 */
public final class SonyArtworkFallback {
    private SonyArtworkFallback() {
    }

    public static Bitmap createFallbackBitmap(String modelName, int colorCode, int sizePx) {
        int size = Math.max(128, Math.min(1024, sizePx));
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Palette pal = resolvePalette(colorCode);
        if (SonyDeviceDetector.isOverEarModel(modelName)) {
            drawOverEar(canvas, size, pal);
        } else {
            drawEarbudsAndCase(canvas, size, pal);
        }
        return bmp;
    }

    private static final class Palette {
        final int bodyLight;
        final int bodyDark;
        final int accentRing;
        final int earTip;
        final int shadowAlpha;

        Palette(int bodyLight, int bodyDark, int accentRing, int earTip, int shadowAlpha) {
            this.bodyLight = bodyLight;
            this.bodyDark = bodyDark;
            this.accentRing = accentRing;
            this.earTip = earTip;
            this.shadowAlpha = shadowAlpha;
        }
    }

    private static Palette resolvePalette(int colorCode) {
        int low = (colorCode >= 0) ? (colorCode & 0x0F) : 0x01;
        switch (low) {
            case 0x02: // White
            case 0x03: // Silver / Platinum Silver
            case 0x0B: // Cream
                return new Palette(0xFFF0ECE4, 0xFFCCC6BA, 0xFFC5AC7F, 0xFFB8B2A6, 0x26000000);
            case 0x05: // Blue / Smoky Blue
                return new Palette(0xFF425B78, 0xFF203042, 0xFFD4C19C, 0xFF1D2938, 0x33000000);
            case 0x06: // Pink / Smoky Pink
                return new Palette(0xFFE3B9BE, 0xFFB8888E, 0xFFD5B79C, 0xFFA4787E, 0x28000000);
            case 0x08: // Green / Sage
                return new Palette(0xFF5D7061, 0xFF334237, 0xFFCDB996, 0xFF2A362D, 0x30000000);
            case 0x09: // Gray
                return new Palette(0xFF6B7078, 0xFF3C4047, 0xFFCBB18B, 0xFF2E3136, 0x33000000);
            case 0x01: // Matte Black + Copper (signature WF-1000XM4)
            default:
                return new Palette(0xFF3C4047, 0xFF1C1F24, 0xFFC8A575, 0xFF14161A, 0x3B000000);
        }
    }

    private static void drawEarbudsAndCase(Canvas c, int s, Palette pal) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Soft floor shadow
        p.setShader(new RadialGradient(s * 0.50f, s * 0.84f, s * 0.34f,
                pal.shadowAlpha, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawOval(new RectF(s * 0.14f, s * 0.77f, s * 0.86f, s * 0.90f), p);
        p.setShader(null);

        // Background charging case (sleek rounded pill capsule slightly behind left)
        RectF caseBody = new RectF(s * 0.24f, s * 0.14f, s * 0.76f, s * 0.62f);
        p.setShader(new LinearGradient(caseBody.left, caseBody.top, caseBody.right, caseBody.bottom,
                pal.bodyLight, pal.bodyDark, Shader.TileMode.CLAMP));
        c.drawRoundRect(caseBody, s * 0.14f, s * 0.14f, p);
        p.setShader(null);

        // Lid seam line & front notch on case
        Paint lineP = new Paint(Paint.ANTI_ALIAS_FLAG);
        lineP.setStyle(Paint.Style.STROKE);
        lineP.setStrokeWidth(Math.max(1.5f, s * 0.008f));
        lineP.setColor(0x45000000);
        c.drawLine(caseBody.left + s * 0.02f, s * 0.34f, caseBody.right - s * 0.02f, s * 0.34f, lineP);

        p.setColor(0x66FFFFFF);
        p.setAlpha(90);
        c.drawRoundRect(new RectF(s * 0.45f, s * 0.32f, s * 0.55f, s * 0.35f), s * 0.01f, s * 0.01f, p);
        p.setAlpha(255);

        // Foreground Left & Right WF earbuds
        drawSingleBud(c, s, s * 0.31f, s * 0.63f, -14f, true, pal);
        drawSingleBud(c, s, s * 0.69f, s * 0.63f, 14f, false, pal);
    }

    private static void drawSingleBud(Canvas c, int s, float cx, float cy, float tiltDeg, boolean isLeft, Palette pal) {
        c.save();
        c.translate(cx, cy);
        c.rotate(tiltDeg);

        float r = s * 0.155f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Silicone nozzle/ear tip facing inward
        float tipX = isLeft ? r * 0.78f : -r * 0.78f;
        float tipY = r * 0.30f;
        p.setColor(pal.earTip);
        c.drawOval(new RectF(tipX - r * 0.46f, tipY - r * 0.36f, tipX + r * 0.46f, tipY + r * 0.36f), p);

        // Main ergonomic bud body
        p.setShader(new LinearGradient(-r, -r, r, r, pal.bodyLight, pal.bodyDark, Shader.TileMode.CLAMP));
        c.drawOval(new RectF(-r, -r * 1.05f, r, r * 0.95f), p);
        p.setShader(null);

        // Touch sensor circle
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(1.5f, s * 0.008f));
        ring.setColor(0x38FFFFFF);
        c.drawCircle(0, -r * 0.18f, r * 0.54f, ring);

        // Sony signature metallic copper/gold mic ring accent
        float micX = isLeft ? -r * 0.42f : r * 0.42f;
        float micY = r * 0.38f;
        ring.setStrokeWidth(Math.max(2f, s * 0.011f));
        ring.setColor(pal.accentRing);
        c.drawCircle(micX, micY, r * 0.22f, ring);
        p.setColor(0xFF15171B);
        c.drawCircle(micX, micY, r * 0.14f, p);

        c.restore();
    }

    private static void drawOverEar(Canvas c, int s, Palette pal) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Floor shadow
        p.setShader(new RadialGradient(s * 0.50f, s * 0.87f, s * 0.36f,
                pal.shadowAlpha, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawOval(new RectF(s * 0.16f, s * 0.80f, s * 0.84f, s * 0.92f), p);
        p.setShader(null);

        // Outer headband arch
        Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
        band.setStyle(Paint.Style.STROKE);
        band.setStrokeCap(Paint.Cap.ROUND);
        band.setStrokeWidth(s * 0.075f);
        band.setShader(new LinearGradient(s * 0.2f, s * 0.1f, s * 0.8f, s * 0.5f,
                pal.bodyLight, pal.bodyDark, Shader.TileMode.CLAMP));

        Path arch = new Path();
        RectF archBounds = new RectF(s * 0.20f, s * 0.12f, s * 0.80f, s * 0.72f);
        arch.addArc(archBounds, 190f, 160f);
        c.drawPath(arch, band);

        // Metallic sliders
        Paint slider = new Paint(Paint.ANTI_ALIAS_FLAG);
        slider.setColor(pal.accentRing);
        slider.setStrokeWidth(s * 0.022f);
        slider.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(s * 0.22f, s * 0.43f, s * 0.25f, s * 0.54f, slider);
        c.drawLine(s * 0.78f, s * 0.43f, s * 0.75f, s * 0.54f, slider);

        // Left & Right oval earcups
        drawEarCup(c, s, s * 0.26f, s * 0.64f, -12f, true, pal);
        drawEarCup(c, s, s * 0.74f, s * 0.64f, 12f, false, pal);
    }

    private static void drawEarCup(Canvas c, int s, float cx, float cy, float tilt, boolean isLeft, Palette pal) {
        c.save();
        c.translate(cx, cy);
        c.rotate(tilt);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float rw = s * 0.12f;
        float rh = s * 0.19f;

        // Plush earpad inward
        p.setColor(pal.earTip);
        float padOff = isLeft ? rw * 0.45f : -rw * 0.45f;
        c.drawOval(new RectF(-rw * 0.85f + padOff, -rh * 0.94f, rw * 0.85f + padOff, rh * 0.94f), p);

        // Outer shell
        p.setShader(new LinearGradient(-rw, -rh, rw, rh, pal.bodyLight, pal.bodyDark, Shader.TileMode.CLAMP));
        c.drawOval(new RectF(-rw, -rh, rw, rh), p);
        p.setShader(null);

        // Mic accent grille dot
        p.setColor(pal.accentRing);
        c.drawCircle(isLeft ? -rw * 0.25f : rw * 0.25f, -rh * 0.48f, s * 0.011f, p);

        c.restore();
    }
}
