package io.github.nicochanyo.sonypods;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
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

        public Palette(boolean dark, int cardBg, int subcardBg, int cardStroke,
                       int textPrimary, int textSecondary, int divider, int ripple,
                       int accent, int onAccent, int tonal, int btnSecondaryBg,
                       int seekbarTrack, int unselectedRing, int handleColor, int destructive) {
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
        }

        public static Palette fromModuleTheme(Context ctx) {
            boolean dark = ConfigStore.isDarkThemeActive(ctx);
            int cardBg = ConfigStore.resolveThemeColor(ctx, R.attr.couiCard, R.color.coui_card);
            int subcardBg = ConfigStore.resolveThemeColor(ctx, R.attr.couiSubcard, R.color.coui_subcard);
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
                    dark ? 0xFFFF554A : 0xFFD93025
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
        if (view == null) return;
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
        final LinearLayout card = new LinearLayout(ctx);
        final AnimatedDialog dialog = new AnimatedDialog(ctx, card, d, centerMode);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        int marginH = Math.round((centerMode ? 22f : 12f) * d);
        int marginVTop = Math.round((centerMode ? 48f : 24f) * d);
        int marginVBottomBase = centerMode
                ? Math.round(48f * d)
                : Math.round(12f * d) + resolveNavBarFallbackPx(ctx);

        FrameLayout outer = new FrameLayout(ctx);
        outer.setClipChildren(false);
        outer.setClipToPadding(false);
        outer.setPadding(marginH, marginVTop, marginH, marginVBottomBase);
        outer.setOnClickListener(v -> dialog.dismiss());

        outer.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()).bottom
                    : insets.getSystemWindowInsetBottom();
            int padBottom = centerMode
                    ? Math.max(Math.round(48f * d), bottom + Math.round(16f * d))
                    : bottom + Math.round(12f * d);
            v.setPadding(marginH, marginVTop, marginH, padBottom);
            return insets;
        });

        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setFocusable(true);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setColor(pal.cardBg);
        cardBg.setCornerRadius(28f * d);
        cardBg.setStroke(Math.max(1, Math.round(0.75f * d)), pal.cardStroke);
        card.setBackground(cardBg);
        card.setElevation(24f * d);
        card.setPadding(
                Math.round((centerMode ? 22f : 20f) * d),
                Math.round((centerMode ? 22f : 10f) * d),
                Math.round((centerMode ? 22f : 20f) * d),
                Math.round(20f * d));

        if (!centerMode) {
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
            win.setDimAmount(pal.dark ? 0.62f : 0.40f);
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            win.setWindowAnimations(0);
            if (Build.VERSION.SDK_INT >= 30) {
                win.setDecorFitsSystemWindows(false);
            }
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    centerMode ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT);
            win.setGravity(centerMode ? Gravity.CENTER : Gravity.BOTTOM);
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
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
        private final View card;
        private final float density;
        private final boolean centerMode;
        private boolean dismissing;

        AnimatedDialog(Context ctx, View card, float density, boolean centerMode) {
            super(ctx);
            this.card = card;
            this.density = density;
            this.centerMode = centerMode;
        }

        @Override
        public void dismiss() {
            if (dismissing) return;
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
        bg.setColor(pal.subcardBg);
        bg.setCornerRadius(18f * d);
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
        row.setOnClickListener(onClick);

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
        shape.setCornerRadius(24f * d);
        int rippleColor = styleMode == 0 ? pal.ripple : 0x33FFFFFF;
        btn.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), shape, shape));
        btn.setOnClickListener(onClick);
        return btn;
    }

    public static void addButtonRow(Context ctx, LinearLayout card, Palette pal,
                                    String leftText, int leftStyle, View.OnClickListener leftClick,
                                    String rightText, int rightStyle, View.OnClickListener rightClick) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
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
        CouiBounceScrollView sv = new CouiBounceScrollView(ctx) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int capped = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST);
                super.onMeasure(widthMeasureSpec, capped);
            }
        };
        sv.setDisallowParentOnDown(true);
        sv.setVerticalScrollBarEnabled(false);
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
     * ColorOS 17 styled anchored popup menu ({@code COUIPopupListWindow}) for compact option pickers.
     */
    public static void showPopupMenu(Context ctx, View anchor, Palette pal,
                                     String[] items, int checkedIndex, IntCallback onSelect) {
        if (ctx == null || anchor == null || items == null || items.length == 0) {
            return;
        }
        float d = ctx.getResources().getDisplayMetrics().density;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setFocusable(true);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setColor(pal.cardBg);
        cardBg.setCornerRadius(20f * d);
        cardBg.setStroke(Math.max(1, Math.round(0.75f * d)), pal.dark ? 0x26FFFFFF : 0x12000000);
        card.setBackground(cardBg);
        card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        card.setClipToOutline(true);
        card.setElevation(0f);
        card.setPadding(0, 0, 0, 0);

        PopupShadowContainer shadowWrapper = new PopupShadowContainer(ctx, card, pal, d);
        int padH = Math.round(28f * d);
        int padTop = Math.round(18f * d);
        int padBottom = Math.round(36f * d);
        shadowWrapper.setPadding(padH, padTop, padH, padBottom);

        final AnimatedPopupWindow popup = new AnimatedPopupWindow(shadowWrapper, shadowWrapper, d);
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
            row.setBackground(createRowBackground(pal.ripple, sel ? selBgColor : 0));
            row.setMinimumHeight(Math.round(50f * d));
            row.setPadding(Math.round(18f * d), Math.round(14f * d), Math.round(16f * d), Math.round(14f * d));
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

            TextView tv = new TextView(ctx);
            tv.setText(items[i]);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            tv.setTypeface(Typeface.create("sans-serif-medium", sel ? Typeface.BOLD : Typeface.NORMAL));
            tv.setTextColor(sel ? pal.accent : pal.textPrimary);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tLp.setMarginEnd(Math.round(14f * d));
            row.addView(tv, tLp);

            PopupTickView tick = new PopupTickView(ctx, pal.accent, sel);
            int tickSize = Math.round(18f * d);
            row.addView(tick, new LinearLayout.LayoutParams(tickSize, tickSize));

            card.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i < items.length - 1) {
                addDivider(ctx, card, pal, 18);
            }
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
        float touchX = anchorLoc[0] + anchor.getWidth() * 0.5f;
        float touchY = anchorLoc[1] + anchor.getHeight() * 0.5f;
        Object tagPt = anchor.getTag(TAG_TOUCH_POINT);
        if (tagPt instanceof float[]) {
            float[] pt = (float[]) tagPt;
            if (pt.length >= 2 && pt[0] > 0f && pt[1] > 0f) {
                touchX = pt[0];
                touchY = pt[1];
            }
        }

        int edgePad = Math.round(16f * d);
        int cardX;
        if (touchX < dm.widthPixels * 0.25f) {
            cardX = Math.round(touchX - 24f * d);
        } else if (touchX > dm.widthPixels * 0.75f) {
            cardX = Math.round(touchX - popW + 24f * d);
        } else {
            cardX = Math.round(touchX - popW * 0.5f);
        }
        cardX = Math.max(edgePad, Math.min(dm.widthPixels - popW - edgePad, cardX));

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
        int cardY = Math.max(minTop, Math.min(maxTop, Math.round(touchY + 8f * d)));

        float pivotX = Math.max(14f * d, Math.min(popW - 14f * d, touchX - cardX));
        float pivotY = Math.max(0f, Math.min(popH - 14f * d, touchY - cardY));
        popup.setDirection(false);
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, cardX - padH, cardY - padTop);

        shadowWrapper.setPivotX(padH + pivotX);
        shadowWrapper.setPivotY(padTop + pivotY);
        shadowWrapper.setScaleX(0.48f);
        shadowWrapper.setScaleY(0.38f);
        shadowWrapper.setTranslationY(-12f * d);
        shadowWrapper.setAlpha(0f);
        shadowWrapper.animate()
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(280L)
                .setInterpolator(POPUP_OPEN_EASE)
                .start();
        ObjectAnimator.ofFloat(shadowWrapper, View.ALPHA, 0f, 1f).setDuration(180L).start();
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
            this.drawShadow = !pal.dark;
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
        private final View card;
        private final float density;
        private boolean showAbove;
        private boolean dismissing;

        AnimatedPopupWindow(View contentView, View card, float density) {
            super(contentView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
            this.card = card;
            this.density = density;
        }

        void setDirection(boolean showAbove) {
            this.showAbove = showAbove;
        }

        @Override
        public void dismiss() {
            if (dismissing) return;
            if (!isShowing() || card == null || !card.isAttachedToWindow() || card.getWindowToken() == null) {
                try {
                    super.dismiss();
                } catch (Throwable ignored) {
                }
                return;
            }
            dismissing = true;
            card.animate().cancel();
            card.animate()
                    .scaleX(0.68f)
                    .scaleY(0.60f)
                    .translationY((showAbove ? 8f : -8f) * density)
                    .alpha(0f)
                    .setDuration(160L)
                    .setInterpolator(POPUP_CLOSE_EASE)
                    .withEndAction(() -> {
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
