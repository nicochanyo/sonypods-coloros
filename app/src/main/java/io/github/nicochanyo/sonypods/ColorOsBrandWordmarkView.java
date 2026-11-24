package io.github.nicochanyo.sonypods;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.LocaleList;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.view.View;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Official ColorOS 17 single-line brand wordmark ({@code SonyPods ColorOS}) combining sleek
 * OPlus geometric {@code SonyPods} typography on the exact same horizontal baseline and cap-height
 * as the 1:1 official geometric monoline {@code ColorOS} logotype, with an authentic pearlescent
 * diagonal gradient and soft diffuse drop shadow.
 */
public class ColorOsBrandWordmarkView extends View {
    public static final int STYLE_ABOUT_HERO = 0;
    public static final int STYLE_OTA_HERO = 1;

    private static final String WORD_TOP = "SonyPods";
    private static final String WORD_BOTTOM = "ColorOS";

    private static final String FONT_OPSANS = "/system/fonts/OPSans-En-Regular.ttf";
    private static final String FONT_SYSSANS = "/system/fonts/SysSans-En-Regular.ttf";

    // Normalized 1:1 official ColorOS geometric monoline constants (unit = cap height)
    private static final float SW = 0.128f;
    private static final float HALF = SW * 0.5f;
    private static final float FLAT_TOP = 0.032f;
    private static final float BASE = 0.952f;
    private static final float X_TOP = 0.280f;
    private static final float X_BOT = 0.968f;
    private static final float X_CY = (X_TOP + X_BOT) * 0.5f;
    private static final float X_RY = (X_BOT - X_TOP) * 0.5f - HALF;
    private static final float X_RX = 0.3535f - HALF;

    // 100% opaque paired ColorOS 17 pearlescent gradients with subtle cool vs. warm separation
    private static final int[] SONY_ABOUT_COLORS = new int[]{0xFFFFFFFF, 0xFFF7FAFF, 0xFFE6EEFF};
    private static final int[] COLOROS_ABOUT_COLORS = new int[]{0xFFFDF0FF, 0xFFF7D6FA, 0xFFFFD4DF};

    private static final int[] SONY_OTA_COLORS = new int[]{0xFFFFFFFF, 0xFFF8FBFF, 0xFFE9F0FF};
    private static final int[] COLOROS_OTA_COLORS = new int[]{0xFFFCEBFF, 0xFFF6D4FC, 0xFFFFD2E4};

    private static final float[] GRAD_POSITIONS = new float[]{0f, 0.52f, 1f};

    private static final int SHADOW_COLOR = 0x5214082C;

    private static volatile Typeface cachedSleekBrand;
    private static final SparseArray<Typeface> DISPLAY_DIGIT_CACHE = new SparseArray<>();
    private static final Object FONT_LOCK = new Object();

    private final Paint textFgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint textShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint vecFgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vecShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path pathBottom = new Path();
    private final Rect capBounds = new Rect();
    private final Rect textBounds = new Rect();
    private final RectF arcRect = new RectF();

    private int brandStyle = STYLE_ABOUT_HERO;
    private float nominalTextSizeDp = 35f;
    private float shadowBlurDp = 6.5f;
    private float shadowDyDp = 2.4f;

    private float density = 1f;
    private float glowPadX;
    private float glowPadY;

    private final float bottomWordUnits = measureWordUnits(WORD_BOTTOM);

    // Computed single-line horizontal layout metrics in pixels
    private float fittedTextPx;
    private float fittedVecH;
    private float textW;
    private float wordGapPx;
    private float vecW;
    private float totalW;
    private float totalVisualH;
    private float startX;
    private float vecTopY;
    private float baselineY;
    private float vecX;
    private float shadowDyPx;

    // Cache keys for avoiding rebuild during redraws
    private float builtContentW = -1f;
    private float builtContentH = -1f;
    private int builtStyle = -1;

    public ColorOsBrandWordmarkView(Context context) {
        super(context);
        init(context);
    }

