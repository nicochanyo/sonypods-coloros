package io.github.nicochanyo.sonypods;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.BlendModeColorFilter;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Bridge to official ColorOS 17 system material, backdrop blur, G2 smooth outline,
 * metaball coupling, and linear-motor waveform haptic APIs in bootclasspath
 * ({@code oplus-framework.jar}), with clean fallbacks for non-ColorOS environments.
 */
public final class ColorOsLivingFlowEngine {
    private static final String TAG = "SonyPods-LivingFlow";
    private static final int TAG_LAYOUT_LISTENER = 0x53504C46;
    private static final int TAG_POPUP_GLASS_STATE = 0x534F4E5A;

    public enum GlassPreset {
        BOTTOM_BAR_PILL,
        DOCK_CIRCLE,
        TOP_BAR_CIRCLE,
        EMBEDDED_TOP_CIRCLE,
        OTA_TOP_CIRCLE,
        POPUP_MENU,
        DARK_FROSTED_CARD
    }

    public enum HapticKind {
        TICK,
        TAP,
        SWITCH,
        CONFIRM
    }

    // Verified official ColorOS 17 WaveformEffect IDs (com.oplus.os.WaveformEffect)
    private static final int WAVEFORM_TICK = 0x44;      // EFFECT_CUSTOMIZED_WEAK_GRANULAR
    private static final int WAVEFORM_TAP = 1;          // EFFECT_WEAK_SHORT_VIBRATE_ONCE
    private static final int WAVEFORM_SWITCH = 0x11f;   // EFFECT_OTHER_STYLESWITCH_SOFT
    private static final int WAVEFORM_CONFIRM = 2;      // EFFECT_MODERATE_SHORT_VIBRATE_ONCE

    private static final Method sSetBgRenderEffect;
    private static final Method sSetShadowClipping;
    private static final Constructor<?> sOplusViewCtor;
    private static final Method sSetOverrideLightSource;
    private static final Constructor<?> sViewRootManagerCtor;
    private static final Method sVrmGetBgBlurDrawable;
    private static final Method sVrmSetBlurRadius;
    private static final Method sVrmSetCornerRadius;
    private static final Method sVrmSetBlurParams;
    private static final Constructor<?> sBlurParamCtor;
    private static final Method sBpSetBlurType;
    private static final Method sBpSetSmoothCornerType;
    private static final Method sBpSetSmoothCornerWeight;
    private static final Method sBpSetMaterialParams;

    private static final Constructor<?> sBaseParamsCtor;
    private static final Constructor<?> sCornerParamsCtor;
    private static final Constructor<?> sEdgeParamsCtor;
    private static final Constructor<?> sShadowParamsCtor;
    private static final Constructor<?> sMetaBallParamsCtor;

    private static final Method sSetBaseParams;
    private static final Method sSetCornerParams;
    private static final Method sSetEdgeParams;
    private static final Method sSetShadowParams;
    private static final Method sSetMetaBallParams;
    private static final Method sSetMetaBallChain;
    private static final Method sSetCausticColor;
    private static final Method sSetCausticLayout;
    private static final Method sSetCausticParams;

    private static final Constructor<?> sOutlineSmoothCtor;
    private static final Method sOutlineSmoothSetRoundRect;

    private static final Constructor<?> sWaveformBuilderCtor;
    private static final Method sWaveformSetType;
    private static final Method sWaveformSetAsync;
    private static final Method sWaveformBuild;
    private static final Method sLinearMotorVibrate;

    private static final int sOplusAnimLevel;
    private static volatile boolean sOutlineSmoothBlocked;
    private static volatile boolean sLinearMotorDenied;

    static {
        Method setBgEffect = null;
        Method setShadowClip = null;
        Constructor<?> oplusViewCtor = null;
        Method setOverrideLight = null;
        Constructor<?> baseCtor = null;
        Constructor<?> cornerCtor = null;
        Constructor<?> edgeCtor = null;
        Constructor<?> shadowCtor = null;
        Constructor<?> metaCtor = null;
        Method setBase = null;
        Method setCorner = null;
        Method setEdge = null;
        Method setShadow = null;
        Method setMeta = null;
        Method setMetaChain = null;
        Method setCausticCol = null;
        Method setCausticLay = null;
        Method setCausticPar = null;
        Constructor<?> outSmoothCtor = null;
        Method outSmoothSetRect = null;
        Constructor<?> wfBuilderCtor = null;
        Method wfSetType = null;
        Method wfSetAsync = null;
        Method wfBuild = null;
        Method lmVibrate = null;
        Constructor<?> vrmCtor = null;
        Method vrmGetBlurDrw = null;
        Method vrmSetRadius = null;
        Method vrmSetCorner = null;
        Method vrmSetParams = null;
        Constructor<?> bpCtor = null;
        Method bpSetType = null;
        Method bpSetCornerType = null;
        Method bpSetCornerWeight = null;
        Method bpSetMatParams = null;
        int animLevel = 0;

        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method getInt = sp.getMethod("getInt", String.class, int.class);
            animLevel = (Integer) getInt.invoke(null, "persist.sys.oplus.anim_level", 0);
        } catch (Throwable ignored) {
        }

        if (Build.VERSION.SDK_INT >= 31) {
            try {
                Class<?> bgEffectCls = Class.forName("com.oplus.view.OplusViewBackgroundRenderEffect");
                setBgEffect = bgEffectCls.getMethod("setBackgroundRenderEffect", RenderEffect.class, View.class);
            } catch (Throwable ignored) {
            }
        }

        try {
            Class<?> oplusViewCls = Class.forName("com.oplus.view.OplusView");
            setShadowClip = oplusViewCls.getMethod("setShadowClippingEnabled", View.class, boolean.class);
        } catch (Throwable ignored) {
        }

