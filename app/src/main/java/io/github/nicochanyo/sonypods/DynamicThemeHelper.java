package io.github.nicochanyo.sonypods;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Accent-only vivid dynamic color helper that preserves neutral COUI light/dark surfaces.
 */
public final class DynamicThemeHelper {
    private static final int STOCK_ACCENT_LIGHT = 0xFF007DFF;
    private static final int STOCK_ACCENT_DARK = 0xFF1A8CFF;
    private static final int STOCK_TONAL_LIGHT = 0x1F007DFF;
    private static final int STOCK_TONAL_DARK = 0x2E1A8CFF;

    private static final String TAG_ICON = "accent_icon";
    private static final String TAG_ICON_BG = "accent_icon_bg";
    private static final String TAG_TEXT = "accent_text";
    private static final String TAG_CHIP = "accent_chip";
    private static final String TAG_BTN = "accent_btn";

    private DynamicThemeHelper() {
    }

    public static boolean isDynamicColorActive(Context ctx) {
        return Build.VERSION.SDK_INT >= 31 && ConfigStore.isMonetEnabled(ctx);
    }

    public static int getAccent(Context ctx) {
        boolean isDark = ConfigStore.isDarkThemeActive(ctx);
        int fallback = isDark ? STOCK_ACCENT_DARK : STOCK_ACCENT_LIGHT;
        if (!isDynamicColorActive(ctx) || ctx == null) {
            return fallback;
        }
        if (ConfigStore.isLivingFlowUi(ctx)) {
            int sysAccent = readColorOsSystemAccent(ctx, isDark);
            if (sysAccent != 0) {
                return sysAccent;
            }
            return fallback;
        }
        int seed = readWallpaperAccent(ctx);
        if (seed == 0) {
            return fallback;
        }
        float[] hsv = new float[3];
        Color.colorToHSV(seed, hsv);
        if (hsv[1] < 0.04f) {
            return fallback;
        }
        return computeVividAccent(hsv, isDark);
    }

    public static int getOnAccent(Context ctx) {
        int accent = getAccent(ctx);
        if (ctx != null && ConfigStore.isLivingFlowUi(ctx)) {
            return luminance(accent) < 0.78f ? 0xFFFFFFFF : 0xFF101215;
        }
        return luminance(accent) < 0.50f ? 0xFFFFFFFF : 0xFF101215;
    }

    public static Drawable createRadioIndicator(Context ctx, boolean selected) {
        float d = ctx.getResources().getDisplayMetrics().density;
        boolean dark = ConfigStore.isDarkThemeActive(ctx);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        if (selected) {
            ring.setColor(ctx.getColor(R.color.lf_card));
            ring.setStroke(Math.round(5.5f * d), getAccent(ctx));
        } else {
            ring.setColor(Color.TRANSPARENT);
            ring.setStroke(Math.max(1, Math.round(1.5f * d)), dark ? 0x45FFFFFF : 0x38000000);
        }
        return ring;
    }

    public static int getTonal(Context ctx) {
        boolean isDark = ConfigStore.isDarkThemeActive(ctx);
        if (!isDynamicColorActive(ctx)) {
            return isDark ? STOCK_TONAL_DARK : STOCK_TONAL_LIGHT;
        }
        int alpha = isDark ? 0x36 : 0x22;
        return (alpha << 24) | (getAccent(ctx) & 0x00FFFFFF);
    }

    public static void applyAccent(View root, Context ctx) {
        applyAccent(root, ctx, null);
    }

    public static void applyAccent(View root, Context ctx, Switch animatingSwitch) {
        if (root == null || ctx == null) {
            return;
        }
        int accent = getAccent(ctx);
        int onAccent = getOnAccent(ctx);
        int tonal = getTonal(ctx);
        int switchOff = ConfigStore.resolveThemeColor(ctx, R.attr.couiSwitchOff, R.color.coui_switch_off);
        int ripple = ConfigStore.resolveThemeColor(ctx, R.attr.couiRipple, R.color.coui_ripple);
        float density = ctx.getResources().getDisplayMetrics().density;
        boolean isDark = ConfigStore.isDarkThemeActive(ctx);
        boolean livingFlow = ConfigStore.isLivingFlowUi(ctx);
        boolean monet = isDynamicColorActive(ctx);
        applyRecursive(root, accent, onAccent, tonal, switchOff, ripple, density,
                isDark, livingFlow, monet, animatingSwitch);
    }

    public static void tintAlertDialog(AlertDialog dialog, Context ctx) {
        if (dialog == null || ctx == null) {
            return;
        }
        int accent = getAccent(ctx);
        int[] ids = new int[]{
                AlertDialog.BUTTON_POSITIVE,
                AlertDialog.BUTTON_NEGATIVE,
                AlertDialog.BUTTON_NEUTRAL
        };
        for (int id : ids) {
            Button btn = dialog.getButton(id);
            if (btn != null) {
                btn.setTextColor(accent);
            }
        }
    }

