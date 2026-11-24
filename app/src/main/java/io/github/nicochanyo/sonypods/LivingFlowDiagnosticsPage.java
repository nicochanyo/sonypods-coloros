package io.github.nicochanyo.sonypods;

import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.Locale;
import java.util.WeakHashMap;

/**
 * ColorOS 17 native Level-2 push sub-page for {@code 狀態與診斷}, modeled directly on
 * {@code Settings -> 關於本機 -> 狀態} ({@code StatusInfoFragment}).
 */
public final class LivingFlowDiagnosticsPage {
    private static final PathInterpolator PUSH_EASE = new PathInterpolator(0.30f, 0.0f, 0.10f, 1.0f);
    private static final PathInterpolator POP_EASE = new PathInterpolator(0.30f, 0.0f, 0.15f, 1.0f);
    private static final WeakHashMap<FrameLayout, DiagState> ACTIVE_STATES = new WeakHashMap<>();

    private static final class DiagState {
        View page;
        View dimOverlay;
        View level1;
        Object backCallback;
        Object backDispatcher;
        boolean closing;
    }

    private LivingFlowDiagnosticsPage() {
    }

    public static boolean hasActiveStage(Context ctx) {
        if (!(ctx instanceof MainActivity)) return false;
        View c = ((MainActivity) ctx).findViewById(android.R.id.content);
        if (!(c instanceof FrameLayout)) return false;
        synchronized (ACTIVE_STATES) {
            DiagState st = ACTIVE_STATES.get(c);
            return st != null && !st.closing && st.page != null && st.page.isAttachedToWindow();
        }
    }

    public static boolean popActiveStage(Context ctx) {
        if (!(ctx instanceof MainActivity)) return false;
        View c = ((MainActivity) ctx).findViewById(android.R.id.content);
        if (!(c instanceof FrameLayout)) return false;
        DiagState st;
        synchronized (ACTIVE_STATES) {
            st = ACTIVE_STATES.get(c);
        }
        if (st != null && !st.closing) {
            pop((FrameLayout) c, st);
            return true;
        }
        return false;
    }

    public static void show(MainActivity activity, boolean xspActive, boolean rootAvailable) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        View rawContent = activity.findViewById(android.R.id.content);
        if (!(rawContent instanceof FrameLayout)) return;
        final FrameLayout container = (FrameLayout) rawContent;

        synchronized (ACTIVE_STATES) {
            DiagState existing = ACTIVE_STATES.get(container);
            if (existing != null && !existing.closing) {
                return;
            }
        }

        final float d = activity.getResources().getDisplayMetrics().density;
        final CouiBottomSheet.Palette pal = CouiBottomSheet.Palette.fromModuleTheme(activity);
        final int pageBgColor = pal.dark ? 0xFF000000 : 0xFFF2F3F5;
        final int cardColor = pal.dark ? 0xFF1E1E1E : 0xFFFFFFFF;

        ConfigStore.HeadsetState st = ConfigStore.readGlobalState(activity);
        ConfigStore.SettingsData cfg = ConfigStore.loadFromPrefs(ConfigStore.openAppPrefs(activity).prefs);

        // Localized strings & snapshot text
        String valXposed = activity.getString(xspActive ? R.string.diag_val_xposed_on : R.string.diag_val_xposed_off);
        String valSync = activity.getString(xspActive ? R.string.channel_xsp : R.string.channel_private_warn);
        String valRoot = activity.getString(rootAvailable ? R.string.diag_val_root_ready : R.string.diag_val_root_denied);

        String devModel = st.connected
                ? ((st.name != null && !st.name.isEmpty()) ? st.name : st.effectiveModelName())
                : activity.getString(R.string.headset_disconnected_title);
        String macColor = st.connected
                ? (st.mac + (st.colorCode >= 0 ? " · " + activity.getString(R.string.badge_color_fmt, st.colorCode) : ""))
                : "—";
        String batL = formatBatValue(activity, st.connected ? st.effectiveLeft() : -1, st.leftCharging || st.singleCharging);
        String batR = formatBatValue(activity, st.connected ? st.effectiveRight() : -1, st.rightCharging || st.singleCharging);
        String batCase = formatBatValue(activity, st.connected ? st.caseBattery : -1, st.caseCharging);