        try {
            Class<?> oplusViewCls = Class.forName("com.oplus.view.OplusView");
            oplusViewCtor = oplusViewCls.getConstructor(View.class);
            setOverrideLight = oplusViewCls.getMethod("setOverrideLightSourceGeometry",
                    float.class, float.class, float.class, float.class, float.class);
        } catch (Throwable ignored) {
        }

        try {
            Class<?> cornerCls = Class.forName("com.oplus.view.material.OplusMaterialCornerParams");
            Class<?> edgeCls = Class.forName("com.oplus.view.material.OplusMaterialEdgeParams");
            Class<?> shadowCls = Class.forName("com.oplus.view.material.OplusMaterialShadowParams");
            Class<?> utilCls = Class.forName("com.oplus.view.material.OplusMaterialUtil");

            cornerCtor = cornerCls.getConstructor(float.class, float.class);
            edgeCtor = edgeCls.getConstructor(int.class, float.class, float.class, float.class);
            shadowCtor = shadowCls.getConstructor(int.class, float.class, float.class, float.class);

            setCorner = utilCls.getMethod("setCornerParams", View.class, cornerCls);
            setEdge = utilCls.getMethod("setEdgeParams", View.class, edgeCls);
            setShadow = utilCls.getMethod("setShadowParams", View.class, shadowCls);
        } catch (Throwable t) {
            Log.d(TAG, "OplusMaterialUtil core unavailable: " + t.getMessage());
        }

        try {
            Class<?> baseCls = Class.forName("com.oplus.view.material.OplusMaterialBaseParams");
            Class<?> utilCls = Class.forName("com.oplus.view.material.OplusMaterialUtil");
            baseCtor = baseCls.getConstructor(int.class, Rect.class);
            setBase = utilCls.getMethod("setBaseParams", View.class, baseCls);
        } catch (Throwable ignored) {
        }

        try {
            Class<?> metaCls = Class.forName("com.oplus.view.material.OplusMaterialMetaBallParams");
            Class<?> utilCls = Class.forName("com.oplus.view.material.OplusMaterialUtil");
            metaCtor = metaCls.getConstructor(int.class, int.class, int.class, float.class, float.class);
            setMeta = utilCls.getMethod("setMetaBallParams", View.class, metaCls);
            setMetaChain = utilCls.getMethod("setMetaBallChain", Set.class);
        } catch (Throwable ignored) {
        }

        try {
            Class<?> utilCls = Class.forName("com.oplus.view.material.OplusMaterialUtil");
            setCausticCol = utilCls.getMethod("setOutlineCausticShadowColor", View.class, int.class);
            setCausticLay = utilCls.getMethod("setOutlineCausticShadowLayout", View.class, int.class, int.class, int.class, int.class);
            setCausticPar = utilCls.getMethod("setOutlineCausticShadowParams", View.class,
                    float.class, float.class, float.class, float.class, float.class, float.class);
        } catch (Throwable ignored) {
        }

        try {
            Class<?> osCls = Class.forName("com.oplus.graphics.OplusOutlineSmooth");
            outSmoothCtor = osCls.getConstructor(Outline.class);
            outSmoothSetRect = osCls.getMethod("setSmoothRoundRect",
                    int.class, int.class, int.class, int.class, float.class, float.class);
        } catch (Throwable ignored) {
        }

        try {
            Class<?> lmCls = Class.forName("com.oplus.os.LinearmotorVibrator");
            Class<?> wfCls = Class.forName("com.oplus.os.WaveformEffect");
            Class<?> wfBCls = Class.forName("com.oplus.os.WaveformEffect$Builder");
            wfBuilderCtor = wfBCls.getConstructor();
            wfSetType = wfBCls.getMethod("setEffectType", int.class);
            wfSetAsync = wfBCls.getMethod("setAsynchronous", boolean.class);
            wfBuild = wfBCls.getMethod("build");
            lmVibrate = lmCls.getMethod("vibrate", wfCls);
        } catch (Throwable ignored) {
        }

        try {
            Class<?> vrmCls = Class.forName("com.oplus.view.ViewRootManager");
            vrmCtor = vrmCls.getConstructor(View.class);
            vrmGetBlurDrw = vrmCls.getMethod("getBackgroundBlurDrawable");
            vrmSetRadius = vrmCls.getMethod("setBlurRadius", int.class);
            vrmSetCorner = vrmCls.getMethod("setCornerRadius", float.class, float.class, float.class, float.class);
        } catch (Throwable ignored) {
        }

        try {
            Class<?> vrmCls = Class.forName("com.oplus.view.ViewRootManager");
            Class<?> bpCls = Class.forName("com.oplus.graphics.OplusBlurParam");
            vrmSetParams = vrmCls.getMethod("setBlurParams", bpCls);
            bpCtor = bpCls.getConstructor();
            bpSetType = bpCls.getMethod("setBlurType", int.class);
            bpSetCornerType = bpCls.getMethod("setSmoothCornerType", int.class);
            bpSetCornerWeight = bpCls.getMethod("setSmoothCornerWeight", float.class);
            bpSetMatParams = bpCls.getMethod("setMaterialParams", int.class, float[].class, float[].class);
        } catch (Throwable ignored) {
        }