    private static int readColorOsSystemAccent(Context ctx, boolean isDark) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            if (cr != null) {
                String jsonStr = Settings.Secure.getString(cr, "theme_customization_overlay_packages");
                if (jsonStr != null && !jsonStr.trim().isEmpty()) {
                    try {
                        JSONObject obj = new JSONObject(jsonStr);
                        int monetEnable = obj.optInt("material_you_overlay_enable", 1);
                        String colorSource = obj.optString("android.theme.customization.color_source", "");
                        if (monetEnable == 0 && !"preset".equalsIgnoreCase(colorSource)) {
                            return isDark ? STOCK_ACCENT_DARK : STOCK_ACCENT_LIGHT;
                        }
                        String hex = obj.optString("android.theme.customization.accent_color", "");
                        if (hex.isEmpty()) {
                            hex = obj.optString("android.theme.customization.system_palette", "");
                        }
                        int parsed = parseHexRgb(hex);
                        if (parsed != 0) {
                            float[] hsv = new float[3];
                            Color.colorToHSV(parsed, hsv);
                            if (hsv[1] >= 0.03f) {
                                if (isDark && luminance(parsed) < 0.20f) {
                                    hsv[2] = Math.max(hsv[2], 0.58f);
                                    return Color.HSVToColor(0xFF, hsv);
                                }
                                return parsed;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                try {
                    int customInt = Settings.System.getInt(cr, "coui_theme_custom_color", 0);
                    if (customInt != 0) {
                        int c = 0xFF000000 | (customInt & 0x00FFFFFF);
                        float[] hsv = new float[3];
                        Color.colorToHSV(c, hsv);
                        if (hsv[1] >= 0.03f) {
                            return c;
                        }
                    }
                } catch (Throwable ignored) {
                }

                try {
                    int sysUiAccent = parseHexRgb(Settings.Secure.getString(cr, "sysui_type_accent_color"));
                    if (sysUiAccent != 0) {
                        float[] hsv = new float[3];
                        Color.colorToHSV(sysUiAccent, hsv);
                        if (hsv[1] >= 0.03f) {
                            return sysUiAccent;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        if (Build.VERSION.SDK_INT >= 31) {
            int[] monetIds = isDark
                    ? new int[]{android.R.color.system_accent1_300, android.R.color.system_accent1_400, android.R.color.system_accent1_500}
                    : new int[]{android.R.color.system_accent1_400, android.R.color.system_accent1_500};
            float[] hsv = new float[3];
            for (int resId : monetIds) {
                try {
                    int color = ctx.getColor(resId);
                    Color.colorToHSV(color, hsv);
                    if (hsv[1] >= 0.04f) {
                        return color;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return 0;
    }

    private static int parseHexRgb(String raw) {
        if (raw == null) return 0;
        String clean = raw.trim();
        if (clean.startsWith("#")) {
            clean = clean.substring(1);
        } else if (clean.startsWith("0x") || clean.startsWith("0X")) {
            clean = clean.substring(2);
        }
        if (clean.length() != 6 && clean.length() != 8) {
            return 0;
        }
        try {
            long val = Long.parseLong(clean, 16);
            return 0xFF000000 | ((int) val & 0x00FFFFFF);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int readWallpaperAccent(Context ctx) {
        int[] candidates = new int[]{
                android.R.color.system_accent1_500,
                android.R.color.system_accent1_600,
                android.R.color.system_accent1_400
        };
        float[] hsv = new float[3];
        for (int resId : candidates) {
            try {
                int color = ctx.getColor(resId);
                Color.colorToHSV(color, hsv);
                if (hsv[1] >= 0.04f) {
                    return color;
                }
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private static int computeVividAccent(float[] hsv, boolean isDark) {
        float h = hsv[0];
        float s;
        float v;
        if (isDark) {
            s = Math.min(0.94f, Math.max(0.72f, (float) Math.pow(hsv[1], 0.52) * 1.12f));
            v = Math.max(0.86f, Math.min(0.98f, hsv[2] * 1.22f));
            if (h >= 210f && h <= 285f) {
                v = Math.max(v, 0.94f);
                s = Math.min(s, 0.84f);
            }
        } else {
            s = Math.min(0.96f, Math.max(0.78f, (float) Math.pow(hsv[1], 0.48) * 1.15f));
            v = Math.max(0.68f, Math.min(0.88f, hsv[2] * 1.08f));
            int c = Color.HSVToColor(0xFF, new float[]{h, s, v});
            float lum = luminance(c);
            if (lum > 0.60f) {
                v = Math.max(0.56f, v * (0.60f / lum));
            }
        }
        return Color.HSVToColor(0xFF, new float[]{h, s, v});
    }

    private static float luminance(int color) {
        return (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f;
    }

    private static void applyRecursive(View v, int accent, int onAccent, int tonal,
                                       int switchOff, int ripple, float density,
                                       boolean isDark, boolean livingFlow, boolean monet,
                                       Switch animatingSwitch) {
        if (v instanceof Switch) {
            tintSwitchTrack((Switch) v, accent, switchOff, density, v == animatingSwitch);
        } else if (v instanceof SeekBar) {
            v.invalidate();
        }
        if (v.getId() == R.id.vNavSlidingPill && !livingFlow) {
            GradientDrawable pill = new GradientDrawable();
            pill.setShape(GradientDrawable.RECTANGLE);
            pill.setColor(tonal);
            pill.setCornerRadius(27f * density);
            v.setBackground(pill);
        }

        Object tagObj = v.getTag();
        if (tagObj instanceof String) {
            String tag = (String) tagObj;
            if (TAG_ICON.equals(tag) && v instanceof ImageView) {
                ((ImageView) v).setColorFilter(accent, PorterDuff.Mode.SRC_IN);
            } else if (TAG_ICON_BG.equals(tag)) {
                GradientDrawable squircle = new GradientDrawable();
                squircle.setShape(GradientDrawable.RECTANGLE);
                squircle.setCornerRadius(11f * density);
                squircle.setColor(((isDark ? 0x28 : 0x16) << 24) | (accent & 0x00FFFFFF));
                v.setBackground(squircle);
            } else if (TAG_TEXT.equals(tag) && v instanceof TextView) {
                ((TextView) v).setTextColor(accent);
            } else if (TAG_CHIP.equals(tag) && v instanceof TextView) {
                TextView tv = (TextView) v;
                GradientDrawable chip = new GradientDrawable();
                chip.setShape(GradientDrawable.RECTANGLE);
                chip.setColor(tonal);
                chip.setCornerRadius(999f * density);
                if (tv.isClickable()) {
                    tv.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), chip, chip));
                } else {
                    tv.setBackground(chip);
                }
                tv.setTextColor(accent);
                tintCompoundDrawables(tv, accent);
            } else if (TAG_BTN.equals(tag) && v instanceof TextView) {
                GradientDrawable shape = new GradientDrawable();
                shape.setShape(GradientDrawable.RECTANGLE);
                shape.setColor(accent);
                shape.setCornerRadius((livingFlow ? 999f : 24f) * density);
                v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, shape));
                ((TextView) v).setTextColor(onAccent);
            }
        }

        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyRecursive(vg.getChildAt(i), accent, onAccent, tonal, switchOff, ripple,
                        density, isDark, livingFlow, monet, animatingSwitch);
            }
        }
    }

    private static void tintCompoundDrawables(TextView tv, int accent) {
        Drawable[] rel = tv.getCompoundDrawablesRelative();
        if (rel != null) {
            for (Drawable d : rel) {
                if (d != null) {
                    d.mutate().setColorFilter(accent, PorterDuff.Mode.SRC_IN);
                }
            }
        }
        Drawable[] std = tv.getCompoundDrawables();
        if (std != null) {
            for (Drawable d : std) {
                if (d != null) {
                    d.mutate().setColorFilter(accent, PorterDuff.Mode.SRC_IN);
                }
            }
        }
    }

    private static void tintSwitchTrack(Switch sw, int accent, int switchOff,
                                        float density, boolean isAnimatingNow) {
        Drawable raw = sw.getTrackDrawable();
        if (Build.VERSION.SDK_INT >= 29 && raw instanceof StateListDrawable) {
            StateListDrawable sld = (StateListDrawable) raw.mutate();
            int count = sld.getStateCount();
            boolean updated = false;
            for (int i = 0; i < count; i++) {
                Drawable child = sld.getStateDrawable(i);
                if (child instanceof GradientDrawable) {
                    int[] stateSet = sld.getStateSet(i);
                    boolean isCheckedBranch = false;
                    if (stateSet != null) {
                        for (int attr : stateSet) {
                            if (attr == android.R.attr.state_checked) {
                                isCheckedBranch = true;
                                break;
                            }
                        }
                    }
                    ((GradientDrawable) child.mutate()).setColor(isCheckedBranch ? accent : switchOff);
                    updated = true;
                }
            }
            if (updated) {
                sld.setState(sw.getDrawableState());
                if (!isAnimatingNow && !sw.isLaidOut()) {
                    sld.jumpToCurrentState();
                }
                sw.invalidate();
                return;
            }
        }

        Rect oldBounds = (raw != null && !raw.getBounds().isEmpty()) ? new Rect(raw.getBounds()) : null;
        Drawable track = createSwitchTrack(accent, switchOff, density);
        if (oldBounds != null) {
            track.setBounds(oldBounds);
        }
        track.setState(sw.getDrawableState());
        if (!isAnimatingNow) {
            track.jumpToCurrentState();
        }
        sw.setTrackDrawable(track);
        sw.invalidate();
    }

    private static Drawable createSwitchTrack(int accent, int switchOff, float density) {
        int w = Math.round(44f * density);
        int h = Math.round(24f * density);
        float r = 12f * density;

        GradientDrawable checked = new GradientDrawable();
        checked.setShape(GradientDrawable.RECTANGLE);
        checked.setColor(accent);
        checked.setCornerRadius(r);
        checked.setSize(w, h);

        GradientDrawable unchecked = new GradientDrawable();
        unchecked.setShape(GradientDrawable.RECTANGLE);
        unchecked.setColor(switchOff);
        unchecked.setCornerRadius(r);
        unchecked.setSize(w, h);

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_checked}, checked);
        sld.addState(new int[]{}, unchecked);
        return sld;
    }
}