        String protoStr = st.connected
                ? ("Sony Tandem V" + st.protocolVersion
                        + " (0x" + Integer.toHexString(Math.max(0, st.ncAsmInquiredType))
                        + " · 0x" + Integer.toHexString(Math.max(0, st.speakToChatInquiredType)) + ")")
                : "—";
        String modeStr = st.connected ? st.formatModeLabel(activity) : activity.getString(R.string.badge_proto_none);
        String smartStr = st.connected
                ? (activity.getString(R.string.pref_asm_level_title) + " " + st.asmLevel
                        + " · " + activity.getString(R.string.diag_key_voice_focus) + " "
                        + activity.getString(st.voiceFocus ? R.string.diag_state_on : R.string.diag_state_off)
                        + " · " + activity.getString(R.string.diag_key_speak_chat) + " "
                        + activity.getString(st.speakToChat ? R.string.diag_state_on : R.string.diag_state_off)
                        + " · " + activity.getString(R.string.diag_key_dsee) + " "
                        + activity.getString(st.dsee ? R.string.diag_state_on : R.string.diag_state_off))
                : "—";

        int catCount = SonyModelImageManager.getCatalogCount(activity);
        String catStr = catCount > 0
                ? activity.getString(R.string.pref_sync_catalog_sub_count, catCount)
                : activity.getString(R.string.pref_sync_catalog_sub_empty);
        String customArtStr = activity.getString(SonyModelImageManager.hasCustomOverride(activity)
                ? R.string.pref_custom_artwork_sub_set : R.string.pref_custom_artwork_sub_none);
        String overrideStr = (cfg.customModelOverride != null && !cfg.customModelOverride.trim().isEmpty())
                ? cfg.customModelOverride.trim() : activity.getString(R.string.model_override_auto);

        String recentLogs = XposedCompat.dumpRecentLogs();

        StringBuilder dump = new StringBuilder();
        dump.append(activity.getString(R.string.diag_key_xposed)).append(": ").append(valXposed).append('\n');
        dump.append(activity.getString(R.string.about_env_channel_title)).append(": ").append(valSync).append('\n');
        dump.append(activity.getString(R.string.diag_key_root)).append(": ").append(valRoot).append('\n');
        dump.append(activity.getString(R.string.diag_key_model)).append(": ").append(devModel).append('\n');
        dump.append(activity.getString(R.string.diag_key_mac)).append(": ").append(macColor).append('\n');
        dump.append(activity.getString(R.string.battery_left)).append(": ").append(batL)
                .append(" · ").append(activity.getString(R.string.battery_right)).append(": ").append(batR)
                .append(" · ").append(activity.getString(R.string.battery_case)).append(": ").append(batCase).append('\n');
        dump.append(activity.getString(R.string.diag_key_protocol)).append(": ").append(protoStr).append('\n');
        dump.append(activity.getString(R.string.diag_key_anc_mode)).append(": ").append(modeStr).append('\n');
        dump.append(activity.getString(R.string.diag_key_smart)).append(": ").append(smartStr).append('\n');
        dump.append(activity.getString(R.string.pref_sync_catalog_title)).append(": ").append(catStr).append('\n');
        dump.append(activity.getString(R.string.pref_model_override_title)).append(": ").append(overrideStr).append('\n');
        if (recentLogs != null && !recentLogs.isEmpty()) {
            dump.append('\n').append(recentLogs);
        }
        final String copyText = dump.toString();

        final DiagState state = new DiagState();
        state.level1 = container.getChildCount() > 0 ? container.getChildAt(0) : null;

        View dimOverlay = new View(activity);
        dimOverlay.setBackgroundColor(Color.BLACK);
        dimOverlay.setAlpha(0f);
        dimOverlay.setClickable(false);
        state.dimOverlay = dimOverlay;

        SwipeablePageRoot root = new SwipeablePageRoot(activity, d, container, state);
        root.setBackgroundColor(pageBgColor);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setClickable(true);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        state.page = root;

