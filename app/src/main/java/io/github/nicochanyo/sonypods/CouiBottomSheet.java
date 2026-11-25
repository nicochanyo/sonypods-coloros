package io.github.nicochanyo.sonypods;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Shared ColorOS 17 styled floating bottom sheet, centered modal card, pill input/selector
 * widgets, and anchored COUI popup list window. Zero external resource ID dependencies so it
 * renders identically inside both the module app and hooked system apps ({@code com.heytap.mydevices}).
 */
public final class CouiBottomSheet {
    private static final int TAG_TOUCH_POINT = 0x53504354;
    private static final int TAG_TOUCH_FEEDBACK = 0x53504C54;
    private static final PathInterpolator FLUID_EASE = new PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f);
    private static final PathInterpolator POPUP_OPEN_EASE = new PathInterpolator(0.20f, 1.06f, 0.30f, 1.0f);
    private static final PathInterpolator POPUP_CLOSE_EASE = new PathInterpolator(0.40f, 0f, 0.65f, 1f);
    private static final PathInterpolator SHEET_OPEN_EASE = new PathInterpolator(0.18f, 1.0f, 0.28f, 1.0f);
    private static final PathInterpolator SHEET_CLOSE_EASE = new PathInterpolator(0.40f, 0f, 0.75f, 1f);

    public interface IntCallback {
        void onSelected(int index);
    }

    public static final class Palette {
        public final boolean dark;
        public final int cardBg;
        public final int subcardBg;
        public final int cardStroke;
        public final int textPrimary;
        public final int textSecondary;
        public final int divider;
        public final int ripple;
        public final int accent;
        public final int onAccent;
        public final int tonal;
        public final int btnSecondaryBg;
        public final int seekbarTrack;
        public final int unselectedRing;
        public final int handleColor;
        public final int destructive;
        public final boolean livingFlow;

        public Palette(boolean dark, int cardBg, int subcardBg, int cardStroke,
                       int textPrimary, int textSecondary, int divider, int ripple,
                       int accent, int onAccent, int tonal, int btnSecondaryBg,
                       int seekbarTrack, int unselectedRing, int handleColor, int destructive) {
            this(dark, cardBg, subcardBg, cardStroke, textPrimary, textSecondary, divider, ripple,
                    accent, onAccent, tonal, btnSecondaryBg, seekbarTrack, unselectedRing,
                    handleColor, destructive, false);
        }

        public Palette(boolean dark, int cardBg, int subcardBg, int cardStroke,
                       int textPrimary, int textSecondary, int divider, int ripple,
                       int accent, int onAccent, int tonal, int btnSecondaryBg,
                       int seekbarTrack, int unselectedRing, int handleColor, int destructive,
                       boolean livingFlow) {
            this.dark = dark;
            this.cardBg = cardBg;
            this.subcardBg = subcardBg;
            this.cardStroke = cardStroke;
            this.textPrimary = textPrimary;
            this.textSecondary = textSecondary;
            this.divider = divider;
            this.ripple = ripple;
            this.accent = accent;
            this.onAccent = onAccent;
            this.tonal = tonal;
            this.btnSecondaryBg = btnSecondaryBg;
            this.seekbarTrack = seekbarTrack;
            this.unselectedRing = unselectedRing;
            this.handleColor = handleColor;
            this.destructive = destructive;
            this.livingFlow = livingFlow;
        }

        public static Palette fromModuleTheme(Context ctx) {
            boolean dark = ConfigStore.isDarkThemeActive(ctx);
            boolean lf = ConfigStore.isLivingFlowUi(ctx);
            int cardBg = lf
                    ? (dark ? 0xFF1E1E1E : 0xFFFFFFFF)
                    : ConfigStore.resolveThemeColor(ctx, R.attr.couiCard, R.color.coui_card);
            int subcardBg = lf
                    ? (dark ? 0xFF28282C : 0xFFF2F3F5)
                    : ConfigStore.resolveThemeColor(ctx, R.attr.couiSubcard, R.color.coui_subcard);
            int textPrimary = ConfigStore.resolveThemeColor(ctx, R.attr.couiTextPrimary, R.color.coui_text_primary);
            int textSecondary = ConfigStore.resolveThemeColor(ctx, R.attr.couiTextSecondary, R.color.coui_text_secondary);
            int divider = ConfigStore.resolveThemeColor(ctx, R.attr.couiDivider, R.color.coui_divider);
            int ripple = ConfigStore.resolveThemeColor(ctx, R.attr.couiRipple, R.color.coui_ripple);
            int btnSec = ConfigStore.resolveThemeColor(ctx, R.attr.couiBtnSecondaryBg, R.color.coui_btn_secondary_bg);
            int track = ConfigStore.resolveThemeColor(ctx, R.attr.couiSeekbarTrack, R.color.coui_seekbar_track);
            int accent = DynamicThemeHelper.getAccent(ctx);
            int onAccent = DynamicThemeHelper.getOnAccent(ctx);
            int tonal = DynamicThemeHelper.getTonal(ctx);
            return new Palette(
                    dark,
                    cardBg,
                    subcardBg,
                    dark ? 0x22FFFFFF : 0x10000000,
                    textPrimary,
                    textSecondary,
                    divider,
                    ripple,
                    accent,
                    onAccent,
                    tonal,
                    btnSec,
                    track,
                    dark ? 0xFF48484C : 0xFFC8CBD3,
                    dark ? 0x38FFFFFF : 0x24000000,
                    dark ? 0xFFFF554A : 0xFFD93025,
                    lf
            );
        }

        public static Palette fromSystemOverlay(Context ctx) {
            boolean dark = (ctx.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

            int accent = resolveHostAccentColor(ctx, dark);
            int onAccent = colorLuminance(accent) < 0.66f ? 0xFFFFFFFF : 0xFF121214;
            int tonal = ((dark ? 0x33 : 0x1F) << 24) | (accent & 0x00FFFFFF);

            int defaultCardBg = dark ? 0xFF242426 : 0xFFFFFFFF;
            int cardBg = resolveAttrColor(
                    ctx,
                    new String[]{"couiColorSurfaceTop", "couiColorSurface", "couiColorCardBackground"},
                    new int[]{android.R.attr.colorBackgroundFloating},
                    defaultCardBg,
                    true);

            int subcardBg = resolveAttrColor(
                    ctx,
                    new String[]{"couiColorCardBackground", "couiColorBackground", "couiColorSurfaceWithCard"},
                    null,
                    0,
                    true);
            if (subcardBg == 0 || subcardBg == cardBg) {
                subcardBg = dark ? adjustBrightness(cardBg, 1.30f) : 0xFFF4F5F7;
                if (subcardBg == cardBg) {
                    subcardBg = dark ? 0xFF2F2F33 : 0xFFF3F5F9;
                }
            }

            int textPrimary = resolveAttrColor(
                    ctx,
                    new String[]{"couiColorPrimaryNeutral", "couiColorPrimaryText"},
                    new int[]{android.R.attr.textColorPrimary},
                    dark ? 0xFFF2F2F7 : 0xFF1A1A1A,
                    true);
            int textSecondary = resolveAttrColor(
                    ctx,
                    new String[]{"couiColorSecondNeutral", "couiColorSecondaryText"},
                    new int[]{android.R.attr.textColorSecondary},
                    dark ? 0xFF98989E : 0xFF7A7A82,
                    true);
            int divider = resolveAttrColor(
                    ctx,
                    new String[]{"couiColorDivider"},
                    null,
                    dark ? 0x1AFFFFFF : 0x12000000,
                    false);
            int ripple = resolveAttrColor(
                    ctx,
                    new String[]{"couiColorPressBackground", "couiColorCardPressed"},
                    null,
                    dark ? 0x22FFFFFF : 0x12000000,
                    false);
            int btnSec = resolveAttrColor(
                    ctx,
                    new String[]{"couiColorPressBackground", "couiColorCardPressed", "couiColorContainerTheme"},
                    null,
                    dark ? 0xFF2E2E34 : 0xFFEBECEF,
                    true);
            int track = resolveAttrColor(
                    ctx,
                    new String[]{"couiColorDivider", "couiColorControlNormal"},
                    null,
                    dark ? 0xFF3A3A40 : 0xFFE2E4EA,
                    true);
            if (Color.alpha(track) < 0xC0) {
                track = dark ? 0xFF3A3A40 : 0xFFE2E4EA;
            }

            return new Palette(
                    dark,
                    cardBg,
                    subcardBg,
                    dark ? 0x22FFFFFF : 0x10000000,
                    textPrimary,
                    textSecondary,
                    divider,
                    ripple,
                    accent,
                    onAccent,
                    tonal,
                    btnSec,
                    track,
                    dark ? 0xFF525258 : 0xFFC5C8D0,
                    dark ? 0x38FFFFFF : 0x24000000,
                    dark ? 0xFFFF554A : 0xFFD93025
            );
        }

        private static int resolveHostAccentColor(Context ctx, boolean dark) {
            float[] hsv = new float[3];
            int attrColor = resolveAttrColor(
                    ctx,
                    new String[]{"couiColorPrimary", "couiColorPrimaryTheme", "couiColorLabelTheme", "colorPrimary", "colorAccent"},
                    new int[]{android.R.attr.colorAccent, android.R.attr.colorPrimary},
                    0,
                    true);
            if (attrColor != 0) {
                Color.colorToHSV(attrColor, hsv);
                if (hsv[1] >= 0.05f) {
                    return attrColor;
                }
            }
            if (Build.VERSION.SDK_INT >= 31 && ctx != null) {
                int[] monetIds = dark
                        ? new int[]{android.R.color.system_accent1_300, android.R.color.system_accent1_200, android.R.color.system_accent1_400}
                        : new int[]{android.R.color.system_accent1_600, android.R.color.system_accent1_500, android.R.color.system_accent1_700};
                for (int resId : monetIds) {
                    try {
                        int c = ctx.getColor(resId);
                        Color.colorToHSV(c, hsv);
                        if (hsv[1] >= 0.05f) {
                            return c;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            return dark ? 0xFF3B82FF : 0xFF0066FF;
        }

        private static int resolveAttrColor(Context ctx, String[] attrNames, int[] androidAttrs,
                                            int fallback, boolean requireOpaque) {
            if (ctx == null || ctx.getTheme() == null) {
                return fallback;
            }
            TypedValue tv = new TypedValue();
            if (attrNames != null) {
                String pkg = ctx.getPackageName();
                for (String name : attrNames) {
                    int attrId = ctx.getResources().getIdentifier(name, "attr", pkg);
                    if (attrId != 0 && ctx.getTheme().resolveAttribute(attrId, tv, true)) {
                        int resolved = extractColorFromTypedValue(ctx, tv, requireOpaque);
                        if (resolved != 0) return resolved;
                    }
                }
            }
            if (androidAttrs != null) {
                for (int attrId : androidAttrs) {
                    if (attrId != 0 && ctx.getTheme().resolveAttribute(attrId, tv, true)) {
                        int resolved = extractColorFromTypedValue(ctx, tv, requireOpaque);
                        if (resolved != 0) return resolved;
                    }
                }
            }
            return fallback;
        }

        private static int extractColorFromTypedValue(Context ctx, TypedValue tv, boolean requireOpaque) {
            int c = 0;
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                c = tv.data;
            } else if (tv.resourceId != 0) {
                try {
                    c = ctx.getColor(tv.resourceId);
                } catch (Throwable ignored) {
                    return 0;
                }
            }
            if (c == 0) return 0;
            if (requireOpaque && Color.alpha(c) < 0xE0) return 0;
            return c;
        }

        private static int adjustBrightness(int color, float factor) {
            int r = Math.min(255, Math.max(0, Math.round(Color.red(color) * factor)));
            int g = Math.min(255, Math.max(0, Math.round(Color.green(color) * factor)));
            int b = Math.min(255, Math.max(0, Math.round(Color.blue(color) * factor)));
            return Color.argb(Color.alpha(color), r, g, b);
        }

        private static float colorLuminance(int color) {
            return (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f;
        }
    }

    public static final class SheetHandle {
        public final Dialog dialog;
        public final LinearLayout card;
        public final Palette palette;

        SheetHandle(Dialog dialog, LinearLayout card, Palette palette) {
            this.dialog = dialog;
            this.card = card;
            this.palette = palette;
        }
    }

    private CouiBottomSheet() {
    }

    public static void trackTouchPoint(View view) {
        if (view == null || view.getTag(TAG_TOUCH_FEEDBACK) != null) return;
        view.setOnTouchListener((v, ev) -> {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN
                    || action == MotionEvent.ACTION_MOVE
                    || action == MotionEvent.ACTION_UP) {
                v.setTag(TAG_TOUCH_POINT, new float[]{ev.getRawX(), ev.getRawY()});
            }
            return false;
        });
    }

    public static SheetHandle create(Context ctx, Palette pal) {
        return createSheetInternal(ctx, pal, false);
    }

    public static SheetHandle createCenter(Context ctx, Palette pal) {
        return createSheetInternal(ctx, pal, true);
    }

    private static SheetHandle createSheetInternal(Context ctx, Palette pal, boolean centerMode) {
        float d = ctx.getResources().getDisplayMetrics().density;
        final boolean lfDocked = pal.livingFlow && !centerMode;
        final LinearLayout card = new LinearLayout(ctx);
        final AnimatedDialog dialog = new AnimatedDialog(ctx, card, d, centerMode, lfDocked);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        final int marginH = Math.round((centerMode ? 22f : (lfDocked ? 0f : 12f)) * d);
        final int marginVTop = Math.round((centerMode ? 48f : (lfDocked ? 48f : 24f)) * d);
        int marginVBottomBase = centerMode
                ? Math.round(48f * d)
                : (lfDocked ? 0 : Math.round(12f * d) + resolveNavBarFallbackPx(ctx));

        FrameLayout outer = new FrameLayout(ctx);
        outer.setClipChildren(false);
        outer.setClipToPadding(false);
        outer.setPadding(marginH, marginVTop, marginH, marginVBottomBase);
        outer.setOnClickListener(v -> dialog.dismiss());

        outer.setOnApplyWindowInsetsListener((v, insets) -> {
            if (lfDocked) {
                int topInset = Build.VERSION.SDK_INT >= 30
                        ? insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()).top
                        : insets.getSystemWindowInsetTop();
                int navBottom = Build.VERSION.SDK_INT >= 30
                        ? insets.getInsets(WindowInsets.Type.systemBars()).bottom
                        : insets.getSystemWindowInsetBottom();
                int imeBottom = Build.VERSION.SDK_INT >= 30
                        ? insets.getInsets(WindowInsets.Type.ime()).bottom
                        : 0;
                v.setPadding(0, topInset + Math.round(12f * d), 0, 0);
                card.setPadding(
                        Math.round(16f * d),
                        Math.round(16f * d),
                        Math.round(16f * d),
                        Math.max(navBottom, imeBottom) + Math.round(18f * d));
            } else {
                int bottom = Build.VERSION.SDK_INT >= 30
                        ? insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()).bottom
                        : insets.getSystemWindowInsetBottom();
                int padBottom = centerMode
                        ? Math.max(Math.round(48f * d), bottom + Math.round(16f * d))
                        : bottom + Math.round(12f * d);
                v.setPadding(marginH, marginVTop, marginH, padBottom);
            }
            return insets;
        });

        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setFocusable(true);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        if (lfDocked) {
            int sheetFill = pal.dark ? 0xFF161618 : 0xFFF2F3F5;
            float r = 28f * d;
            cardBg.setColor(sheetFill);
            cardBg.setCornerRadii(new float[]{r, r, r, r, 0f, 0f, 0f, 0f});
            if (pal.dark) {
                cardBg.setStroke(Math.max(1, Math.round(0.6f * d)), 0x22FFFFFF);
            }
            card.setBackground(cardBg);
            card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            card.setClipToOutline(true);
            card.setElevation(24f * d);
            card.setPadding(
                    Math.round(16f * d),
                    Math.round(16f * d),
                    Math.round(16f * d),
                    resolveNavBarFallbackPx(ctx) + Math.round(18f * d));
        } else {
            int sheetFill = pal.livingFlow ? (pal.dark ? 0xE61C1C1F : 0xEBFFFFFF) : pal.cardBg;
            int sheetStroke = pal.livingFlow ? (pal.dark ? 0x2CFFFFFF : 0x4DFFFFFF) : pal.cardStroke;
            cardBg.setColor(sheetFill);
            cardBg.setCornerRadius(28f * d);
            cardBg.setStroke(Math.max(1, Math.round(0.75f * d)), sheetStroke);
            card.setBackground(cardBg);
            card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            card.setClipToOutline(true);
            card.setElevation(24f * d);
            card.setPadding(
                    Math.round((centerMode ? 22f : 20f) * d),
                    Math.round((centerMode ? 22f : 10f) * d),
                    Math.round((centerMode ? 22f : 20f) * d),
                    Math.round(20f * d));
        }

        if (!centerMode && !lfDocked) {
            View handle = new View(ctx);
            GradientDrawable handleBg = new GradientDrawable();
            handleBg.setShape(GradientDrawable.RECTANGLE);
            handleBg.setColor(pal.handleColor);
            handleBg.setCornerRadius(2f * d);
            handle.setBackground(handleBg);
            LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                    Math.round(36f * d), Math.round(4f * d));
            handleLp.gravity = Gravity.CENTER_HORIZONTAL;
            handleLp.bottomMargin = Math.round(14f * d);
            card.addView(handle, handleLp);
        }

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                centerMode ? Gravity.CENTER : Gravity.BOTTOM);
        outer.addView(card, cardLp);

        dialog.setContentView(outer);
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            win.setDimAmount(pal.dark ? 0.56f : (lfDocked ? 0.36f : 0.40f));
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                    | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            win.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
                    | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            win.setStatusBarColor(Color.TRANSPARENT);
            win.setNavigationBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= 29) {
                win.setNavigationBarContrastEnforced(false);
            }
            win.setWindowAnimations(0);
            if (lfDocked) {
                win.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR);
                if (Build.VERSION.SDK_INT >= 30) {
                    win.setDecorFitsSystemWindows(false);
                    try {
                        WindowManager.LayoutParams lp = win.getAttributes();
                        lp.setFitInsetsTypes(0);
                        lp.setFitInsetsSides(0);
                        lp.layoutInDisplayCutoutMode =
                                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                        for (Class<?> c = lp.getClass(); c != null; c = c.getSuperclass()) {
                            try {
                                java.lang.reflect.Field f = c.getDeclaredField("oplusFlags");
                                f.setAccessible(true);
                                f.setInt(lp, f.getInt(lp) | 0x8000);
                                break;
                            } catch (NoSuchFieldException ignored) {
                            }
                        }
                        win.setAttributes(lp);
                    } catch (Throwable ignored) {
                    }
                }
            } else if (Build.VERSION.SDK_INT >= 30) {
                win.setDecorFitsSystemWindows(false);
            }
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    (centerMode || lfDocked) ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT);
            win.setGravity(centerMode ? Gravity.CENTER : Gravity.BOTTOM);
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            if (lfDocked) {
                ColorOsLivingFlowEngine.applySmoothCard(card, 28f, 2.0f, pal.dark, false);
            }
            if (Build.VERSION.SDK_INT >= 30) {
                win.getDecorView().post(() -> {
                    android.view.WindowInsetsController ctrl = win.getInsetsController();
                    if (ctrl != null) {
                        int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                        ctrl.setSystemBarsAppearance(pal.dark ? 0 : mask, mask);
                    }
                });
            }
        }

        dialog.setOnShowListener(dIf -> {
            outer.requestApplyInsets();
            card.animate().cancel();
            if (centerMode) {
                card.setScaleX(0.86f);
                card.setScaleY(0.86f);
                card.setAlpha(0f);
                card.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(280L)
                        .setInterpolator(POPUP_OPEN_EASE)
                        .start();
            } else if (lfDocked) {
                card.setScaleX(1f);
                card.setScaleY(1f);
                card.setAlpha(1f);
                float startSlide = Math.max(260f * d, card.getHeight() > 0 ? card.getHeight() : 320f * d);
                card.setTranslationY(startSlide);
                LivingFlowEffects.FluidSpring slideSpring = LivingFlowEffects.FluidSpring.fromCoui(0.34f, 0.02f)
                        .setThresholds(0.4f, 3.0f);
                dialog.attachOpenSpring(slideSpring);
                slideSpring.setValue(startSlide);
                slideSpring.setListener(y -> card.setTranslationY(Math.max(0f, y)), null);
                slideSpring.animateTo(0f);
            } else {
                card.setTranslationY(110f * d);
                card.setScaleX(0.96f);
                card.setScaleY(0.96f);
                card.setAlpha(0f);
                card.animate()
                        .translationY(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(320L)
                        .setInterpolator(SHEET_OPEN_EASE)
                        .start();
            }
        });

        return new SheetHandle(dialog, card, pal);
    }

    private static final class AnimatedDialog extends Dialog {
        private static final PathInterpolator DOCKED_DISMISS_EASE = new PathInterpolator(0.35f, 0.0f, 0.68f, 1.0f);
        private final View card;
        private final float density;
        private final boolean centerMode;
        private final boolean lfDocked;
        private LivingFlowEffects.FluidSpring openSpring;
        private boolean dismissing;

        AnimatedDialog(Context ctx, View card, float density, boolean centerMode, boolean lfDocked) {
            super(ctx, lfDocked ? android.R.style.Theme_Translucent_NoTitleBar : 0);
            this.card = card;
            this.density = density;
            this.centerMode = centerMode;
            this.lfDocked = lfDocked;
        }

        void attachOpenSpring(LivingFlowEffects.FluidSpring spring) {
            if (this.openSpring != null) {
                this.openSpring.cancel();
            }
            this.openSpring = spring;
        }

        @Override
        public void dismiss() {
            if (dismissing) return;
            if (openSpring != null) {
                openSpring.cancel();
                openSpring = null;
            }
            Context ctx = getContext();
            if (ctx instanceof Activity) {
                Activity act = (Activity) ctx;
                if (act.isFinishing() || act.isDestroyed()) {
                    try {
                        super.dismiss();
                    } catch (Throwable ignored) {
                    }
                    return;
                }
            }
            if (!isShowing() || card == null || !card.isAttachedToWindow() || card.getWindowToken() == null) {
                try {
                    super.dismiss();
                } catch (Throwable ignored) {
                }
                return;
            }
            dismissing = true;
            card.animate().cancel();
            if (centerMode) {
                card.animate()
                        .scaleX(0.90f)
                        .scaleY(0.90f)
                        .alpha(0f)
                        .setDuration(170L)
                        .setInterpolator(SHEET_CLOSE_EASE)
                        .withEndAction(() -> {
                            try {
                                AnimatedDialog.super.dismiss();
                            } catch (Throwable ignored) {
                            }
                        })
                        .start();
            } else if (lfDocked) {
                float endSlide = Math.max(240f * density, card.getHeight());
                card.animate()
                        .translationY(endSlide)
                        .setDuration(220L)
                        .setInterpolator(DOCKED_DISMISS_EASE)
                        .withEndAction(() -> {
                            try {
                                AnimatedDialog.super.dismiss();
                            } catch (Throwable ignored) {
                            }
                        })
                        .start();
            } else {
                card.animate()
                        .translationY(88f * density)
                        .scaleX(0.97f)
                        .scaleY(0.97f)
                        .alpha(0f)
                        .setDuration(200L)
                        .setInterpolator(SHEET_CLOSE_EASE)
                        .withEndAction(() -> {
                            try {
                                AnimatedDialog.super.dismiss();
                            } catch (Throwable ignored) {
                            }
                        })
                        .start();
            }
        }
    }

    public static TextView addLivingFlowTopBar(Context ctx, SheetHandle sheet,
                                               String title, String cancelText,
                                               String actionText, int actionStyle,
                                               View.OnClickListener onAction) {
        float d = ctx.getResources().getDisplayMetrics().density;
        Palette pal = sheet.palette;

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setOutlineProvider(null);
        bar.setTranslationZ(16f * d);
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        bar.setPadding(0, Math.round(6f * d), 0, Math.round(10f * d));

        int pillH = Math.round(38f * d);
        int pillMinW = Math.round(64f * d);
        int pillPadH = Math.round(15f * d);

        TextView btnCancel = new TextView(ctx);
        btnCancel.setText(cancelText != null ? cancelText : locCancelText(ctx));
        btnCancel.setAllCaps(false);
        btnCancel.setSingleLine(true);
        btnCancel.setGravity(Gravity.CENTER);
        btnCancel.setMinWidth(pillMinW);
        btnCancel.setPadding(pillPadH, 0, pillPadH, 0);
        btnCancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        btnCancel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btnCancel.setTextColor(pal.textPrimary);

        GradientDrawable cancelBg = new GradientDrawable();
        cancelBg.setShape(GradientDrawable.RECTANGLE);
        cancelBg.setColor(pal.dark ? 0xFF2C2C30 : 0xFFFFFFFF);
        cancelBg.setCornerRadius(19f * d);
        if (pal.dark) {
            cancelBg.setStroke(Math.max(1, Math.round(0.6f * d)), 0x1FFFFFFF);
        }
        btnCancel.setBackground(new RippleDrawable(ColorStateList.valueOf(pal.ripple), cancelBg, cancelBg));
        if (!pal.dark) {
            btnCancel.setElevation(2f * d);
            if (Build.VERSION.SDK_INT >= 28) {
                btnCancel.setOutlineAmbientShadowColor(0x12000000);
                btnCancel.setOutlineSpotShadowColor(0x1E000000);
            }
        }
        LivingFlowEffects.TouchFeedback.attach(btnCancel,
                LivingFlowEffects.TouchFeedback.Profile.PRIMARY_BUTTON, pal.dark, 19f, false, true, false);
        ColorOsLivingFlowEngine.applyLuminousPillButton(btnCancel, false, pal.accent, pal.dark, true);
        btnCancel.setOnClickListener(v -> sheet.dialog.dismiss());

        bar.addView(btnCancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, pillH));

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvTitle.setTextColor(pal.textPrimary);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setSingleLine(true);
        tvTitle.setMaxLines(1);
        tvTitle.setAutoSizeTextTypeUniformWithConfiguration(13, 17, 1, TypedValue.COMPLEX_UNIT_SP);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMarginStart(Math.round(8f * d));
        titleLp.setMarginEnd(Math.round(8f * d));
        bar.addView(tvTitle, titleLp);

        TextView btnAction = null;
        if (actionText != null && !actionText.isEmpty()) {
            btnAction = new TextView(ctx);
            btnAction.setText(actionText);
            btnAction.setAllCaps(false);
            btnAction.setSingleLine(true);
            btnAction.setGravity(Gravity.CENTER);
            btnAction.setMinWidth(pillMinW);
            btnAction.setPadding(Math.round(14f * d), 0, Math.round(14f * d), 0);
            btnAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            btnAction.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

            int bgCol;
            int fgCol;
            if (actionStyle == 2) {
                bgCol = pal.destructive;
                fgCol = 0xFFFFFFFF;
            } else if (actionStyle == 1) {
                bgCol = pal.accent;
                fgCol = pal.onAccent;
            } else {
                bgCol = pal.dark ? 0xFF2C2C30 : 0xFFFFFFFF;
                fgCol = pal.accent;
            }
            btnAction.setTextColor(fgCol);

            GradientDrawable actBg = new GradientDrawable();
            actBg.setShape(GradientDrawable.RECTANGLE);
            actBg.setColor(bgCol);
            actBg.setCornerRadius(19f * d);
            int rCol = actionStyle == 0 ? pal.ripple : 0x33FFFFFF;
            btnAction.setBackground(new RippleDrawable(ColorStateList.valueOf(rCol), actBg, actBg));
            if (!pal.dark && actionStyle == 0) {
                btnAction.setElevation(2f * d);
                if (Build.VERSION.SDK_INT >= 28) {
                    btnAction.setOutlineAmbientShadowColor(0x12000000);
                    btnAction.setOutlineSpotShadowColor(0x1E000000);
                }
            }
            LivingFlowEffects.TouchFeedback.attach(btnAction,
                    LivingFlowEffects.TouchFeedback.Profile.PRIMARY_BUTTON, pal.dark, 19f, false, true, false);
            ColorOsLivingFlowEngine.applyLuminousPillButton(
                    btnAction, actionStyle != 0, actionStyle == 2 ? pal.destructive : pal.accent, pal.dark, true);
            btnAction.setOnClickListener(onAction);

            bar.addView(btnAction, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, pillH));
        } else {
            View spacer = new View(ctx);
            spacer.setVisibility(View.INVISIBLE);
            bar.addView(spacer, new LinearLayout.LayoutParams(pillMinW, 1));
        }

        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = -Math.round(6f * d);
        barLp.bottomMargin = Math.round(4f * d);
        sheet.card.addView(bar, barLp);
        return btnAction != null ? btnAction : tvTitle;
    }

    public static void showSingleChoiceSheet(Context ctx, Palette pal, String title,
                                             String[] items, int checkedIndex, IntCallback onSelect) {
        showSingleChoiceSheet(ctx, pal, title, null, items, checkedIndex, onSelect);
    }

    public static void showSingleChoiceSheet(Context ctx, Palette pal, String title, String cancelText,
                                             String[] items, int checkedIndex, IntCallback onSelect) {
        if (ctx == null || pal == null || items == null || items.length == 0) {
            return;
        }
        float d = ctx.getResources().getDisplayMetrics().density;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        final SheetHandle sheet = create(ctx, pal);
        addLivingFlowTopBar(ctx, sheet, title, cancelText != null ? cancelText : locCancelText(ctx), null, 0, null);

        ScrollView scroll = createMaxHeightScrollView(ctx, Math.round(dm.heightPixels * 0.56f));
        LinearLayout cardGroup = createSubcardGroup(ctx, pal);
        scroll.addView(cardGroup, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final DonutRadioIndicatorView[] radios = new DonutRadioIndicatorView[items.length];
        final boolean[] picked = new boolean[]{false};
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            boolean sel = (i == checkedIndex);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setClickable(true);
            row.setFocusable(true);
            row.setMinimumHeight(Math.round(58f * d));
            row.setBackground(createRowBackground(pal.ripple, 0));
            row.setPadding(Math.round(20f * d), Math.round(16f * d), Math.round(20f * d), Math.round(16f * d));

            TextView tv = new TextView(ctx);
            tv.setText(items[i]);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tv.setTextColor(pal.textPrimary);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tLp.setMarginEnd(Math.round(14f * d));
            row.addView(tv, tLp);

            DonutRadioIndicatorView radio = new DonutRadioIndicatorView(ctx, pal, sel);
            radios[i] = radio;
            int radioSize = Math.round(22f * d);
            row.addView(radio, new LinearLayout.LayoutParams(radioSize, radioSize));

            row.setOnClickListener(v -> {
                if (picked[0]) return;
                picked[0] = true;
                for (int k = 0; k < radios.length; k++) {
                    if (radios[k] != null) {
                        radios[k].setChecked(k == idx);
                    }
                }
                if (pal.livingFlow) {
                    ColorOsLivingFlowEngine.performHaptic(v, ColorOsLivingFlowEngine.HapticKind.TICK);
                }
                v.postDelayed(() -> {
                    sheet.dialog.dismiss();
                    if (onSelect != null) {
                        onSelect.onSelected(idx);
                    }
                }, 90L);
            });

            cardGroup.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i < items.length - 1) {
                addDivider(ctx, cardGroup, pal, 20);
            }
        }
        sheet.dialog.show();
    }

    public static TextView addHeader(Context ctx, LinearLayout card, Palette pal,
                                     String title, String subtitle, String rightBadgeText) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvTitle.setTextColor(pal.textPrimary);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(tvTitle, titleLp);

        TextView badgeTv = null;
        if (rightBadgeText != null) {
            badgeTv = new TextView(ctx);
            badgeTv.setText(rightBadgeText);
            badgeTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            badgeTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            badgeTv.setTextColor(pal.accent);
            badgeTv.setPadding(Math.round(11f * d), Math.round(4f * d), Math.round(11f * d), Math.round(4f * d));
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setColor(pal.tonal);
            bg.setCornerRadius(999f * d);
            badgeTv.setBackground(bg);
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeLp.setMarginStart(Math.round(10f * d));
            row.addView(badgeTv, badgeLp);
        }

        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView tvSub = new TextView(ctx);
            tvSub.setText(subtitle);
            tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            tvSub.setLineSpacing(0f, 1.25f);
            tvSub.setTextColor(pal.textSecondary);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = Math.round(5f * d);
            card.addView(tvSub, subLp);
        }
        return badgeTv;
    }

    public static LinearLayout createSubcardGroup(Context ctx, Palette pal) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout group = new LinearLayout(ctx);
        group.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        if (pal.livingFlow) {
            bg.setColor(pal.dark ? 0xFF242428 : 0xFFFFFFFF);
            bg.setCornerRadius(22f * d);
            if (pal.dark) {
                bg.setStroke(Math.max(1, Math.round(0.5f * d)), 0x16FFFFFF);
            }
        } else {
            bg.setColor(pal.subcardBg);
            bg.setCornerRadius(18f * d);
        }
        group.setBackground(bg);
        group.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        group.setClipToOutline(true);
        return group;
    }

    public static void addDivider(Context ctx, LinearLayout parent, Palette pal, int marginStartDp) {
        float d = ctx.getResources().getDisplayMetrics().density;
        View div = new View(ctx);
        div.setBackgroundColor(pal.divider);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(0.6f * d)));
        lp.setMarginStart(Math.round(marginStartDp * d));
        lp.setMarginEnd(Math.round(16f * d));
        parent.addView(div, lp);
    }

    public static CheckBadgeView addCheckRow(Context ctx, LinearLayout group, Palette pal,
                                             String title, String subtitle, boolean checked,
                                             boolean addBottomDivider, View.OnClickListener onClick) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(createRipple(pal.ripple));
        row.setPadding(Math.round(16f * d), Math.round(14f * d), Math.round(16f * d), Math.round(14f * d));
        row.setOnClickListener(v -> {
            if (pal.livingFlow) {
                ColorOsLivingFlowEngine.performHaptic(v, ColorOsLivingFlowEngine.HapticKind.TICK);
            }
            if (onClick != null) {
                onClick.onClick(v);
            }
        });

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", checked ? Typeface.BOLD : Typeface.NORMAL));
        tvTitle.setTextColor(checked ? pal.accent : pal.textPrimary);
        textCol.addView(tvTitle);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView tvSub = new TextView(ctx);
            tvSub.setText(subtitle);
            tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            tvSub.setTextColor(pal.textSecondary);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = Math.round(2f * d);
            textCol.addView(tvSub, slp);
        }

        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.setMarginEnd(Math.round(12f * d));
        row.addView(textCol, colLp);

        CheckBadgeView badge = new CheckBadgeView(ctx, pal.accent, pal.onAccent, pal.unselectedRing, checked);
        int badgeSize = Math.round(22f * d);
        row.addView(badge, new LinearLayout.LayoutParams(badgeSize, badgeSize));

        group.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (addBottomDivider) {
            addDivider(ctx, group, pal, 16);
        }
        return badge;
    }

    public static EditText addPillInputBox(Context ctx, LinearLayout parent, Palette pal,
                                           String initialValue, String hint) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(pal.subcardBg);
        bg.setCornerRadius(22f * d);
        bg.setStroke(Math.max(1, Math.round(0.8f * d)), pal.divider);
        box.setBackground(bg);
        box.setPadding(Math.round(16f * d), Math.round(4f * d), Math.round(10f * d), Math.round(4f * d));

        EditText input = new EditText(ctx);
        input.setBackground(null);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        input.setTextColor(pal.textPrimary);
        input.setHintTextColor(pal.textSecondary);
        input.setHint(hint);
        if (initialValue != null && !initialValue.isEmpty()) {
            input.setText(initialValue);
            input.setSelection(initialValue.length());
        }
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                0, Math.round(40f * d), 1f);
        box.addView(input, inLp);

        ClearGlyphView btnClear = new ClearGlyphView(ctx, pal.textSecondary, pal.subcardBg);
        btnClear.setVisibility((initialValue != null && !initialValue.isEmpty()) ? View.VISIBLE : View.GONE);
        btnClear.setOnClickListener(v -> input.setText(""));
        int clearSize = Math.round(28f * d);
        box.addView(btnClear, new LinearLayout.LayoutParams(clearSize, clearSize));

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                btnClear.setVisibility(s != null && s.length() > 0 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.topMargin = Math.round(14f * d);
        parent.addView(box, boxLp);
        return input;
    }

    /** ColorOS 17 Settings borderless single-input card (e.g. {@code COUICardSingleInputView}). */
    public static EditText addLivingFlowBorderlessInputCard(Context ctx, LinearLayout parent, Palette pal,
                                                             String initialValue, String hint) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout card = createSubcardGroup(ctx, pal);
        card.setMinimumHeight(Math.round(56f * d));
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Math.round(18f * d), Math.round(6f * d), Math.round(12f * d), Math.round(6f * d));

        EditText input = new EditText(ctx);
        input.setBackground(null);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        input.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        input.setTextColor(pal.textPrimary);
        input.setHintTextColor(pal.textSecondary);
        input.setHint(hint);
        if (Build.VERSION.SDK_INT >= 29) {
            GradientDrawable cursor = new GradientDrawable();
            cursor.setShape(GradientDrawable.RECTANGLE);
            cursor.setSize(Math.max(2, Math.round(2f * d)), Math.round(20f * d));
            cursor.setColor(pal.accent);
            input.setTextCursorDrawable(cursor);
        }
        if (initialValue != null && !initialValue.isEmpty()) {
            input.setText(initialValue);
            input.setSelection(initialValue.length());
        }
        card.addView(input, new LinearLayout.LayoutParams(0, Math.round(44f * d), 1f));

        ClearGlyphView btnClear = new ClearGlyphView(ctx, pal.textSecondary, pal.cardBg);
        btnClear.setVisibility((initialValue != null && !initialValue.isEmpty()) ? View.VISIBLE : View.GONE);
        btnClear.setOnClickListener(v -> {
            ColorOsLivingFlowEngine.performHaptic(v, ColorOsLivingFlowEngine.HapticKind.TAP);
            input.setText("");
        });
        int clearSize = Math.round(28f * d);
        card.addView(btnClear, new LinearLayout.LayoutParams(clearSize, clearSize));

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                btnClear.setVisibility(s != null && s.length() > 0 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        parent.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return input;
    }

    /** ColorOS 17 Settings Donut Radio preset list card two-way synced with {@code linkedInput}. */
    public static void addLivingFlowDonutPresetCard(Context ctx, LinearLayout parent, Palette pal,
                                                    String[] labels, String[] values, EditText linkedInput) {
        if (ctx == null || parent == null || labels == null || values == null || labels.length == 0) {
            return;
        }
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout cardGroup = createSubcardGroup(ctx, pal);
        final DonutRadioIndicatorView[] radios = new DonutRadioIndicatorView[labels.length];
        String initial = linkedInput != null && linkedInput.getText() != null
                ? linkedInput.getText().toString().trim() : "";

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            final String val = values[i];
            boolean sel = val.isEmpty() ? initial.isEmpty() : val.equalsIgnoreCase(initial);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setClickable(true);
            row.setFocusable(true);
            row.setMinimumHeight(Math.round(56f * d));
            row.setBackground(createRowBackground(pal.ripple, 0));
            row.setPadding(Math.round(20f * d), Math.round(15f * d), Math.round(20f * d), Math.round(15f * d));

            TextView tv = new TextView(ctx);
            tv.setText(labels[i]);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tv.setTextColor(pal.textPrimary);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tLp.setMarginEnd(Math.round(14f * d));
            row.addView(tv, tLp);

            DonutRadioIndicatorView radio = new DonutRadioIndicatorView(ctx, pal, sel);
            radios[i] = radio;
            int radioSize = Math.round(22f * d);
            row.addView(radio, new LinearLayout.LayoutParams(radioSize, radioSize));

            row.setOnClickListener(v -> {
                ColorOsLivingFlowEngine.performHaptic(v, ColorOsLivingFlowEngine.HapticKind.TICK);
                if (linkedInput != null) {
                    linkedInput.setText(val);
                    linkedInput.setSelection(val.length());
                }
                for (int k = 0; k < radios.length; k++) {
                    if (radios[k] != null) {
                        radios[k].setChecked(k == idx);
                    }
                }
            });

            cardGroup.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i < labels.length - 1) {
                addDivider(ctx, cardGroup, pal, 20);
            }
        }

        if (linkedInput != null) {
            linkedInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    String cur = s != null ? s.toString().trim() : "";
                    for (int k = 0; k < radios.length; k++) {
                        if (radios[k] != null) {
                            boolean match = values[k].isEmpty() ? cur.isEmpty() : values[k].equalsIgnoreCase(cur);
                            radios[k].setChecked(match);
                        }
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });
        }

        parent.addView(cardGroup, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    public static Button createPillButton(Context ctx, Palette pal, String text,
                                          int styleMode, View.OnClickListener onClick) {
        // styleMode: 0 = secondary neutral, 1 = primary accent, 2 = destructive crimson
        float d = ctx.getResources().getDisplayMetrics().density;
        Button btn = new Button(ctx);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setStateListAnimator(null);
        btn.setSingleLine(true);
        btn.setMaxLines(1);
        btn.setGravity(Gravity.CENTER);
        btn.setEllipsize(TextUtils.TruncateAt.END);
        btn.setPadding(Math.round(14f * d), 0, Math.round(14f * d), 0);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        btn.setAutoSizeTextTypeUniformWithConfiguration(11, 15, 1, TypedValue.COMPLEX_UNIT_SP);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        int bgColor;
        int fgColor;
        if (styleMode == 1) {
            bgColor = pal.accent;
            fgColor = pal.onAccent;
        } else if (styleMode == 2) {
            bgColor = pal.destructive;
            fgColor = 0xFFFFFFFF;
        } else {
            bgColor = pal.btnSecondaryBg;
            fgColor = pal.textPrimary;
        }
        btn.setTextColor(fgColor);

        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(bgColor);
        shape.setCornerRadius((pal.livingFlow ? 999f : 24f) * d);
        int rippleColor = styleMode == 0 ? pal.ripple : 0x33FFFFFF;
        btn.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), shape, shape));
        btn.setOnClickListener(onClick);
        if (pal.livingFlow) {
            LivingFlowEffects.TouchFeedback.attach(
                    btn, LivingFlowEffects.TouchFeedback.Profile.PRIMARY_BUTTON, pal.dark, 24f, false, true);
            ColorOsLivingFlowEngine.applyLuminousPillButton(
                    btn, styleMode != 0, styleMode == 2 ? pal.destructive : pal.accent, pal.dark, true);
        }
        return btn;
    }

    public static void addButtonRow(Context ctx, LinearLayout card, Palette pal,
                                    String leftText, int leftStyle, View.OnClickListener leftClick,
                                    String rightText, int rightStyle, View.OnClickListener rightClick) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        int h = Math.round(46f * d);

        Button leftBtn = createPillButton(ctx, pal, leftText, leftStyle, leftClick);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(0, h, 1f);
        lLp.setMarginEnd(Math.round(5f * d));
        row.addView(leftBtn, lLp);

        Button rightBtn = createPillButton(ctx, pal, rightText, rightStyle, rightClick);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(0, h, 1f);
        rLp.setMarginStart(Math.round(5f * d));
        row.addView(rightBtn, rLp);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Math.round(16f * d);
        card.addView(row, rowLp);
    }

    public static ScrollView createMaxHeightScrollView(Context ctx, int maxHeightPx) {
        final float d = ctx.getResources().getDisplayMetrics().density;
        CouiBounceScrollView sv = new CouiBounceScrollView(ctx) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int capped = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST);
                super.onMeasure(widthMeasureSpec, capped);
            }

            @Override
            protected void dispatchDraw(Canvas canvas) {
                int save = canvas.save();
                canvas.clipRect(getScrollX(), getScrollY(), getScrollX() + getWidth(), getScrollY() + getHeight());
                super.dispatchDraw(canvas);
                canvas.restoreToCount(save);
            }
        };
        sv.setDisallowParentOnDown(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(true);
        sv.setClipToPadding(true);
        sv.setOutlineProvider(ViewOutlineProvider.BOUNDS);
        sv.setClipToOutline(true);
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(Math.round(12f * d));
        return sv;
    }

    public static void addSingleButton(Context ctx, LinearLayout card, Palette pal,
                                       String text, int styleMode, int topMarginDp,
                                       View.OnClickListener onClick) {
        float d = ctx.getResources().getDisplayMetrics().density;
        Button btn = createPillButton(ctx, pal, text, styleMode, onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(46f * d));
        lp.topMargin = Math.round(topMarginDp * d);
        card.addView(btn, lp);
    }

    /**
     * ColorOS 17 styled anchored popup menu ({@code COUIPopupListWindow} + {@code PopupGravityMotionParams})
     * for compact option pickers and toolbar action menus.
     */
    public static void showPopupMenu(Context ctx, View anchor, Palette pal,
                                     String[] items, int checkedIndex, IntCallback onSelect) {
        if (ctx == null || anchor == null || items == null || items.length == 0) {
            return;
        }
        if (!anchor.isAttachedToWindow() || anchor.getWindowToken() == null) {
            return;
        }
        if (ctx instanceof Activity) {
            Activity act = (Activity) ctx;
            if (act.isFinishing() || act.isDestroyed()) {
                return;
            }
        }
        float d = ctx.getResources().getDisplayMetrics().density;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();

        PopupShadowContainer[] wrapperHolder = new PopupShadowContainer[1];
        final ViewGroup card;
        final LinearLayout rowsContainer;
        final float cardCornerDp = pal.livingFlow ? 24f : 20f;
        if (pal.livingFlow) {
            DragSelectMenuContainer dragMenu = new DragSelectMenuContainer(ctx, pal, d, items.length);
            card = dragMenu;
            rowsContainer = dragMenu.rowsList;
            ColorOsLivingFlowEngine.applyPopupWindowGlass(card, pal.dark, cardCornerDp);
        } else {
            LinearLayout plainCard = new LinearLayout(ctx);
            plainCard.setOrientation(LinearLayout.VERTICAL);
            plainCard.setClickable(true);
            plainCard.setFocusable(true);
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setShape(GradientDrawable.RECTANGLE);
            cardBg.setColor(pal.cardBg);
            cardBg.setCornerRadius(cardCornerDp * d);
            cardBg.setStroke(Math.max(1, Math.round(0.75f * d)), pal.dark ? 0x26FFFFFF : 0x12000000);
            plainCard.setBackground(cardBg);
            plainCard.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            plainCard.setClipToOutline(true);
            plainCard.setElevation(0f);
            plainCard.setPadding(0, 0, 0, 0);
            card = plainCard;
            rowsContainer = plainCard;
        }

        PopupShadowContainer shadowWrapper = new PopupShadowContainer(ctx, card, pal, d);
        wrapperHolder[0] = shadowWrapper;
        int padH = Math.round(44f * d);
        int padTop = Math.round(36f * d);
        int padBottom = Math.round(56f * d);
        shadowWrapper.setPadding(padH, padTop, padH, padBottom);

        final AnimatedPopupWindow popup = new AnimatedPopupWindow(
                shadowWrapper, pal.livingFlow ? card : shadowWrapper, pal.livingFlow, d);
        shadowWrapper.setOnClickListener(v -> popup.dismiss());

        final boolean[] picked = new boolean[]{false};
        int selBgColor = ((pal.dark ? 0x24 : 0x14) << 24) | (pal.accent & 0x00FFFFFF);

        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            boolean sel = (i == checkedIndex);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setClickable(true);
            row.setFocusable(true);
            if (!pal.livingFlow) {
                row.setBackground(createRowBackground(pal.ripple, sel ? selBgColor : 0));
            } else if (sel) {
                GradientDrawable selPill = new GradientDrawable();
                selPill.setShape(GradientDrawable.RECTANGLE);
                selPill.setCornerRadius(999f * d);
                selPill.setColor(selBgColor);
                row.setBackground(selPill);
            }
            row.setMinimumHeight(Math.round(50f * d));
            row.setPadding(
                    Math.round((pal.livingFlow ? 20f : 18f) * d),
                    Math.round(14f * d),
                    Math.round((pal.livingFlow ? 18f : 16f) * d),
                    Math.round(14f * d));

            Runnable triggerPick = () -> {
                if (picked[0]) return;
                picked[0] = true;
                popup.dismiss();
                if (onSelect != null) {
                    onSelect.onSelected(idx);
                }
            };
            if (!pal.livingFlow) {
                row.setOnClickListener(v -> {
                    if (picked[0]) return;
                    picked[0] = true;
                    v.postDelayed(() -> {
                        popup.dismiss();
                        if (onSelect != null) {
                            onSelect.onSelected(idx);
                        }
                    }, 70L);
                });
            } else {
                row.setOnClickListener(v -> triggerPick.run());
                ((DragSelectMenuContainer) card).setRowView(i, row);
            }

            boolean showTick = (checkedIndex >= 0);
            TextView tv = new TextView(ctx);
            tv.setText(items[i]);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, pal.livingFlow ? 16f : 15f);
            tv.setTypeface(Typeface.create("sans-serif-medium", sel ? Typeface.BOLD : Typeface.NORMAL));
            tv.setTextColor(sel ? pal.accent : pal.textPrimary);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (showTick) {
                tLp.setMarginEnd(Math.round(14f * d));
            }
            row.addView(tv, tLp);

            if (showTick) {
                PopupTickView tick = new PopupTickView(ctx, pal.accent, sel);
                int tickSize = Math.round(18f * d);
                row.addView(tick, new LinearLayout.LayoutParams(tickSize, tickSize));
            }

            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (pal.livingFlow && sel) {
                rLp.setMargins(Math.round(8f * d), 0, Math.round(8f * d), 0);
                row.setPadding(Math.round(12f * d), Math.round(14f * d),
                        Math.round(10f * d), Math.round(14f * d));
            }
            rowsContainer.addView(row, rLp);
            if (!pal.livingFlow && i < items.length - 1) {
                addDivider(ctx, rowsContainer, pal, 18);
            }
        }

        if (card instanceof DragSelectMenuContainer) {
            ((DragSelectMenuContainer) card).setSelectionCallback(idx -> {
                if (picked[0]) return;
                picked[0] = true;
                card.postDelayed(() -> {
                    popup.dismiss();
                    if (onSelect != null) {
                        onSelect.onSelected(idx);
                    }
                }, 55L);
            });
        }

        int maxW = Math.round(dm.widthPixels * 0.72f);
        card.measure(
                View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST));
        int popW = Math.max(Math.round(196f * d), Math.min(maxW, card.getMeasuredWidth() + Math.round(14f * d)));
        card.measure(
                View.MeasureSpec.makeMeasureSpec(popW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST));
        int popH = card.getMeasuredHeight();

        shadowWrapper.addView(card, new FrameLayout.LayoutParams(popW, popH));
        popup.setWidth(popW + padH * 2);
        popup.setHeight(popH + padTop + padBottom);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(0f);
        popup.setClippingEnabled(false);
        popup.setAnimationStyle(0);

        int[] anchorLoc = new int[2];
        anchor.getLocationInWindow(anchorLoc);
        int anchorW = Math.max(1, anchor.getWidth());
        int anchorH = Math.max(1, anchor.getHeight());
        float anchorCx = anchorLoc[0] + anchorW * 0.5f;
        float anchorCy = anchorLoc[1] + anchorH * 0.5f;

        boolean compactAnchor = anchorW <= Math.round(96f * d) || (pal.livingFlow && checkedIndex < 0);

        int edgePad = Math.round(16f * d);
        int minTop = Math.round(56f * d);
        View rootView = anchor.getRootView();
        int winBottom = dm.heightPixels;
        if (rootView != null && rootView.getHeight() > 0) {
            int[] rootLoc = new int[2];
            rootView.getLocationInWindow(rootLoc);
            winBottom = Math.max(dm.heightPixels, rootLoc[1] + rootView.getHeight());
        }
        int safeBottomY = winBottom - resolveNavBarFallbackPx(ctx) - Math.round(24f * d);
        int bottomNavId = ctx.getResources().getIdentifier("layoutBottomNav", "id", ctx.getPackageName());
        if (bottomNavId != 0 && rootView != null) {
            View bottomNav = rootView.findViewById(bottomNavId);
            if (bottomNav != null && bottomNav.getVisibility() == View.VISIBLE && bottomNav.getHeight() > 0) {
                int[] navLoc = new int[2];
                bottomNav.getLocationInWindow(navLoc);
                if (navLoc[1] > minTop) {
                    safeBottomY = Math.min(safeBottomY, navLoc[1] - Math.round(12f * d));
                }
            }
        }
        int maxTop = Math.max(minTop, safeBottomY - popH);

        int cardX;
        int cardY;
        boolean showAbove = false;
        float pivotX;
        float pivotY;

        if (compactAnchor) {
            // COUIPopupListWindow exact toolbar/circle anchor alignment: flush right/left edge, 8dp below
            cardX = anchorCx >= dm.widthPixels * 0.5f
                    ? (anchorLoc[0] + anchorW - popW)
                    : anchorLoc[0];
            cardX = Math.max(edgePad, Math.min(dm.widthPixels - popW - edgePad, cardX));
            int belowY = anchorLoc[1] + anchorH + Math.round(8f * d);
            int aboveY = anchorLoc[1] - popH - Math.round(8f * d);
            if (belowY + popH <= safeBottomY || aboveY < minTop) {
                cardY = Math.max(minTop, Math.min(maxTop, belowY));
                showAbove = false;
                pivotY = 0f;
            } else {
                cardY = Math.max(minTop, Math.min(maxTop, aboveY));
                showAbove = true;
                pivotY = popH;
            }
            pivotX = Math.max(18f * d, Math.min(popW - 18f * d, anchorCx - cardX));
        } else {
            float touchX = anchorCx;
            float touchY = anchorCy;
            Object tagPt = anchor.getTag(TAG_TOUCH_POINT);
            if (tagPt instanceof float[]) {
                float[] pt = (float[]) tagPt;
                if (pt.length >= 2 && pt[0] > 0f && pt[1] > 0f) {
                    touchX = pt[0];
                    touchY = pt[1];
                }
            }
            if (touchX < dm.widthPixels * 0.25f) {
                cardX = Math.round(touchX - 24f * d);
            } else if (touchX > dm.widthPixels * 0.75f) {
                cardX = Math.round(touchX - popW + 24f * d);
            } else {
                cardX = Math.round(touchX - popW * 0.5f);
            }
            cardX = Math.max(edgePad, Math.min(dm.widthPixels - popW - edgePad, cardX));
            int belowY = Math.round(touchY + 8f * d);
            int aboveY = Math.round(touchY - popH - 8f * d);
            if (belowY + popH <= safeBottomY || aboveY < minTop) {
                cardY = Math.max(minTop, Math.min(maxTop, belowY));
                showAbove = false;
                pivotY = Math.max(0f, Math.min(popH - 14f * d, touchY - cardY));
            } else {
                cardY = Math.max(minTop, Math.min(maxTop, aboveY));
                showAbove = true;
                pivotY = popH;
            }
            pivotX = Math.max(14f * d, Math.min(popW - 14f * d, touchX - cardX));
        }

        popup.setDirection(showAbove);
        try {
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, cardX - padH, cardY - padTop);
        } catch (Throwable ignored) {
            return;
        }
        popup.bindAnchorDetachGuard(anchor);

        if (pal.livingFlow) {
            card.setPivotX(pivotX);
            card.setPivotY(pivotY);
            card.setTranslationX(0f);
            card.setTranslationY(0f);
            if (card instanceof DragSelectMenuContainer) {
                ((DragSelectMenuContainer) card).setAnchorPivot(pivotX, pivotY);
            }
        } else {
            shadowWrapper.setPivotX(padH + pivotX);
            shadowWrapper.setPivotY(padTop + pivotY);
            shadowWrapper.setTranslationY(0f);
        }
        popup.startOpenAnimation();
    }

    /**
     * ColorOS 17 {@code COUITouchListView}-style popup container with drag-to-select capsule pill
     * highlight driven by 120Hz springs, {@code COUIPopupTouchMotionAnimator}-matched whole-menu
     * elastic drag deformation, and tactile tick feedback across rows.
     */
    private static final class DragSelectMenuContainer extends FrameLayout {
        final LinearLayout rowsList;
        private final View[] rowViews;
        private final float d;
        private final float hInset;
        private final float vInset;
        private final float hForgive;
        private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF pillRect = new RectF();
        private final LivingFlowEffects.FluidSpring pillTopSpring =
                LivingFlowEffects.FluidSpring.fromCoui(0.18f, 0.08f).setThresholds(0.25f, 1.0f);
        private final LivingFlowEffects.FluidSpring pillBottomSpring =
                LivingFlowEffects.FluidSpring.fromCoui(0.18f, 0.08f).setThresholds(0.25f, 1.0f);
        private final LivingFlowEffects.FluidSpring pillAlphaSpring =
                LivingFlowEffects.FluidSpring.fromCoui(0.16f, 0.0f).setThresholds(0.01f, 0.05f);
        private final LivingFlowEffects.FluidSpring menuTxSpring =
                LivingFlowEffects.FluidSpring.fromCoui(0.15f, 0.0f).setThresholds(0.15f, 1.0f);
        private final LivingFlowEffects.FluidSpring menuTySpring =
                LivingFlowEffects.FluidSpring.fromCoui(0.15f, 0.0f).setThresholds(0.15f, 1.0f);
        private final LivingFlowEffects.FluidSpring menuSxSpring =
                LivingFlowEffects.FluidSpring.fromCoui(0.15f, 0.0f).setThresholds(0.001f, 0.01f);
        private final LivingFlowEffects.FluidSpring menuSySpring =
                LivingFlowEffects.FluidSpring.fromCoui(0.15f, 0.0f).setThresholds(0.001f, 0.01f);

        private float anchorPivotX;
        private float anchorPivotY;
        private float downRawX;
        private float downRawY;
        private float curTop;
        private float curBottom;
        private float curAlpha;
        private float contentAlpha = 1f;
        private int hoveredIdx = -1;
        private boolean tracking;
        private boolean selectionDone;
        private IntCallback onPick;

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }

        void setContentAlpha(float alpha) {
            this.contentAlpha = Math.max(0f, Math.min(1f, alpha));
            if (rowsList != null) {
                rowsList.setAlpha(this.contentAlpha);
            }
            invalidate();
        }

        DragSelectMenuContainer(Context ctx, Palette pal, float d, int itemCount) {
            super(ctx);
            this.d = d;
            this.rowViews = new View[itemCount];
            this.hInset = 8f * d;
            this.vInset = 1.5f * d;
            this.hForgive = 24f * d;

            setClickable(true);
            setFocusable(true);
            setWillNotDraw(false);
            setClipChildren(false);
            setClipToPadding(false);

            rowsList = new LinearLayout(ctx);
            rowsList.setOrientation(LinearLayout.VERTICAL);
            rowsList.setClipChildren(false);
            rowsList.setClipToPadding(false);
            int vPad = Math.round(8f * d);
            rowsList.setPadding(0, vPad, 0, vPad);
            addView(rowsList, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            pillPaint.setStyle(Paint.Style.FILL);
            pillPaint.setColor(pal.dark ? 0x26FFFFFF : 0x13000000);

            pillTopSpring.setListener(v -> {
                curTop = v;
                invalidate();
            }, null);
            pillBottomSpring.setListener(v -> {
                curBottom = v;
                invalidate();
            }, null);
            pillAlphaSpring.setListener(v -> {
                curAlpha = Math.max(0f, Math.min(1f, v));
                invalidate();
            }, null);

            menuSxSpring.setValue(1f);
            menuSySpring.setValue(1f);
            menuTxSpring.setListener(this::setTranslationX, null);
            menuTySpring.setListener(this::setTranslationY, null);
            menuSxSpring.setListener(this::setScaleX, null);
            menuSySpring.setListener(this::setScaleY, null);
        }

        void setAnchorPivot(float px, float py) {
            this.anchorPivotX = px;
            this.anchorPivotY = py;
        }

        void prepareForDismiss() {
            cancelMenuDeformSprings();
            setTranslationX(0f);
            setTranslationY(0f);
            setPivotX(anchorPivotX);
            setPivotY(anchorPivotY);
        }

        private void cancelMenuDeformSprings() {
            menuTxSpring.cancel();
            menuTySpring.cancel();
            menuSxSpring.cancel();
            menuSySpring.cancel();
        }

        private static float couiRubberBand(float delta, float maxDist, float ratio) {
            float abs = Math.abs(delta);
            if (abs <= 0.001f || maxDist <= 0.001f) return 0f;
            float mag = maxDist * (1f - 1f / (1f + (abs * ratio) / maxDist));
            return delta >= 0f ? mag : -mag;
        }

        private void onMenuDragDown(float rawX, float rawY) {
            downRawX = rawX;
            downRawY = rawY;
            setPivotX(Math.max(1f, getWidth()) * 0.5f);
            setPivotY(Math.max(1f, getHeight()) * 0.5f);
            menuTxSpring.setCouiParams(0.20f, 0.0f).setValue(getTranslationX());
            menuTySpring.setCouiParams(0.20f, 0.0f).setValue(getTranslationY());
            menuSxSpring.setCouiParams(0.20f, 0.0f).setValue(getScaleX());
            menuSySpring.setCouiParams(0.20f, 0.0f).setValue(getScaleY());
            menuSxSpring.animateTo(0.992f);
            menuSySpring.animateTo(0.992f);
        }

        private void onMenuDragMove(float rawX, float rawY, float localY) {
            float dx = rawX - downRawX;
            float dy = rawY - downRawY;
            float w = Math.max(1f, getWidth());
            float h = Math.max(1f, getHeight());
            float overY = localY < 0f ? localY : (localY > h ? (localY - h) : 0f);
            float effDy = dy + overY * 0.9f;

            float tx = couiRubberBand(dx, 18f * d, 0.10f);
            float ty = couiRubberBand(effDy, 22f * d, 0.11f);
            float deformX = Math.abs(couiRubberBand(dx, 140f * d, 0.55f)) / (140f * d);
            float deformY = Math.abs(couiRubberBand(effDy, 150f * d, 0.55f)) / (150f * d);

            float px = w * (0.5f - Math.max(-0.18f, Math.min(0.18f, (dx / w) * 0.25f)));
            float py = effDy >= 0f
                    ? h * (0.5f - Math.min(0.25f, deformY * 0.45f))
                    : h * (0.5f + Math.min(0.25f, deformY * 0.45f));
            setPivotX(px);
            setPivotY(py);

            float targetSx = Math.max(0.955f, Math.min(1.045f, 0.992f + 0.042f * deformX - 0.022f * deformY));
            float targetSy = Math.max(0.955f, Math.min(1.055f, 0.992f + 0.048f * deformY - 0.020f * deformX));

            menuTxSpring.setCouiParams(0.15f, 0.0f);
            menuTySpring.setCouiParams(0.15f, 0.0f);
            menuSxSpring.setCouiParams(0.15f, 0.0f);
            menuSySpring.setCouiParams(0.15f, 0.0f);
            menuTxSpring.animateTo(tx);
            menuTySpring.animateTo(ty);
            menuSxSpring.animateTo(targetSx);
            menuSySpring.animateTo(targetSy);
        }

        private void releaseMenuDeform() {
            menuTxSpring.setCouiParams(0.38f, 0.18f);
            menuTySpring.setCouiParams(0.38f, 0.18f);
            menuSxSpring.setCouiParams(0.38f, 0.18f);
            menuSySpring.setCouiParams(0.38f, 0.18f);
            menuTxSpring.animateTo(0f);
            menuTySpring.animateTo(0f);
            menuSxSpring.animateTo(1f);
            menuSySpring.animateTo(1f);
        }

        void setRowView(int index, View row) {
            if (index >= 0 && index < rowViews.length) {
                rowViews[index] = row;
            }
        }

        void setSelectionCallback(IntCallback cb) {
            this.onPick = cb;
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            if (curAlpha > 0.01f && contentAlpha > 0.01f && curBottom > curTop + 4f && getWidth() > hInset * 2f) {
                int baseAlpha = Color.alpha(pillPaint.getColor());
                int a = Math.max(0, Math.min(255, Math.round(baseAlpha * curAlpha * contentAlpha)));
                pillPaint.setAlpha(a);
                pillRect.set(hInset, curTop + vInset, getWidth() - hInset, curBottom - vInset);
                float r = pillRect.height() * 0.5f;
                canvas.drawRoundRect(pillRect, r, r, pillPaint);
                pillPaint.setAlpha(baseAlpha);
            }
            super.dispatchDraw(canvas);
        }

        private int findRowAt(float x, float y, float extraHoriz) {
            if (x < -extraHoriz || x > getWidth() + extraHoriz) {
                return -1;
            }
            int listTop = rowsList.getTop();
            for (int i = 0; i < rowViews.length; i++) {
                View r = rowViews[i];
                if (r == null) continue;
                float top = listTop + r.getTop();
                float bot = listTop + r.getBottom();
                if (y >= top && y <= bot) {
                    return i;
                }
            }
            return -1;
        }

        private void scaleRow(int index, float targetScale) {
            if (index < 0 || index >= rowViews.length || rowViews[index] == null) return;
            View row = rowViews[index];
            row.animate().cancel();
            row.setPivotX(row.getWidth() * 0.5f);
            row.setPivotY(row.getHeight() * 0.5f);
            row.animate().scaleX(targetScale).scaleY(targetScale).setDuration(90L).start();
        }

        private void updateHover(int newIdx, boolean immediatePos) {
            if (newIdx == hoveredIdx) return;
            int prev = hoveredIdx;
            hoveredIdx = newIdx;
            if (prev >= 0) {
                scaleRow(prev, 1f);
            }
            if (newIdx >= 0 && rowViews[newIdx] != null) {
                View row = rowViews[newIdx];
                float top = rowsList.getTop() + row.getTop();
                float bot = rowsList.getTop() + row.getBottom();
                if (immediatePos || curAlpha < 0.05f) {
                    pillTopSpring.setValue(top);
                    pillBottomSpring.setValue(bot);
                    curTop = top;
                    curBottom = bot;
                } else {
                    pillTopSpring.animateTo(top);
                    pillBottomSpring.animateTo(bot);
                }
                pillAlphaSpring.animateTo(1f);
                scaleRow(newIdx, 0.98f);
                ColorOsLivingFlowEngine.performHaptic(this, immediatePos
                        ? ColorOsLivingFlowEngine.HapticKind.TAP
                        : ColorOsLivingFlowEngine.HapticKind.TICK);
            } else {
                pillAlphaSpring.animateTo(0f);
            }
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (selectionDone) return true;
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                int idx = findRowAt(ev.getX(), ev.getY(), 0f);
                tracking = true;
                onMenuDragDown(ev.getRawX(), ev.getRawY());
                updateHover(idx, true);
                return true;
            }
            return super.onInterceptTouchEvent(ev);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (selectionDone) return true;
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                tracking = true;
                onMenuDragDown(ev.getRawX(), ev.getRawY());
                updateHover(findRowAt(ev.getX(), ev.getY(), 0f), true);
                return true;
            } else if (action == MotionEvent.ACTION_MOVE && tracking) {
                onMenuDragMove(ev.getRawX(), ev.getRawY(), ev.getY());
                updateHover(findRowAt(ev.getX(), ev.getY(), hForgive), false);
                return true;
            } else if (action == MotionEvent.ACTION_UP && tracking) {
                tracking = false;
                releaseMenuDeform();
                int finalIdx = hoveredIdx;
                if (finalIdx >= 0) {
                    selectionDone = true;
                    scaleRow(finalIdx, 1f);
                    ColorOsLivingFlowEngine.performHaptic(this, ColorOsLivingFlowEngine.HapticKind.TAP);
                    if (onPick != null) {
                        onPick.onSelected(finalIdx);
                    }
                } else {
                    updateHover(-1, false);
                }
                return true;
            } else if (action == MotionEvent.ACTION_CANCEL) {
                tracking = false;
                releaseMenuDeform();
                updateHover(-1, false);
                return true;
            }
            return super.onTouchEvent(ev);
        }

        @Override
        protected void onDetachedFromWindow() {
            pillTopSpring.cancel();
            pillBottomSpring.cancel();
            pillAlphaSpring.cancel();
            cancelMenuDeformSprings();
            super.onDetachedFromWindow();
        }
    }

    private static final class PopupShadowContainer extends FrameLayout {
        private final View card;
        private final boolean drawShadow;
        private final Paint farShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint nearShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF shadowRect = new RectF();
        private final float cornerRadius;
        private final float subpixelInset;

        PopupShadowContainer(Context ctx, View card, Palette pal, float density) {
            super(ctx);
            this.card = card;
            this.drawShadow = !pal.dark && !pal.livingFlow;
            this.cornerRadius = 20f * density;
            this.subpixelInset = 0.5f * density;
            setWillNotDraw(!drawShadow);
            setClipChildren(false);
            setClipToPadding(false);

            if (drawShadow) {
                farShadowPaint.setStyle(Paint.Style.FILL);
                farShadowPaint.setColor(pal.cardBg);
                farShadowPaint.setShadowLayer(18f * density, 0f, 6f * density, 0x1A000000);

                nearShadowPaint.setStyle(Paint.Style.FILL);
                nearShadowPaint.setColor(pal.cardBg);
                nearShadowPaint.setShadowLayer(6f * density, 0f, 2.5f * density, 0x10000000);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (drawShadow && card != null && card.getWidth() > 0 && card.getHeight() > 0) {
                shadowRect.set(
                        card.getLeft() + subpixelInset,
                        card.getTop() + subpixelInset,
                        card.getRight() - subpixelInset,
                        card.getBottom() - subpixelInset);
                canvas.drawRoundRect(shadowRect, cornerRadius, cornerRadius, farShadowPaint);
                canvas.drawRoundRect(shadowRect, cornerRadius, cornerRadius, nearShadowPaint);
            }
            super.onDraw(canvas);
        }
    }

    public static Drawable createRowBackground(int rippleColor, int staticFillColor) {
        Drawable content = staticFillColor != 0 ? new ColorDrawable(staticFillColor) : null;
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, new ColorDrawable(Color.WHITE));
    }

    private static final class AnimatedPopupWindow extends PopupWindow {
        private final View target;
        private final boolean livingFlow;
        private final float density;
        private final LivingFlowEffects.FluidSpring scaleSpring =
                LivingFlowEffects.FluidSpring.fromCoui(0.28f, 0.14f).setThresholds(0.002f, 0.015f);
        private final LivingFlowEffects.FluidSpring alphaSpring =
                LivingFlowEffects.FluidSpring.fromCoui(0.22f, 0.0f).setThresholds(0.004f, 0.02f);
        private float curScale = 1f;
        private float curAlpha = 1f;
        private boolean showAbove;
        private boolean dismissing;
        private boolean dismissFinished;
        private View boundAnchor;
        private View.OnAttachStateChangeListener anchorDetachListener;
        private final Runnable forceDismissRunnable = this::finishDismissNow;

        AnimatedPopupWindow(View contentView, View target, boolean livingFlow, float density) {
            super(contentView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
            this.target = target;
            this.livingFlow = livingFlow;
            this.density = density;
            scaleSpring.setListener(s -> {
                curScale = s;
                target.setScaleX(s);
                target.setScaleY(s);
                if (livingFlow) {
                    ColorOsLivingFlowEngine.syncPopupGlassTransform(target, curScale, curAlpha, dismissing);
                }
            }, null);
            alphaSpring.setListener(a -> {
                curAlpha = Math.max(0f, Math.min(1f, a));
                if (livingFlow) {
                    applyLivingFlowAlpha(curAlpha);
                } else {
                    target.setAlpha(curAlpha);
                }
                if (dismissing && curAlpha <= 0.02f) {
                    finishDismissNow();
                }
            }, finalAlpha -> {
                if (dismissing) {
                    finishDismissNow();
                }
            });
        }

        private void applyLivingFlowAlpha(float alpha) {
            if (target instanceof DragSelectMenuContainer) {
                target.setAlpha(1f);
                ((DragSelectMenuContainer) target).setContentAlpha(alpha);
            } else {
                target.setAlpha(alpha);
            }
            ColorOsLivingFlowEngine.syncPopupGlassTransform(target, curScale, alpha, dismissing);
        }

        void setDirection(boolean showAbove) {
            this.showAbove = showAbove;
        }

        void bindAnchorDetachGuard(View anchor) {
            clearAnchorDetachGuard();
            if (anchor == null) return;
            boundAnchor = anchor;
            anchorDetachListener = new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    finishDismissNow();
                }
            };
            anchor.addOnAttachStateChangeListener(anchorDetachListener);
        }

        private void clearAnchorDetachGuard() {
            if (boundAnchor != null && anchorDetachListener != null) {
                try {
                    boundAnchor.removeOnAttachStateChangeListener(anchorDetachListener);
                } catch (Throwable ignored) {
                }
            }
            boundAnchor = null;
            anchorDetachListener = null;
        }

        void startOpenAnimation() {
            if (livingFlow) {
                curScale = 0.24f;
                curAlpha = 0f;
                target.setScaleX(0.24f);
                target.setScaleY(0.24f);
                target.setVisibility(View.VISIBLE);
                scaleSpring.setCouiParams(0.28f, 0.14f);
                alphaSpring.setCouiParams(0.18f, 0.0f).setThresholds(0.004f, 0.02f);
                scaleSpring.setValue(0.24f);
                alphaSpring.setValue(0f);
                applyLivingFlowAlpha(0f);
                scaleSpring.animateTo(1f);
                alphaSpring.animateTo(1f);
            } else {
                target.setScaleX(0.48f);
                target.setScaleY(0.38f);
                target.setTranslationY((showAbove ? 12f : -12f) * density);
                target.setAlpha(0f);
                target.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationY(0f)
                        .setDuration(280L)
                        .setInterpolator(POPUP_OPEN_EASE)
                        .start();
                ObjectAnimator.ofFloat(target, View.ALPHA, 0f, 1f).setDuration(180L).start();
            }
        }

        private void finishDismissNow() {
            if (dismissFinished) return;
            dismissFinished = true;
            clearAnchorDetachGuard();
            if (target != null) {
                target.removeCallbacks(forceDismissRunnable);
                target.animate().cancel();
            }
            scaleSpring.cancel();
            alphaSpring.cancel();
            if (livingFlow && target != null) {
                curAlpha = 0f;
                curScale = 0.24f;
                if (target instanceof DragSelectMenuContainer) {
                    target.setAlpha(1f);
                    ((DragSelectMenuContainer) target).setContentAlpha(0f);
                } else {
                    target.setAlpha(0f);
                }
                ColorOsLivingFlowEngine.syncPopupGlassTransform(target, 0.24f, 0f, true);
                target.setVisibility(View.INVISIBLE);
            }
            try {
                AnimatedPopupWindow.super.dismiss();
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void dismiss() {
            if (dismissing) return;
            if (!isShowing() || target == null || !target.isAttachedToWindow() || target.getWindowToken() == null) {
                finishDismissNow();
                return;
            }
            dismissing = true;
            if (livingFlow) {
                if (target instanceof DragSelectMenuContainer) {
                    ((DragSelectMenuContainer) target).prepareForDismiss();
                }
                curScale = Math.max(0.24f, Math.max(target.getScaleX(), target.getScaleY()));
                curAlpha = Math.max(0f, Math.min(1f, curAlpha));
                scaleSpring.setCouiParams(0.22f, 0.0f).setValue(curScale);
                alphaSpring.setCouiParams(0.16f, 0.0f).setThresholds(0.015f, 0.05f).setValue(curAlpha);
                target.removeCallbacks(forceDismissRunnable);
                target.postDelayed(forceDismissRunnable, 260L);
                scaleSpring.animateTo(0.24f);
                alphaSpring.animateTo(0f);
                return;
            }
            target.animate().cancel();
            target.animate()
                    .scaleX(0.68f)
                    .scaleY(0.60f)
                    .translationY((showAbove ? 8f : -8f) * density)
                    .alpha(0f)
                    .setDuration(160L)
                    .setInterpolator(POPUP_CLOSE_EASE)
                    .withEndAction(() -> {
                        clearAnchorDetachGuard();
                        try {
                            AnimatedPopupWindow.super.dismiss();
                        } catch (Throwable ignored) {
                        }
                    })
                    .start();
        }
    }

    public static void addChipFlowGroup(Context ctx, LinearLayout parent, Palette pal,
                                        String[] labels, String[] values, EditText linkedInput) {
        if (ctx == null || parent == null || labels == null || values == null || labels.length == 0) {
            return;
        }
        float d = ctx.getResources().getDisplayMetrics().density;
        ChipFlowLayout flow = new ChipFlowLayout(ctx, Math.round(8f * d), Math.round(8f * d));
        final TextView[] chips = new TextView[labels.length];
        String initial = linkedInput != null && linkedInput.getText() != null
                ? linkedInput.getText().toString().trim() : "";

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            final String val = values[i];
            boolean sel = val.isEmpty() ? initial.isEmpty() : val.equalsIgnoreCase(initial);
            TextView chip = createChipBadge(ctx, pal, labels[i], sel, v -> {
                if (linkedInput != null) {
                    linkedInput.setText(val);
                    linkedInput.setSelection(val.length());
                }
                for (int k = 0; k < chips.length; k++) {
                    if (chips[k] != null) {
                        styleChip(chips[k], pal, k == idx, d);
                    }
                }
            });
            chip.setSingleLine(true);
            chip.setGravity(Gravity.CENTER);
            chips[idx] = chip;
            flow.addView(chip, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (linkedInput != null) {
            linkedInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    String cur = s != null ? s.toString().trim() : "";
                    for (int k = 0; k < chips.length; k++) {
                        if (chips[k] != null) {
                            boolean match = values[k].isEmpty() ? cur.isEmpty() : values[k].equalsIgnoreCase(cur);
                            styleChip(chips[k], pal, match, d);
                        }
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(12f * d);
        parent.addView(flow, lp);
    }

    public static TextView createChipBadge(Context ctx, Palette pal, String label,
                                         boolean selected, View.OnClickListener onClick) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView chip = new TextView(ctx);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setPadding(Math.round(13f * d), Math.round(7f * d), Math.round(13f * d), Math.round(7f * d));
        styleChip(chip, pal, selected, d);
        chip.setOnClickListener(onClick);
        return chip;
    }

    private static final class ChipFlowLayout extends ViewGroup {
        private final int hGap;
        private final int vGap;

        ChipFlowLayout(Context ctx, int hGapPx, int vGapPx) {
            super(ctx);
            this.hGap = hGapPx;
            this.vGap = vGapPx;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int widthMode = MeasureSpec.getMode(widthMeasureSpec);
            int availW = widthMode == MeasureSpec.UNSPECIFIED
                    ? Integer.MAX_VALUE
                    : Math.max(0, MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight());

            int lineW = 0;
            int lineH = 0;
            int maxLineW = 0;
            int totalH = 0;
            int count = getChildCount();

            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                measureChild(child,
                        MeasureSpec.makeMeasureSpec(availW, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();
                if (lineW > 0 && lineW + hGap + cw > availW) {
                    maxLineW = Math.max(maxLineW, lineW);
                    totalH += lineH + vGap;
                    lineW = cw;
                    lineH = ch;
                } else {
                    lineW += (lineW > 0 ? hGap : 0) + cw;
                    lineH = Math.max(lineH, ch);
                }
            }
            if (lineH > 0) {
                maxLineW = Math.max(maxLineW, lineW);
                totalH += lineH;
            }
            int w = maxLineW + getPaddingLeft() + getPaddingRight();
            int h = totalH + getPaddingTop() + getPaddingBottom();
            setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int pl = getPaddingLeft();
            int pr = (r - l) - getPaddingRight();
            int x = pl;
            int y = getPaddingTop();
            int lineH = 0;
            int count = getChildCount();
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();
                if (x > pl && x + cw > pr) {
                    x = pl;
                    y += lineH + vGap;
                    lineH = 0;
                }
                child.layout(x, y, x + cw, y + ch);
                x += cw + hGap;
                lineH = Math.max(lineH, ch);
            }
        }
    }

    public static void styleChip(TextView chip, Palette pal, boolean selected, float density) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(999f * density);
        bg.setColor(selected ? pal.tonal : pal.subcardBg);
        bg.setStroke(Math.max(1, Math.round(0.8f * density)), selected ? pal.accent : pal.divider);
        chip.setBackground(new RippleDrawable(ColorStateList.valueOf(pal.ripple), bg, bg));
        chip.setTextColor(selected ? pal.accent : pal.textPrimary);
    }

    private static RippleDrawable createRipple(int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), null,
                new ColorDrawable(Color.WHITE));
    }

    private static int resolveNavBarFallbackPx(Context ctx) {
        int resId = ctx.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (resId > 0) {
            return ctx.getResources().getDimensionPixelSize(resId);
        }
        return Math.round(28f * ctx.getResources().getDisplayMetrics().density);
    }

    /** Drawn circular ColorOS checkmark badge (works in any host process without R.drawable). */
    public static final class CheckBadgeView extends View {
        private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path checkPath = new Path();
        private boolean checked;

        CheckBadgeView(Context ctx, int fillColor, int checkColor, int ringColor, boolean checked) {
            super(ctx);
            this.checked = checked;
            float d = ctx.getResources().getDisplayMetrics().density;
            circlePaint.setStyle(Paint.Style.FILL);
            circlePaint.setColor(fillColor);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(1.6f * d);
            ringPaint.setColor(ringColor);
            checkPaint.setStyle(Paint.Style.STROKE);
            checkPaint.setStrokeCap(Paint.Cap.ROUND);
            checkPaint.setStrokeJoin(Paint.Join.ROUND);
            checkPaint.setStrokeWidth(2.1f * d);
            checkPaint.setColor(checkColor);
        }

        public void setChecked(boolean checked) {
            if (this.checked != checked) {
                this.checked = checked;
                invalidate();
                if (checked) {
                    setScaleX(0.82f);
                    setScaleY(0.82f);
                    animate().scaleX(1f).scaleY(1f).setDuration(180L).setInterpolator(FLUID_EASE).start();
                }
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            float r = Math.min(cx, cy) * 0.88f;
            if (checked) {
                canvas.drawCircle(cx, cy, r, circlePaint);
                checkPath.reset();
                checkPath.moveTo(cx - r * 0.36f, cy + r * 0.02f);
                checkPath.lineTo(cx - r * 0.09f, cy + r * 0.29f);
                checkPath.lineTo(cx + r * 0.39f, cy - r * 0.25f);
                canvas.drawPath(checkPath, checkPaint);
            } else {
                canvas.drawCircle(cx, cy, r - ringPaint.getStrokeWidth() * 0.5f, ringPaint);
            }
        }
    }

    private static final class PopupTickView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final boolean show;

        PopupTickView(Context ctx, int color, boolean show) {
            super(ctx);
            this.show = show;
            float d = ctx.getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(2.1f * d);
            paint.setColor(color);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!show) return;
            float w = getWidth();
            float h = getHeight();
            path.reset();
            path.moveTo(w * 0.20f, h * 0.52f);
            path.lineTo(w * 0.42f, h * 0.74f);
            path.lineTo(w * 0.82f, h * 0.28f);
            canvas.drawPath(path, paint);
        }
    }

    private static String locCancelText(Context ctx) {
        try {
            java.util.Locale loc = ctx.getResources().getConfiguration().getLocales().get(0);
            if (loc != null && "zh".equals(loc.getLanguage())) {
                return "取消";
            }
        } catch (Throwable ignored) {
        }
        return "Cancel";
    }

    private static final class DonutRadioIndicatorView extends View {
        private final Paint selectedRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint unselectedRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float strokeWidthPx;
        private boolean checked;

        DonutRadioIndicatorView(Context ctx, Palette pal, boolean checked) {
            super(ctx);
            this.checked = checked;
            float d = ctx.getResources().getDisplayMetrics().density;
            this.strokeWidthPx = 5.6f * d;

            selectedRingPaint.setStyle(Paint.Style.STROKE);
            selectedRingPaint.setColor(pal.accent);
            selectedRingPaint.setStrokeWidth(strokeWidthPx);

            unselectedRingPaint.setStyle(Paint.Style.STROKE);
            unselectedRingPaint.setColor(pal.unselectedRing);
            unselectedRingPaint.setStrokeWidth(1.5f * d);
        }

        void setChecked(boolean target) {
            if (this.checked != target) {
                this.checked = target;
                invalidate();
                if (target) {
                    setScaleX(0.82f);
                    setScaleY(0.82f);
                    animate().scaleX(1f).scaleY(1f).setDuration(180L).setInterpolator(FLUID_EASE).start();
                }
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            float outerR = Math.min(cx, cy) * 0.88f;
            if (checked) {
                float sw = Math.min(strokeWidthPx, outerR * 0.56f);
                selectedRingPaint.setStrokeWidth(sw);
                canvas.drawCircle(cx, cy, outerR - sw * 0.5f, selectedRingPaint);
            } else {
                float sw = unselectedRingPaint.getStrokeWidth();
                canvas.drawCircle(cx, cy, outerR - sw * 0.5f, unselectedRingPaint);
            }
        }
    }

    private static final class ClearGlyphView extends View {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint xPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ClearGlyphView(Context ctx, int tint, int cutBg) {
            super(ctx);
            float d = ctx.getResources().getDisplayMetrics().density;
            bgPaint.setStyle(Paint.Style.FILL);
            bgPaint.setColor((0x2E << 24) | (tint & 0x00FFFFFF));
            xPaint.setStyle(Paint.Style.STROKE);
            xPaint.setStrokeWidth(1.7f * d);
            xPaint.setStrokeCap(Paint.Cap.ROUND);
            xPaint.setColor(tint);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            float r = Math.min(cx, cy) * 0.68f;
            canvas.drawCircle(cx, cy, r, bgPaint);
            float o = r * 0.36f;
            canvas.drawLine(cx - o, cy - o, cx + o, cy + o, xPaint);
            canvas.drawLine(cx + o, cy - o, cx - o, cy + o, xPaint);
        }
    }
}
