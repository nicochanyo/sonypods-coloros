package io.github.nicochanyo.sonypods;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.LeadingMarginSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Official ColorOS 17 ({@code OTA.apk}) full-screen Software Update &amp; Update Notes
 * same-layer push stack with static official {@code kv_bg_17} cosmic backdrop,
 * real liquid-glass frosted cards + ColorOS 17 edge micro-glow, unclipped shadow-free
 * top bar glass buttons, interactive 36dp edge-swipe back, and smooth height-animated
 * collapsible changelog sections.
 */
public final class LivingFlowUpdatePages {
    private static final PathInterpolator PUSH_EASE = new PathInterpolator(0.30f, 0.0f, 0.10f, 1.0f);
    private static final PathInterpolator POP_EASE = new PathInterpolator(0.30f, 0.0f, 0.15f, 1.0f);
    private static final PathInterpolator OTA_RISE_EASE = new PathInterpolator(0.22f, 0.0f, 0.12f, 1.0f);
    private static final WeakHashMap<FrameLayout, OtaStageState> STAGE_MAP = new WeakHashMap<>();

    private static final class OtaStageState {
        final MainActivity activity;
        final FrameLayout container;
        final View baseContent;
        final List<OtaPageEntry> stack = new ArrayList<>();
        Object backCallback;
        Object backDispatcher;

        OtaStageState(MainActivity activity, FrameLayout container, View baseContent) {
            this.activity = activity;
            this.container = container;
            this.baseContent = baseContent;
        }
    }

    private interface InnerNavigationHandler {
        boolean isInnerActive();

        boolean handleBack();

        void onInnerSwipeDrag(float deltaX, float progress);

        void onInnerSwipeSettle(boolean dismissInner, float dragWidth);
    }

    private static final class OtaPageEntry {
        final SwipeablePageRoot root;
        final View dimOverlay;
        InnerNavigationHandler innerHandler;
        boolean closing;

        OtaPageEntry(SwipeablePageRoot root, View dimOverlay) {
            this.root = root;
            this.dimOverlay = dimOverlay;
        }
    }

    private LivingFlowUpdatePages() {
    }

    public static boolean hasActiveStage(Context ctx) {
        if (!(ctx instanceof MainActivity)) return false;
        View raw = ((MainActivity) ctx).findViewById(android.R.id.content);
        if (!(raw instanceof FrameLayout)) return false;
        synchronized (STAGE_MAP) {
            OtaStageState st = STAGE_MAP.get(raw);
            if (st == null || st.stack.isEmpty()) return false;
            for (int i = st.stack.size() - 1; i >= 0; i--) {
                if (!st.stack.get(i).closing) return true;
            }
            return false;
        }
    }

    public static boolean popActiveStage(Context ctx) {
        if (!(ctx instanceof MainActivity)) return false;
        View raw = ((MainActivity) ctx).findViewById(android.R.id.content);
        if (!(raw instanceof FrameLayout)) return false;
        OtaStageState st;
        OtaPageEntry top = null;
        synchronized (STAGE_MAP) {
            st = STAGE_MAP.get(raw);
            if (st != null) {
                for (int i = st.stack.size() - 1; i >= 0; i--) {
                    OtaPageEntry candidate = st.stack.get(i);
                    if (!candidate.closing) {
                        top = candidate;
                        break;
                    }
                }
            }
        }
        if (st != null && top != null) {
            if (top.innerHandler != null && top.innerHandler.handleBack()) {
                return true;
            }
            popEntry(st, top);
            return true;
        }
        return false;
    }

    public static void onHostPause(Context ctx) {
        // Static backdrop requires no playback pause
    }

    public static void onHostResume(Context ctx) {
        if (!(ctx instanceof MainActivity)) return;
        View raw = ((MainActivity) ctx).findViewById(android.R.id.content);
        if (!(raw instanceof FrameLayout)) return;
        synchronized (STAGE_MAP) {
            OtaStageState st = STAGE_MAP.get(raw);
            if (st != null && !st.stack.isEmpty()) {
                OtaPageEntry top = st.stack.get(st.stack.size() - 1);
                if (!top.closing) {
                    applyOtaSystemBars(st.activity);
                }
            }
        }
    }

    public static void showSoftwareUpdatePage(MainActivity activity) {
        showUnifiedOtaPage(activity, false);
    }

    public static void showChangelogPage(MainActivity activity) {
        showUnifiedOtaPage(activity, true);
    }

    private static void showUnifiedOtaPage(MainActivity activity, boolean initialShowNotes) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        View raw = activity.findViewById(android.R.id.content);
        if (!(raw instanceof FrameLayout)) return;
        final FrameLayout container = (FrameLayout) raw;
        final OtaStageState stage = getOrCreateStage(activity, container);

        final float d = activity.getResources().getDisplayMetrics().density;
        final View backdrop = createStaticOtaBackdrop(activity, d);

        final View dimOverlay = new View(activity);
        dimOverlay.setBackgroundColor(0xFF000000);
        dimOverlay.setAlpha(0f);

        final SwipeablePageRoot pageRoot = new SwipeablePageRoot(activity, d, stage);
        final OtaPageEntry entry = new OtaPageEntry(pageRoot, dimOverlay);
        pageRoot.bindEntry(entry);

        pageRoot.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final MainActivity.RepoReleaseInfo[] currentInfo = new MainActivity.RepoReleaseInfo[]{
                MainActivity.getCachedLatestRelease()
        };

        // Shared TopBar (stationary Back & More icons, cross-translating Title)
        final LinearLayout topBar = new LinearLayout(activity);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setOutlineProvider(null);
        topBar.setTranslationZ(16f * d);
        topBar.setClipChildren(false);
        topBar.setClipToPadding(false);
        topBar.setPadding(Math.round(16f * d), resolveStatusBarFallbackPx(activity) + Math.round(10f * d),
                Math.round(16f * d), Math.round(10f * d));

        int btnSize = Math.round(40f * d);
        CircleIconButton btnBack = new CircleIconButton(activity, d, true);
        topBar.addView(btnBack, new LinearLayout.LayoutParams(btnSize, btnSize));

        FrameLayout titleSwitcher = new FrameLayout(activity);
        titleSwitcher.setClipChildren(false);
        titleSwitcher.setClipToPadding(false);