        int statusFallback = resolveStatusBarFallbackPx(activity);
        int navFallback = resolveNavBarFallbackPx(activity);
        int headerPadH = Math.round(16f * d);
        int headerPadTop = statusFallback + Math.round(10f * d);
        int headerPadBot = Math.round(10f * d);
        int btnSize = Math.round(42f * d);
        int headerH = headerPadTop + btnSize + headerPadBot;
        int blurExtra = Math.round(20f * d);

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);

        CouiBounceScrollView scrollView = new CouiBounceScrollView(activity);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        int padH = Math.round(16f * d);
        scrollView.setPadding(padH, headerH + Math.round(6f * d), padH, navFallback + Math.round(28f * d));
        scrollView.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TopGradientBlurView topBlur = new TopGradientBlurView(activity);
        topBlur.setClickable(false);
        topBlur.setFocusable(false);
        topBlur.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        topBlur.setStatusBarTopPx(statusFallback);
        topBlur.setSurfaceColor(pageBgColor);

        root.addView(scrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(topBlur, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, headerH + blurExtra, Gravity.TOP));
        topBlur.setOutlineProvider(null);
        topBlur.setTranslationZ(4f * d);
        root.bindProgressiveBlur(scrollView, topBlur);
        scrollView.getViewTreeObserver().addOnScrollChangedListener(topBlur::notifyTargetInvalidated);
        scrollView.setOnOverscrollListener(ty -> topBlur.notifyTargetInvalidated());

        // Header Bar: circular glass Back + Title + circular glass Copy
        LinearLayout headerWrap = new LinearLayout(activity);
        headerWrap.setOutlineProvider(null);
        headerWrap.setTranslationZ(12f * d);
        headerWrap.setOrientation(LinearLayout.HORIZONTAL);
        headerWrap.setGravity(Gravity.CENTER_VERTICAL);
        headerWrap.setClipChildren(false);
        headerWrap.setClipToPadding(false);
        headerWrap.setPadding(headerPadH, headerPadTop, headerPadH, headerPadBot);

        HeaderCircleIconButton btnBack = new HeaderCircleIconButton(activity, d, pal.dark, cardColor, pal.textPrimary, true);
        btnBack.setOnClickListener(v -> pop(container, state));
        headerWrap.addView(btnBack, new LinearLayout.LayoutParams(btnSize, btnSize));

        TextView tvTitle = new TextView(activity);
        tvTitle.setText(R.string.dialog_logs_title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvTitle.setTextColor(pal.textPrimary);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMarginStart(Math.round(14f * d));
        titleLp.setMarginEnd(Math.round(10f * d));
        headerWrap.addView(tvTitle, titleLp);

        HeaderCircleIconButton btnCopy = new HeaderCircleIconButton(activity, d, pal.dark, cardColor, pal.textPrimary, false);
        btnCopy.setContentDescription(activity.getString(R.string.btn_copy_logs));
        btnCopy.setOnClickListener(v -> copySnapshot(activity, copyText));
        headerWrap.addView(btnCopy, new LinearLayout.LayoutParams(btnSize, btnSize));

        root.addView(headerWrap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // Card 1: Module & Environment
        LinearLayout envCard = createCard(activity, pal, cardColor, d);
        addKvRow(activity, envCard, pal, activity.getString(R.string.diag_key_xposed), valXposed, true, d);
        addKvRow(activity, envCard, pal, activity.getString(R.string.about_env_channel_title), valSync, true, d);
        addKvRow(activity, envCard, pal, activity.getString(R.string.diag_key_root), valRoot, false, d);
        body.addView(envCard, cardLp(d, 0));

        // Card 2: Headset Connection & Battery
        LinearLayout batCard = createCard(activity, pal, cardColor, d);
        addKvRow(activity, batCard, pal, activity.getString(R.string.diag_key_model), devModel, true, d);
        addKvRow(activity, batCard, pal, activity.getString(R.string.diag_key_mac), macColor, true, d);
        addKvRow(activity, batCard, pal, activity.getString(R.string.battery_left), batL, true, d);
        addKvRow(activity, batCard, pal, activity.getString(R.string.battery_right), batR, true, d);
        addKvRow(activity, batCard, pal, activity.getString(R.string.battery_case), batCase, false, d);
        body.addView(batCard, cardLp(d, 12));

        // Card 3: Audio & Protocol
        LinearLayout audioCard = createCard(activity, pal, cardColor, d);
        addKvRow(activity, audioCard, pal, activity.getString(R.string.diag_key_protocol), protoStr, true, d);
        addKvRow(activity, audioCard, pal, activity.getString(R.string.diag_key_anc_mode), modeStr, true, d);
        addStackedKvRow(activity, audioCard, pal, activity.getString(R.string.diag_key_smart), smartStr, false, d);
        body.addView(audioCard, cardLp(d, 12));

        // Card 4: Artwork & Model Cache
        LinearLayout artCard = createCard(activity, pal, cardColor, d);
        addKvRow(activity, artCard, pal, activity.getString(R.string.pref_sync_catalog_title), catStr, true, d);
        addKvRow(activity, artCard, pal, activity.getString(R.string.pref_custom_artwork_title), customArtStr, true, d);
        addKvRow(activity, artCard, pal, activity.getString(R.string.pref_model_override_title), overrideStr, false, d);
        body.addView(artCard, cardLp(d, 12));

        // Card 5: Process Debug Logs
        LinearLayout logCard = createCard(activity, pal, cardColor, d);
        logCard.setPadding(Math.round(18f * d), Math.round(16f * d), Math.round(18f * d), Math.round(16f * d));
        TextView logHdr = new TextView(activity);
        logHdr.setText(R.string.diag_logs_section);
        logHdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        logHdr.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        logHdr.setTextColor(pal.textPrimary);
        logCard.addView(logHdr);

        TextView logBody = new TextView(activity);
        if (recentLogs != null && !recentLogs.isEmpty()) {
            logBody.setText(recentLogs);
            logBody.setTypeface(Typeface.MONOSPACE);
            logBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            logBody.setTextIsSelectable(true);
            logBody.setTextColor(pal.textSecondary);
        } else {
            logBody.setText(R.string.diag_logs_empty);
            logBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            logBody.setTextColor(pal.textSecondary);
        }
        logBody.setLineSpacing(0f, 1.32f);
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lbLp.topMargin = Math.round(8f * d);
        logCard.addView(logBody, lbLp);
        body.addView(logCard, cardLp(d, 12));

        // Bottom Full-Width Action Pill
        Button copyBtn = CouiBottomSheet.createPillButton(
                activity, pal, activity.getString(R.string.btn_copy_logs), 1,
                v -> copySnapshot(activity, copyText));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(48f * d));
        btnLp.topMargin = Math.round(16f * d);
        body.addView(copyBtn, btnLp);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()).top
                    : insets.getSystemWindowInsetTop();
            int bottom = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars()).bottom
                    : insets.getSystemWindowInsetBottom();
            int actualTop = top > 0 ? top : statusFallback;
            int actualHeaderTop = actualTop + Math.round(10f * d);
            int actualHeaderH = actualHeaderTop + btnSize + headerPadBot;
            headerWrap.setPadding(headerPadH, actualHeaderTop, headerPadH, headerPadBot);
            scrollView.setPadding(padH, actualHeaderH + Math.round(6f * d), padH, bottom + Math.round(28f * d));
            topBlur.setStatusBarTopPx(actualTop);
            ViewGroup.LayoutParams blurLp = topBlur.getLayoutParams();
            if (blurLp != null && blurLp.height != actualHeaderH + blurExtra) {
                blurLp.height = actualHeaderH + blurExtra;
                topBlur.setLayoutParams(blurLp);
            }
            topBlur.notifyTargetInvalidated();
            return insets;
        });

        root.setOnKeyListener((v, keyCode, event) -> {
            if (Build.VERSION.SDK_INT < 33 && keyCode == KeyEvent.KEYCODE_BACK
                    && event.getAction() == KeyEvent.ACTION_UP) {
                pop(container, state);
                return true;
            }
            return false;
        });

        synchronized (ACTIVE_STATES) {
            ACTIVE_STATES.put(container, state);
        }
        registerBackCallback(activity, container, state);

        int width = container.getWidth() > 0
                ? container.getWidth() : activity.getResources().getDisplayMetrics().widthPixels;
        container.addView(dimOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setTranslationX(width);
        container.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.requestApplyInsets();
        root.requestFocus();

        if (state.level1 != null) {
            state.level1.animate().cancel();
            state.level1.animate()
                    .translationX(-width * 0.28f)
                    .setDuration(380L)
                    .setInterpolator(PUSH_EASE)
                    .start();
        }
        dimOverlay.animate().alpha(0.26f).setDuration(380L).setInterpolator(PUSH_EASE).start();
        root.animate()
                .translationX(0f)
                .setDuration(380L)
                .setInterpolator(PUSH_EASE)
                .start();
    }

    private static void pop(FrameLayout container, DiagState state) {
        if (state == null || state.closing) return;
        state.closing = true;
        unregisterBackCallback(state);

        int width = container.getWidth() > 0
                ? container.getWidth() : container.getResources().getDisplayMetrics().widthPixels;
        if (state.level1 != null) {
            state.level1.animate().cancel();
            state.level1.animate()
                    .translationX(0f)
                    .setDuration(340L)
                    .setInterpolator(POP_EASE)
                    .start();
        }
        state.dimOverlay.animate().cancel();
        state.dimOverlay.animate()
                .alpha(0f)
                .setDuration(340L)
                .setInterpolator(POP_EASE)
                .withEndAction(() -> safeRemove(container, state.dimOverlay))
                .start();

        state.page.animate().cancel();
        state.page.animate()
                .translationX(width)
                .setDuration(340L)
                .setInterpolator(POP_EASE)
                .withEndAction(() -> {
                    safeRemove(container, state.page);
                    synchronized (ACTIVE_STATES) {
                        if (ACTIVE_STATES.get(container) == state) {
                            ACTIVE_STATES.remove(container);
                        }
                    }
                })
                .start();
    }

    private static void safeRemove(FrameLayout parent, View child) {
        if (parent == null || child == null) return;
        try {
            parent.removeView(child);
        } catch (Throwable ignored) {
        }
    }

    private static void registerBackCallback(MainActivity activity, FrameLayout container, DiagState state) {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            OnBackInvokedDispatcher disp = activity.getOnBackInvokedDispatcher();
            if (disp != null) {
                OnBackInvokedCallback cb = () -> pop(container, state);
                disp.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
                state.backCallback = cb;
                state.backDispatcher = disp;
            }
        } catch (Throwable ignored) {
        }
    }

    private static void unregisterBackCallback(DiagState state) {
        if (Build.VERSION.SDK_INT < 33 || state.backCallback == null) return;
        try {
            if (state.backDispatcher instanceof OnBackInvokedDispatcher
                    && state.backCallback instanceof OnBackInvokedCallback) {
                ((OnBackInvokedDispatcher) state.backDispatcher)
                        .unregisterOnBackInvokedCallback((OnBackInvokedCallback) state.backCallback);
            }
        } catch (Throwable ignored) {
        }
        state.backCallback = null;
        state.backDispatcher = null;
    }

    private static void copySnapshot(Context ctx, String text) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("SonyPods Diagnostics", text));
            Toast.makeText(ctx, R.string.toast_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private static String formatBatValue(Context ctx, int lvl, boolean charging) {
        if (lvl < 0) return ctx.getString(R.string.battery_unknown);
        return ctx.getString(charging ? R.string.battery_charging_fmt : R.string.battery_normal_fmt, lvl);
    }

    private static LinearLayout createCard(Context ctx, CouiBottomSheet.Palette pal, int cardColor, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(cardColor);
        bg.setCornerRadius(22f * d);
        if (pal.dark) {
            bg.setStroke(Math.max(1, Math.round(0.5f * d)), 0x16FFFFFF);
        }
        card.setBackground(bg);
        card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        card.setClipToOutline(true);
        return card;
    }

    private static LinearLayout.LayoutParams cardLp(float d, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(topMarginDp * d);
        return lp;
    }

    private static void addKvRow(Context ctx, LinearLayout group, CouiBottomSheet.Palette pal,
                                 String key, String value, boolean addDivider, float d) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Math.round(54f * d));
        row.setPadding(Math.round(18f * d), Math.round(14f * d), Math.round(18f * d), Math.round(14f * d));

        TextView kTv = new TextView(ctx);
        kTv.setText(key);
        kTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        kTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        kTv.setTextColor(pal.textPrimary);
        row.addView(kTv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView vTv = new TextView(ctx);
        vTv.setText(value);
        vTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        vTv.setTextColor(pal.textSecondary);
        vTv.setGravity(Gravity.END);
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vLp.setMarginStart(Math.round(14f * d));
        row.addView(vTv, vLp);

        group.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (addDivider) {
            CouiBottomSheet.addDivider(ctx, group, pal, 18);
        }
    }

    private static void addStackedKvRow(Context ctx, LinearLayout group, CouiBottomSheet.Palette pal,
                                        String key, String value, boolean addDivider, float d) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Math.round(18f * d), Math.round(14f * d), Math.round(18f * d), Math.round(14f * d));

        TextView kTv = new TextView(ctx);
        kTv.setText(key);
        kTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        kTv.setTextColor(pal.textSecondary);
        col.addView(kTv);

        TextView vTv = new TextView(ctx);
        vTv.setText(value);
        vTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        vTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        vTv.setLineSpacing(0f, 1.25f);
        vTv.setTextColor(pal.textPrimary);
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vLp.topMargin = Math.round(3f * d);
        col.addView(vTv, vLp);

        group.addView(col, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (addDivider) {
            CouiBottomSheet.addDivider(ctx, group, pal, 18);
        }
    }

    private static int resolveStatusBarFallbackPx(Context ctx) {
        int resId = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) return ctx.getResources().getDimensionPixelSize(resId);
        return Math.round(32f * ctx.getResources().getDisplayMetrics().density);
    }

    private static int resolveNavBarFallbackPx(Context ctx) {
        int resId = ctx.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (resId > 0) return ctx.getResources().getDimensionPixelSize(resId);
        return Math.round(28f * ctx.getResources().getDisplayMetrics().density);
    }

    private static final class HeaderCircleIconButton extends FrameLayout {
        private final boolean isBack;
        private final float d;
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path iconPath = new Path();
        private final RectF copyRectBack = new RectF();
        private final RectF copyRectFront = new RectF();

        HeaderCircleIconButton(Context ctx, float d, boolean dark, int fillColor, int iconColor, boolean isBack) {
            super(ctx);
            this.d = d;
            this.isBack = isBack;
            setWillNotDraw(false);
            setClickable(true);
            setFocusable(true);

            ColorOsLivingFlowEngine.applyGlassSurface(
                    this, ColorOsLivingFlowEngine.GlassPreset.TOP_BAR_CIRCLE,
                    dark, 21f, true, false);

            iconPaint.setColor(iconColor);
            iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeCap(Paint.Cap.ROUND);
            iconPaint.setStrokeJoin(Paint.Join.ROUND);

            LivingFlowEffects.TouchFeedback.attach(
                    this, LivingFlowEffects.TouchFeedback.Profile.GLASS_CIRCLE, dark, 21f, true, false, true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            if (isBack) {
                iconPaint.setStrokeWidth(2.0f * d);
                float arm = 7.2f * d;
                float head = 5.4f * d;
                iconPath.reset();
                iconPath.moveTo(cx + arm, cy);
                iconPath.lineTo(cx - arm, cy);
                iconPath.moveTo(cx - arm + head, cy - head);
                iconPath.lineTo(cx - arm, cy);
                iconPath.lineTo(cx - arm + head, cy + head);
                canvas.drawPath(iconPath, iconPaint);
            } else {
                iconPaint.setStrokeWidth(1.65f * d);
                float r = 2.0f * d;
                copyRectFront.set(cx - 5.2f * d, cy - 3.2f * d, cx + 3.2f * d, cy + 5.8f * d);
                iconPath.reset();
                iconPath.moveTo(cx - 2.4f * d, cy - 3.2f * d);
                iconPath.lineTo(cx - 2.4f * d, cy - 5.2f * d);
                iconPath.arcTo(cx - 2.4f * d, cy - 6.2f * d, cx + 0.4f * d, cy - 3.4f * d, 180f, 90f, false);
                iconPath.lineTo(cx + 4.4f * d, cy - 6.2f * d);
                iconPath.arcTo(cx + 3.2f * d, cy - 6.2f * d, cx + 5.8f * d, cy - 3.6f * d, 270f, 90f, false);
                iconPath.lineTo(cx + 5.8f * d, cy + 2.4f * d);
                iconPath.arcTo(cx + 3.4f * d, cy + 0.8f * d, cx + 5.8f * d, cy + 3.4f * d, 0f, 90f, false);
                iconPath.lineTo(cx + 3.2f * d, cy + 3.4f * d);
                canvas.drawPath(iconPath, iconPaint);
                canvas.drawRoundRect(copyRectFront, r, r, iconPaint);
            }
        }
    }

    /** Root container supporting interactive ColorOS left/right edge-swipe back. */
    private static final class SwipeablePageRoot extends ProgressiveBlurFrameLayout {
        private final float edgeWidthPx;
        private final int touchSlop;
        private final FrameLayout container;
        private final DiagState state;
        private VelocityTracker vt;
        private boolean tracking;
        private boolean dragging;
        private float downX;
        private float downY;

        SwipeablePageRoot(Context ctx, float d, FrameLayout container, DiagState state) {
            super(ctx);
            this.edgeWidthPx = 36f * d;
            this.touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
            this.container = container;
            this.state = state;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (state.closing) return false;
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                int w = getWidth();
                float x = ev.getX();
                if (w > 0 && (x <= edgeWidthPx || x >= w - edgeWidthPx)) {
                    tracking = true;
                    dragging = false;
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    vt = VelocityTracker.obtain();
                    vt.addMovement(ev);
                } else {
                    tracking = false;
                }
            } else if (action == MotionEvent.ACTION_MOVE && tracking) {
                if (vt != null) vt.addMovement(ev);
                float dx = Math.abs(ev.getRawX() - downX);
                float dy = Math.abs(ev.getRawY() - downY);
                if (dy > touchSlop && dy > dx * 1.15f) {
                    tracking = false;
                    recycleVt();
                } else if (dx > touchSlop && dx > dy * 1.2f) {
                    dragging = true;
                    return true;
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                tracking = false;
                dragging = false;
                recycleVt();
            }
            return super.onInterceptTouchEvent(ev);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (!dragging) return super.onTouchEvent(ev);
            if (vt != null) vt.addMovement(ev);
            int width = Math.max(1, container.getWidth());
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_MOVE) {
                float delta = Math.max(0f, Math.min(width, Math.abs(ev.getRawX() - downX)));
                float progress = delta / width;
                setTranslationX(delta);
                if (state.level1 != null) {
                    state.level1.setTranslationX(-width * 0.28f * (1f - progress));
                }
                state.dimOverlay.setAlpha(0.26f * (1f - progress));
                return true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                float delta = Math.max(0f, Math.abs(ev.getRawX() - downX));
                float vx = 0f;
                if (vt != null) {
                    vt.computeCurrentVelocity(1000);
                    vx = Math.abs(vt.getXVelocity());
                }
                recycleVt();
                tracking = false;
                dragging = false;
                float minVel = 650f * getResources().getDisplayMetrics().density;
                if (delta > width * 0.32f || vx > minVel) {
                    pop(container, state);
                } else {
                    animate().translationX(0f).setDuration(260L).setInterpolator(PUSH_EASE).start();
                    if (state.level1 != null) {
                        state.level1.animate().translationX(-width * 0.28f)
                                .setDuration(260L).setInterpolator(PUSH_EASE).start();
                    }
                    state.dimOverlay.animate().alpha(0.26f)
                            .setDuration(260L).setInterpolator(PUSH_EASE).start();
                }
                return true;
            }
            return super.onTouchEvent(ev);
        }

        private void recycleVt() {
            if (vt != null) {
                vt.recycle();
                vt = null;
            }
        }
    }
}