    public ColorOsBrandWordmarkView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ColorOsBrandWordmarkView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        density = Math.max(0.5f, dm.density);
        glowPadX = 18f * density;
        glowPadY = 18f * density;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        Typeface brandTf = getSleekBrandTypeface();
        configureTextPaint(textFgPaint, brandTf, 0xFFFFFFFF);
        configureTextPaint(textShadowPaint, brandTf, SHADOW_COLOR);
        configureStrokePaint(vecFgPaint, 0xFFFFFFFF);
        configureStrokePaint(vecShadowPaint, SHADOW_COLOR);

        setAlpha(0.96f);
        setContentDescription("SonyPods ColorOS");
        applyStyleDefaults(brandStyle);
    }

    private static void configureTextPaint(Paint p, Typeface tf, int color) {
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(tf);
        p.setLetterSpacing(-0.010f);
        p.setColor(color);
    }

    private static void configureStrokePaint(Paint p, int color) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
    }

    private void applyStyleDefaults(int style) {
        if (style == STYLE_OTA_HERO) {
            nominalTextSizeDp = 38f;
            shadowBlurDp = 7.5f;
            shadowDyDp = 2.6f;
        } else {
            nominalTextSizeDp = 35f;
            shadowBlurDp = 6.5f;
            shadowDyDp = 2.4f;
        }
    }

    public void setBrandStyle(int style) {
        if (this.brandStyle == style && builtStyle == style) {
            return;
        }
        this.brandStyle = style;
        applyStyleDefaults(style);
        builtContentW = -1f;
        builtContentH = -1f;
        builtStyle = -1;
        requestLayout();
        invalidate();
    }

    /**
     * Loads and caches the sleek OPlus geometric sans typeface ({@code O Sans wght 465}) matched
     * to the stroke proportion of official {@code ColorOS}, with safe ColorOS/Android fallbacks.
     */
    public static Typeface getSleekBrandTypeface() {
        Typeface tf = cachedSleekBrand;
        if (tf != null) {
            return tf;
        }
        synchronized (FONT_LOCK) {
            if (cachedSleekBrand == null) {
                Typeface loaded = loadVariableSystemFont(FONT_OPSANS, "'wght' 465");
                if (loaded == null) {
                    loaded = loadVariableSystemFont(FONT_SYSSANS, "'wght' 470, 'opsz' 1080");
                }
                if (loaded == null) {
                    loaded = createWeightFallback(465, "sans-serif-medium");
                }
                cachedSleekBrand = loaded;
            }
            return cachedSleekBrand;
        }
    }

    /**
     * Loads a ColorOS display numeral typeface (for ASCII version strings such as {@code 1.0.3})
     * using OPPO Sans 4.0 EN when present on ColorOS 15-17 devices.
     */
    public static Typeface getDisplayDigitTypeface(int weight) {
        synchronized (FONT_LOCK) {
            Typeface cached = DISPLAY_DIGIT_CACHE.get(weight);
            if (cached != null) {
                return cached;
            }
            int opsz = weight >= 500 ? 1090 : 1050;
            Typeface loaded = loadVariableSystemFont(
                    FONT_SYSSANS, "'wght' " + weight + ", 'opsz' " + opsz);
            if (loaded == null) {
                loaded = loadVariableSystemFont(FONT_OPSANS, "'wght' " + weight);
            }
            if (loaded == null) {
                loaded = createWeightFallback(weight, weight >= 500 ? "sans-serif-medium" : "sans-serif");
            }
            DISPLAY_DIGIT_CACHE.put(weight, loaded);
            return loaded;
        }
    }

    /**
     * Returns a CJK-safe system UI typeface at the requested weight for localized subtitles such as
     * device market names or update status strings. Never loads Latin-only font files.
     */
    public static Typeface getUiSubtitleTypeface(int weight) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Typeface.create(Typeface.DEFAULT, weight, false);
        }
        return Typeface.create(weight >= 480 ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL);
    }

    /**
     * Resolves the official OPlus device marketing name localized to the active UI language, with
     * a clean fallback to {@link Build#MANUFACTURER} and {@link Build#MODEL}.
     */
    public static String resolveDeviceMarketName(Context context) {
        boolean isZh = false;
        if (context != null) {
            Configuration cfg = context.getResources().getConfiguration();
            LocaleList locales = cfg.getLocales();
            if (!locales.isEmpty()) {
                Locale primary = locales.get(0);
                isZh = primary != null && "zh".equalsIgnoreCase(primary.getLanguage());
            }
        }
        String[] keys = isZh
                ? new String[]{"ro.vendor.oplus.market.name", "ro.oplus.market.name", "ro.vendor.oplus.market.enname"}
                : new String[]{"ro.vendor.oplus.market.enname", "ro.oplus.market.enname", "ro.vendor.oplus.market.name"};
        for (String key : keys) {
            String val = readSystemProp(key);
            if (!val.isEmpty()) {
                return val;
            }
        }
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.trim() : "";
        String model = Build.MODEL != null ? Build.MODEL.trim() : "";
        if (manufacturer.isEmpty()) {
            return model;
        }
        if (model.isEmpty()) {
            return manufacturer;
        }
        if (model.toLowerCase(Locale.ROOT).startsWith(manufacturer.toLowerCase(Locale.ROOT))) {
            return model;
        }
        return manufacturer + " " + model;
    }

    private static Typeface loadVariableSystemFont(String path, String variationSettings) {
        try {
            File file = new File(path);
            if (!file.isFile() || !file.canRead()) {
                return null;
            }
            return new Typeface.Builder(file)
                    .setFontVariationSettings(variationSettings)
                    .build();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Typeface createWeightFallback(int weight, String legacyFamily) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Typeface.create(Typeface.SANS_SERIF, weight, false);
        }
        return Typeface.create(legacyFamily, Typeface.NORMAL);
    }

    private static String readSystemProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            Object res = get.invoke(null, key, "");
            return res instanceof String ? ((String) res).trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void measureAtTextSize(float textPx) {
        fittedTextPx = textPx;
        textFgPaint.setTextSize(textPx);
        textFgPaint.getTextBounds("P", 0, 1, capBounds);
        int flatCapH = Math.max(1, -capBounds.top);
        fittedVecH = flatCapH / (BASE - FLAT_TOP);
        textW = textFgPaint.measureText(WORD_TOP);
        wordGapPx = 0.28f * fittedVecH;
        vecW = bottomWordUnits * fittedVecH;
        totalW = textW + wordGapPx + vecW;
    }

    private float computeSingleLineMetrics(float availWidthPx) {
        measureAtTextSize(nominalTextSizeDp * density);
        if (availWidthPx > 0f && totalW > availWidthPx) {
            float scaledPx = fittedTextPx * Math.max(0.25f, availWidthPx / totalW);
            measureAtTextSize(scaledPx);
        }
        textFgPaint.getTextBounds(WORD_TOP, 0, WORD_TOP.length(), textBounds);
        float aboveBase = BASE * fittedVecH;
        float belowBase = Math.max((1.0f - BASE) * fittedVecH, Math.max(0, textBounds.bottom));
        totalVisualH = aboveBase + belowBase;
        return totalW;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        float availW = -1f;
        if (widthMode == MeasureSpec.EXACTLY || widthMode == MeasureSpec.AT_MOST) {
            availW = Math.max(1f,
                    MeasureSpec.getSize(widthMeasureSpec)
                            - getPaddingLeft() - getPaddingRight() - 2f * glowPadX);
        }

        float w = computeSingleLineMetrics(availW);
        int desiredW = (int) Math.ceil(w + 2f * glowPadX + getPaddingLeft() + getPaddingRight());
        int desiredH = (int) Math.ceil(totalVisualH + 2f * glowPadY + getPaddingTop() + getPaddingBottom());
        setMeasuredDimension(
                resolveSize(desiredW, widthMeasureSpec),
                resolveSize(desiredH, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float contentLeft = getPaddingLeft() + glowPadX;
        float contentTop = getPaddingTop() + glowPadY;
        float contentW = Math.max(1f, getWidth() - getPaddingLeft() - getPaddingRight() - 2f * glowPadX);
        float contentH = Math.max(1f, getHeight() - getPaddingTop() - getPaddingBottom() - 2f * glowPadY);

        ensureGeometryBuilt(contentLeft, contentTop, contentW, contentH);

        // Pass 1: silky soft diffuse drop shadow underneath both aligned marks
        canvas.drawText(WORD_TOP, startX, baselineY + shadowDyPx, textShadowPaint);
        canvas.save();
        canvas.translate(0f, shadowDyPx);
        canvas.drawPath(pathBottom, vecShadowPaint);
        canvas.restore();

        // Pass 2: unified pearlescent gradient foreground
        canvas.drawText(WORD_TOP, startX, baselineY, textFgPaint);
        canvas.drawPath(pathBottom, vecFgPaint);
    }

    private void ensureGeometryBuilt(float contentLeft, float contentTop, float contentW, float contentH) {
        if (Float.compare(builtContentW, contentW) == 0
                && Float.compare(builtContentH, contentH) == 0
                && builtStyle == brandStyle) {
            return;
        }
        builtContentW = contentW;
        builtContentH = contentH;
        builtStyle = brandStyle;

        computeSingleLineMetrics(contentW);

        startX = contentLeft + (contentW - totalW) * 0.5f;
        vecTopY = contentTop + (contentH - totalVisualH) * 0.5f;
        baselineY = vecTopY + BASE * fittedVecH;
        vecX = startX + textW + wordGapPx;
        shadowDyPx = shadowDyDp * density;

        pathBottom.reset();
        buildWordPath(pathBottom, WORD_BOTTOM, vecX, vecTopY, fittedVecH);

        boolean ota = brandStyle == STYLE_OTA_HERO;
        int[] sonyColors = ota ? SONY_OTA_COLORS : SONY_ABOUT_COLORS;
        int[] colorOsColors = ota ? COLOROS_OTA_COLORS : COLOROS_ABOUT_COLORS;

        float blurPx = shadowBlurDp * density;
        BlurMaskFilter blurFilter = new BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL);

        textShadowPaint.setTextSize(fittedTextPx);
        textShadowPaint.setMaskFilter(blurFilter);
        textFgPaint.setShader(createDiagonalPearlShader(
                startX, vecTopY, textW, fittedVecH, sonyColors));

        float vecStrokeW = SW * fittedVecH;
        vecShadowPaint.setStrokeWidth(vecStrokeW);
        vecShadowPaint.setMaskFilter(blurFilter);

        vecFgPaint.setStrokeWidth(vecStrokeW);
        vecFgPaint.setShader(createDiagonalPearlShader(
                vecX, vecTopY, vecW, fittedVecH, colorOsColors));
    }

    private static Shader createDiagonalPearlShader(
            float x0, float y0, float w, float h, int[] colors) {
        float ax = 0.45f / Math.max(1f, w);
        float ay = 0.55f / Math.max(1f, h);
        float inv = 1f / Math.max(1e-6f, ax * ax + ay * ay);
        float dx = ax * inv;
        float dy = ay * inv;
        return new LinearGradient(
                x0, y0, x0 + dx, y0 + dy,
                colors, GRAD_POSITIONS, Shader.TileMode.CLAMP);
    }

    private void buildWordPath(Path dst, String word, float startX, float startY, float capH) {
        float x = startX;
        for (int i = 0; i < word.length(); i++) {
            char ch = word.charAt(i);
            if (i > 0) {
                x += kernUnits(word.charAt(i - 1), ch) * capH;
            }
            appendGlyph(dst, ch, x, startY, capH);
            x += glyphWidthUnits(ch) * capH;
        }
    }

    private static float measureWordUnits(String word) {
        float w = 0f;
        for (int i = 0; i < word.length(); i++) {
            if (i > 0) {
                w += kernUnits(word.charAt(i - 1), word.charAt(i));
            }
            w += glyphWidthUnits(word.charAt(i));
        }
        return w;
    }

    private static float kernUnits(char prev, char curr) {
        if (prev == 'C' && curr == 'o') return 0.096f;
        if (prev == 'o' && curr == 'l') return 0.127f;
        if (prev == 'l' && curr == 'o') return 0.127f;
        if (prev == 'o' && curr == 'r') return 0.127f;
        if (prev == 'r' && curr == 'O') return 0.083f;
        if (prev == 'O' && curr == 'S') return 0.083f;
        return 0.105f;
    }

    private static float glyphWidthUnits(char ch) {
        switch (ch) {
            case 'C': return 0.880f;
            case 'o': return 0.707f;
            case 'l': return SW;
            case 'r': return 0.296f;
            case 'O': return 0.980f;
            case 'S': return 0.720f;
            default: return 0.600f;
        }
    }

    private void appendGlyph(Path dst, char ch, float x0, float y0, float h) {
        switch (ch) {
            case 'C': {
                float r = 0.500f - HALF;
                setUnitRect(x0, y0, h, 0.500f - r, 0.500f - r, 0.500f + r, 0.500f + r);
                dst.addArc(arcRect, 40f, 280f);
                break;
            }
            case 'o': {
                setUnitRect(x0, y0, h, 0.3535f - X_RX, X_CY - X_RY, 0.3535f + X_RX, X_CY + X_RY);
                dst.addOval(arcRect, Path.Direction.CW);
                break;
            }
            case 'l': {
                dst.moveTo(x0 + HALF * h, y0 + FLAT_TOP * h);
                dst.lineTo(x0 + HALF * h, y0 + BASE * h);
                break;
            }
            case 'r': {
                float rad = 0.270f;
                float topY = X_TOP + HALF;
                dst.moveTo(x0 + HALF * h, y0 + BASE * h);
                dst.lineTo(x0 + HALF * h, y0 + (topY + rad) * h);
                setUnitRect(x0, y0, h, HALF, topY, HALF + 2f * rad, topY + 2f * rad);
                dst.arcTo(arcRect, 180f, 85f, false);
                break;
            }
            case 'O': {
                float rx = 0.490f - HALF;
                float ry = 0.497f - HALF;
                setUnitRect(x0, y0, h, 0.490f - rx, 0.500f - ry, 0.490f + rx, 0.500f + ry);
                dst.addOval(arcRect, Path.Direction.CW);
                break;
            }
            case 'S': {
                dst.moveTo(x0 + 0.635f * h, y0 + 0.155f * h);
                cubicU(dst, x0, y0, h, 0.560f, 0.067f, 0.455f, 0.067f, 0.370f, 0.067f);
                cubicU(dst, x0, y0, h, 0.205f, 0.067f, 0.118f, 0.150f, 0.118f, 0.260f);
                cubicU(dst, x0, y0, h, 0.118f, 0.380f, 0.215f, 0.432f, 0.380f, 0.482f);
                cubicU(dst, x0, y0, h, 0.555f, 0.534f, 0.652f, 0.600f, 0.652f, 0.735f);
                cubicU(dst, x0, y0, h, 0.652f, 0.862f, 0.535f, 0.933f, 0.355f, 0.933f);
                cubicU(dst, x0, y0, h, 0.215f, 0.933f, 0.125f, 0.885f, 0.068f, 0.805f);
                break;
            }
            default:
                break;
        }
    }

    private void setUnitRect(float x0, float y0, float h, float l, float t, float r, float b) {
        arcRect.set(x0 + l * h, y0 + t * h, x0 + r * h, y0 + b * h);
    }

    private static void cubicU(Path dst, float x0, float y0, float h,
                               float x1, float y1, float x2, float y2, float x3, float y3) {
        dst.cubicTo(
                x0 + x1 * h, y0 + y1 * h,
                x0 + x2 * h, y0 + y2 * h,
                x0 + x3 * h, y0 + y3 * h);
    }
}