        final TextView tvTitleUpdate = createTopBarTitleView(
                activity, activity.getString(R.string.lf_software_update_title));
        final TextView tvTitleNotes = createTopBarTitleView(
                activity, activity.getString(R.string.lf_update_notes_title));
        titleSwitcher.addView(tvTitleUpdate, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL | Gravity.START));
        titleSwitcher.addView(tvTitleNotes, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL | Gravity.START));

        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMarginStart(Math.round(14f * d));
        titleLp.setMarginEnd(Math.round(10f * d));
        topBar.addView(titleSwitcher, titleLp);

        CircleIconButton btnMore = new CircleIconButton(activity, d, false);
        btnMore.setOnClickListener(anchor -> showRepoMenu(activity, anchor, currentInfo[0]));
        topBar.addView(btnMore, new LinearLayout.LayoutParams(btnSize, btnSize));

        // Same-level body container layered cleanly beneath topBar
        final FrameLayout bodyStack = new FrameLayout(activity);
        bodyStack.setClipChildren(true);
        bodyStack.setClipToPadding(false);

        // --- Mode 0: Software Update Overview Body ---
        final LinearLayout updateBody = new LinearLayout(activity);
        updateBody.setOrientation(LinearLayout.VERTICAL);
        updateBody.setClipChildren(false);
        updateBody.setClipToPadding(false);

        final LinearLayout heroBox = new LinearLayout(activity);
        heroBox.setOrientation(LinearLayout.VERTICAL);
        heroBox.setGravity(Gravity.CENTER);
        heroBox.setPadding(Math.round(24f * d), Math.round(8f * d),
                Math.round(24f * d), Math.round(24f * d));

        final TextView tvHeroTitle = new TextView(activity);
        tvHeroTitle.setText(R.string.app_name);
        tvHeroTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f);
        tvHeroTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvHeroTitle.setLetterSpacing(-0.025f);
        tvHeroTitle.setSingleLine(true);
        tvHeroTitle.setMaxLines(1);
        tvHeroTitle.setAutoSizeTextTypeUniformWithConfiguration(24, 36, 1, TypedValue.COMPLEX_UNIT_SP);
        tvHeroTitle.setIncludeFontPadding(false);
        tvHeroTitle.setGravity(Gravity.CENTER);
        tvHeroTitle.setTextColor(0xFFFFFFFF);
        tvHeroTitle.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            if (h > 0 && h != (ob - ot)) {
                tvHeroTitle.getPaint().setShader(new LinearGradient(
                        0f, 0f, 0f, h,
                        new int[]{0xFFFFFFFF, 0xFFFCEEFF, 0xFFF3DCFF},
                        new float[]{0f, 0.65f, 1f},
                        Shader.TileMode.CLAMP));
                tvHeroTitle.invalidate();
            }
        });
        heroBox.addView(tvHeroTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tvHeroVer = new TextView(activity);
        tvHeroVer.setText(MainActivity.formatVerLabel(activity.getInstalledVersionName()));
        tvHeroVer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        tvHeroVer.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvHeroVer.setTextColor(0xF2FFFFFF);
        tvHeroVer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams verLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        verLp.topMargin = Math.round(8f * d);
        heroBox.addView(tvHeroVer, verLp);

        TextView tvHeroDevice = new TextView(activity);
        tvHeroDevice.setText(Build.MANUFACTURER + " " + Build.MODEL + " · API 102");
        tvHeroDevice.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tvHeroDevice.setTextColor(0xBEFFFFFF);
        tvHeroDevice.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams devLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        devLp.topMargin = Math.round(6f * d);
        heroBox.addView(tvHeroDevice, devLp);

        final LinearLayout heroCheckRow = new LinearLayout(activity);
        heroCheckRow.setOrientation(LinearLayout.HORIZONTAL);
        heroCheckRow.setGravity(Gravity.CENTER);
        final IndeterminateSpinnerView heroSpinner = new IndeterminateSpinnerView(activity, d);
        LinearLayout.LayoutParams hsLp = new LinearLayout.LayoutParams(
                Math.round(15f * d), Math.round(15f * d));
        hsLp.setMarginEnd(Math.round(8f * d));
        heroCheckRow.addView(heroSpinner, hsLp);

        TextView tvHeroCheck = new TextView(activity);
        tvHeroCheck.setText(R.string.lf_checking_update_status);
        tvHeroCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        tvHeroCheck.setTextColor(0xCCFFFFFF);
        heroCheckRow.addView(tvHeroCheck, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams hcrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hcrLp.topMargin = Math.round(20f * d);
        heroCheckRow.setVisibility(initialShowNotes ? View.GONE : View.VISIBLE);
        heroBox.addView(heroCheckRow, hcrLp);

        updateBody.addView(heroBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final LinearLayout bottomCard = new LinearLayout(activity);
        bottomCard.setOrientation(LinearLayout.VERTICAL);
        bottomCard.setClipChildren(false);
        bottomCard.setClipToPadding(false);
        ColorOsLivingFlowEngine.applyGlassSurface(
                bottomCard, ColorOsLivingFlowEngine.GlassPreset.DARK_FROSTED_CARD,
                true, 26f, false, false);
        bottomCard.setPadding(Math.round(22f * d), Math.round(22f * d),
                Math.round(22f * d), Math.round(20f * d));

        LinearLayout statusRow = new LinearLayout(activity);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        final IndeterminateSpinnerView spinner = new IndeterminateSpinnerView(activity, d);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
                Math.round(18f * d), Math.round(18f * d));
        spLp.setMarginEnd(Math.round(10f * d));
        spinner.setVisibility(View.GONE);
        statusRow.addView(spinner, spLp);

        TextView tvStatusTitle = new TextView(activity);
        tvStatusTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        tvStatusTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvStatusTitle.setTextColor(0xFFFFFFFF);
        statusRow.addView(tvStatusTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        bottomCard.addView(statusRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tvVersionSub = new TextView(activity);
        tvVersionSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        tvVersionSub.setTextColor(0xC2FFFFFF);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Math.round(5f * d);
        bottomCard.addView(tvVersionSub, subLp);

        TextView btnPrimary = createLightGlassPillButton(activity, d);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(46f * d));
        pLp.topMargin = Math.round(20f * d);
        bottomCard.addView(btnPrimary, pLp);

        final TextView btnSecondary = createDarkGlassPillButton(activity, d);
        btnSecondary.setText(R.string.lf_btn_view_changelog);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(46f * d));
        sLp.topMargin = Math.round(12f * d);
        bottomCard.addView(btnSecondary, sLp);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int sideMargin = Math.round(16f * d);
        cardLp.setMargins(sideMargin, 0, sideMargin, resolveNavBarFallbackPx(activity) + Math.round(14f * d));
        updateBody.addView(bottomCard, cardLp);

        bodyStack.addView(updateBody, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- Mode 1: Update Notes Scrollable Card Body ---
        final CouiBounceScrollView changelogScroll = new CouiBounceScrollView(activity) {
            @Override
            protected float getBottomFadingEdgeStrength() {
                return 0f;
            }

            @Override
            protected void dispatchDraw(Canvas canvas) {
                int save = canvas.save();
                canvas.clipRect(getScrollX(), getScrollY(), getScrollX() + getWidth(), getScrollY() + getHeight());
                super.dispatchDraw(canvas);
                canvas.restoreToCount(save);
            }
        };
        changelogScroll.setVerticalScrollBarEnabled(false);
        changelogScroll.setClipChildren(true);
        changelogScroll.setClipToPadding(false);
        changelogScroll.setOutlineProvider(ViewOutlineProvider.BOUNDS);
        changelogScroll.setClipToOutline(true);
        changelogScroll.setVerticalFadingEdgeEnabled(true);
        changelogScroll.setFadingEdgeLength(Math.round(24f * d));
        int sidePad = Math.round(16f * d);
        changelogScroll.setPadding(sidePad, 0, sidePad,
                resolveNavBarFallbackPx(activity) + Math.round(24f * d));

        final LinearLayout changelogCard = new LinearLayout(activity);
        changelogCard.setOrientation(LinearLayout.VERTICAL);
        changelogCard.setPadding(0, 0, 0, Math.round(6f * d));
        ColorOsLivingFlowEngine.applyGlassSurface(
                changelogCard, ColorOsLivingFlowEngine.GlassPreset.DARK_FROSTED_CARD,
                true, 26f, false, false);

        populateChangelogCard(activity, changelogCard, currentInfo[0], d);
        FrameLayout.LayoutParams cardScrollLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardScrollLp.topMargin = Math.round(8f * d);
        changelogScroll.addView(changelogCard, cardScrollLp);
        bodyStack.addView(changelogScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams bodyStackLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        bodyStackLp.topMargin = resolveStatusBarFallbackPx(activity) + Math.round(60f * d);
        pageRoot.addView(bodyStack, bodyStackLp);
        pageRoot.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        topBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            if (h > 0) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bodyStack.getLayoutParams();
                if (lp != null && lp.topMargin != h) {
                    lp.topMargin = h;
                    bodyStack.setLayoutParams(lp);
                }
                pageRoot.setTopBarSafeY(h + Math.round(4f * d));
            }
        });

        final boolean[] showingNotes = new boolean[]{initialShowNotes};
        final int[] switchGen = new int[1];

        tvTitleUpdate.setVisibility(initialShowNotes ? View.GONE : View.VISIBLE);
        tvTitleNotes.setVisibility(initialShowNotes ? View.VISIBLE : View.GONE);
        updateBody.setVisibility(initialShowNotes ? View.GONE : View.VISIBLE);
        changelogScroll.setVisibility(initialShowNotes ? View.VISIBLE : View.GONE);

        final class ModeSwitcher {
            void switchTo(boolean toNotes, boolean fromSwipe) {
                if (showingNotes[0] == toNotes) return;
                showingNotes[0] = toNotes;
                final int gen = ++switchGen[0];

                tvTitleUpdate.animate().cancel();
                tvTitleNotes.animate().cancel();
                updateBody.animate().cancel();
                changelogScroll.animate().cancel();

                final float titleShift = 18f * d;
                final float bodyShift = 36f * d;
                final TextView outTitle = toNotes ? tvTitleUpdate : tvTitleNotes;
                final TextView inTitle = toNotes ? tvTitleNotes : tvTitleUpdate;
                final View outBody = toNotes ? updateBody : changelogScroll;
                final View inBody = toNotes ? changelogScroll : updateBody;

                outTitle.animate()
                        .alpha(0f)
                        .translationX(toNotes ? -titleShift : titleShift)
                        .setDuration(260L)
                        .setInterpolator(PUSH_EASE)
                        .withEndAction(() -> {
                            if (switchGen[0] == gen) {
                                outTitle.setVisibility(View.GONE);
                                outTitle.setTranslationX(0f);
                            }
                        })
                        .start();

                inTitle.setVisibility(View.VISIBLE);
                inTitle.setAlpha(0f);
                inTitle.setTranslationX(toNotes ? titleShift : -titleShift);
                inTitle.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .setDuration(300L)
                        .setInterpolator(PUSH_EASE)
                        .start();

                float outTargetX = toNotes ? -bodyShift : (fromSwipe ? Math.max(bodyShift * 2.5f, outBody.getTranslationX() + 96f * d) : bodyShift);
                outBody.animate()
                        .alpha(0f)
                        .translationX(outTargetX)
                        .setDuration(250L)
                        .setInterpolator(PUSH_EASE)
                        .withEndAction(() -> {
                            if (switchGen[0] == gen) {
                                outBody.setVisibility(View.GONE);
                                outBody.setTranslationX(0f);
                                outBody.setAlpha(1f);
                            }
                        })
                        .start();

                if (toNotes) {
                    changelogScroll.scrollTo(0, 0);
                }
                inBody.setVisibility(View.VISIBLE);
                inBody.setAlpha(0f);
                inBody.setTranslationX(toNotes ? bodyShift : -bodyShift);
                inBody.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .setDuration(340L)
                        .setInterpolator(PUSH_EASE)
                        .start();
            }
        }
        final ModeSwitcher switcher = new ModeSwitcher();

        entry.innerHandler = new InnerNavigationHandler() {
            @Override
            public boolean isInnerActive() {
                return !initialShowNotes && showingNotes[0];
            }

            @Override
            public boolean handleBack() {
                if (!initialShowNotes && showingNotes[0]) {
                    switcher.switchTo(false, false);
                    return true;
                }
                return false;
            }

            @Override
            public void onInnerSwipeDrag(float deltaX, float progress) {
                changelogScroll.setTranslationX(deltaX);
                changelogScroll.setAlpha(Math.max(0.35f, 1f - progress * 0.65f));
            }

            @Override
            public void onInnerSwipeSettle(boolean dismissInner, float dragWidth) {
                if (dismissInner) {
                    switcher.switchTo(false, true);
                } else {
                    changelogScroll.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(240L)
                            .setInterpolator(PUSH_EASE)
                            .start();
                }
            }
        };

        btnSecondary.setOnClickListener(v -> switcher.switchTo(true, false));
        btnBack.setOnClickListener(v -> {
            if (entry.innerHandler != null && entry.innerHandler.handleBack()) {
                return;
            }
            popEntry(stage, entry);
        });

        final String buildStr = "SonyPods_" + activity.getInstalledVersionName()
                + "." + activity.getInstalledVersionCode();
        final Runnable[] triggerCheck = new Runnable[1];

        final class StateBinder {
            private boolean firstBind = true;

            void bind(boolean checking, MainActivity.RepoReleaseInfo info, boolean failed) {
                if (!firstBind) {
                    animateCrossFade(tvStatusTitle);
                    animateCrossFade(tvVersionSub);
                }
                firstBind = false;

                if (checking) {
                    spinner.setVisibility(View.VISIBLE);
                    spinner.startSpinning();
                    tvStatusTitle.setText(R.string.lf_checking_update_status);
                    tvVersionSub.setText(buildStr);
                    btnPrimary.setEnabled(false);
                    btnPrimary.setAlpha(0.62f);
                    btnPrimary.setText(R.string.chip_update_checking);
                    btnPrimary.setOnClickListener(null);
                    return;
                }
                spinner.stopSpinning();
                spinner.setVisibility(View.GONE);
                btnPrimary.setEnabled(true);
                btnPrimary.setAlpha(1f);
                if (info != null && activity.isNewerRelease(info)) {
                    String latestVer = MainActivity.formatVerLabel(info.versionName);
                    tvStatusTitle.setText(activity.getString(
                            R.string.dialog_update_available_title_fmt, latestVer));
                    tvVersionSub.setText(activity.getString(
                            R.string.dialog_update_ver_compare_fmt,
                            MainActivity.formatVerLabel(activity.getInstalledVersionName()),
                            latestVer));
                    btnPrimary.setText(R.string.btn_update_download);
                    btnPrimary.setOnClickListener(v -> {
                        String url = (info.downloadUrl != null && !info.downloadUrl.isEmpty())
                                ? info.downloadUrl : info.repoPageUrl;
                        if (url != null && !url.isEmpty()) {
                            activity.openRepoOrUrl(url);
                        } else {
                            activity.openRepoPage(info);
                        }
                    });
                    if (!initialShowNotes && !showingNotes[0]) {
                        populateChangelogCard(activity, changelogCard, info, d);
                    }
                } else {
                    tvStatusTitle.setText(R.string.dialog_update_latest_title);
                    tvVersionSub.setText(buildStr);
                    btnPrimary.setText(R.string.lf_btn_recheck_update);
                    btnPrimary.setOnClickListener(v -> triggerCheck[0].run());
                    if (failed) {
                        Toast.makeText(activity, R.string.toast_update_failed, Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }
        final StateBinder binder = new StateBinder();

        final boolean[] heroRevealed = new boolean[]{initialShowNotes};
        final long pageOpenMs = SystemClock.uptimeMillis();
        final long MIN_CENTER_CHECK_MS = 780L;

        final Runnable syncHeroCenter = () -> {
            if (heroRevealed[0]) return;
            int cardH = bottomCard.getHeight();
            if (cardH <= 0) return;
            LinearLayout.LayoutParams clp = (LinearLayout.LayoutParams) bottomCard.getLayoutParams();
            int cardFullH = cardH + (clp != null ? clp.bottomMargin : 0);
            int topBarH = topBar.getHeight();
            float centerShiftY = Math.max(48f * d, (cardFullH - topBarH) * 0.5f);
            heroBox.setTranslationY(centerShiftY);
        };
        updateBody.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> syncHeroCenter.run());
        bottomCard.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> syncHeroCenter.run());

        final Runnable revealFromCenter = () -> {
            if (heroRevealed[0] || entry.closing || !pageRoot.isAttachedToWindow()) {
                return;
            }
            heroRevealed[0] = true;
            heroSpinner.stopSpinning();
            heroCheckRow.animate().cancel();
            heroCheckRow.animate()
                    .alpha(0f)
                    .translationY(-8f * d)
                    .setDuration(260L)
                    .setInterpolator(PUSH_EASE)
                    .withEndAction(() -> heroCheckRow.setVisibility(View.INVISIBLE))
                    .start();

            heroBox.animate().cancel();
            heroBox.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(780L)
                    .setInterpolator(OTA_RISE_EASE)
                    .start();

            bottomCard.setVisibility(View.VISIBLE);
            bottomCard.setAlpha(0f);
            bottomCard.setTranslationY(72f * d);
            bottomCard.animate().cancel();
            bottomCard.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(90L)
                    .setDuration(760L)
                    .setInterpolator(OTA_RISE_EASE)
                    .start();
        };

        final Runnable scheduleReveal = () -> {
            if (heroRevealed[0] || entry.closing || !pageRoot.isAttachedToWindow()) {
                return;
            }
            long waitMs = Math.max(0L, MIN_CENTER_CHECK_MS - (SystemClock.uptimeMillis() - pageOpenMs));
            if (waitMs == 0L) {
                revealFromCenter.run();
            } else {
                pageRoot.postDelayed(revealFromCenter, waitMs);
            }
        };

        triggerCheck[0] = () -> {
            binder.bind(true, currentInfo[0], false);
            activity.runRepoUpdateCheck(info -> {
                if (entry.closing || !pageRoot.isAttachedToWindow()
                        || activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (info != null) {
                    currentInfo[0] = info;
                }
                binder.bind(false, currentInfo[0], info == null);
            });
        };

        pageRoot.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()).top
                    : insets.getSystemWindowInsetTop();
            int bottom = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars()).bottom
                    : insets.getSystemWindowInsetBottom();
            int barTop = top + Math.round(10f * d);
            topBar.setPadding(Math.round(16f * d), barTop,
                    Math.round(16f * d), Math.round(10f * d));
            int expectedBarH = barTop + btnSize + Math.round(10f * d);
            FrameLayout.LayoutParams bsLp = (FrameLayout.LayoutParams) bodyStack.getLayoutParams();
            if (bsLp != null && bsLp.topMargin != expectedBarH) {
                bsLp.topMargin = expectedBarH;
                bodyStack.setLayoutParams(bsLp);
            }
            pageRoot.setTopBarSafeY(expectedBarH + Math.round(4f * d));
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bottomCard.getLayoutParams();
            if (lp != null) {
                lp.bottomMargin = bottom + Math.round(14f * d);
                bottomCard.setLayoutParams(lp);
            }
            changelogScroll.setPadding(sidePad, 0, sidePad,
                    bottom + Math.round(24f * d));
            return insets;
        });

        pushEntry(stage, entry);

        if (!initialShowNotes) {
            bottomCard.setVisibility(View.INVISIBLE);
            bottomCard.setAlpha(0f);
            bottomCard.setTranslationY(72f * d);
            if (currentInfo[0] != null) {
                binder.bind(false, currentInfo[0], false);
            }
            heroSpinner.startSpinning();
            heroBox.setAlpha(0f);
            heroBox.setScaleX(0.96f);
            heroBox.setScaleY(0.96f);
            heroBox.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setStartDelay(60L)
                    .setDuration(340L)
                    .setInterpolator(PUSH_EASE)
                    .start();

            activity.runRepoUpdateCheck(info -> {
                if (entry.closing || !pageRoot.isAttachedToWindow()
                        || activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (info != null) {
                    currentInfo[0] = info;
                }
                binder.bind(false, currentInfo[0], info == null && currentInfo[0] == null);
                scheduleReveal.run();
            });
            if (currentInfo[0] != null) {
                pageRoot.postDelayed(revealFromCenter, 1400L);
            }
        } else {
            binder.bind(false, currentInfo[0], false);
            bottomCard.setVisibility(View.VISIBLE);
            bottomCard.setAlpha(1f);
            bottomCard.setTranslationY(0f);

            changelogCard.setAlpha(0f);
            changelogCard.setTranslationY(16f * d);
            changelogCard.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(85L)
                    .setDuration(360L)
                    .setInterpolator(PUSH_EASE)
                    .start();
        }
    }

    private static TextView createTopBarTitleView(Context ctx, String text) {
        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(text);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        return tvTitle;
    }

    private static void populateChangelogCard(MainActivity activity, LinearLayout glassCard,
                                              MainActivity.RepoReleaseInfo cached, float d) {
        glassCard.removeAllViews();

        LinearLayout verHeader = new LinearLayout(activity);
        verHeader.setOrientation(LinearLayout.VERTICAL);
        verHeader.setPadding(Math.round(22f * d), Math.round(20f * d),
                Math.round(22f * d), Math.round(20f * d));

        TextView tvVerLabel = new TextView(activity);
        tvVerLabel.setText(R.string.lf_software_version_label);
        tvVerLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        tvVerLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvVerLabel.setTextColor(0xFFFFFFFF);
        verHeader.addView(tvVerLabel);

        TextView tvVerValue = new TextView(activity);
        tvVerValue.setText("SonyPods_" + activity.getInstalledVersionName()
                + "." + activity.getInstalledVersionCode());
        tvVerValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        tvVerValue.setTextColor(0xC4FFFFFF);
        LinearLayout.LayoutParams vvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vvLp.topMargin = Math.round(6f * d);
        verHeader.addView(tvVerValue, vvLp);

        glassCard.addView(verHeader, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addGlassDivider(glassCard, d, 0x26FFFFFF);

        String notesPrefix = activity.getString(R.string.lf_update_notes_title);
        boolean hasRemoteSection = false;
        if (cached != null && activity.isNewerRelease(cached)) {
            CharSequence remoteNotes = activity.formatLocalizedChangelog(cached,
                    new CouiBottomSheet.Palette(true, 0xFF1E1E24, 0xFF28282E, 0x26FFFFFF,
                            0xFFFFFFFF, 0xBFFFFFFF, 0x22FFFFFF, 0x22FFFFFF,
                            0xFFFFFFFF, 0xFF14151C, 0x22FFFFFF, 0xFF28282C,
                            0x33FFFFFF, 0x44FFFFFF, 0x33FFFFFF, 0xFFFF554A, true));
            if (remoteNotes != null && remoteNotes.length() > 0) {
                hasRemoteSection = true;
                addCollapsibleSection(
                        activity,
                        glassCard,
                        d,
                        notesPrefix + " · " + MainActivity.formatVerLabel(cached.versionName),
                        formatBulletText(remoteNotes.toString(), d),
                        true,
                        true);
            }
        }

        int[] verTitles = new int[]{
                R.string.changelog_v103_title,
                R.string.changelog_v102_title,
                R.string.changelog_v101_title,
                R.string.changelog_v100_title
        };
        int[] verBodies = new int[]{
                R.string.changelog_v103_body,
                R.string.changelog_v102_body,
                R.string.changelog_v101_body,
                R.string.changelog_v100_body
        };
        for (int i = 0; i < verTitles.length; i++) {
            String verTag = activity.getString(verTitles[i]);
            String secTitle = (i == 0) ? (notesPrefix + " · " + verTag) : verTag;
            boolean expandDefault = (!hasRemoteSection && i == 0);
            addCollapsibleSection(
                    activity,
                    glassCard,
                    d,
                    secTitle,
                    formatBulletText(activity.getString(verBodies[i]), d),
                    expandDefault,
                    i < verTitles.length - 1);
        }
    }

    private static View createStaticOtaBackdrop(Context ctx, float d) {
        FrameLayout root = new FrameLayout(ctx);
        ImageView baseIv = new ImageView(ctx);
        baseIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Drawable officialBase = loadSystemOtaDrawable(ctx);
        if (officialBase != null) {
            baseIv.setImageDrawable(officialBase);
        } else {
            baseIv.setBackground(new StaticCosmicAuroraDrawable(d));
        }
        root.addView(baseIv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (Build.VERSION.SDK_INT >= 31) {
            BottomSoftBlurLayer blurLayer = new BottomSoftBlurLayer(ctx);
            ImageView blurIv = new ImageView(ctx);
            blurIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Drawable officialBlur = loadSystemOtaDrawable(ctx);
            if (officialBlur != null) {
                blurIv.setImageDrawable(officialBlur.mutate());
            } else {
                blurIv.setBackground(new StaticCosmicAuroraDrawable(d));
            }
            blurIv.setRenderEffect(RenderEffect.createBlurEffect(7f * d, 7f * d, Shader.TileMode.CLAMP));
            blurLayer.addView(blurIv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(blurLayer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        return root;
    }

    private static final class BottomSoftBlurLayer extends FrameLayout {
        private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        BottomSoftBlurLayer(Context ctx) {
            super(ctx);
            maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w > 0 && h > 0) {
                maskPaint.setShader(new LinearGradient(
                        0f, h * 0.54f, 0f, h * 0.72f,
                        0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                super.dispatchDraw(canvas);
                return;
            }
            int sc = canvas.saveLayer(0f, 0f, w, h, null);
            super.dispatchDraw(canvas);
            canvas.drawRect(0f, 0f, w, h, maskPaint);
            canvas.restoreToCount(sc);
        }
    }

    private static Drawable loadSystemOtaDrawable(Context ctx) {
        try {
            Context otaCtx = ctx.createPackageContext("com.oplus.ota", Context.CONTEXT_IGNORE_SECURITY);
            String[] candidates = {
                    "kv_bg_17", "kv_bg_17_card",
                    "kv_bg_16", "kv_bg_16_card",
                    "kv_bg_15", "kv_bg_15_card"
            };
            for (String name : candidates) {
                int resId = otaCtx.getResources().getIdentifier(name, "drawable", "com.oplus.ota");
                if (resId != 0) {
                    return otaCtx.getDrawable(resId);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static OtaStageState getOrCreateStage(MainActivity activity, FrameLayout container) {
        synchronized (STAGE_MAP) {
            OtaStageState existing = STAGE_MAP.get(container);
            if (existing != null) {
                return existing;
            }
            View baseContent = container.getChildCount() > 0 ? container.getChildAt(0) : null;
            OtaStageState created = new OtaStageState(activity, container, baseContent);
            STAGE_MAP.put(container, created);
            return created;
        }
    }

    private static void pushEntry(OtaStageState stage, OtaPageEntry entry) {
        final View underView;
        final OtaPageEntry prevEntry;
        synchronized (STAGE_MAP) {
            int size = stage.stack.size();
            prevEntry = size > 0 ? stage.stack.get(size - 1) : null;
            underView = prevEntry != null ? prevEntry.root : stage.baseContent;
            stage.stack.add(entry);
        }

        if (prevEntry == null) {
            registerBackCallback(stage);
        }
        applyOtaSystemBars(stage.activity);

        int width = stage.container.getWidth() > 0
                ? stage.container.getWidth()
                : stage.container.getResources().getDisplayMetrics().widthPixels;

        stage.container.addView(entry.dimOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        entry.root.setTranslationX(width);
        stage.container.addView(entry.root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        entry.root.requestApplyInsets();
        entry.root.requestFocus();

        if (underView != null) {
            underView.animate().cancel();
            underView.animate()
                    .translationX(-width * 0.28f)
                    .setDuration(380L)
                    .setInterpolator(PUSH_EASE)
                    .start();
        }
        entry.dimOverlay.animate().alpha(0.32f).setDuration(380L).setInterpolator(PUSH_EASE).start();
        entry.root.animate()
                .translationX(0f)
                .setDuration(380L)
                .setInterpolator(PUSH_EASE)
                .start();
    }

    private static void popEntry(OtaStageState stage, OtaPageEntry entry) {
        if (stage == null || entry == null || entry.closing) return;
        entry.closing = true;

        final View underView;
        final OtaPageEntry revealedEntry;
        final boolean isLast;
        synchronized (STAGE_MAP) {
            int idx = stage.stack.indexOf(entry);
            revealedEntry = (idx > 0) ? stage.stack.get(idx - 1) : null;
            underView = revealedEntry != null ? revealedEntry.root : stage.baseContent;
            int activeCount = 0;
            for (int i = 0; i < stage.stack.size(); i++) {
                if (!stage.stack.get(i).closing) activeCount++;
            }
            isLast = (activeCount == 0);
        }

        if (isLast) {
            unregisterBackCallback(stage);
            restoreActivitySystemBars(stage.activity);
        }

        int width = stage.container.getWidth() > 0
                ? stage.container.getWidth()
                : stage.container.getResources().getDisplayMetrics().widthPixels;

        if (underView != null) {
            underView.animate().cancel();
            underView.animate()
                    .translationX(0f)
                    .setDuration(340L)
                    .setInterpolator(POP_EASE)
                    .start();
        }
        entry.dimOverlay.animate().cancel();
        entry.dimOverlay.animate()
                .alpha(0f)
                .setDuration(340L)
                .setInterpolator(POP_EASE)
                .withEndAction(() -> safeRemove(stage.container, entry.dimOverlay))
                .start();

        entry.root.animate().cancel();
        entry.root.animate()
                .translationX(width)
                .setDuration(340L)
                .setInterpolator(POP_EASE)
                .withEndAction(() -> {
                    safeRemove(stage.container, entry.root);
                    synchronized (STAGE_MAP) {
                        stage.stack.remove(entry);
                        if (stage.stack.isEmpty()) {
                            STAGE_MAP.remove(stage.container);
                        }
                    }
                })
                .start();
    }

    private static View resolveUnderView(OtaStageState stage, OtaPageEntry entry) {
        synchronized (STAGE_MAP) {
            int idx = stage.stack.indexOf(entry);
            if (idx > 0) {
                return stage.stack.get(idx - 1).root;
            }
            return stage.baseContent;
        }
    }

    private static void safeRemove(FrameLayout parent, View child) {
        if (parent == null || child == null) return;
        try {
            parent.removeView(child);
        } catch (Throwable ignored) {
        }
    }

    private static void applyOtaSystemBars(MainActivity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 30) return;
        Window win = activity.getWindow();
        if (win == null) return;
        WindowInsetsController ctrl = win.getInsetsController();
        if (ctrl != null) {
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            ctrl.setSystemBarsAppearance(0, mask);
        }
    }

    private static void restoreActivitySystemBars(MainActivity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 30) return;
        Window win = activity.getWindow();
        if (win == null) return;
        WindowInsetsController ctrl = win.getInsetsController();
        if (ctrl != null) {
            boolean lightBars = !ConfigStore.isDarkThemeActive(activity);
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            ctrl.setSystemBarsAppearance(lightBars ? mask : 0, mask);
        }
    }

    private static void registerBackCallback(OtaStageState stage) {
        if (Build.VERSION.SDK_INT < 33 || stage.backCallback != null) return;
        try {
            OnBackInvokedDispatcher disp = stage.activity.getOnBackInvokedDispatcher();
            if (disp != null) {
                OnBackInvokedCallback cb = () -> popActiveStage(stage.activity);
                disp.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
                stage.backCallback = cb;
                stage.backDispatcher = disp;
            }
        } catch (Throwable ignored) {
        }
    }

    private static void unregisterBackCallback(OtaStageState stage) {
        if (Build.VERSION.SDK_INT < 33 || stage.backCallback == null) return;
        try {
            if (stage.backDispatcher instanceof OnBackInvokedDispatcher
                    && stage.backCallback instanceof OnBackInvokedCallback) {
                ((OnBackInvokedDispatcher) stage.backDispatcher)
                        .unregisterOnBackInvokedCallback((OnBackInvokedCallback) stage.backCallback);
            }
        } catch (Throwable ignored) {
        }
        stage.backCallback = null;
        stage.backDispatcher = null;
    }

    private static void animateCrossFade(View v) {
        v.animate().cancel();
        v.setAlpha(0.35f);
        v.animate().alpha(1f).setDuration(200L).setInterpolator(PUSH_EASE).start();
    }

    private static void addCollapsibleSection(Context ctx, LinearLayout card, float d,
                                              String title, CharSequence bodyText,
                                              boolean initiallyExpanded, boolean addBottomDivider) {
        final boolean[] expanded = new boolean[]{initiallyExpanded};

        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setClickable(true);
        headerRow.setFocusable(true);
        headerRow.setBackground(null);
        headerRow.setPadding(Math.round(16f * d), Math.round(18f * d),
                Math.round(14f * d), Math.round(16f * d));

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.setMarginEnd(Math.round(12f * d));
        headerRow.addView(tvTitle, tLp);

        ChevronCircleView chev = new ChevronCircleView(ctx, d);
        chev.setDuplicateParentStateEnabled(true);
        GradientDrawable chevMask = new GradientDrawable();
        chevMask.setShape(GradientDrawable.OVAL);
        chevMask.setColor(0xFFFFFFFF);
        chev.setForeground(new RippleDrawable(ColorStateList.valueOf(0x36FFFFFF), null, chevMask));
        chev.setRotation(initiallyExpanded ? 0f : 180f);
        int chevSize = Math.round(28f * d);
        headerRow.addView(chev, new LinearLayout.LayoutParams(chevSize, chevSize));

        LinearLayout.LayoutParams hrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int sideInset = Math.round(6f * d);
        hrLp.setMarginStart(sideInset);
        hrLp.setMarginEnd(sideInset);
        card.addView(headerRow, hrLp);

        ExpandableBodyLayout bodyWrap = new ExpandableBodyLayout(ctx, d, initiallyExpanded);
        TextView tvBody = new TextView(ctx);
        tvBody.setText(bodyText);
        tvBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        tvBody.setLineSpacing(0f, 1.42f);
        tvBody.setTextColor(0xE6FFFFFF);
        tvBody.setPadding(Math.round(22f * d), Math.round(2f * d),
                Math.round(22f * d), Math.round(20f * d));
        bodyWrap.addView(tvBody, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(bodyWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        headerRow.setOnClickListener(v -> {
            ColorOsLivingFlowEngine.performHaptic(v, ColorOsLivingFlowEngine.HapticKind.TICK);
            expanded[0] = !expanded[0];
            chev.animate().cancel();
            chev.setScaleX(0.86f);
            chev.setScaleY(0.86f);
            chev.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .rotation(expanded[0] ? 0f : 180f)
                    .setDuration(260L)
                    .setInterpolator(PUSH_EASE)
                    .start();
            bodyWrap.setExpandedAnimated(expanded[0]);
        });

        if (addBottomDivider) {
            addGlassDivider(card, d, 0x1EFFFFFF);
        }
    }

    private static CharSequence formatBulletText(String raw, float d) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String[] lines = raw.replace("\r\n", "\n").replace("\r", "\n").split("\n");
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int indentPx = Math.round(13f * d);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            int start = sb.length();
            sb.append(line);
            if (line.startsWith("•")) {
                sb.setSpan(new LeadingMarginSpan.Standard(0, indentPx),
                        start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }

    private static void addGlassDivider(LinearLayout parent, float d, int color) {
        View div = new View(parent.getContext());
        div.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(0.6f * d)));
        lp.setMarginStart(Math.round(22f * d));
        lp.setMarginEnd(Math.round(22f * d));
        parent.addView(div, lp);
    }

    private static void showRepoMenu(MainActivity activity, View anchor, MainActivity.RepoReleaseInfo info) {
        CouiBottomSheet.Palette darkPal = new CouiBottomSheet.Palette(
                true, 0xFF22222A, 0xFF2B2B34, 0x2EFFFFFF,
                0xFFFFFFFF, 0xBFFFFFFF, 0x22FFFFFF, 0x26FFFFFF,
                DynamicThemeHelper.getAccent(activity), 0xFFFFFFFF, 0x26FFFFFF, 0xFF2C2C34,
                0x33FFFFFF, 0x55FFFFFF, 0x38FFFFFF, 0xFFFF554A, true);
        String[] items = new String[]{activity.getString(R.string.btn_update_open_repo)};
        CouiBottomSheet.showPopupMenu(activity, anchor, darkPal, items, -1, which -> {
            if (which == 0) {
                activity.openRepoPage(info);
            }
        });
    }

    private static TextView createLightGlassPillButton(Context ctx, float d) {
        TextView btn = new TextView(ctx);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btn.setTextColor(0xFF14151C);

        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(0xFFEBEDF2);
        shape.setCornerRadius(999f * d);
        btn.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1E000000), shape, shape));
        LivingFlowEffects.TouchFeedback.attach(
                btn, LivingFlowEffects.TouchFeedback.Profile.PRIMARY_BUTTON, false, 999f, false, true, false);
        ColorOsLivingFlowEngine.applyLuminousPillButton(btn, false, 0, false, false);
        return btn;
    }

    private static TextView createDarkGlassPillButton(Context ctx, float d) {
        TextView btn = new TextView(ctx);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setTextColor(0xFFFFFFFF);

        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(0x22FFFFFF);
        shape.setStroke(Math.max(1, Math.round(0.7f * d)), 0x20FFFFFF);
        shape.setCornerRadius(999f * d);
        btn.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2EFFFFFF), shape, shape));
        LivingFlowEffects.TouchFeedback.attach(
                btn, LivingFlowEffects.TouchFeedback.Profile.PRIMARY_BUTTON, true, 999f, false, true, false);
        ColorOsLivingFlowEngine.applyLuminousPillButton(btn, false, 0, true, false);
        return btn;
    }

    private static int resolveStatusBarFallbackPx(Context ctx) {
        int resId = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) {
            return ctx.getResources().getDimensionPixelSize(resId);
        }
        return Math.round(32f * ctx.getResources().getDisplayMetrics().density);
    }

    private static int resolveNavBarFallbackPx(Context ctx) {
        int resId = ctx.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (resId > 0) {
            return ctx.getResources().getDimensionPixelSize(resId);
        }
        return Math.round(28f * ctx.getResources().getDisplayMetrics().density);
    }

    /**
     * Smooth height + alpha + slide reveal wrapper replacing abrupt {@code View.GONE} switches.
     */
    private static final class ExpandableBodyLayout extends FrameLayout {
        private final float d;
        private float fraction;
        private ValueAnimator animator;

        ExpandableBodyLayout(Context ctx, float d, boolean initiallyExpanded) {
            super(ctx);
            this.d = d;
            this.fraction = initiallyExpanded ? 1f : 0f;
            setClipChildren(true);
            setClipToPadding(true);
        }

        void setExpandedAnimated(boolean expand) {
            float target = expand ? 1f : 0f;
            if (animator != null) {
                animator.cancel();
            }
            animator = ValueAnimator.ofFloat(fraction, target);
            animator.setDuration(280L);
            animator.setInterpolator(PUSH_EASE);
            animator.addUpdateListener(a -> {
                fraction = (Float) a.getAnimatedValue();
                applyChildVisuals();
                requestLayout();
            });
            animator.start();
        }

        private void applyChildVisuals() {
            if (getChildCount() > 0) {
                View child = getChildAt(0);
                child.setAlpha(fraction);
                child.setTranslationY(-(1f - fraction) * 8f * d);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (getChildCount() == 0) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                return;
            }
            View child = getChildAt(0);
            int childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            measureChildWithMargins(child, widthMeasureSpec, 0, childHeightSpec, 0);
            int w = resolveSize(child.getMeasuredWidth(), widthMeasureSpec);
            int h = Math.max(0, Math.round(child.getMeasuredHeight() * fraction));
            setMeasuredDimension(w, h);
            applyChildVisuals();
        }
    }

    private static final class IndeterminateSpinnerView extends View {
        private final float d;
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF boundsRect = new RectF();
        private float angleDeg;
        private ValueAnimator spinAnim;

        IndeterminateSpinnerView(Context ctx, float d) {
            super(ctx);
            this.d = d;
            trackPaint.setStyle(Paint.Style.STROKE);
            trackPaint.setStrokeWidth(2.1f * d);
            trackPaint.setColor(0x36FFFFFF);

            arcPaint.setStyle(Paint.Style.STROKE);
            arcPaint.setStrokeWidth(2.1f * d);
            arcPaint.setStrokeCap(Paint.Cap.ROUND);
            arcPaint.setColor(0xFFFFFFFF);
        }

        void startSpinning() {
            if (spinAnim != null && spinAnim.isRunning()) return;
            spinAnim = ValueAnimator.ofFloat(0f, 360f);
            spinAnim.setDuration(820L);
            spinAnim.setRepeatCount(ValueAnimator.INFINITE);
            spinAnim.setInterpolator(new LinearInterpolator());
            spinAnim.addUpdateListener(a -> {
                angleDeg = (Float) a.getAnimatedValue();
                invalidate();
            });
            spinAnim.start();
        }

        void stopSpinning() {
            if (spinAnim != null) {
                spinAnim.cancel();
                spinAnim = null;
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            stopSpinning();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float pad = 2.0f * d;
            boundsRect.set(pad, pad, getWidth() - pad, getHeight() - pad);
            canvas.drawOval(boundsRect, trackPaint);
            canvas.drawArc(boundsRect, angleDeg - 90f, 230f, false, arcPaint);
        }
    }

    private static final class CircleIconButton extends FrameLayout {
        private final boolean isBack;
        private final float d;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrowPath = new Path();

        CircleIconButton(Context ctx, float d, boolean isBack) {
            super(ctx);
            this.d = d;
            this.isBack = isBack;
            setWillNotDraw(false);
            setClickable(true);
            setFocusable(true);

            ColorOsLivingFlowEngine.applyGlassSurface(
                    this, ColorOsLivingFlowEngine.GlassPreset.OTA_TOP_CIRCLE,
                    true, 20f, true, false);

            paint.setColor(0xFFFFFFFF);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            LivingFlowEffects.TouchFeedback.attach(
                    this, LivingFlowEffects.TouchFeedback.Profile.GLASS_CIRCLE, true, 20f, true, false, true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            if (isBack) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.0f * d);
                float arm = 7.2f * d;
                float head = 5.4f * d;
                arrowPath.reset();
                arrowPath.moveTo(cx + arm, cy);
                arrowPath.lineTo(cx - arm, cy);
                arrowPath.moveTo(cx - arm + head, cy - head);
                arrowPath.lineTo(cx - arm, cy);
                arrowPath.lineTo(cx - arm + head, cy + head);
                canvas.drawPath(arrowPath, paint);
            } else {
                paint.setStyle(Paint.Style.FILL);
                float r = 1.65f * d;
                float gap = 5.4f * d;
                canvas.drawCircle(cx, cy - gap, r, paint);
                canvas.drawCircle(cx, cy, r, paint);
                canvas.drawCircle(cx, cy + gap, r, paint);
            }
        }
    }

    private static final class ChevronCircleView extends View {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chevPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float d;

        ChevronCircleView(Context ctx, float d) {
            super(ctx);
            this.d = d;
            bgPaint.setStyle(Paint.Style.FILL);
            bgPaint.setColor(0x22FFFFFF);

            chevPaint.setStyle(Paint.Style.STROKE);
            chevPaint.setStrokeWidth(1.8f * d);
            chevPaint.setStrokeCap(Paint.Cap.ROUND);
            chevPaint.setStrokeJoin(Paint.Join.ROUND);
            chevPaint.setColor(0xFFFFFFFF);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            float r = Math.min(cx, cy);
            canvas.drawCircle(cx, cy, r, bgPaint);

            float dx = 4.6f * d;
            float dy = 2.6f * d;
            path.reset();
            path.moveTo(cx - dx, cy + dy * 0.55f);
            path.lineTo(cx, cy - dy * 0.85f);
            path.lineTo(cx + dx, cy + dy * 0.55f);
            canvas.drawPath(path, chevPaint);
        }
    }

    /** Static cosmic aurora fallback matching official {@code drawable/kv_bg_17}. */
    private static final class StaticCosmicAuroraDrawable extends Drawable {
        private final Paint skyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rightSpherePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rightGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rightRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint diagPlanePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint leftSpherePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint leftRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path rightSpherePath = new Path();
        private final Path diagPlanePath = new Path();
        private final Path leftSpherePath = new Path();
        private final RectF ovalRect = new RectF();
        private int lastW;
        private int lastH;

        StaticCosmicAuroraDrawable(float d) {
            rightRimPaint.setStyle(Paint.Style.STROKE);
            rightRimPaint.setStrokeWidth(1.25f * d);
            leftRimPaint.setStyle(Paint.Style.STROKE);
            leftRimPaint.setStrokeWidth(1.0f * d);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            rebuildShaders(bounds.width(), bounds.height());
        }

        private void rebuildShaders(int w, int h) {
            if (w <= 0 || h <= 0 || (w == lastW && h == lastH)) return;
            lastW = w;
            lastH = h;

            skyPaint.setShader(new LinearGradient(
                    0f, 0f, w * 0.25f, h,
                    new int[]{0xFF0A0D24, 0xFF141842, 0xFF211E58, 0xFF1B1948},
                    new float[]{0f, 0.42f, 0.72f, 1f},
                    Shader.TileMode.CLAMP));

            float rCx = w * 0.80f;
            float rCy = h * 0.56f;
            float rRx = w * 0.54f;
            float rRy = h * 0.21f;
            rightSpherePath.reset();
            ovalRect.set(rCx - rRx, rCy - rRy, rCx + rRx, rCy + rRy);
            rightSpherePath.addOval(ovalRect, Path.Direction.CW);
            rightSpherePaint.setShader(new LinearGradient(
                    rCx - rRx * 0.4f, rCy - rRy,
                    rCx + rRx * 0.2f, rCy + rRy,
                    new int[]{0x705848A8, 0xD89A76E8, 0xF0EE7C70, 0xFFFF6412, 0xFFFFBA3C},
                    new float[]{0f, 0.28f, 0.58f, 0.82f, 1f},
                    Shader.TileMode.CLAMP));
            rightGlowPaint.setShader(new RadialGradient(
                    rCx + rRx * 0.05f, rCy + rRy * 0.12f, rRx * 0.95f,
                    new int[]{0x88FF721C, 0x38D064E0, 0x00201C52},
                    new float[]{0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP));
            rightRimPaint.setShader(new LinearGradient(
                    rCx - rRx, rCy - rRy, rCx + rRx, rCy + rRy * 0.4f,
                    new int[]{0x208C7CE8, 0x90EAD8FF, 0x45FFB468},
                    new float[]{0f, 0.48f, 1f},
                    Shader.TileMode.CLAMP));

            diagPlanePath.reset();
            diagPlanePath.moveTo(0f, h * 0.51f);
            diagPlanePath.lineTo(w, h * 0.76f);
            diagPlanePath.lineTo(w, h);
            diagPlanePath.lineTo(0f, h);
            diagPlanePath.close();
            diagPlanePaint.setShader(new LinearGradient(
                    0f, h * 0.52f, w * 0.85f, h,
                    new int[]{0xEE0B0E26, 0xDC211D5A, 0xEA362A7C},
                    new float[]{0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP));

            float lCx = w * 0.24f;
            float lCy = h * 0.82f;
            float lRx = w * 0.48f;
            float lRy = h * 0.21f;
            leftSpherePath.reset();
            ovalRect.set(lCx - lRx, lCy - lRy, lCx + lRx, lCy + lRy);
            leftSpherePath.addOval(ovalRect, Path.Direction.CW);
            leftSpherePaint.setShader(new RadialGradient(
                    lCx - lRx * 0.12f, lCy - lRy * 0.16f, Math.max(lRx, lRy) * 1.15f,
                    new int[]{0xFFFF9828, 0xF5FF5A1E, 0xD08F52D8, 0x44352874},
                    new float[]{0f, 0.36f, 0.72f, 1f},
                    Shader.TileMode.CLAMP));
            leftRimPaint.setShader(new LinearGradient(
                    lCx - lRx, lCy - lRy, lCx + lRx * 0.6f, lCy,
                    new int[]{0x66FFD29C, 0x80BFA4FF, 0x185848A8},
                    new float[]{0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            rebuildShaders(b.width(), b.height());
            float w = b.width();
            float h = b.height();

            canvas.drawRect(b, skyPaint);

            int s1 = canvas.save();
            canvas.rotate(-17f, w * 0.80f, h * 0.56f);
            canvas.drawOval(w * 0.24f, h * 0.33f, w * 1.38f, h * 0.79f, rightGlowPaint);
            canvas.drawPath(rightSpherePath, rightSpherePaint);
            canvas.drawPath(rightSpherePath, rightRimPaint);
            canvas.restoreToCount(s1);

            canvas.drawPath(diagPlanePath, diagPlanePaint);

            int s2 = canvas.save();
            canvas.rotate(-14f, w * 0.24f, h * 0.82f);
            canvas.drawPath(leftSpherePath, leftSpherePaint);
            canvas.restoreToCount(s2);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }

    /** Full-screen root container supporting ColorOS interactive 36dp edge-swipe back. */
    private static final class SwipeablePageRoot extends FrameLayout {
        private final float edgeWidthPx;
        private final int touchSlop;
        private final OtaStageState stage;
        private float topBarSafeY;
        private OtaPageEntry entry;
        private VelocityTracker vt;
        private boolean tracking;
        private boolean dragging;
        private float downX;
        private float downY;

        SwipeablePageRoot(Context ctx, float d, OtaStageState stage) {
            super(ctx);
            this.edgeWidthPx = 36f * d;
            this.topBarSafeY = resolveStatusBarFallbackPx(ctx) + 64f * d;
            this.touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
            this.stage = stage;
            setClickable(true);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClipChildren(false);
            setClipToPadding(false);
        }

        void setTopBarSafeY(float safeY) {
            this.topBarSafeY = safeY;
        }

        void bindEntry(OtaPageEntry entry) {
            this.entry = entry;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (entry == null || entry.closing) return false;
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                int w = getWidth();
                float x = ev.getX();
                float y = ev.getY();
                boolean inTopBarRow = (y <= topBarSafeY);
                if (w > 0 && !inTopBarRow && (x <= edgeWidthPx || x >= w - edgeWidthPx)) {
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
            if (!dragging || entry == null) return super.onTouchEvent(ev);
            if (vt != null) vt.addMovement(ev);
            int width = Math.max(1, stage.container.getWidth());
            int action = ev.getActionMasked();
            boolean innerMode = entry.innerHandler != null && entry.innerHandler.isInnerActive();
            View under = innerMode ? null : resolveUnderView(stage, entry);
            if (action == MotionEvent.ACTION_MOVE) {
                float delta = Math.max(0f, Math.min(width, Math.abs(ev.getRawX() - downX)));
                float progress = delta / width;
                if (innerMode) {
                    entry.innerHandler.onInnerSwipeDrag(delta, progress);
                } else {
                    setTranslationX(delta);
                    if (under != null) {
                        under.setTranslationX(-width * 0.28f * (1f - progress));
                    }
                    entry.dimOverlay.setAlpha(0.32f * (1f - progress));
                }
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
                boolean commitBack = (delta > width * 0.30f || vx > minVel);
                if (innerMode) {
                    entry.innerHandler.onInnerSwipeSettle(commitBack, width);
                } else if (commitBack) {
                    popEntry(stage, entry);
                } else {
                    animate().translationX(0f).setDuration(260L).setInterpolator(PUSH_EASE).start();
                    if (under != null) {
                        under.animate().translationX(-width * 0.28f)
                                .setDuration(260L).setInterpolator(PUSH_EASE).start();
                    }
                    entry.dimOverlay.animate().alpha(0.32f)
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