        sOplusAnimLevel = animLevel;
        sSetBgRenderEffect = setBgEffect;
        sSetShadowClipping = setShadowClip;
        sOplusViewCtor = oplusViewCtor;
        sSetOverrideLightSource = setOverrideLight;
        sViewRootManagerCtor = vrmCtor;
        sVrmGetBgBlurDrawable = vrmGetBlurDrw;
        sVrmSetBlurRadius = vrmSetRadius;
        sVrmSetCornerRadius = vrmSetCorner;
        sVrmSetBlurParams = vrmSetParams;
        sBlurParamCtor = bpCtor;
        sBpSetBlurType = bpSetType;
        sBpSetSmoothCornerType = bpSetCornerType;
        sBpSetSmoothCornerWeight = bpSetCornerWeight;
        sBpSetMaterialParams = bpSetMatParams;
        sBaseParamsCtor = baseCtor;
        sCornerParamsCtor = cornerCtor;
        sEdgeParamsCtor = edgeCtor;
        sShadowParamsCtor = shadowCtor;
        sMetaBallParamsCtor = metaCtor;
        sSetBaseParams = setBase;
        sSetCornerParams = setCorner;
        sSetEdgeParams = setEdge;
        sSetShadowParams = setShadow;
        sSetMetaBallParams = setMeta;
        sSetMetaBallChain = setMetaChain;
        sSetCausticColor = setCausticCol;
        sSetCausticLayout = setCausticLay;
        sSetCausticParams = setCausticPar;
        sOutlineSmoothCtor = outSmoothCtor;
        sOutlineSmoothSetRoundRect = outSmoothSetRect;
        sWaveformBuilderCtor = wfBuilderCtor;
        sWaveformSetType = wfSetType;
        sWaveformSetAsync = wfSetAsync;
        sWaveformBuild = wfBuild;
        sLinearMotorVibrate = lmVibrate;
    }

    private ColorOsLivingFlowEngine() {
    }

    public static boolean isBackdropBlurSupported() {
        return Build.VERSION.SDK_INT >= 31 && sSetBgRenderEffect != null && sOplusAnimLevel < 3;
    }

    public static boolean isHardwareMaterialSupported() {
        return Build.VERSION.SDK_INT >= 31
                && sSetEdgeParams != null
                && sSetShadowParams != null
                && sSetCornerParams != null;
    }

    public static void applyGlassSurface(View view, GlassPreset preset, boolean dark,
                                         float cornerRadiusDp, boolean isCircle, boolean isCapsule) {
        if (view == null) return;
        final Context ctx = view.getContext();
        final float d = ctx.getResources().getDisplayMetrics().density;
        final float cornerWeight = (isCircle || isCapsule) ? 1.0f : 1.5f;

        if (!isCircle && !isCapsule) {
            try {
                if (sSetShadowClipping != null) {
                    sSetShadowClipping.invoke(null, view, true);
                }
            } catch (Throwable ignored) {
            }
        }

        installSmoothOutline(view, cornerRadiusDp, cornerWeight, isCircle, isCapsule);

        if (preset == GlassPreset.DARK_FROSTED_CARD) {
            view.setClipToOutline(true);
            applyGlassFallbackDrawable(view, preset, dark, cornerRadiusDp, isCircle, isCapsule, d);
            return;
        }

        if (preset == GlassPreset.EMBEDDED_TOP_CIRCLE) {
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            if (!isHardwareMaterialSupported()) {
                bg.setColor(dark ? 0xCC2C2C32 : 0xD4FFFFFF);
                bg.setStroke(Math.max(1, Math.round(0.85f * d)), dark ? 0x2AFFFFFF : 0x16000000);
                view.setBackground(bg);
                applyDiffuseFloatingShadow(view, GlassPreset.TOP_BAR_CIRCLE, dark, d);
                return;
            }
            bg.setColor(dark ? 0x9E2E2E34 : 0xB8FFFFFF);
            if (sSetCornerParams != null) {
                view.setClipToOutline(false);
            }
            view.setBackground(bg);
            applyPresetMaterialTokens(view, GlassPreset.TOP_BAR_CIRCLE, dark, cornerRadiusDp, true, false, 1.0f, d);
            return;
        }

        if (!isBackdropBlurSupported() || Build.VERSION.SDK_INT < 31) {
            applyGlassFallbackDrawable(view, preset, dark, cornerRadiusDp, isCircle, isCapsule, d);
            return;
        }

        boolean hwMaterial = isHardwareMaterialSupported();
        if (isCircle || isCapsule) {
            if (hwMaterial) {
                if (sSetCornerParams != null) {
                    view.setClipToOutline(false);
                }
                // Transparent View background lets ColorOS RenderNode background RenderEffect refract content cleanly
                view.setBackground(new ColorDrawable(Color.TRANSPARENT));
            } else {
                view.setClipToOutline(true);
                GradientDrawable fallbackGlass = new GradientDrawable();
                if (isCircle) {
                    fallbackGlass.setShape(GradientDrawable.OVAL);
                } else {
                    fallbackGlass.setShape(GradientDrawable.RECTANGLE);
                    fallbackGlass.setCornerRadius(999f * d);
                }
                int tint = (preset == GlassPreset.OTA_TOP_CIRCLE)
                        ? 0x28222036
                        : (dark ? 0x18222226 : 0x24FFFFFF);
                int strokeCol = (preset == GlassPreset.OTA_TOP_CIRCLE)
                        ? 0x32FFFFFF
                        : (dark ? 0x28FFFFFF : 0x16000000);
                fallbackGlass.setColor(tint);
                fallbackGlass.setStroke(Math.max(1, Math.round(0.85f * d)), strokeCol);
                view.setBackground(fallbackGlass);
            }
        } else {
            GradientDrawable rectBg = new GradientDrawable();
            rectBg.setShape(GradientDrawable.RECTANGLE);
            rectBg.setColor(Color.TRANSPARENT);
            rectBg.setCornerRadius(cornerRadiusDp * d);
            int strokeW = preset == GlassPreset.DARK_FROSTED_CARD
                    ? Math.max(1, Math.round(0.65f * d))
                    : Math.max(1, Math.round(0.85f * d));
            int strokeColor = preset == GlassPreset.DARK_FROSTED_CARD
                    ? 0x24FFFFFF
                    : (dark ? 0x24FFFFFF : 0x14000000);
            rectBg.setStroke(strokeW, strokeColor);
            view.setBackground(rectBg);
        }

        RenderEffect effect = buildPresetBlurChain(preset, dark);
        try {
            if (effect != null) {
                sSetBgRenderEffect.invoke(null, effect, view);
            }
        } catch (Throwable t) {
            Log.d(TAG, "setBackgroundRenderEffect failed, using fallback", t);
            applyGlassFallbackDrawable(view, preset, dark, cornerRadiusDp, isCircle, isCapsule, d);
            return;
        }

        applyPresetMaterialTokens(view, preset, dark, cornerRadiusDp, isCircle, isCapsule, cornerWeight, d);
    }

    private static RenderEffect buildPresetBlurChain(GlassPreset preset, boolean dark) {
        if (Build.VERSION.SDK_INT < 31) return null;
        final float radius;
        if (preset == GlassPreset.TOP_BAR_CIRCLE || preset == GlassPreset.OTA_TOP_CIRCLE) {
            radius = 20f;
        } else if (preset == GlassPreset.POPUP_MENU) {
            radius = 30f;
        } else if (preset == GlassPreset.DARK_FROSTED_CARD) {
            radius = 34f;
        } else {
            radius = 25f;
        }
        final int c1;
        final BlendMode m1;
        final int c2;
        final BlendMode m2;

        if (preset == GlassPreset.OTA_TOP_CIRCLE) {
            c1 = 0x5A201C34;
            m1 = BlendMode.SRC_OVER;
            c2 = 0x3058507E;
            m2 = BlendMode.OVERLAY;
        } else if (preset == GlassPreset.DARK_FROSTED_CARD) {
            c1 = 0x7A1A1830;
            m1 = BlendMode.SRC_OVER;
            c2 = 0x30605888;
            m2 = BlendMode.OVERLAY;
        } else if (preset == GlassPreset.TOP_BAR_CIRCLE) {
            if (dark) {
                c1 = 0xCC262626;
                m1 = BlendMode.SRC_OVER;
                c2 = 0xB37F7F7F;
                m2 = BlendMode.OVERLAY;
            } else {
                c1 = 0x66333333;
                m1 = BlendMode.COLOR_DODGE;
                c2 = 0x99F5F5F5;
                m2 = BlendMode.LUMINOSITY;
            }
        } else if (preset == GlassPreset.POPUP_MENU) {
            if (dark) {
                c1 = 0xCC242428;
                m1 = BlendMode.SRC_OVER;
                c2 = 0x9968686E;
                m2 = BlendMode.OVERLAY;
            } else {
                c1 = 0x66383838;
                m1 = BlendMode.COLOR_DODGE;
                c2 = 0xB8F8F8FA;
                m2 = BlendMode.LUMINOSITY;
            }
        } else {
            // COUI Clock.apk COUIMaterialBlurEffect FRAMEWORK_BOTTOM_BAR_PARAMS_*
            if (dark) {
                c1 = 0x80808080;
                m1 = BlendMode.OVERLAY;
                c2 = 0x99333333;
                m2 = BlendMode.LUMINOSITY;
            } else {
                c1 = 0x80333333;
                m1 = BlendMode.COLOR_DODGE;
                c2 = 0x66FFFFFF;
                m2 = BlendMode.LUMINOSITY;
            }
        }

        RenderEffect blur = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.MIRROR);
        RenderEffect layer1 = RenderEffect.createColorFilterEffect(new BlendModeColorFilter(c1, m1), blur);
        return RenderEffect.createColorFilterEffect(new BlendModeColorFilter(c2, m2), layer1);
    }

    private static void applyPresetMaterialTokens(View view, GlassPreset preset, boolean dark,
                                                  float cornerRadiusDp, boolean isCircle, boolean isCapsule,
                                                  float cornerWeight, float d) {
        if (!isCircle && !isCapsule) {
            applyDiffuseFloatingShadow(view, preset, dark, d);
            return;
        }
        int edgeType = isCircle ? 2 : 1; // EDGE_TYPE_CIRCLE=2, EDGE_TYPE_RECTANGLE=1
        float edgeWidthPx;
        float edgeAlpha;
        float fadeInAlpha;
        float fadeOutAlpha = 1.0f;

        if (preset == GlassPreset.OTA_TOP_CIRCLE) {
            edgeWidthPx = 1.9f * d;
            edgeAlpha = 0.30f;
            fadeInAlpha = 0.12f;
        } else if (isCircle) {
            // Official Clock.apk TYPE_FRAMEWORK_CIRCLE_1
            edgeWidthPx = 1.9f * d;
            edgeAlpha = dark ? 0.20f : 0.60f;
            fadeInAlpha = dark ? 0.15f : 0.10f;
        } else {
            // Official Clock.apk TYPE_FRAMEWORK_CAPSULE_3
            edgeWidthPx = 2.5f * d;
            edgeAlpha = dark ? 0.25f : 0.80f;
            fadeInAlpha = dark ? 0.10f : 0.03f;
        }

        try {
            if (sSetEdgeParams != null && sEdgeParamsCtor != null) {
                Object edge = sEdgeParamsCtor.newInstance(edgeType, edgeWidthPx, edgeAlpha, 0f);
                sSetEdgeParams.invoke(null, view, edge);
            }
            if (sSetShadowParams != null && sShadowParamsCtor != null) {
                Object shadow = sShadowParamsCtor.newInstance(edgeType, 1.0f, fadeInAlpha, fadeOutAlpha);
                sSetShadowParams.invoke(null, view, shadow);
            }
            if (preset == GlassPreset.BOTTOM_BAR_PILL && sSetCausticParams != null) {
                // BaseBottomFloatingMenuView.K(): coui_caustic_color_2 + level4 layout + sdfWidth
                if (sSetCausticColor != null) {
                    sSetCausticColor.invoke(null, view, 0x42FFFFFF);
                }
                if (sSetCausticLayout != null) {
                    sSetCausticLayout.invoke(null, view, 0, 0, 0, Math.round(4.9f * d));
                }
                sSetCausticParams.invoke(null, view, 10.4f * d, 0.6f, 0.6f, 18.0f, 0.45f, 0.6f);
            } else if (preset == GlassPreset.TOP_BAR_CIRCLE && sSetCausticParams != null) {
                // ToolbarMaterialEffectDelegate: coui_caustic_color_1 + level2 layout + sdfWidth
                if (sSetCausticColor != null) {
                    sSetCausticColor.invoke(null, view, dark ? 0x33FFFFFF : 0x2EFFFFFF);
                }
                if (sSetCausticLayout != null) {
                    sSetCausticLayout.invoke(null, view, 0, 0, 0, Math.round(4.9f * d));
                }
                sSetCausticParams.invoke(null, view, 9.23f * d, 0.6f, 0.6f, 18.0f, 0.45f, 0.6f);
            }
        } catch (Throwable ignored) {
        }
        applyDiffuseFloatingShadow(view, preset, dark, d);
        attachGeometryLayoutListener(view, cornerRadiusDp, cornerWeight, isCircle, isCapsule, d);
    }

    /**
     * Applies official ColorOS 17 {@code COUIButton} / {@code COUIFloatingButton} hardware
     * luminous rim ({@code OplusMaterialEdgeParams} + {@code OplusMaterialShadowParams}) and
     * colored/subtle caustic halo directly on an existing pill button without altering its background.
     */
    public static void applyLuminousPillButton(View view, boolean isPrimaryColored,
                                               int accentColor, boolean dark, boolean enableCaustic) {
        if (view == null) return;
        final float d = view.getResources().getDisplayMetrics().density;
        installSmoothOutline(view, 999f, 1.0f, false, true);
        if (!isHardwareMaterialSupported()) {
            return;
        }
        view.setClipToOutline(false);

        final float edgeWidthPx = 2.8f * d;
        final float edgeAlpha;
        final float fadeScale;
        final float fadeIn;
        if (isPrimaryColored) {
            // TYPE_CONTENT_CAPSULE_3 (official Clock.apk COUIMaterialStrokeEffect)
            edgeAlpha = 0.50f;
            fadeScale = 1.0f;
            fadeIn = 0.05f;
        } else {
            // TYPE_CONTENT_CAPSULE_1
            edgeAlpha = dark ? 0.30f : 0.26f;
            fadeScale = dark ? 1.0f : 0.80f;
            fadeIn = dark ? 0.10f : 0.08f;
        }

        try {
            Object edge = sEdgeParamsCtor.newInstance(1, edgeWidthPx, edgeAlpha, 0f);
            sSetEdgeParams.invoke(null, view, edge);
            Object shadow = sShadowParamsCtor.newInstance(1, fadeScale, fadeIn, 1.0f);
            sSetShadowParams.invoke(null, view, shadow);

            if (enableCaustic && sSetCausticParams != null) {
                if (isPrimaryColored) {
                    int r = Color.red(accentColor);
                    int g = Color.green(accentColor);
                    int b = Color.blue(accentColor);
                    view.setElevation(8f * d);
                    if (Build.VERSION.SDK_INT >= 28) {
                        int shadowCol = Color.argb(30, r, g, b);
                        view.setOutlineAmbientShadowColor(shadowCol);
                        view.setOutlineSpotShadowColor(shadowCol);
                    }
                    if (sOplusViewCtor != null && sSetOverrideLightSource != null) {
                        Object ov = sOplusViewCtor.newInstance(view);
                        sSetOverrideLightSource.invoke(ov, -1.0f, -600f * d, 0f, 600f * d, 0f);
                    }
                    if (sSetCausticColor != null) {
                        sSetCausticColor.invoke(null, view, Color.argb(153, r, g, b));
                    }
                    if (sSetCausticLayout != null) {
                        sSetCausticLayout.invoke(null, view, 0, 0, 0, Math.round(4.9f * d));
                    }
                    sSetCausticParams.invoke(null, view, 10.4f * d, 0.6f, 0.6f, 18.0f, 0.45f, 0.6f);
                } else if (!dark) {
                    view.setElevation(12f * d);
                    if (Build.VERSION.SDK_INT >= 28) {
                        view.setOutlineAmbientShadowColor(0x16000000);
                        view.setOutlineSpotShadowColor(0x16000000);
                    }
                    if (sOplusViewCtor != null && sSetOverrideLightSource != null) {
                        Object ov = sOplusViewCtor.newInstance(view);
                        sSetOverrideLightSource.invoke(ov, -1.0f, -160f * d, 0f, 800f * d, 0f);
                    }
                    if (sSetCausticColor != null) {
                        sSetCausticColor.invoke(null, view, 0x2EFFFFFF);
                    }
                    if (sSetCausticLayout != null) {
                        sSetCausticLayout.invoke(null, view, 0, 0, 0, Math.round(4.9f * d));
                    }
                    sSetCausticParams.invoke(null, view, 10.4f * d, 0.6f, 0.6f, 18.0f, 0.45f, 0.6f);
                }
            }
        } catch (Throwable ignored) {
        }
        attachGeometryLayoutListener(view, 999f, 1.0f, false, true, d);
    }

    private static void attachGeometryLayoutListener(View view, float cornerRadiusDp, float cornerWeight,
                                                     boolean isCircle, boolean isCapsule, float d) {
        updateGeometryParamsNow(view, cornerRadiusDp, cornerWeight, isCircle, isCapsule, d);

        Object existing = view.getTag(TAG_LAYOUT_LISTENER);
        if (existing instanceof View.OnLayoutChangeListener) {
            view.removeOnLayoutChangeListener((View.OnLayoutChangeListener) existing);
        }
        View.OnLayoutChangeListener listener = (v, left, top, right, bottom, ol, ot, or, ob) -> {
            if ((right - left) != (or - ol) || (bottom - top) != (ob - ot)) {
                updateGeometryParamsNow(v, cornerRadiusDp, cornerWeight, isCircle, isCapsule, d);
            }
        };
        view.setTag(TAG_LAYOUT_LISTENER, listener);
        view.addOnLayoutChangeListener(listener);
    }

    private static void applyDiffuseFloatingShadow(View view, GlassPreset preset, boolean dark, float d) {
        if (view == null) return;
        if (preset == GlassPreset.BOTTOM_BAR_PILL) {
            if (sSetCausticParams != null) {
                view.setElevation(24f * d);
                if (Build.VERSION.SDK_INT >= 28) {
                    int shadowCol = Color.argb(dark ? 86 : 45, 0, 0, 0);
                    view.setOutlineAmbientShadowColor(shadowCol);
                    view.setOutlineSpotShadowColor(shadowCol);
                }
                if (sOplusViewCtor != null && sSetOverrideLightSource != null) {
                    try {
                        Object ov = sOplusViewCtor.newInstance(view);
                        sSetOverrideLightSource.invoke(ov, -1.0f, -320f * d, 0f, 1600f * d, 0f);
                    } catch (Throwable ignored) {
                    }
                }
            } else {
                view.setElevation(18f * d);
                if (Build.VERSION.SDK_INT >= 28) {
                    view.setOutlineAmbientShadowColor(dark ? 0x38000000 : 0x16000000);
                    view.setOutlineSpotShadowColor(dark ? 0x4E000000 : 0x22000000);
                }
            }
        } else if (preset == GlassPreset.POPUP_MENU) {
            view.setElevation(20f * d);
            if (Build.VERSION.SDK_INT >= 28) {
                view.setOutlineAmbientShadowColor(dark ? 0x38000000 : 0x1C000000);
                view.setOutlineSpotShadowColor(dark ? 0x4C000000 : 0x22000000);
            }
            if (sOplusViewCtor != null && sSetOverrideLightSource != null) {
                try {
                    Object ov = sOplusViewCtor.newInstance(view);
                    sSetOverrideLightSource.invoke(ov, -1.0f, -75f * d, 1000f * d, 900f * d, 0f);
                } catch (Throwable ignored) {
                }
            }
        } else if (preset == GlassPreset.DARK_FROSTED_CARD) {
            view.setElevation(0f);
        } else if (preset == GlassPreset.OTA_TOP_CIRCLE) {
            view.setElevation(0f);
        } else {
            // TOP_BAR_CIRCLE / DOCK_CIRCLE: official Clock.apk caustic upper geometry (lightZ=0)
            if (sSetCausticParams != null) {
                view.setElevation(16f * d);
                if (Build.VERSION.SDK_INT >= 28) {
                    int shadowCol = Color.argb(dark ? 64 : 30, 0, 0, 0);
                    view.setOutlineAmbientShadowColor(shadowCol);
                    view.setOutlineSpotShadowColor(shadowCol);
                }
                if (sOplusViewCtor != null && sSetOverrideLightSource != null) {
                    try {
                        Object ov = sOplusViewCtor.newInstance(view);
                        sSetOverrideLightSource.invoke(ov, -1.0f, -160f * d, 0f, 800f * d, 0f);
                    } catch (Throwable ignored) {
                    }
                }
            } else {
                view.setElevation(12f * d);
                if (Build.VERSION.SDK_INT >= 28) {
                    view.setOutlineAmbientShadowColor(dark ? 0x32000000 : 0x14000000);
                    view.setOutlineSpotShadowColor(dark ? 0x44000000 : 0x1E000000);
                }
                if (sOplusViewCtor != null && sSetOverrideLightSource != null) {
                    try {
                        Object ov = sOplusViewCtor.newInstance(view);
                        sSetOverrideLightSource.invoke(ov, -1.0f, -110f * d, 1000f * d, 900f * d, 0f);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private static void updateGeometryParamsNow(View view, float cornerRadiusDp, float cornerWeight,
                                                boolean isCircle, boolean isCapsule, float d) {
        int w = view.getWidth();
        int h = view.getHeight();
        float rPx;
        if ((isCircle || isCapsule) && h > 0 && w > 0) {
            rPx = (isCircle ? Math.min(w, h) : h) * 0.5f;
        } else {
            rPx = cornerRadiusDp * d;
        }
        try {
            if (sSetCornerParams != null && sCornerParamsCtor != null) {
                Object corner = sCornerParamsCtor.newInstance(rPx, cornerWeight);
                sSetCornerParams.invoke(null, view, corner);
            }
        } catch (Throwable ignored) {
        }
    }

    private static final class PopupGlassState {
        Object vrm;
        Drawable blurDrawable;
        float baseCornerPx;
        int baseBlurPx;
        float baseElevationPx;
        boolean hardwareMaterial;
        float lastScale = 1f;
        float lastAlpha = 1f;
    }

    private static void applyGlassFallbackDrawable(View view, GlassPreset preset, boolean dark,
                                                   float cornerRadiusDp, boolean isCircle, boolean isCapsule, float d) {
        if (preset == GlassPreset.DARK_FROSTED_CARD) {
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setColor(0xA418181E);
            bg.setCornerRadius(cornerRadiusDp * d);
            bg.setStroke(Math.max(1, Math.round(0.65f * d)), 0x24FFFFFF);
            view.setBackground(bg);
            view.setElevation(0f);
            return;
        }
        if (preset == GlassPreset.OTA_TOP_CIRCLE) {
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(0x40222036);
            bg.setStroke(Math.max(1, Math.round(0.85f * d)), 0x32FFFFFF);
            view.setBackground(bg);
            applyDiffuseFloatingShadow(view, preset, true, d);
            return;
        }
        GradientDrawable bg = new GradientDrawable();
        if (isCircle) {
            bg.setShape(GradientDrawable.OVAL);
        } else {
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius((isCapsule ? 999f : cornerRadiusDp) * d);
        }
        if (dark) {
            int col = preset == GlassPreset.POPUP_MENU ? 0xE02A2A30
                    : (preset == GlassPreset.TOP_BAR_CIRCLE ? 0xDC222226 : 0xE2222226);
            bg.setColor(col);
            bg.setStroke(Math.max(1, Math.round(0.85f * d)), 0x2EFFFFFF);
        } else {
            int col = preset == GlassPreset.POPUP_MENU ? 0xE8FFFFFF
                    : (preset == GlassPreset.TOP_BAR_CIRCLE ? 0xE6FFFFFF : 0xEEFFFFFF);
            bg.setColor(col);
            bg.setStroke(Math.max(1, Math.round(0.85f * d)),
                    preset == GlassPreset.POPUP_MENU ? 0x10000000 : 0x1E000000);
        }
        view.setBackground(bg);
        applyDiffuseFloatingShadow(view, preset, dark, d);
    }

    /**
     * Applies ColorOS 17 cross-window frosted glass blur (matching {@code COUIPopupListWindow} +
     * {@code RoundFrameLayout}) inside a floating {@link android.widget.PopupWindow}, falling back
     * gracefully to a crisp translucent ColorOS surface if cross-window blur is unavailable.
     */
    public static void applyPopupWindowGlass(View card, boolean dark, float cornerRadiusDp) {
        if (card == null) return;
        final float d = card.getResources().getDisplayMetrics().density;
        final PopupGlassState st = new PopupGlassState();
        st.baseCornerPx = cornerRadiusDp * d;
        st.baseBlurPx = Math.round(45f * d);
        st.baseElevationPx = 20f * d;
        card.setTag(TAG_POPUP_GLASS_STATE, st);

        applyGlassFallbackDrawable(card, GlassPreset.POPUP_MENU, dark, cornerRadiusDp, false, false, d);
        installSmoothOutline(card, cornerRadiusDp, 2.0f, false, false);
        card.setClipToOutline(true);

        if (!isBackdropBlurSupported() || sViewRootManagerCtor == null || sVrmGetBgBlurDrawable == null
                || sVrmSetBlurParams == null || sBlurParamCtor == null || sBpSetMaterialParams == null) {
            return;
        }
        card.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                try {
                    Object vrm = sViewRootManagerCtor.newInstance(v);
                    Object drw = sVrmGetBgBlurDrawable.invoke(vrm);
                    if (!(drw instanceof Drawable)) return;

                    Object bp = sBlurParamCtor.newInstance();
                    if (sBpSetBlurType != null) sBpSetBlurType.invoke(bp, 2);
                    if (sBpSetSmoothCornerType != null) sBpSetSmoothCornerType.invoke(bp, 1);
                    if (sBpSetSmoothCornerWeight != null) sBpSetSmoothCornerWeight.invoke(bp, 2.0f);
                    float[] blend = argbToRgbaFloats(dark ? 0x8A2C2C32 : 0x9EFFFFFF);
                    float[] mix = argbToRgbaFloats(dark ? 0x6236363E : 0x52FFFFFF);
                    sBpSetMaterialParams.invoke(bp, 4, blend, mix);
                    sVrmSetBlurParams.invoke(vrm, bp);

                    if (sVrmSetBlurRadius != null) {
                        sVrmSetBlurRadius.invoke(vrm, st.baseBlurPx);
                    }
                    if (sVrmSetCornerRadius != null) {
                        sVrmSetCornerRadius.invoke(vrm, st.baseCornerPx, st.baseCornerPx,
                                st.baseCornerPx, st.baseCornerPx);
                    }
                    st.vrm = vrm;
                    st.blurDrawable = (Drawable) drw;
                    v.setBackground((Drawable) drw);

                    if (isHardwareMaterialSupported()) {
                        st.hardwareMaterial = true;
                        v.setClipToOutline(false);
                        if (sSetCornerParams != null && sCornerParamsCtor != null) {
                            Object corner = sCornerParamsCtor.newInstance(st.baseCornerPx, 2.0f);
                            sSetCornerParams.invoke(null, v, corner);
                        }
                        if (sSetEdgeParams != null && sEdgeParamsCtor != null) {
                            Object edge = sEdgeParamsCtor.newInstance(1, 2.2f * d, dark ? 0.32f : 0.85f, 0f);
                            sSetEdgeParams.invoke(null, v, edge);
                        }
                        if (sSetShadowParams != null && sShadowParamsCtor != null) {
                            Object shadow = sShadowParamsCtor.newInstance(1, 1.0f, dark ? 0.12f : 0.04f, 1.0f);
                            sSetShadowParams.invoke(null, v, shadow);
                        }
                        if (Build.VERSION.SDK_INT >= 28) {
                            v.setOutlineAmbientShadowColor(dark ? 0x38000000 : 0x1C000000);
                            v.setOutlineSpotShadowColor(dark ? 0x4C000000 : 0x22000000);
                        }
                        if (sOplusViewCtor != null && sSetOverrideLightSource != null) {
                            Object ov = sOplusViewCtor.newInstance(v);
                            sSetOverrideLightSource.invoke(ov, -1.0f, -75f * d, 1000f * d, 900f * d, 0f);
                        }
                        attachGeometryLayoutListener(v, cornerRadiusDp, 2.0f, false, false, d);
                    }
                    syncPopupGlassTransform(v, st.lastScale, st.lastAlpha, false);
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                st.vrm = null;
                st.blurDrawable = null;
            }
        });
    }

    /**
     * Synchronizes cross-window blur radius, smooth corner radius, drawable alpha, and elevation
     * with popup open/dismiss spring animations (matching official {@code RoundFrameLayout}).
     */
    public static void syncPopupGlassTransform(View card, float scale, float alpha) {
        syncPopupGlassTransform(card, scale, alpha, false);
    }

    public static void syncPopupGlassTransform(View card, float scale, float alpha, boolean isDismissing) {
        if (card == null) return;
        Object raw = card.getTag(TAG_POPUP_GLASS_STATE);
        if (!(raw instanceof PopupGlassState)) return;
        PopupGlassState st = (PopupGlassState) raw;
        float a = Math.max(0f, Math.min(1f, alpha));
        float s = Math.max(0.15f, scale);
        st.lastScale = s;
        st.lastAlpha = a;

        float elevFactor = a * a * Math.max(0f, Math.min(1f, (s - 0.32f) / 0.68f));
        card.setElevation(st.baseElevationPx * elevFactor);

        int alphaByte = Math.round(a * 255f);
        if (st.blurDrawable != null) {
            st.blurDrawable.setAlpha(alphaByte);
        } else {
            Drawable bg = card.getBackground();
            if (bg != null) {
                bg.setAlpha(alphaByte);
            }
        }
        if (st.vrm != null) {
            try {
                if (sVrmSetBlurRadius != null) {
                    sVrmSetBlurRadius.invoke(st.vrm, (isDismissing && a <= 0.04f) ? 0 : st.baseBlurPx);
                }
                if (sVrmSetCornerRadius != null) {
                    float r = st.baseCornerPx * s;
                    sVrmSetCornerRadius.invoke(st.vrm, r, r, r, r);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static float[] argbToRgbaFloats(int argb) {
        return new float[]{
                ((argb >> 16) & 0xFF) / 255f,
                ((argb >> 8) & 0xFF) / 255f,
                (argb & 0xFF) / 255f,
                ((argb >> 24) & 0xFF) / 255f
        };
    }

    public static void applySmoothCard(View view, float cornerRadiusDp, float weight,
                                       boolean dark, boolean luminousRim) {
        if (view == null) return;
        view.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        view.setClipToOutline(true);
    }

    public static void bindMetaBallDock(View leftBtn, View centerPill, View rightBtn) {
        if (leftBtn == null || centerPill == null || rightBtn == null) return;
        if (!isBackdropBlurSupported() || sSetMetaBallParams == null || sSetMetaBallChain == null || sMetaBallParamsCtor == null) {
            return;
        }
        try {
            // MODE_X = 0, index 1..3
            sSetMetaBallParams.invoke(null, leftBtn, sMetaBallParamsCtor.newInstance(1, 0, 0, 0f, 0f));
            sSetMetaBallParams.invoke(null, centerPill, sMetaBallParamsCtor.newInstance(2, 0, 0, 0f, 0f));
            sSetMetaBallParams.invoke(null, rightBtn, sMetaBallParamsCtor.newInstance(3, 0, 0, 0f, 0f));

            Set<View> chain = new LinkedHashSet<>();
            chain.add(leftBtn);
            chain.add(centerPill);
            chain.add(rightBtn);
            sSetMetaBallChain.invoke(null, chain);
        } catch (Throwable ignored) {
        }
    }

    public static void applyWindowGlassBlur(Window window, View cardView, boolean dark, float cornerRadiusDp) {
        if (window == null) return;
        float d = window.getContext().getResources().getDisplayMetrics().density;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.setBlurBehindRadius(Math.round(28f * d));
                window.setAttributes(lp);
            } catch (Throwable ignored) {
            }
        }
        if (cardView != null) {
            applySmoothCard(cardView, cornerRadiusDp, 2.0f, dark, true);
        }
    }

    public static void performHaptic(View view, HapticKind kind) {
        if (view == null) return;
        Context ctx = view.getContext();
        if (!sLinearMotorDenied && sLinearMotorVibrate != null && sWaveformBuilderCtor != null && ctx != null) {
            try {
                Object lm = ctx.getSystemService("linearmotor");
                if (lm != null) {
                    int effectId;
                    switch (kind) {
                        case TICK:
                            effectId = WAVEFORM_TICK;
                            break;
                        case SWITCH:
                            effectId = WAVEFORM_SWITCH;
                            break;
                        case CONFIRM:
                            effectId = WAVEFORM_CONFIRM;
                            break;
                        case TAP:
                        default:
                            effectId = WAVEFORM_TAP;
                            break;
                    }
                    Object builder = sWaveformBuilderCtor.newInstance();
                    sWaveformSetType.invoke(builder, effectId);
                    sWaveformSetAsync.invoke(builder, true);
                    Object waveform = sWaveformBuild.invoke(builder);
                    sLinearMotorVibrate.invoke(lm, waveform);
                    return;
                }
            } catch (Throwable t) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                if (cause instanceof SecurityException) {
                    sLinearMotorDenied = true;
                }
            }
        }

        int flag = (kind == HapticKind.TICK)
                ? HapticFeedbackConstants.CLOCK_TICK
                : (Build.VERSION.SDK_INT >= 23
                ? HapticFeedbackConstants.CONTEXT_CLICK
                : HapticFeedbackConstants.KEYBOARD_TAP);
        view.performHapticFeedback(flag);
    }

    public static void installSmoothOutline(View view, float cornerRadiusDp, float weight,
                                            boolean isCircle, boolean isCapsule) {
        final float d = view.getResources().getDisplayMetrics().density;
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                int w = v.getWidth();
                int h = v.getHeight();
                if (w <= 0 || h <= 0) return;
                if (isCircle) {
                    int size = Math.min(w, h);
                    int ox = (w - size) / 2;
                    int oy = (h - size) / 2;
                    outline.setOval(ox, oy, ox + size, oy + size);
                    return;
                }
                float radius = isCapsule ? (h * 0.5f) : (cornerRadiusDp * d);
                if (!sOutlineSmoothBlocked && sOutlineSmoothCtor != null && sOutlineSmoothSetRoundRect != null && !isCapsule) {
                    try {
                        Object smoother = sOutlineSmoothCtor.newInstance(outline);
                        sOutlineSmoothSetRoundRect.invoke(smoother, 0, 0, w, h, radius, weight);
                        return;
                    } catch (Throwable ignored) {
                        sOutlineSmoothBlocked = true;
                    }
                }
                outline.setRoundRect(0, 0, w, h, radius);
            }
        });
        view.setClipToOutline(true);
    }
}
