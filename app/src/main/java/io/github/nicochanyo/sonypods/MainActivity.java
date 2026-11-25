package io.github.nicochanyo.sonypods;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.libxposed.service.XposedProvider;

/**
 * Main COUI 17 extra-settings, artwork manager, diagnostics, and built-in
 * root scope restarter activity for SonyPods ColorOS.
 */
public class MainActivity extends Activity {
    private static final String STATE_TAB = "active_tab";
    private static final String KEY_LAST_SHOWN_CHANGELOG_VER = "last_shown_changelog_ver";
    private static final String URL_SONYPODS = "https://github.com/Mercury000/SonyPods";
    private static final String URL_HUAWEIPODS = "https://github.com/ByteJuno/HuaweiPods-ColorOS";
    private static final String COOLAPK_DEEP_PREFIX = "coolmarket://u/";
    private static final String COOLAPK_WEB_PREFIX = "https://www.coolapk.com/u/";
    private static final String LSPOSED_REPO_JSON_URL = "https://modules.lsposed.org/module/io.github.nicochanyo.sonypods.json";
    private static final String LSPOSED_REPO_PAGE_URL = "https://modules.lsposed.org/module/io.github.nicochanyo.sonypods/";
    private static final String GITHUB_LATEST_API_URL = "https://api.github.com/repos/Xposed-Modules-Repo/io.github.nicochanyo.sonypods/releases/latest";
    private static final String GITHUB_SOURCE_LATEST_API_URL = "https://api.github.com/repos/nicochanyo/sonypods-coloros/releases/latest";
    private static final String UID_AUTHOR = "25380289";
    private static final String ACTION_COLOROS_DETAIL_PANEL = "com.oplus.mydevices.ACTION_DEVICE_DETAILED_PANEL";
    private static final String PKG_MYDEVICES = "com.heytap.mydevices";
    private static final long SLIDER_SYNC_DELAY_MS = 250L;
    private static final int REQ_PICK_PNG = 201;

    private static final int TAB_HEADSET = 0;
    private static final int TAB_INTEGRATION = 1;
    private static final int TAB_ABOUT = 2;

    private static final PathInterpolator FLUID_EASE = new PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f);
    private static final OvershootInterpolator ICON_SPRING = new OvershootInterpolator(1.25f);
    private static final OvershootInterpolator NAV_SPRING_BACK = new OvershootInterpolator(1.15f);
    private static final float COLLAPSED_TITLE_SCALE = 0.70f;
    private static final float SUBTITLE_FADE_END_PROGRESS = 0.48f;

    private static int sPendingTabAfterRecreate = -1;

    private SharedPreferences prefs;
    private boolean xspActive;
    private boolean hadLocalKeys;
    private boolean remoteRestoreTried;
    private ConfigStore.SettingsData data;
    private ConfigStore.HeadsetState headsetState = new ConfigStore.HeadsetState();

    private int activeTab = TAB_HEADSET;
    private boolean suppressUiCallbacks;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger syncGeneration = new AtomicInteger(0);
    private final Runnable debouncedSync = this::syncRemoteAsync;

    // Header & Progressive Blur
    private ProgressiveBlurFrameLayout layoutRootBlur;
    private TopGradientBlurView vTopHeaderBlur;
    private View layoutHeader;
    private TextView tvPageTitle;
    private TextView tvPageSubtitle;
    private ValueAnimator headerCollapseAnimator;
    private ValueAnimator subtitleTabFadeAnimator;
    private float headerCollapseProgress;
    private float headerTitleTabAlpha = 1f;
    private float subtitleTabAlpha = 1f;
    private int statusBarTopPx;
    private int headerTitleShiftPx;
    private int headerSubtitleShiftPx;
    private int headerCollapseRangePx;

    // Pages & Swipe
    private View layoutPageContainer;
    private final View[] pages = new View[3];
    private int touchSlop;
    private float downX;
    private float downY;
    private boolean trackSwipe;
    private boolean swipeTriggered;

    // Floating Pill Bottom Nav
    private View vBottomNavShadow;
    private View layoutBottomNavContainer;
    private View layoutBottomNav;
    private View layoutNavItems;
    private View vNavSlidingPill;
    private ValueAnimator navPillMorphAnimator;
    private final View[] navTabs = new View[3];
    private final ImageView[] navIcons = new ImageView[3];
    private final TextView[] navLabels = new TextView[3];
    private boolean navDragging;
    private float navDownRawX;
    private float navDownRawY;
    private float navItemsRestLeftScreenX;
    private float navItemsWidth;
    private float navPillWidth;
    private float navMinPillX;
    private float navMaxPillX;

    // Tab 0: Headset
    private ImageView ivStatusIcon;
    private TextView tvStatusTitle;
    private TextView tvStatusSubtitle;
    private TextView tvHeadsetName;
    private TextView tvHeadsetMac;
    private TextView tvProtocolBadge;
    private ImageView ivArtworkPreview;
    private TextView tvBatteryLeft;
    private TextView tvBatteryRight;
    private TextView tvBatteryCase;
    private TextView tvCurrentAncBadge;
    private ViewGroup layoutHeadsetControls;
    private View layoutAmbientSub;
    private TextView tvAsmLevelVal;
    private SeekBar sbAsmLevel;
    private Switch swVoiceFocus;
    private View rowScSound;
    private View rowScInteraction;
    private View rowScConnection;
    private View rowScPower;
    private TextView tvScSoundVal;
    private TextView tvScInteractionVal;
    private TextView tvScConnectionVal;
    private TextView tvScPowerVal;
    private TextView tvCycleModeVal;
    private Switch swHandover;
    private TextView tvCatalogStatus;
    private TextView tvCustomArtworkStatus;
    private TextView tvModelOverrideVal;

    // Tab 1: Integration & Built-in Restart Scope
    private Switch swBridgeEnabled;
    private Switch swMyDevicesPanel;
    private Switch swNativeArtwork;
    private Switch swFluidCloudCapsule;
    private Switch swQsTile;
    private Switch swVendorBypass;
    private Switch swMelodyChannel;
    private Switch swOpenCasePopup;
    private View divOpenPopupMode;
    private View rowOpenPopupMode;
    private TextView tvOpenPopupModeVal;
    private Switch swSuppressNativePairing;
    private ImageView ivRootIcon;
    private TextView tvRootStatus;
    private volatile boolean rootAvailable;

    // Tab 2: About
    private TextView tvSysVersion;
    private TextView tvDeviceModel;
    private TextView tvAboutChannel;
    private TextView tvAboutUpdateChip;
    private TextView tvAboutLanguageVal;
    private TextView tvAboutDarkModeVal;
    private TextView tvAboutUiStyleVal;
    private Switch swAboutMonet;
    private Switch swAboutDebugMode;
    private boolean updateCheckRunning;
    private static RepoReleaseInfo sCachedLatestRelease;
    private static boolean sSilentUpdateCheckDone;

    // ColorOS 17 Living Flow UI state & views
    private boolean livingFlowUi;
    private View btnTopQuickAppearance;
    private View cardAboutDynamicHero;
    private ColorOsDynamicBackdropView vAboutDynamicBg;
    private View layoutAboutHeroLogo;
    private ColorOsBrandWordmarkView vAboutHeroWordmark;
    private TextView tvAboutHeroVer;
    private TextView tvAboutHeroUpdateStatus;
    private TextView tvAboutBuildSub;
    private View bannerModuleInactive;
    private View vBannerWarnEmblem;
    private View cardAboutModuleStatus;
    private View vAboutStatusEmblem;
    private ImageView ivAboutStatusIcon;
    private TextView tvAboutStatusTitle;
    private TextView tvAboutStatusBadge;
    private TextView tvAboutStatusSubtitle;
    private View rowAboutScopeToggle;
    private TextView tvAboutScopeCount;
    private ImageView ivAboutScopeChevron;
    private LinearLayout layoutAboutScopeBody;
    private TextView tvAboutScopeList;
    private boolean aboutScopeExpanded;
    private ValueAnimator aboutScopeAnimator;
    private View cardHeadsetHero;
    private View vHeroGlowAura;
    private View vBatBarLeft;
    private View vBatBarRight;
    private View vBatBarCase;
    private View layoutQuickAncCapsule;
    private View btnAncNc;
    private View btnAncAmbient;
    private View btnAncOff;
    private ImageView ivAncIconNc;
    private ImageView ivAncIconAmbient;
    private ImageView ivAncIconOff;
    private TextView tvAncLabelNc;
    private TextView tvAncLabelAmbient;
    private TextView tvAncLabelOff;
    private View ivAncRadioNc;
    private View ivAncRadioAmbient;
    private View ivAncRadioOff;
    private LivingFlowEffects.SpotLightDrawable navSpotLight;
    private LivingFlowEffects.NavBarElasticController navElasticController;
    private int lastAppliedAccent;
    private float lastNavRawX;
    private float navDragVx;
    private long lastNavEventMs;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshHeadsetStateUi();
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        String lang = ConfigStore.getEffectiveAppLanguage(newBase);
        Locale locale = resolveLocale(lang);
        Locale.setDefault(locale);
        Configuration cfg = new Configuration(newBase.getResources().getConfiguration());
        cfg.setLocale(locale);
        applyDarkModeToConfig(cfg, ConfigStore.getAppDarkMode(newBase));
        super.attachBaseContext(newBase.createConfigurationContext(cfg));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyLocaleToResources();
        boolean isDark = ConfigStore.isDarkThemeActive(this);
        setTheme(isDark ? R.style.Theme_CouiSonyPods_Dark : R.style.Theme_CouiSonyPods_Light);
        super.onCreate(savedInstanceState);
        livingFlowUi = ConfigStore.isLivingFlowUi(this);
        setupEdgeToEdge();
        setContentView(livingFlowUi ? R.layout.activity_main_living_flow : R.layout.activity_main);
        if (livingFlowUi) {
            getWindow().setBackgroundDrawable(new ColorDrawable(getColor(R.color.lf_bg)));
        }
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        ConfigStore.AppPrefs opened = ConfigStore.openAppPrefs(this);
        prefs = opened.prefs;
        xspActive = opened.xspActive;
        hadLocalKeys = opened.hadLocalKeys;
        data = ConfigStore.loadFromPrefs(prefs);

        final boolean isRecreate = sPendingTabAfterRecreate >= TAB_HEADSET && sPendingTabAfterRecreate <= TAB_ABOUT;
        if (isRecreate) {
            activeTab = sPendingTabAfterRecreate;
            sPendingTabAfterRecreate = -1;
        } else if (savedInstanceState != null) {
            activeTab = savedInstanceState.getInt(STATE_TAB, TAB_HEADSET);
        }

        bindViews();
        applyInsetsPadding();
        syncUiState();
        refreshHeadsetStateUi();
        DynamicThemeHelper.applyAccent(findViewById(android.R.id.content), this);
        lastAppliedAccent = DynamicThemeHelper.getAccent(this);
        switchTab(activeTab, false);
        if (sCachedLatestRelease != null) {
            applyUpdateChipState(sCachedLatestRelease);
        } else if (!sSilentUpdateCheckDone) {
            sSilentUpdateCheckDone = true;
            checkUpdateFromLsposedRepo(false);
        }

        XposedProvider.setConnectionListener(() -> {
            xspActive = XposedProvider.isConnected();
            syncUiState();
            syncRemoteAsync();
        });
        syncRemoteAsync();

        if (!isRecreate && savedInstanceState == null) {
            SharedPreferences localSp = getSharedPreferences(ConfigStore.PREFS_NAME, Context.MODE_PRIVATE);
            int curVer = getInstalledVersionCode();
            int lastShown = localSp.getInt(KEY_LAST_SHOWN_CHANGELOG_VER, 0);
            if (lastShown < curVer) {
                localSp.edit().putInt(KEY_LAST_SHOWN_CHANGELOG_VER, curVer).apply();
                getWindow().getDecorView().post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        showChangelogDialog();
                    }
                });
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(ConfigStore.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        if (livingFlowUi) {
            int curAccent = DynamicThemeHelper.getAccent(this);
            if (curAccent != lastAppliedAccent) {
                lastAppliedAccent = curAccent;
                DynamicThemeHelper.applyAccent(findViewById(android.R.id.content), this);
                updateNavIndicator(activeTab, activeTab, 0, false, false);
                boolean darkNow = ConfigStore.isDarkThemeActive(this);
                View b1 = findViewById(R.id.btnOpenNativePanel);
                View b2 = findViewById(R.id.btnOpenNativePanelTab2);
                if (b1 != null) ColorOsLivingFlowEngine.applyLuminousPillButton(b1, true, curAccent, darkNow, true);
                if (b2 != null) ColorOsLivingFlowEngine.applyLuminousPillButton(b2, true, curAccent, darkNow, true);
                syncUiState();
            }
            if (vAboutDynamicBg != null && activeTab == TAB_ABOUT && !LivingFlowUpdatePages.hasActiveStage(this)) {
                vAboutDynamicBg.resumePlayback();
            }
            LivingFlowUpdatePages.onHostResume(this);
        }
        refreshHeadsetStateUi();
    }

    @Override
    protected void onPause() {
        if (vAboutDynamicBg != null) {
            vAboutDynamicBg.pausePlayback();
        }
        LivingFlowUpdatePages.onHostPause(this);
        try {
            unregisterReceiver(stateReceiver);
        } catch (Throwable ignored) {
        }
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent resultIntent) {
        super.onActivityResult(requestCode, resultCode, resultIntent);
        if (requestCode == REQ_PICK_PNG && resultCode == RESULT_OK && resultIntent != null && resultIntent.getData() != null) {
            final Uri picked = resultIntent.getData();
            new Thread(() -> {
                boolean ok = SonyModelImageManager.importCustomImage(this, picked);
                runOnUiThread(() -> {
                    if (ok) {
                        data.customArtworkUri = "custom";
                        persistImmediate();
                        syncUiState();
                        refreshHeadsetStateUi();
                        Toast.makeText(this, R.string.toast_custom_art_saved, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, R.string.toast_custom_art_failed, Toast.LENGTH_SHORT).show();
                    }
                });
            }, "SonyPods-ImportArt").start();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, activeTab);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        syncActiveHeaderToScrollNow();
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        syncActiveHeaderToScrollNow();
    }

    @Override
    protected void onDestroy() {
        XposedProvider.setConnectionListener(null);
        mainHandler.removeCallbacks(debouncedSync);
        if (navPillMorphAnimator != null) {
            navPillMorphAnimator.cancel();
            navPillMorphAnimator = null;
        }
        if (headerCollapseAnimator != null) {
            headerCollapseAnimator.cancel();
            headerCollapseAnimator = null;
        }
        if (subtitleTabFadeAnimator != null) {
            subtitleTabFadeAnimator.cancel();
            subtitleTabFadeAnimator = null;
        }
        if (tvPageTitle != null) tvPageTitle.animate().cancel();
        if (tvPageSubtitle != null) tvPageSubtitle.animate().cancel();
        if (layoutBottomNav != null) layoutBottomNav.animate().cancel();
        if (vNavSlidingPill != null) vNavSlidingPill.animate().cancel();
        if (navElasticController != null) {
            navElasticController.cancel();
            navElasticController = null;
        }
        if (navSpotLight != null) {
            navSpotLight.cancel();
            navSpotLight = null;
        }
        if (aboutScopeAnimator != null) {
            aboutScopeAnimator.cancel();
            aboutScopeAnimator = null;
        }
        syncExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getRawX();
                downY = ev.getRawY();
                swipeTriggered = false;
                trackSwipe = isSwipeEligibleTouch(ev.getRawX(), ev.getRawY());
                break;
            case MotionEvent.ACTION_MOVE:
                if (trackSwipe && !swipeTriggered) {
                    float dx = ev.getRawX() - downX;
                    float dy = ev.getRawY() - downY;
                    if (Math.abs(dy) > Math.max(touchSlop * 2f, dp(24)) && Math.abs(dy) > Math.abs(dx)) {
                        trackSwipe = false;
                    } else if (Math.abs(dx) > dp(64) && Math.abs(dx) > Math.abs(dy) * 1.8f) {
                        swipeTriggered = true;
                        trackSwipe = false;
                        int nextTab = dx < 0 ? activeTab + 1 : activeTab - 1;
                        if (nextTab >= TAB_HEADSET && nextTab <= TAB_ABOUT) {
                            switchTab(nextTab, true);
                        }
                        MotionEvent cancel = MotionEvent.obtain(ev);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(cancel);
                        cancel.recycle();
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                trackSwipe = false;
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    private boolean isSwipeEligibleTouch(float rawX, float rawY) {
        if (rawY < statusBarTopPx) {
            return false;
        }
        if (layoutPageContainer == null || !layoutPageContainer.isShown()
                || SoundConnectSheets.hasActiveStage(this)
                || LivingFlowDiagnosticsPage.hasActiveStage(this)
                || LivingFlowUpdatePages.hasActiveStage(this)
                || !isPointInView(layoutPageContainer, rawX, rawY)) {
            return false;
        }
        if (isPointInView(layoutBottomNavContainer, rawX, rawY)
                || isPointInView(btnTopQuickAppearance, rawX, rawY)
                || isPointInView(layoutQuickAncCapsule, rawX, rawY)) {
            return false;
        }
        return !isPointInInteractiveChild(pages[activeTab], rawX, rawY);
    }

    private static boolean isPointInView(View v, float rawX, float rawY) {
        if (v == null || v.getVisibility() != View.VISIBLE) {
            return false;
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        Rect r = new Rect(loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight());
        return r.contains(Math.round(rawX), Math.round(rawY));
    }

    private static boolean isPointInInteractiveChild(View root, float rawX, float rawY) {
        if (root == null || root.getVisibility() != View.VISIBLE) {
            return false;
        }
        if ((root instanceof SeekBar || root instanceof Switch) && isPointInView(root, rawX, rawY)) {
            return true;
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (isPointInInteractiveChild(vg.getChildAt(i), rawX, rawY)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void setupEdgeToEdge() {
        Window window = getWindow();
        boolean isNight = ConfigStore.isDarkThemeActive(this);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            window.getDecorView().post(() -> {
                WindowInsetsController ctrl = window.getInsetsController();
                if (ctrl != null) {
                    int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    ctrl.setSystemBarsAppearance(isNight ? 0 : mask, mask);
                }
            });
        }
    }

    private void applyInsetsPadding() {
        final int headerPadStart = livingFlowUi ? dp(22) : dp(24);
        final int headerPadEnd = livingFlowUi ? dp(18) : dp(24);
        final int headerTopBase = livingFlowUi ? 0 : dp(28);
        final int headerBotBase = livingFlowUi ? dp(12) : dp(16);
        headerTitleShiftPx = dp(24);
        headerSubtitleShiftPx = livingFlowUi ? dp(34) : dp(26);
        headerCollapseRangePx = dp(64);

        layoutHeader.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()).top
                    : insets.getSystemWindowInsetTop();
            statusBarTopPx = top;
            v.setPaddingRelative(headerPadStart, top + headerTopBase, headerPadEnd, headerBotBase);
            if (vTopHeaderBlur != null) {
                if (livingFlowUi) {
                    vTopHeaderBlur.setSurfaceColor(getColor(R.color.lf_bg));
                }
                vTopHeaderBlur.setStatusBarTopPx(top);
            }
            syncHeaderGeometryAndScrollPadding();
            return insets;
        });
        layoutHeader.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if ((bottom - top) != (oldBottom - oldTop)) {
                syncHeaderGeometryAndScrollPadding();
            }
        });
        layoutHeader.requestApplyInsets();

        final int navBotBase = livingFlowUi ? dp(8) : dp(14);
        layoutBottomNavContainer.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars()).bottom
                    : insets.getSystemWindowInsetBottom();
            v.setPadding(0, 0, 0, bottom + navBotBase);
            if (vBottomNavShadow != null) {
                vBottomNavShadow.setPadding(0, 0, 0, 0);
                ViewGroup.LayoutParams lp = vBottomNavShadow.getLayoutParams();
                int targetH = bottom + dp(livingFlowUi ? 116 : 104);
                if (lp != null && lp.height != targetH) {
                    lp.height = targetH;
                    vBottomNavShadow.setLayoutParams(lp);
                }
            }
            return insets;
        });
        layoutBottomNavContainer.requestApplyInsets();
    }

    private void syncHeaderGeometryAndScrollPadding() {
        if (layoutHeader == null) return;
        int headerH = layoutHeader.getHeight();
        if (headerH > 0) {
            int contentTopPad = headerH + dp(2);
            for (View page : pages) {
                if (page instanceof ViewGroup && ((ViewGroup) page).getChildCount() > 0) {
                    View inner = ((ViewGroup) page).getChildAt(0);
                    if (inner.getPaddingTop() != contentTopPad) {
                        inner.setPaddingRelative(
                                inner.getPaddingStart(),
                                contentTopPad,
                                inner.getPaddingEnd(),
                                inner.getPaddingBottom());
                    }
                }
            }
        }
        if (livingFlowUi && tvPageTitle != null && tvPageTitle.getHeight() > 0) {
            float collapsedH = tvPageTitle.getHeight() * 0.6875f;
            float targetCollapsedTop = dp(26) - collapsedH * 0.5f;
            headerTitleShiftPx = Math.max(dp(34), Math.round(dp(54) - targetCollapsedTop));
        }
        if (vTopHeaderBlur != null && tvPageTitle != null && tvPageTitle.getHeight() > 0) {
            int targetBlurH;
            if (livingFlowUi) {
                targetBlurH = statusBarTopPx + dp(70);
            } else {
                int collapsedTitleH = Math.round(tvPageTitle.getHeight() * COLLAPSED_TITLE_SCALE);
                targetBlurH = statusBarTopPx + dp(4) + collapsedTitleH + dp(56);
            }
            ViewGroup.LayoutParams lp = vTopHeaderBlur.getLayoutParams();
            if (lp != null && lp.height != targetBlurH) {
                lp.height = targetBlurH;
                vTopHeaderBlur.setLayoutParams(lp);
            }
        }
        syncActiveHeaderToScrollNow();
    }

    private void syncActiveHeaderToScrollNow() {
        View active = (activeTab >= 0 && activeTab < pages.length) ? pages[activeTab] : null;
        if (active != null) {
            active.post(() -> {
                if (active == pages[activeTab]) {
                    syncHeaderToScrollY(active.getScrollY(), false);
                    if (vTopHeaderBlur != null) {
                        vTopHeaderBlur.notifyTargetInvalidated();
                    }
                }
            });
            syncHeaderToScrollY(active.getScrollY(), false);
        }
        if (vTopHeaderBlur != null) {
            vTopHeaderBlur.notifyTargetInvalidated();
        }
    }

    private static float computeSubtitleAlpha(float collapseProgress) {
        float t = Math.max(0f, Math.min(1f, collapseProgress / SUBTITLE_FADE_END_PROGRESS));
        return Math.max(0f, Math.min(1f, 1f - (float) Math.pow(t, 1.35)));
    }

    private void applyHeaderCollapseProgress(float p) {
        headerCollapseProgress = Math.max(0f, Math.min(1f, p));
        if (livingFlowUi) {
            if (tvPageTitle != null) {
                float pivotX = (tvPageTitle.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL)
                        ? tvPageTitle.getWidth() : 0f;
                tvPageTitle.setPivotX(pivotX);
                tvPageTitle.setPivotY(0f);
                // Match Clock.apk: shrink 32sp -> 22sp (0.6875x) and stop without vanishing
                float titleScale = 1f - (1f - 0.6875f) * headerCollapseProgress;
                tvPageTitle.setScaleX(titleScale);
                tvPageTitle.setScaleY(titleScale);
                tvPageTitle.setTranslationY(-headerTitleShiftPx * headerCollapseProgress);
                tvPageTitle.setAlpha(headerTitleTabAlpha);
            }
            if (tvPageSubtitle != null) {
                float subT = Math.min(1f, headerCollapseProgress / 0.56f);
                tvPageSubtitle.setScaleX(1f);
                tvPageSubtitle.setScaleY(1f);
                tvPageSubtitle.setTranslationY(-headerSubtitleShiftPx * subT);
                float subAlpha = Math.max(0f, 1f - (float) Math.pow(subT, 1.35));
                tvPageSubtitle.setAlpha(subAlpha * subtitleTabAlpha);
            }
            return;
        }
        if (tvPageTitle != null) {
            float pivotX = (tvPageTitle.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL)
                    ? tvPageTitle.getWidth() : 0f;
            tvPageTitle.setPivotX(pivotX);
            tvPageTitle.setPivotY(0f);
            float scale = 1f - (1f - COLLAPSED_TITLE_SCALE) * headerCollapseProgress;
            tvPageTitle.setScaleX(scale);
            tvPageTitle.setScaleY(scale);
            tvPageTitle.setTranslationY(-headerTitleShiftPx * headerCollapseProgress);
            tvPageTitle.setAlpha(1f);
        }
        if (tvPageSubtitle != null) {
            float subT = Math.max(0f, Math.min(1f, headerCollapseProgress / SUBTITLE_FADE_END_PROGRESS));
            float neededTravelPx;
            if (tvPageTitle != null && tvPageTitle.getHeight() > 0 && tvPageSubtitle.getTop() > tvPageTitle.getTop()) {
                float scaleAtFade = 1f - (1f - COLLAPSED_TITLE_SCALE) * SUBTITLE_FADE_END_PROGRESS;
                float titleBottomAtFade = tvPageTitle.getTop()
                        - headerTitleShiftPx * SUBTITLE_FADE_END_PROGRESS
                        + tvPageTitle.getHeight() * scaleAtFade;
                float targetSubTopAtFade = titleBottomAtFade - dp(5);
                neededTravelPx = Math.max(headerSubtitleShiftPx, tvPageSubtitle.getTop() - targetSubTopAtFade);
            } else {
                neededTravelPx = headerSubtitleShiftPx;
            }
            tvPageSubtitle.setScaleX(1f);
            tvPageSubtitle.setScaleY(1f);
            tvPageSubtitle.setTranslationY(-neededTravelPx * subT);
            tvPageSubtitle.setAlpha(computeSubtitleAlpha(headerCollapseProgress) * subtitleTabAlpha);
        }
        if (btnTopQuickAppearance != null) {
            btnTopQuickAppearance.setTranslationY(0f);
        }
    }

    private void syncHeaderToScrollY(int scrollY, boolean animate) {
        float range = Math.max(1f, headerCollapseRangePx > 0 ? headerCollapseRangePx : dp(64));
        float target = Math.max(0f, Math.min(1f, scrollY / range));
        if (headerCollapseAnimator != null) {
            headerCollapseAnimator.cancel();
            headerCollapseAnimator = null;
        }
        if (!animate || Math.abs(target - headerCollapseProgress) < 0.005f) {
            applyHeaderCollapseProgress(target);
            return;
        }
        headerCollapseAnimator = ValueAnimator.ofFloat(headerCollapseProgress, target);
        headerCollapseAnimator.setDuration(260L);
        headerCollapseAnimator.setInterpolator(FLUID_EASE);
        headerCollapseAnimator.addUpdateListener(anim -> applyHeaderCollapseProgress((Float) anim.getAnimatedValue()));
        headerCollapseAnimator.start();
    }

    private void bindViews() {
        layoutRootBlur = findViewById(R.id.layoutRootBlur);
        vTopHeaderBlur = findViewById(R.id.vTopHeaderBlur);
        layoutHeader = findViewById(R.id.layoutHeader);
        tvPageTitle = findViewById(R.id.tvPageTitle);
        tvPageSubtitle = findViewById(R.id.tvPageSubtitle);
        if (vTopHeaderBlur != null) {
            vTopHeaderBlur.setOutlineProvider(null);
        }
        if (layoutHeader != null) {
            layoutHeader.setOutlineProvider(null);
        }

        layoutPageContainer = findViewById(R.id.layoutPageContainer);
        pages[TAB_HEADSET] = findViewById(R.id.pageHeadset);
        pages[TAB_INTEGRATION] = findViewById(R.id.pageIntegration);
        pages[TAB_ABOUT] = findViewById(R.id.pageAbout);

        if (layoutRootBlur != null) {
            layoutRootBlur.bindProgressiveBlur(R.id.layoutPageContainer, R.id.vTopHeaderBlur);
        }
        for (int i = 0; i < pages.length; i++) {
            final int tabIdx = i;
            if (pages[i] != null) {
                pages[i].setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                    if (vTopHeaderBlur != null) {
                        vTopHeaderBlur.notifyTargetInvalidated();
                    }
                    if (tabIdx == activeTab) {
                        syncHeaderToScrollY(scrollY, false);
                    }
                });
                pages[i].addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if (tabIdx == activeTab) {
                        syncHeaderToScrollY(v.getScrollY(), false);
                        if (vTopHeaderBlur != null) {
                            vTopHeaderBlur.notifyTargetInvalidated();
                        }
                    }
                });
                if (pages[i] instanceof CouiBounceScrollView) {
                    ((CouiBounceScrollView) pages[i]).setOnOverscrollListener(translationY -> {
                        if (vTopHeaderBlur != null) {
                            vTopHeaderBlur.notifyTargetInvalidated();
                        }
                    });
                }
            }
        }

        vBottomNavShadow = findViewById(R.id.vBottomNavShadow);
        layoutBottomNavContainer = findViewById(R.id.layoutBottomNavContainer);
        if (vBottomNavShadow != null) {
            vBottomNavShadow.setOutlineProvider(null);
        }
        if (layoutBottomNavContainer != null) {
            layoutBottomNavContainer.setOutlineProvider(null);
        }
        layoutBottomNav = findViewById(R.id.layoutBottomNav);
        layoutNavItems = findViewById(R.id.layoutNavItems);
        vNavSlidingPill = findViewById(R.id.vNavSlidingPill);

        navTabs[0] = findViewById(R.id.navHeadset);
        navTabs[1] = findViewById(R.id.navIntegration);
        navTabs[2] = findViewById(R.id.navAbout);
        navIcons[0] = findViewById(R.id.ivNavHeadset);
        navIcons[1] = findViewById(R.id.ivNavIntegration);
        navIcons[2] = findViewById(R.id.ivNavAbout);
        navLabels[0] = findViewById(R.id.tvNavHeadset);
        navLabels[1] = findViewById(R.id.tvNavIntegration);
        navLabels[2] = findViewById(R.id.tvNavAbout);

        setupBottomNavTouch();
        bindHeadsetViews();
        bindIntegrationViews();
        bindAboutViews();
        if (livingFlowUi) {
            setupLivingFlowUi();
        }
        applyCardOutlineClipping(pages[TAB_HEADSET]);
        applyCardOutlineClipping(pages[TAB_INTEGRATION]);
        applyCardOutlineClipping(pages[TAB_ABOUT]);
    }

    private static void applyCardOutlineClipping(View view) {
        if (view instanceof LinearLayout && view.getBackground() instanceof GradientDrawable) {
            view.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            view.setClipToOutline(true);
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyCardOutlineClipping(vg.getChildAt(i));
            }
        }
    }

    private void bindHeadsetViews() {
        ivStatusIcon = findViewById(R.id.ivStatusIcon);
        tvStatusTitle = findViewById(R.id.tvStatusTitle);
        tvStatusSubtitle = findViewById(R.id.tvStatusSubtitle);
        bannerModuleInactive = findViewById(R.id.bannerModuleInactive);
        vBannerWarnEmblem = findViewById(R.id.vBannerWarnEmblem);
        if (bannerModuleInactive != null) {
            bannerModuleInactive.setOnClickListener(v -> showDiagnosticsDialog());
        }

        ivArtworkPreview = findViewById(R.id.ivArtworkPreview);
        tvHeadsetName = findViewById(R.id.tvHeadsetName);
        tvHeadsetMac = findViewById(R.id.tvHeadsetMac);
        tvProtocolBadge = findViewById(R.id.tvProtocolBadge);
        tvBatteryLeft = findViewById(R.id.tvBatteryLeft);
        tvBatteryRight = findViewById(R.id.tvBatteryRight);
        tvBatteryCase = findViewById(R.id.tvBatteryCase);
        tvCurrentAncBadge = findViewById(R.id.tvCurrentAncBadge);
        layoutHeadsetControls = findViewById(R.id.layoutHeadsetControls);

        layoutAmbientSub = findViewById(R.id.layoutAmbientSub);
        tvAsmLevelVal = findViewById(R.id.tvAsmLevelVal);
        sbAsmLevel = findViewById(R.id.sbAsmLevel);
        swVoiceFocus = findViewById(R.id.swVoiceFocus);
        rowScSound = findViewById(R.id.rowScSound);
        rowScInteraction = findViewById(R.id.rowScInteraction);
        rowScConnection = findViewById(R.id.rowScConnection);
        rowScPower = findViewById(R.id.rowScPower);
        tvScSoundVal = findViewById(R.id.tvScSoundVal);
        tvScInteractionVal = findViewById(R.id.tvScInteractionVal);
        tvScConnectionVal = findViewById(R.id.tvScConnectionVal);
        tvScPowerVal = findViewById(R.id.tvScPowerVal);
        tvCycleModeVal = findViewById(R.id.tvCycleModeVal);
        swHandover = findViewById(R.id.swHandover);

        tvCatalogStatus = findViewById(R.id.tvCatalogStatus);
        tvCustomArtworkStatus = findViewById(R.id.tvCustomArtworkStatus);
        tvModelOverrideVal = findViewById(R.id.tvModelOverrideVal);

        findViewById(R.id.btnRefreshState).setOnClickListener(v -> {
            ConfigStore.sendCommandBroadcast(this, ConfigStore.CMD_REFRESH,
                    headsetState.mac, headsetState.ancMode, headsetState.asmLevel, headsetState.voiceFocus, false);
            refreshHeadsetStateUi();
            Toast.makeText(this, R.string.toast_command_sent, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnOpenNativePanel).setOnClickListener(v -> openNativeColorOsPanel());

        sbAsmLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || suppressUiCallbacks) return;
                int lvl = ConfigStore.clampAsmLevel(progress + 1);
                tvAsmLevelVal.setText(String.valueOf(lvl));
                data.defaultAsmLevel = lvl;
                persistSliderTick();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int lvl = ConfigStore.clampAsmLevel(seekBar.getProgress() + 1);
                data.defaultAsmLevel = lvl;
                persistImmediate();
                ConfigStore.sendCommandBroadcast(MainActivity.this, ConfigStore.CMD_SET_AMBIENT_PARAMS,
                        headsetState.mac, headsetState.ancMode, lvl, data.defaultVoiceFocus, false);
            }
        });

        findViewById(R.id.rowVoiceFocus).setOnClickListener(v -> swVoiceFocus.toggle());
        swVoiceFocus.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressUiCallbacks) return;
            if (livingFlowUi) ColorOsLivingFlowEngine.performHaptic(buttonView, ColorOsLivingFlowEngine.HapticKind.SWITCH);
            data.defaultVoiceFocus = isChecked;
            persistImmediate();
            ConfigStore.sendCommandBroadcast(this, ConfigStore.CMD_SET_AMBIENT_PARAMS,
                    headsetState.mac, headsetState.ancMode, data.defaultAsmLevel, isChecked, false);
        });

        bindSoundConnectCategoryRow(rowScSound, () -> SoundConnectSheets.showSoundAndEqSheet(
                this, this, CouiBottomSheet.Palette.fromModuleTheme(this),
                () -> mainHandler.postDelayed(this::refreshHeadsetStateUi, 240L)));
        bindSoundConnectCategoryRow(rowScInteraction, () -> SoundConnectSheets.showTouchAndSmartSheet(
                this, this, CouiBottomSheet.Palette.fromModuleTheme(this),
                () -> mainHandler.postDelayed(this::refreshHeadsetStateUi, 240L)));
        bindSoundConnectCategoryRow(rowScConnection, () -> SoundConnectSheets.showConnectionAndMultipointSheet(
                this, this, CouiBottomSheet.Palette.fromModuleTheme(this),
                () -> mainHandler.postDelayed(this::refreshHeadsetStateUi, 240L)));
        bindSoundConnectCategoryRow(rowScPower, () -> SoundConnectSheets.showPowerAndSystemSheet(
                this, this, CouiBottomSheet.Palette.fromModuleTheme(this),
                () -> mainHandler.postDelayed(this::refreshHeadsetStateUi, 240L)));

        View rowCycleMode = findViewById(R.id.rowCycleMode);
        CouiBottomSheet.trackTouchPoint(rowCycleMode);
        rowCycleMode.setOnClickListener(v -> showCycleModeDialog(v));
        findViewById(R.id.rowHandover).setOnClickListener(v -> swHandover.toggle());
        swHandover.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressUiCallbacks) return;
            if (livingFlowUi) ColorOsLivingFlowEngine.performHaptic(buttonView, ColorOsLivingFlowEngine.HapticKind.SWITCH);
            data.handover = isChecked;
            persistImmediate();
        });

        findViewById(R.id.btnApplyDefaults).setOnClickListener(v -> {
            persistImmediate();
            ConfigStore.sendCommandBroadcast(this, ConfigStore.CMD_APPLY_DEFAULTS,
                    headsetState.mac, headsetState.ancMode, data.defaultAsmLevel, data.defaultVoiceFocus, false);
            Toast.makeText(this, R.string.toast_command_sent, Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.rowSyncCatalog).setOnClickListener(v -> triggerCatalogSync());
        findViewById(R.id.rowPickCustomArtwork).setOnClickListener(v -> launchCustomArtworkPicker());
        findViewById(R.id.rowClearCustomArtwork).setOnClickListener(v -> {
            SonyModelImageManager.clearCustomImage(this);
            data.customArtworkUri = "";
            persistImmediate();
            syncUiState();
            refreshHeadsetStateUi();
            Toast.makeText(this, R.string.toast_custom_art_cleared, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.rowModelOverride).setOnClickListener(v -> showModelOverrideDialog());
    }

    private void bindIntegrationViews() {
        swBridgeEnabled = findViewById(R.id.swBridgeEnabled);
        swMyDevicesPanel = findViewById(R.id.swMyDevicesPanel);
        swNativeArtwork = findViewById(R.id.swNativeArtwork);
        swFluidCloudCapsule = findViewById(R.id.swFluidCloudCapsule);
        swQsTile = findViewById(R.id.swQsTile);
        swVendorBypass = findViewById(R.id.swVendorBypass);
        swMelodyChannel = findViewById(R.id.swMelodyChannel);
        swOpenCasePopup = findViewById(R.id.swOpenCasePopup);
        divOpenPopupMode = findViewById(R.id.divOpenPopupMode);
        rowOpenPopupMode = findViewById(R.id.rowOpenPopupMode);
        tvOpenPopupModeVal = findViewById(R.id.tvOpenPopupModeVal);
        swSuppressNativePairing = findViewById(R.id.swSuppressNativePairing);
        ivRootIcon = findViewById(R.id.ivRootIcon);
        tvRootStatus = findViewById(R.id.tvRootStatus);

        bindSwitchRow(R.id.rowBridgeEnabled, swBridgeEnabled, v -> data.bridgeEnabled = v);
        bindSwitchRow(R.id.rowMyDevicesPanel, swMyDevicesPanel, v -> data.myDevicesPanel = v);
        bindSwitchRow(R.id.rowNativeArtwork, swNativeArtwork, v -> data.nativeArtwork = v);
        bindSwitchRow(R.id.rowFluidCloudCapsule, swFluidCloudCapsule, v -> data.fluidCloudCapsule = v);
        bindSwitchRow(R.id.rowQsTile, swQsTile, v -> data.qsTile = v);
        bindSwitchRow(R.id.rowVendorBypass, swVendorBypass, v -> data.vendorBypass = v);
        bindSwitchRow(R.id.rowMelodyChannel, swMelodyChannel, v -> data.melodyChannel = v);
        bindSwitchRow(R.id.rowOpenCasePopup, swOpenCasePopup, v -> data.openCasePopup = v);
        CouiBottomSheet.trackTouchPoint(rowOpenPopupMode);
        rowOpenPopupMode.setOnClickListener(v -> showOpenPopupModeDialog(v));
        bindSwitchRow(R.id.rowSuppressNativePairing, swSuppressNativePairing, v -> data.suppressNativePairing = v);

        findViewById(R.id.rowRootStatus).setOnClickListener(v -> refreshRootStatusAsync());
        findViewById(R.id.rowRestartCore).setOnClickListener(v -> runScopeRestartCore());
        findViewById(R.id.rowRestartCustom).setOnClickListener(v -> showCustomRestartScopeDialog());
        findViewById(R.id.rowClearArtworkCache).setOnClickListener(v -> runClearArtworkCacheAndRestart());
        findViewById(R.id.rowPreviewOpenPopup).setOnClickListener(v -> triggerPreviewOpenCasePopup());

        findViewById(R.id.btnOpenNativePanelTab2).setOnClickListener(v -> openNativeColorOsPanel());
        findViewById(R.id.btnResetIntegration).setOnClickListener(v -> {
            ConfigStore.SettingsData def = ConfigStore.createDefaults();
            data.bridgeEnabled = def.bridgeEnabled;
            data.myDevicesPanel = def.myDevicesPanel;
            data.nativeArtwork = def.nativeArtwork;
            data.fluidCloudCapsule = def.fluidCloudCapsule;
            data.qsTile = def.qsTile;
            data.vendorBypass = def.vendorBypass;
            data.melodyChannel = def.melodyChannel;
            data.openCasePopup = def.openCasePopup;
            data.openCasePopupMode = def.openCasePopupMode;
            data.suppressNativePairing = def.suppressNativePairing;
            persistImmediate();
            Toast.makeText(this, R.string.toast_reset_done, Toast.LENGTH_SHORT).show();
        });
        refreshRootStatusAsync();
    }

    private void runClearArtworkCacheAndRestart() {
        Toast.makeText(this, R.string.toast_restarting_scopes, Toast.LENGTH_SHORT).show();
        ScopeRestarter.clearMyDevicesArtworkCacheAndRestartAsync((ok, msg) -> Toast.makeText(
                this,
                ok ? getString(R.string.toast_restart_done) : getString(R.string.toast_restart_failed, msg),
                Toast.LENGTH_SHORT).show());
    }

    private void bindAboutViews() {
        tvSysVersion = findViewById(R.id.tvSysVersion);
        tvDeviceModel = findViewById(R.id.tvDeviceModel);
        tvAboutChannel = findViewById(R.id.tvAboutChannel);
        tvAboutUpdateChip = findViewById(R.id.tvAboutUpdateChip);
        tvAboutUiStyleVal = findViewById(R.id.tvAboutUiStyleVal);
        tvAboutLanguageVal = findViewById(R.id.tvAboutLanguageVal);
        tvAboutDarkModeVal = findViewById(R.id.tvAboutDarkModeVal);
        swAboutMonet = findViewById(R.id.swAboutMonet);
        swAboutDebugMode = findViewById(R.id.swAboutDebugMode);

        cardAboutDynamicHero = findViewById(R.id.cardAboutDynamicHero);
        vAboutDynamicBg = findViewById(R.id.vAboutDynamicBg);
        layoutAboutHeroLogo = findViewById(R.id.layoutAboutHeroLogo);
        vAboutHeroWordmark = findViewById(R.id.vAboutHeroWordmark);
        tvAboutHeroVer = findViewById(R.id.tvAboutHeroVer);
        tvAboutHeroUpdateStatus = findViewById(R.id.tvAboutHeroUpdateStatus);
        tvAboutBuildSub = findViewById(R.id.tvAboutBuildSub);
        cardAboutModuleStatus = findViewById(R.id.cardAboutModuleStatus);
        vAboutStatusEmblem = findViewById(R.id.vAboutStatusEmblem);
        ivAboutStatusIcon = findViewById(R.id.ivAboutStatusIcon);
        tvAboutStatusTitle = findViewById(R.id.tvAboutStatusTitle);
        tvAboutStatusBadge = findViewById(R.id.tvAboutStatusBadge);
        tvAboutStatusSubtitle = findViewById(R.id.tvAboutStatusSubtitle);
        rowAboutScopeToggle = findViewById(R.id.rowAboutScopeToggle);
        tvAboutScopeCount = findViewById(R.id.tvAboutScopeCount);
        ivAboutScopeChevron = findViewById(R.id.ivAboutScopeChevron);
        layoutAboutScopeBody = findViewById(R.id.layoutAboutScopeBody);
        tvAboutScopeList = findViewById(R.id.tvAboutScopeList);

        tvSysVersion.setText("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        tvDeviceModel.setText(Build.MANUFACTURER + " " + Build.MODEL);
        if (tvAboutBuildSub != null) {
            tvAboutBuildSub.setText("v" + getInstalledVersionName() + " (" + getInstalledVersionCode() + ")");
        }

        if (cardAboutDynamicHero != null) {
            cardAboutDynamicHero.setOnClickListener(v -> {
                if (livingFlowUi) {
                    LivingFlowUpdatePages.showSoftwareUpdatePage(this);
                } else {
                    checkUpdateFromLsposedRepo(true);
                }
            });
            cardAboutDynamicHero.setOnLongClickListener(v -> {
                if (!livingFlowUi || vAboutDynamicBg == null) return false;
                vAboutDynamicBg.playEasterEgg();
                playAboutHeroEntrance(true);
                ColorOsLivingFlowEngine.performHaptic(v, ColorOsLivingFlowEngine.HapticKind.CONFIRM);
                return true;
            });
        }

        if (cardAboutModuleStatus != null) {
            cardAboutModuleStatus.setOnClickListener(v -> showDiagnosticsDialog());
        }

        if (tvAboutScopeList != null && tvAboutScopeCount != null) {
            String[] rawPkgs = getString(R.string.about_scope_value).split(",");
            StringBuilder sb = new StringBuilder();
            int pkgCount = 0;
            for (String raw : rawPkgs) {
                String trimmed = raw.trim();
                if (trimmed.isEmpty()) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append("• ").append(trimmed);
                pkgCount++;
            }
            tvAboutScopeList.setText(sb.toString());
            tvAboutScopeCount.setText(getString(R.string.about_scope_summary_fmt, pkgCount));
        }
        if (rowAboutScopeToggle != null) {
            rowAboutScopeToggle.setOnClickListener(v -> toggleAboutScope());
        }

        View rowAboutUiStyle = findViewById(R.id.rowAboutUiStyle);
        if (rowAboutUiStyle != null) {
            CouiBottomSheet.trackTouchPoint(rowAboutUiStyle);
            rowAboutUiStyle.setOnClickListener(v -> showUiStyleDialog(v));
        }

        View rowAboutLanguage = findViewById(R.id.rowAboutLanguage);
        CouiBottomSheet.trackTouchPoint(rowAboutLanguage);
        rowAboutLanguage.setOnClickListener(v -> showLanguageDialog(v));

        View rowAboutDarkMode = findViewById(R.id.rowAboutDarkMode);
        CouiBottomSheet.trackTouchPoint(rowAboutDarkMode);
        rowAboutDarkMode.setOnClickListener(v -> showDarkModeDialog(v));
        findViewById(R.id.rowAboutChangelog).setOnClickListener(v -> showChangelogDialog());
        findViewById(R.id.rowAboutCheckUpdate).setOnClickListener(v -> {
            if (livingFlowUi) {
                LivingFlowUpdatePages.showSoftwareUpdatePage(this);
            } else {
                checkUpdateFromLsposedRepo(true);
            }
        });

        findViewById(R.id.rowAboutMonet).setOnClickListener(v -> swAboutMonet.toggle());
        swAboutMonet.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressUiCallbacks) return;
            if (livingFlowUi) ColorOsLivingFlowEngine.performHaptic(buttonView, ColorOsLivingFlowEngine.HapticKind.SWITCH);
            ConfigStore.setMonetEnabled(this, isChecked);
            DynamicThemeHelper.applyAccent(findViewById(android.R.id.content), this);
            updateNavIndicator(activeTab, activeTab, 0, false, false);
            syncUiState();
            refreshHeadsetStateUi();
        });

        findViewById(R.id.rowAboutDebugMode).setOnClickListener(v -> swAboutDebugMode.toggle());
        swAboutDebugMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressUiCallbacks) return;
            if (livingFlowUi) ColorOsLivingFlowEngine.performHaptic(buttonView, ColorOsLivingFlowEngine.HapticKind.SWITCH);
            data.debugMode = isChecked;
            persistImmediate();
        });

        findViewById(R.id.rowAboutViewLogs).setOnClickListener(v -> showDiagnosticsDialog());
        findViewById(R.id.rowAboutResetAll).setOnClickListener(v -> showResetAllDialog());

        findViewById(R.id.rowAboutAuthor).setOnClickListener(v -> openCoolapkProfile(UID_AUTHOR));
        findViewById(R.id.rowCreditSonyPods).setOnClickListener(v -> openUrl(URL_SONYPODS));
        findViewById(R.id.rowCreditHuaweiPods).setOnClickListener(v -> openUrl(URL_HUAWEIPODS));
    }

    private interface BoolConsumer {
        void accept(boolean value);
    }

    private void bindSwitchRow(int rowId, Switch sw, BoolConsumer setter) {
        findViewById(rowId).setOnClickListener(v -> sw.toggle());
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressUiCallbacks) return;
            if (livingFlowUi) ColorOsLivingFlowEngine.performHaptic(buttonView, ColorOsLivingFlowEngine.HapticKind.SWITCH);
            setter.accept(isChecked);
            persistImmediate();
        });
    }

    private void refreshHeadsetStateUi() {
        headsetState = ConfigStore.readGlobalState(this);
        suppressUiCallbacks = true;
        try {
            String effectiveModel = resolvePreviewModelName(headsetState);
            if (headsetState.connected) {
                String title = (headsetState.name != null && !headsetState.name.isEmpty())
                        ? headsetState.name : effectiveModel;
                tvHeadsetName.setText(title);
                String macText = (headsetState.mac != null && !headsetState.mac.isEmpty())
                        ? headsetState.mac : getString(R.string.status_title_active);
                if (headsetState.colorCode >= 0) {
                    macText = macText + " · " + getString(R.string.badge_color_fmt, headsetState.colorCode);
                }
                tvHeadsetMac.setText(macText);

                String proto = "Tandem V" + headsetState.protocolVersion;
                if (headsetState.ncAsmInquiredType > 0) {
                    proto += String.format(Locale.US, " · 0x%02X", headsetState.ncAsmInquiredType);
                }
                tvProtocolBadge.setText(proto);
                tvCurrentAncBadge.setText(getString(R.string.readout_anc_format, headsetState.formatModeLabel(this)));
                SonyModelImageManager.ensureImageAsync(this, effectiveModel, headsetState.colorCode);
            } else {
                tvHeadsetName.setText(R.string.headset_disconnected_title);
                tvHeadsetMac.setText(R.string.headset_disconnected_sub);
                tvProtocolBadge.setText(R.string.badge_proto_none);
                tvCurrentAncBadge.setText(R.string.headset_disconnected_sub);
            }

            tvBatteryLeft.setText(formatBat(headsetState.effectiveLeft(), headsetState.leftCharging || headsetState.singleCharging));
            tvBatteryRight.setText(formatBat(headsetState.effectiveRight(), headsetState.rightCharging || headsetState.singleCharging));
            tvBatteryCase.setText(formatBat(headsetState.caseBattery, headsetState.caseCharging));
            if (livingFlowUi) {
                updateBatteryGauge(vBatBarLeft, headsetState.connected ? headsetState.effectiveLeft() : -1);
                updateBatteryGauge(vBatBarRight, headsetState.connected ? headsetState.effectiveRight() : -1);
                updateBatteryGauge(vBatBarCase, headsetState.connected ? headsetState.caseBattery : -1);
                updateHeroGlowAura();
                updateQuickAncSegment(headsetState.connected ? headsetState.ancMode : -1, false);
            }

            int asm = headsetState.connected ? headsetState.asmLevel : data.defaultAsmLevel;
            tvAsmLevelVal.setText(String.valueOf(asm));
            sbAsmLevel.setProgress(Math.max(0, Math.min(19, asm - 1)));
            swVoiceFocus.setChecked(headsetState.connected ? headsetState.voiceFocus : data.defaultVoiceFocus);

            ConfigStore.CodecState cs = ConfigStore.readGlobalCodecState(this);
            updateCategoryRowState(rowScSound, tvScSoundVal,
                    SoundConnectSheets.hasSoundFeatures(headsetState),
                    SoundConnectSheets.formatSoundSummary(this, headsetState));
            updateCategoryRowState(rowScInteraction, tvScInteractionVal,
                    SoundConnectSheets.hasInteractionFeatures(headsetState),
                    SoundConnectSheets.formatInteractionSummary(this, headsetState));
            updateCategoryRowState(rowScConnection, tvScConnectionVal,
                    SoundConnectSheets.hasConnectionFeatures(headsetState, cs, headsetState.mac),
                    SoundConnectSheets.formatConnectionSummary(this, headsetState, cs));
            updateCategoryRowState(rowScPower, tvScPowerVal,
                    SoundConnectSheets.hasPowerFeatures(headsetState),
                    SoundConnectSheets.formatPowerSummary(this, headsetState));

            updateArtworkPreviewAsync(effectiveModel, headsetState.colorCode);
        } finally {
            suppressUiCallbacks = false;
        }
    }

    private void bindSoundConnectCategoryRow(View row, Runnable openAction) {
        if (row == null) return;
        row.setOnClickListener(v -> {
            headsetState = ConfigStore.readGlobalState(this);
            if (!headsetState.connected) {
                Toast.makeText(this, R.string.toast_headset_not_connected, Toast.LENGTH_SHORT).show();
                return;
            }
            openAction.run();
        });
    }

    private void updateCategoryRowState(View row, TextView valTv, boolean available, String summary) {
        if (row != null) {
            row.setAlpha(available ? 1.0f : 0.55f);
        }
        if (valTv != null) {
            if (!headsetState.connected) {
                valTv.setText(R.string.sc_status_offline);
            } else if (summary != null && !summary.isEmpty()) {
                valTv.setText(summary);
            } else {
                valTv.setText(available ? "—" : getString(R.string.sc_status_offline));
            }
        }
    }

    private String resolvePreviewModelName(ConfigStore.HeadsetState st) {
        if (data != null && data.customModelOverride != null && !data.customModelOverride.trim().isEmpty()) {
            return SonyDeviceDetector.normalizeModelName(data.customModelOverride.trim());
        }
        if (st != null && st.connected) {
            String m = st.effectiveModelName();
            if (!m.isEmpty()) return m;
        }
        return "WF-1000XM4";
    }

    private void updateArtworkPreviewAsync(String model, int colorCode) {
        final String customUri = data != null ? data.customArtworkUri : "";
        new Thread(() -> {
            Bitmap bmp = SonyAssetProvider.loadBitmap(this, model, colorCode, customUri, 256);
            if (bmp != null) {
                runOnUiThread(() -> {
                    if (ivArtworkPreview != null && !isFinishing() && !isDestroyed()) {
                        ivArtworkPreview.setImageBitmap(bmp);
                    }
                });
            }
        }, "SonyPods-PreviewArt").start();
    }

    private String formatBat(int lvl, boolean charging) {
        if (lvl < 0) return getString(R.string.battery_unknown);
        return getString(charging ? R.string.battery_charging_fmt : R.string.battery_normal_fmt, lvl);
    }

    private void openNativeColorOsPanel() {
        final String mac = (headsetState != null && headsetState.mac != null) ? headsetState.mac.trim() : "";
        final String name = resolvePreviewModelName(headsetState);
        try {
            Intent intent = new Intent(ACTION_COLOROS_DETAIL_PANEL);
            intent.setPackage(PKG_MYDEVICES);
            if (!mac.isEmpty()) {
                intent.putExtra("device_mac_info", mac);
                intent.putExtra("device_id", mac);
                intent.putExtra("device_address", mac);
                intent.putExtra("device_name", name);
                intent.putExtra("device_connect_state", 2);
                try {
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter != null && BluetoothAdapter.checkBluetoothAddress(mac.toUpperCase(Locale.US))) {
                        BluetoothDevice dev = adapter.getRemoteDevice(mac.toUpperCase(Locale.US));
                        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, dev);
                    }
                } catch (Throwable ignored) {
                }
            }
            startActivity(intent);
        } catch (Throwable primaryErr) {
            if (rootAvailable && !mac.isEmpty()) {
                ScopeRestarter.openNativePanelAsRootAsync(mac, name, (ok, msg) -> {
                    if (!ok) {
                        openBluetoothSettingsFallback();
                    }
                });
            } else {
                openBluetoothSettingsFallback();
            }
        }
    }

    private void openBluetoothSettingsFallback() {
        try {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } catch (Throwable ignored) {
            Toast.makeText(this, R.string.toast_native_panel_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void triggerPreviewOpenCasePopup() {
        String model = resolvePreviewModelName(headsetState);
        if (rootAvailable) {
            ScopeRestarter.openOpenCasePopupAsRootAsync(model, (ok, msg) -> {
                if (!ok) {
                    sendOpenPopupBroadcastDirect(model);
                }
            });
        } else {
            sendOpenPopupBroadcastDirect(model);
        }
    }

    private void sendOpenPopupBroadcastDirect(String model) {
        try {
            Intent intent = new Intent(ConfigStore.ACTION_SHOW_OPEN_POPUP);
            intent.setPackage(PKG_MYDEVICES);
            intent.putExtra(ConfigStore.EXTRA_MODEL, model);
            String mac = (headsetState != null && headsetState.mac != null) ? headsetState.mac.trim() : "";
            if (!mac.isEmpty() && BluetoothAdapter.checkBluetoothAddress(mac.toUpperCase(Locale.US))) {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null) {
                    intent.putExtra(ConfigStore.EXTRA_DEVICE, adapter.getRemoteDevice(mac.toUpperCase(Locale.US)));
                }
            }
            sendBroadcast(intent);
        } catch (Throwable t) {
            Toast.makeText(this, R.string.toast_preview_popup_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void triggerCatalogSync() {
        Toast.makeText(this, R.string.toast_syncing_catalog, Toast.LENGTH_SHORT).show();
        final String model = resolvePreviewModelName(headsetState);
        final int color = headsetState != null ? headsetState.colorCode : -1;
        new Thread(() -> {
            SonyModelImageManager.SyncResult res = SonyModelImageManager.syncCatalogBlocking(this, model, color);
            runOnUiThread(() -> {
                syncUiState();
                refreshHeadsetStateUi();
                if (res.ok) {
                    Toast.makeText(this, getString(R.string.toast_sync_ok, res.count), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, getString(R.string.toast_sync_failed, res.message), Toast.LENGTH_LONG).show();
                }
            });
        }, "SonyPods-CatalogSync").start();
    }

    private void launchCustomArtworkPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/png");
            startActivityForResult(intent, REQ_PICK_PNG);
        } catch (Throwable t) {
            Toast.makeText(this, R.string.toast_custom_art_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void showModelOverrideDialog() {
        final CouiBottomSheet.Palette pal = CouiBottomSheet.Palette.fromModuleTheme(this);
        final CouiBottomSheet.SheetHandle sheet = CouiBottomSheet.create(this, pal);
        final String curModel = data.customModelOverride != null ? data.customModelOverride.trim() : "";

        final String autoLabel = getString(R.string.model_override_auto);
        final String[] presets = {
                "", "WF-1000XM4", "WF-1000XM5",
                "WH-1000XM4", "WH-1000XM5", "LinkBuds S", "ULT WEAR"
        };
        final String[] labels = new String[presets.length];
        for (int i = 0; i < presets.length; i++) {
            labels[i] = presets[i].isEmpty() ? autoLabel : presets[i];
        }

        if (livingFlowUi) {
            final EditText[] inputRef = new EditText[1];
            CouiBottomSheet.addLivingFlowTopBar(
                    this, sheet,
                    getString(R.string.dialog_model_override_title),
                    getString(R.string.btn_cancel),
                    getString(R.string.btn_apply), 1, v -> {
                        if (inputRef[0] != null) {
                            data.customModelOverride = inputRef[0].getText().toString().trim();
                        }
                        persistImmediate();
                        syncUiState();
                        refreshHeadsetStateUi();
                        sheet.dialog.dismiss();
                    });

            ScrollView scroll = CouiBottomSheet.createMaxHeightScrollView(
                    this, Math.round(getResources().getDisplayMetrics().heightPixels * 0.62f));
            LinearLayout bodyCol = new LinearLayout(this);
            bodyCol.setOrientation(LinearLayout.VERTICAL);

            inputRef[0] = CouiBottomSheet.addLivingFlowBorderlessInputCard(
                    this, bodyCol, pal, curModel, getString(R.string.dialog_model_override_hint));

            TextView tvSub = new TextView(this);
            tvSub.setText(R.string.dialog_model_override_sub);
            tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            tvSub.setLineSpacing(0f, 1.25f);
            tvSub.setTextColor(pal.textSecondary);
            tvSub.setPadding(dp(16), dp(12), dp(16), dp(8));
            bodyCol.addView(tvSub, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            CouiBottomSheet.addLivingFlowDonutPresetCard(this, bodyCol, pal, labels, presets, inputRef[0]);

            scroll.addView(bodyCol, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            sheet.card.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            sheet.dialog.show();
            return;
        }

        CouiBottomSheet.addHeader(
                this, sheet.card, pal,
                getString(R.string.dialog_model_override_title),
                getString(R.string.dialog_model_override_sub),
                null);

        final EditText input = CouiBottomSheet.addPillInputBox(this, sheet.card, pal, curModel,
                getString(R.string.dialog_model_override_hint));
        CouiBottomSheet.addChipFlowGroup(this, sheet.card, pal, labels, presets, input);

        CouiBottomSheet.addButtonRow(
                this, sheet.card, pal,
                getString(R.string.btn_cancel), 0, v -> sheet.dialog.dismiss(),
                getString(R.string.btn_apply), 1, v -> {
                    data.customModelOverride = input.getText().toString().trim();
                    persistImmediate();
                    syncUiState();
                    refreshHeadsetStateUi();
                    sheet.dialog.dismiss();
                });
        sheet.dialog.show();
    }

    private void refreshRootStatusAsync() {
        if (tvRootStatus != null) {
            tvRootStatus.setText(R.string.root_status_checking);
        }
        ScopeRestarter.checkRootAsync((ok, detail) -> {
            rootAvailable = ok;
            if (ivRootIcon != null) {
                ivRootIcon.setImageResource(ok ? R.drawable.ic_status_active : R.drawable.ic_status_warn);
            }
            if (tvRootStatus != null) {
                tvRootStatus.setText(ok ? R.string.root_status_ready : R.string.root_status_unavailable);
            }
        });
    }

    private void runScopeRestartCore() {
        Toast.makeText(this, R.string.toast_restarting_scopes, Toast.LENGTH_SHORT).show();
        ScopeRestarter.restartCoreScopesAsync((ok, msg) -> {
            if (!ok) refreshRootStatusAsync();
            Toast.makeText(
                    this,
                    ok ? getString(R.string.toast_restart_done) : getString(R.string.toast_restart_failed, msg),
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void showCustomRestartScopeDialog() {
        final String[] pkgs = {
                ScopeRestarter.PKG_BLUETOOTH,
                ScopeRestarter.PKG_MYDEVICES,
                ScopeRestarter.PKG_WIRELESS,
                ScopeRestarter.PKG_MELODY,
                ScopeRestarter.PKG_ACCESSORY,
                ScopeRestarter.PKG_SETTINGS,
                ScopeRestarter.PKG_SYSTEMUI,
                ScopeRestarter.PKG_SOUND_CONNECT
        };
        final String[] rawLabels = {
                getString(R.string.scope_pkg_bluetooth),
                getString(R.string.scope_pkg_mydevices),
                getString(R.string.scope_pkg_wireless),
                getString(R.string.scope_pkg_melody),
                getString(R.string.scope_pkg_accessory),
                getString(R.string.scope_pkg_settings),
                getString(R.string.scope_pkg_systemui),
                getString(R.string.scope_pkg_soundconnect)
        };
        final boolean[] checked = { true, true, true, true, true, false, false, false };

        final CouiBottomSheet.Palette pal = CouiBottomSheet.Palette.fromModuleTheme(this);
        final CouiBottomSheet.SheetHandle sheet = CouiBottomSheet.create(this, pal);
        final View.OnClickListener onRestartAction = v -> {
            List<String> selected = new ArrayList<>();
            boolean hasSysUi = false;
            for (int i = 0; i < pkgs.length; i++) {
                if (checked[i]) {
                    selected.add(pkgs[i]);
                    if (ScopeRestarter.PKG_SYSTEMUI.equals(pkgs[i])) {
                        hasSysUi = true;
                    }
                }
            }
            if (selected.isEmpty()) return;
            sheet.dialog.dismiss();
            if (hasSysUi) {
                confirmAndRestartSelected(selected);
            } else {
                executeRestartSelected(selected);
            }
        };

        final TextView countBadge;
        if (livingFlowUi) {
            CouiBottomSheet.addLivingFlowTopBar(
                    this, sheet,
                    getString(R.string.dialog_restart_custom_title),
                    getString(R.string.btn_cancel),
                    getString(R.string.btn_restart_selected), 1, onRestartAction);

            LinearLayout subRow = new LinearLayout(this);
            subRow.setOrientation(LinearLayout.HORIZONTAL);
            subRow.setGravity(Gravity.CENTER_VERTICAL);
            subRow.setPadding(dp(6), 0, dp(6), dp(10));

            TextView tvSub = new TextView(this);
            tvSub.setText(R.string.dialog_restart_custom_sub);
            tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            tvSub.setTextColor(pal.textSecondary);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            subLp.setMarginEnd(dp(8));
            subRow.addView(tvSub, subLp);

            TextView badge = new TextView(this);
            badge.setText("5 / " + pkgs.length);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            badge.setTextColor(pal.accent);
            badge.setPadding(dp(10), dp(3), dp(10), dp(3));
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setShape(GradientDrawable.RECTANGLE);
            badgeBg.setColor(pal.tonal);
            badgeBg.setCornerRadius(999f * getResources().getDisplayMetrics().density);
            badge.setBackground(badgeBg);
            subRow.addView(badge, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            sheet.card.addView(subRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            countBadge = badge;
        } else {
            countBadge = CouiBottomSheet.addHeader(
                    this, sheet.card, pal,
                    getString(R.string.dialog_restart_custom_title),
                    getString(R.string.dialog_restart_custom_sub),
                    "5 / " + pkgs.length);
        }

        ScrollView scroll = CouiBottomSheet.createMaxHeightScrollView(
                this, Math.round(getResources().getDisplayMetrics().heightPixels * (livingFlowUi ? 0.50f : 0.44f)));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!livingFlowUi) {
            scrollLp.topMargin = dp(14);
        }
        LinearLayout group = CouiBottomSheet.createSubcardGroup(this, pal);
        scroll.addView(group);
        sheet.card.addView(scroll, scrollLp);

        final CouiBottomSheet.CheckBadgeView[] badges = new CouiBottomSheet.CheckBadgeView[pkgs.length];
        for (int i = 0; i < pkgs.length; i++) {
            final int idx = i;
            String raw = rawLabels[i];
            int paren = raw.indexOf(" (");
            String title = paren > 0 ? raw.substring(0, paren).trim() : raw;
            String subtitle = pkgs[i];
            if (ScopeRestarter.PKG_SYSTEMUI.equals(pkgs[i]) && paren > 0) {
                int dot = raw.indexOf("·", paren);
                if (dot > 0) {
                    String note = raw.substring(dot + 1).replace(")", "").trim();
                    subtitle = pkgs[i] + " · " + note;
                }
            }
            badges[i] = CouiBottomSheet.addCheckRow(
                    this, group, pal, title, subtitle, checked[i], i < pkgs.length - 1, v -> {
                        checked[idx] = !checked[idx];
                        badges[idx].setChecked(checked[idx]);
                        int selCount = 0;
                        for (boolean b : checked) {
                            if (b) selCount++;
                        }
                        if (countBadge != null) {
                            countBadge.setText(selCount + " / " + pkgs.length);
                        }
                    });
        }

        if (!livingFlowUi) {
            CouiBottomSheet.addButtonRow(
                    this, sheet.card, pal,
                    getString(R.string.btn_cancel), 0, v -> sheet.dialog.dismiss(),
                    getString(R.string.btn_restart_selected), 1, onRestartAction);
        }
        sheet.dialog.show();
    }

    private void confirmAndRestartSelected(List<String> selected) {
        CouiBottomSheet.Palette pal = CouiBottomSheet.Palette.fromModuleTheme(this);
        CouiBottomSheet.SheetHandle sheet = CouiBottomSheet.create(this, pal);
        if (livingFlowUi) {
            CouiBottomSheet.addLivingFlowTopBar(
                    this, sheet,
                    getString(R.string.dialog_restart_custom_title),
                    getString(R.string.btn_cancel),
                    getString(R.string.btn_restart_selected), 2, v -> {
                        sheet.dialog.dismiss();
                        executeRestartSelected(selected);
                    });
            LinearLayout msgCard = CouiBottomSheet.createSubcardGroup(this, pal);
            msgCard.setPadding(dp(18), dp(18), dp(18), dp(18));
            TextView msgTv = new TextView(this);
            msgTv.setText(R.string.dialog_restart_confirm_sysui);
            msgTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            msgTv.setLineSpacing(0f, 1.35f);
            msgTv.setTextColor(pal.textPrimary);
            msgCard.addView(msgTv);
            sheet.card.addView(msgCard, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            sheet.dialog.show();
            return;
        }
        CouiBottomSheet.addHeader(
                this, sheet.card, pal,
                getString(R.string.dialog_restart_custom_title),
                getString(R.string.dialog_restart_confirm_sysui),
                null);
        CouiBottomSheet.addButtonRow(
                this, sheet.card, pal,
                getString(R.string.btn_cancel), 0, v -> sheet.dialog.dismiss(),
                getString(R.string.btn_restart_selected), 2, v -> {
                    sheet.dialog.dismiss();
                    executeRestartSelected(selected);
                });
        sheet.dialog.show();
    }

    private void executeRestartSelected(List<String> selected) {
        Toast.makeText(this, R.string.toast_restarting_scopes, Toast.LENGTH_SHORT).show();
        ScopeRestarter.restartSelectedAsync(selected, (ok, msg) -> {
            if (!ok) refreshRootStatusAsync();
            Toast.makeText(
                    this,
                    ok ? getString(R.string.toast_restart_done) : getString(R.string.toast_restart_failed, msg),
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void showCycleModeDialog(View anchor) {
        String[] labels = {getString(R.string.cycle_mode_3way), getString(R.string.cycle_mode_2way)};
        int cur = data.cycleMode == ConfigStore.CYCLE_2WAY ? 1 : 0;
        CouiBottomSheet.Palette pal = CouiBottomSheet.Palette.fromModuleTheme(this);
        CouiBottomSheet.IntCallback cb = which -> {
            data.cycleMode = which == 1 ? ConfigStore.CYCLE_2WAY : ConfigStore.CYCLE_3WAY;
            persistImmediate();
        };
        CouiBottomSheet.showPopupMenu(this, anchor, pal, labels, cur, cb);
    }

    private void showOpenPopupModeDialog(View anchor) {
        String[] labels = {
                getString(R.string.popup_mode_pairing_only_desc),
                getString(R.string.popup_mode_pairing_and_connect_desc)
        };
        int cur = data.openCasePopupMode == ConfigStore.POPUP_MODE_PAIRING_AND_CONNECT ? 1 : 0;
        CouiBottomSheet.Palette pal = CouiBottomSheet.Palette.fromModuleTheme(this);
        CouiBottomSheet.IntCallback cb = which -> {
            data.openCasePopupMode = which == 1
                    ? ConfigStore.POPUP_MODE_PAIRING_AND_CONNECT
                    : ConfigStore.POPUP_MODE_PAIRING_ONLY;
            persistImmediate();
        };
        CouiBottomSheet.showPopupMenu(this, anchor, pal, labels, cur, cb);
    }

    private void showLanguageDialog(View anchor) {
        final String[] codes = {
                ConfigStore.LANG_SYSTEM,
                ConfigStore.LANG_ZH_TW,
                ConfigStore.LANG_ZH_CN,
                ConfigStore.LANG_EN
        };
        String detectedLabel = getLanguageLabel(ConfigStore.detectSystemLanguage());
        String[] names = {
                getString(R.string.lang_system_fmt, detectedLabel),
                getString(R.string.lang_zh_tw),
                getString(R.string.lang_zh_cn),
                getString(R.string.lang_en)
        };
        String cur = ConfigStore.getAppLanguage(this);
        int idx = 0;
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(cur)) idx = i;
        }
        CouiBottomSheet.Palette pal = CouiBottomSheet.Palette.fromModuleTheme(this);
        CouiBottomSheet.IntCallback cb = which -> {
            String chosen = codes[which];
            if (!chosen.equals(cur)) {
                ConfigStore.setAppLanguage(this, chosen);
                sPendingTabAfterRecreate = activeTab;
                mainHandler.postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        recreate();
                    }
                }, 90L);
            }
        };
        CouiBottomSheet.showPopupMenu(this, anchor, pal, names, idx, cb);
    }

    private void showDarkModeDialog(View anchor) {
        String[] names = {
                getString(R.string.dark_mode_system),
                getString(R.string.dark_mode_light),
                getString(R.string.dark_mode_dark)
        };
        int cur = ConfigStore.getAppDarkMode(this);
        CouiBottomSheet.Palette pal = CouiBottomSheet.Palette.fromModuleTheme(this);
        CouiBottomSheet.IntCallback cb = which -> {
            if (which != cur) {
                ConfigStore.setAppDarkMode(this, which);
                sPendingTabAfterRecreate = activeTab;
                mainHandler.postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        recreate();
                    }
                }, 90L);
            }
        };
        CouiBottomSheet.showPopupMenu(this, anchor, pal, names, cur, cb);
    }

    private void showUiStyleDialog(View anchor) {
        int curStyle = ConfigStore.getAppUiStyle(this);
        int curIdx = (curStyle == ConfigStore.UI_STYLE_LIVING_FLOW) ? 0 : 1;
        String[] names = {
                getString(R.string.ui_style_living_flow),
                getString(R.string.ui_style_classic)
        };
        CouiBottomSheet.Palette pal = CouiBottomSheet.Palette.fromModuleTheme(this);
        CouiBottomSheet.IntCallback cb = which -> {
            int nextStyle = (which == 0) ? ConfigStore.UI_STYLE_LIVING_FLOW : ConfigStore.UI_STYLE_CLASSIC;
            if (nextStyle != curStyle) {
                ConfigStore.setAppUiStyle(this, nextStyle);
                sPendingTabAfterRecreate = activeTab;
                mainHandler.postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        recreate();
                    }
                }, 90L);
            }
        };
        CouiBottomSheet.showPopupMenu(this, anchor, pal, names, curIdx, cb);
    }

    private void showTopQuickActionsMenu(View anchor) {
        String[] items = {
                getString(R.string.pref_restart_core_title),
                getString(R.string.pref_restart_custom_title),
                getString(R.string.pref_clear_md_cache_title),
                getString(R.string.btn_open_native_panel),
                getString(R.string.pref_preview_open_popup_title)
        };
        CouiBottomSheet.showPopupMenu(this, anchor, CouiBottomSheet.Palette.fromModuleTheme(this),
                items, -1, which -> {
                    if (which == 0) {
                        runScopeRestartCore();
                    } else if (which == 1) {
                        showCustomRestartScopeDialog();
                    } else if (which == 2) {
                        runClearArtworkCacheAndRestart();
                    } else if (which == 3) {
                        openNativeColorOsPanel();
                    } else if (which == 4) {
                        triggerPreviewOpenCasePopup();
                    }
                });
    }

    private void setupLivingFlowUi() {
        btnTopQuickAppearance = findViewById(R.id.btnTopQuickAppearance);
        cardHeadsetHero = findViewById(R.id.cardHeadsetHero);
        vHeroGlowAura = findViewById(R.id.vHeroGlowAura);
        vBatBarLeft = findViewById(R.id.vBatBarLeft);
        vBatBarRight = findViewById(R.id.vBatBarRight);
        vBatBarCase = findViewById(R.id.vBatBarCase);
        layoutQuickAncCapsule = findViewById(R.id.layoutQuickAncCapsule);
        btnAncNc = findViewById(R.id.btnAncNc);
        btnAncAmbient = findViewById(R.id.btnAncAmbient);
        btnAncOff = findViewById(R.id.btnAncOff);
        ivAncIconNc = findViewById(R.id.ivAncIconNc);
        ivAncIconAmbient = findViewById(R.id.ivAncIconAmbient);
        ivAncIconOff = findViewById(R.id.ivAncIconOff);
        tvAncLabelNc = findViewById(R.id.tvAncLabelNc);
        tvAncLabelAmbient = findViewById(R.id.tvAncLabelAmbient);
        tvAncLabelOff = findViewById(R.id.tvAncLabelOff);
        ivAncRadioNc = findViewById(R.id.ivAncRadioNc);
        ivAncRadioAmbient = findViewById(R.id.ivAncRadioAmbient);
        ivAncRadioOff = findViewById(R.id.ivAncRadioOff);

        final boolean dark = ConfigStore.isDarkThemeActive(this);
        final float d = getResources().getDisplayMetrics().density;

        if (vTopHeaderBlur != null) {
            vTopHeaderBlur.setSurfaceColor(getColor(R.color.lf_bg));
            vTopHeaderBlur.setOutlineProvider(null);
            vTopHeaderBlur.setTranslationZ(4f * d);
        }
        if (layoutHeader != null) {
            layoutHeader.setOutlineProvider(null);
            layoutHeader.setTranslationZ(12f * d);
        }
        if (layoutBottomNavContainer != null) {
            layoutBottomNavContainer.setOutlineProvider(null);
            layoutBottomNavContainer.setTranslationZ(16f * d);
        }

        if (bannerModuleInactive != null) {
            GradientDrawable warnBg = new GradientDrawable();
            warnBg.setShape(GradientDrawable.RECTANGLE);
            warnBg.setCornerRadius(20f * d);
            warnBg.setColor(dark ? 0xFF2A2012 : 0xFFFFF5E8);
            warnBg.setStroke(Math.max(1, Math.round(1.0f * d)), dark ? 0x4DFBBF24 : 0x40D97706);
            bannerModuleInactive.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(getColor(R.color.lf_press_bg)), warnBg, warnBg));
            bannerModuleInactive.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            bannerModuleInactive.setClipToOutline(true);
        }
        if (vBannerWarnEmblem != null) {
            GradientDrawable emblemBg = new GradientDrawable();
            emblemBg.setShape(GradientDrawable.OVAL);
            emblemBg.setColor(dark ? 0x2DFBBF24 : 0x1FD97706);
            vBannerWarnEmblem.setBackground(emblemBg);
        }

        if (vBottomNavShadow != null) {
            vBottomNavShadow.setOutlineProvider(null);
            vBottomNavShadow.setTranslationZ(8f * d);
            final int[] stopsColor = dark
                    ? new int[]{0x00000000, 0x10000000, 0x2B000000, 0x4E000000, 0x72000000, 0x94000000}
                    : new int[]{0x00000000, 0x03000000, 0x08000000, 0x10000000, 0x17000000, 0x1E000000};
            final float[] stopsPos = new float[]{0f, 0.24f, 0.48f, 0.70f, 0.86f, 1.0f};
            ShapeDrawable scrim = new ShapeDrawable(new RectShape());
            scrim.getPaint().setDither(true);
            scrim.setShaderFactory(new ShapeDrawable.ShaderFactory() {
                @Override
                public Shader resize(int width, int height) {
                    return new LinearGradient(
                            0f, 0f, 0f, Math.max(1f, height),
                            stopsColor, stopsPos, Shader.TileMode.CLAMP);
                }
            });
            vBottomNavShadow.setBackground(scrim);
        }

        ColorOsLivingFlowEngine.applyGlassSurface(layoutBottomNav, ColorOsLivingFlowEngine.GlassPreset.BOTTOM_BAR_PILL,
                dark, 28f, false, true);
        ColorOsLivingFlowEngine.applyGlassSurface(btnTopQuickAppearance, ColorOsLivingFlowEngine.GlassPreset.TOP_BAR_CIRCLE,
                dark, 21f, true, false);

        navSpotLight = new LivingFlowEffects.SpotLightDrawable(
                dark, d, 28f, false, true, dark ? 0.24f : 0.30f, 88f);
        if (layoutBottomNav != null) {
            layoutBottomNav.setForeground(navSpotLight);
            navElasticController = new LivingFlowEffects.NavBarElasticController(layoutBottomNav);
        }

        LivingFlowEffects.TouchFeedback.attach(btnTopQuickAppearance,
                LivingFlowEffects.TouchFeedback.Profile.GLASS_CIRCLE, dark, 21f, true, false, true);

        int accent = DynamicThemeHelper.getAccent(this);
        int[] primaryBtnIds = {
                R.id.btnRefreshState,
                R.id.btnOpenNativePanel,
                R.id.btnApplyDefaults,
                R.id.btnOpenNativePanelTab2,
                R.id.btnResetIntegration
        };
        for (int id : primaryBtnIds) {
            View btn = findViewById(id);
            if (btn != null) {
                boolean isColoredPrimary = (id == R.id.btnOpenNativePanel || id == R.id.btnOpenNativePanelTab2);
                LivingFlowEffects.TouchFeedback.attach(btn,
                        LivingFlowEffects.TouchFeedback.Profile.PRIMARY_BUTTON, dark, 999f, false, true);
                ColorOsLivingFlowEngine.applyLuminousPillButton(btn, isColoredPrimary, accent, dark, true);
            }
        }

        if (cardAboutDynamicHero != null) {
            ColorOsLivingFlowEngine.applySmoothCard(cardAboutDynamicHero, 24f, 2.0f, dark, false);
            ColorOsLivingFlowEngine.installSmoothOutline(cardAboutDynamicHero, 24f, 2.0f, false, false);
            cardAboutDynamicHero.setClipToOutline(true);
            LivingFlowEffects.TouchFeedback.attach(cardAboutDynamicHero,
                    LivingFlowEffects.TouchFeedback.Profile.HERO_CARD, dark, 24f, false, false);
        }
        if (vAboutHeroWordmark != null) {
            vAboutHeroWordmark.setBrandStyle(ColorOsBrandWordmarkView.STYLE_ABOUT_HERO);
        }
        if (tvAboutHeroVer != null) {
            tvAboutHeroVer.setText(getInstalledVersionName());
            tvAboutHeroVer.setTypeface(ColorOsBrandWordmarkView.getDisplayDigitTypeface(430));
        }
        if (tvAboutHeroUpdateStatus != null) {
            tvAboutHeroUpdateStatus.setTypeface(ColorOsBrandWordmarkView.getUiSubtitleTypeface(450));
        }
        if (tvAboutBuildSub != null) {
            tvAboutBuildSub.setText("v" + getInstalledVersionName() + " (" + getInstalledVersionCode() + ")");
        }

        if (btnTopQuickAppearance != null) {
            btnTopQuickAppearance.setOnClickListener(this::showTopQuickActionsMenu);
        }

        if (btnAncNc != null) btnAncNc.setOnClickListener(v -> onQuickAncClicked(ConfigStore.ANC_NC));
        if (btnAncAmbient != null) btnAncAmbient.setOnClickListener(v -> onQuickAncClicked(ConfigStore.ANC_AMBIENT));
        if (btnAncOff != null) btnAncOff.setOnClickListener(v -> onQuickAncClicked(ConfigStore.ANC_OFF));

        updateHeroGlowAura();
        updateQuickAncSegment(-1, false);
        if (activeTab == TAB_ABOUT) {
            playAboutHeroEntrance(false);
        }
    }

    private void toggleAboutScope() {
        if (layoutAboutScopeBody == null) return;
        if (livingFlowUi && rowAboutScopeToggle != null) {
            ColorOsLivingFlowEngine.performHaptic(rowAboutScopeToggle, ColorOsLivingFlowEngine.HapticKind.TICK);
        }
        aboutScopeExpanded = !aboutScopeExpanded;
        if (ivAboutScopeChevron != null) {
            ivAboutScopeChevron.animate().cancel();
            ivAboutScopeChevron.animate()
                    .rotation(aboutScopeExpanded ? 270f : 90f)
                    .setDuration(260L)
                    .setInterpolator(FLUID_EASE)
                    .start();
        }
        if (aboutScopeAnimator != null) {
            aboutScopeAnimator.cancel();
            aboutScopeAnimator = null;
        }
        View parent = (layoutAboutScopeBody.getParent() instanceof View) ? (View) layoutAboutScopeBody.getParent() : null;
        int availW = parent != null && parent.getWidth() > 0
                ? Math.max(1, parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight())
                : Math.max(1, getResources().getDisplayMetrics().widthPixels - dp(72));
        final boolean expanding = aboutScopeExpanded;
        final int startH = (layoutAboutScopeBody.getVisibility() == View.VISIBLE)
                ? layoutAboutScopeBody.getHeight() : 0;
        final int targetH;
        if (expanding) {
            layoutAboutScopeBody.setVisibility(View.VISIBLE);
            layoutAboutScopeBody.measure(
                    View.MeasureSpec.makeMeasureSpec(availW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            targetH = Math.max(1, layoutAboutScopeBody.getMeasuredHeight());
            ViewGroup.LayoutParams lp = layoutAboutScopeBody.getLayoutParams();
            if (lp != null) {
                lp.height = startH;
                layoutAboutScopeBody.setLayoutParams(lp);
            }
            if (startH == 0) layoutAboutScopeBody.setAlpha(0f);
        } else {
            targetH = 0;
        }

        final int denom = Math.max(1, expanding ? targetH : startH);
        ValueAnimator anim = ValueAnimator.ofInt(startH, targetH);
        anim.setDuration(expanding ? 260L : 240L);
        anim.setInterpolator(FLUID_EASE);
        anim.addUpdateListener(a -> {
            int curH = (Integer) a.getAnimatedValue();
            ViewGroup.LayoutParams lp = layoutAboutScopeBody.getLayoutParams();
            if (lp != null) {
                lp.height = curH;
                layoutAboutScopeBody.setLayoutParams(lp);
            }
            layoutAboutScopeBody.setAlpha(Math.max(0f, Math.min(1f, curH / (float) denom)));
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (aboutScopeAnimator == animation) {
                    aboutScopeAnimator = null;
                }
                if (expanding != aboutScopeExpanded) return;
                ViewGroup.LayoutParams lp = layoutAboutScopeBody.getLayoutParams();
                if (expanding) {
                    if (lp != null) {
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                        layoutAboutScopeBody.setLayoutParams(lp);
                    }
                    layoutAboutScopeBody.setAlpha(1f);
                } else {
                    layoutAboutScopeBody.setVisibility(View.GONE);
                    if (lp != null) {
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                        layoutAboutScopeBody.setLayoutParams(lp);
                    }
                    layoutAboutScopeBody.setAlpha(1f);
                }
            }
        });
        aboutScopeAnimator = anim;
        anim.start();
    }

    private void onQuickAncClicked(int ancMode) {
        headsetState = ConfigStore.readGlobalState(this);
        if (!headsetState.connected) {
            ColorOsLivingFlowEngine.performHaptic(layoutQuickAncCapsule, ColorOsLivingFlowEngine.HapticKind.TICK);
            Toast.makeText(this, R.string.toast_headset_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        ColorOsLivingFlowEngine.performHaptic(layoutQuickAncCapsule, ColorOsLivingFlowEngine.HapticKind.SWITCH);
        updateQuickAncSegment(ancMode, true);
        if (tvCurrentAncBadge != null) {
            int labelRes = (ancMode == ConfigStore.ANC_NC) ? R.string.mode_anc
                    : (ancMode == ConfigStore.ANC_AMBIENT ? R.string.mode_ambient : R.string.mode_off);
            tvCurrentAncBadge.setText(getString(R.string.readout_anc_format, getString(labelRes)));
        }
        ConfigStore.sendCommandBroadcast(this, ConfigStore.CMD_SET_ANC,
                headsetState.mac, ancMode, data.defaultAsmLevel, data.defaultVoiceFocus, false);
        mainHandler.postDelayed(this::refreshHeadsetStateUi, 260L);
    }

    private void updateQuickAncSegment(int ancMode, boolean animate) {
        if (btnAncNc == null || btnAncAmbient == null || btnAncOff == null) {
            return;
        }
        boolean connected = ancMode >= 0;
        if (layoutQuickAncCapsule != null) {
            layoutQuickAncCapsule.setAlpha(connected ? 1.0f : 0.55f);
        }
        int activeInk = getColor(R.color.lf_text_primary);
        int activeLabel = getColor(R.color.lf_text_primary);
        int inactiveColor = getColor(R.color.lf_nav_inactive);
        Typeface regTf = Typeface.DEFAULT;
        Typeface medTf = Typeface.create("sans-serif-medium", Typeface.NORMAL);

        applyAncModeCell(ivAncIconNc, tvAncLabelNc, ivAncRadioNc,
                connected && ancMode == ConfigStore.ANC_NC, activeInk, activeLabel, inactiveColor, medTf, regTf);
        applyAncModeCell(ivAncIconAmbient, tvAncLabelAmbient, ivAncRadioAmbient,
                connected && ancMode == ConfigStore.ANC_AMBIENT, activeInk, activeLabel, inactiveColor, medTf, regTf);
        applyAncModeCell(ivAncIconOff, tvAncLabelOff, ivAncRadioOff,
                connected && ancMode == ConfigStore.ANC_OFF, activeInk, activeLabel, inactiveColor, medTf, regTf);
    }

    private void applyAncModeCell(ImageView icon, TextView label, View radio, boolean selected,
                                  int activeInk, int activeLabel, int inactiveColor,
                                  Typeface medTf, Typeface regTf) {
        if (icon != null) {
            icon.setColorFilter(selected ? activeInk : inactiveColor);
        }
        if (label != null) {
            label.setTextColor(selected ? activeLabel : getColor(R.color.lf_text_secondary));
            label.setTypeface(selected ? medTf : regTf);
        }
        if (radio != null) {
            radio.setBackground(DynamicThemeHelper.createRadioIndicator(this, selected));
        }
    }

    private void updateBatteryGauge(View bar, int pct) {
        if (bar == null) return;
        bar.setTag(pct);
        View parent = (bar.getParent() instanceof View) ? (View) bar.getParent() : null;
        if (parent != null && bar.getTag(R.id.tvBatteryLeft) == null) {
            bar.setTag(R.id.tvBatteryLeft, Boolean.TRUE);
            parent.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if ((r - l) != (or - ol) && bar.getTag() instanceof Integer) {
                    applyBatteryBarWidth(bar, (Integer) bar.getTag(), v.getWidth());
                }
            });
        }
        int trackW = parent != null ? parent.getWidth() : 0;
        if (trackW > 0) {
            applyBatteryBarWidth(bar, pct, trackW);
        }
    }

    private void applyBatteryBarWidth(View bar, int pct, int trackW) {
        ViewGroup.LayoutParams lp = bar.getLayoutParams();
        if (lp == null) return;
        if (pct < 0 || pct > 100 || trackW <= 0) {
            if (lp.width != 0) {
                lp.width = 0;
                bar.setLayoutParams(lp);
            }
            return;
        }
        int targetW = Math.max(dp(4), Math.min(trackW, Math.round(trackW * (pct / 100f))));
        if (lp.width != targetW) {
            lp.width = targetW;
            bar.setLayoutParams(lp);
        }
        int color;
        if (pct <= 20) {
            color = getColor(R.color.lf_bat_low);
        } else if (pct <= 45) {
            color = getColor(R.color.lf_bat_mid);
        } else {
            color = getColor(R.color.lf_bat_good);
        }
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(999f * getResources().getDisplayMetrics().density);
        gd.setColor(color);
        bar.setBackground(gd);
    }

    private void updateHeroGlowAura() {
        if (vHeroGlowAura == null) return;
        boolean dark = ConfigStore.isDarkThemeActive(this);
        int baseColor;
        int alphaCenter;
        if (headsetState != null && headsetState.connected) {
            if (headsetState.ancMode == ConfigStore.ANC_NC) {
                baseColor = DynamicThemeHelper.getAccent(this);
                alphaCenter = dark ? 0x36 : 0x30;
            } else if (headsetState.ancMode == ConfigStore.ANC_AMBIENT) {
                baseColor = 0xFF2CB5C9;
                alphaCenter = dark ? 0x32 : 0x2B;
            } else {
                baseColor = DynamicThemeHelper.getAccent(this);
                alphaCenter = dark ? 0x22 : 0x1D;
            }
        } else {
            baseColor = dark ? 0xFFFFFFFF : DynamicThemeHelper.getAccent(this);
            alphaCenter = dark ? 0x16 : 0x16;
        }
        vHeroGlowAura.setBackground(LivingFlowEffects.createSmoothRadialAuraDrawable(baseColor, alphaCenter));
    }

    private void addDiagnosticRow(LinearLayout group, CouiBottomSheet.Palette pal,
                                  String label, String value, boolean addDivider) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));

        TextView kTv = new TextView(this);
        kTv.setText(label);
        kTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        kTv.setTextColor(pal.textSecondary);
        row.addView(kTv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView vTv = new TextView(this);
        vTv.setText(value);
        vTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        vTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        vTv.setTextColor(pal.textPrimary);
        vTv.setGravity(Gravity.END);
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vLp.setMarginStart(dp(12));
        row.addView(vTv, vLp);

        group.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (addDivider) {
            CouiBottomSheet.addDivider(this, group, pal, 14);
        }
    }

    private void showDiagnosticsDialog() {
        if (livingFlowUi) {
            LivingFlowDiagnosticsPage.show(this, xspActive, rootAvailable);
            return;
        }
        ConfigStore.HeadsetState st = ConfigStore.readGlobalState(this);
        String protoStr = "Tandem V" + st.protocolVersion
                + " (0x" + Integer.toHexString(st.ncAsmInquiredType)
                + ", S2C 0x" + Integer.toHexString(Math.max(0, st.speakToChatInquiredType)) + ")";
        String devStr = st.connected
                ? (st.effectiveModelName() + " · " + st.mac
                        + (st.colorCode >= 0 ? String.format(Locale.US, " · 0x%02X", st.colorCode) : ""))
                : getString(R.string.headset_disconnected_title);
        String batStr = "L=" + st.leftBattery + "% R=" + st.rightBattery
                + "% Case=" + st.caseBattery + "% S=" + st.singleBattery + "%";
        String modeStr = st.formatModeLabel(this)
                + " (ASM=" + st.asmLevel + ", VF=" + st.voiceFocus
                + ", S2C=" + st.speakToChat + ", DSEE=" + st.dsee + ")";
        String artStr = SonyModelImageManager.getCatalogCount(this)
                + (SonyModelImageManager.hasCustomOverride(this) ? " · Custom PNG" : "");

        StringBuilder fullDump = new StringBuilder();
        fullDump.append("Xposed Service: ").append(xspActive ? "Connected (API 102)" : "Not Connected").append('\n');
        fullDump.append("Device: ").append(devStr).append('\n');
        fullDump.append("Protocol: ").append(protoStr).append('\n');
        fullDump.append("Battery: ").append(batStr).append('\n');
        fullDump.append("Mode: ").append(modeStr).append('\n');
        fullDump.append("Catalog: ").append(artStr).append('\n');
        String recent = XposedCompat.dumpRecentLogs();
        if (!recent.isEmpty()) {
            fullDump.append("\n--- App Process Logs ---\n").append(recent);
        }
        final String copyText = fullDump.toString();

        CouiBottomSheet.Palette pal = CouiBottomSheet.Palette.fromModuleTheme(this);
        CouiBottomSheet.SheetHandle sheet = CouiBottomSheet.create(this, pal);
        final View.OnClickListener copyAction = v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("SonyPods Diagnostics", copyText));
                Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show();
            }
        };
        if (livingFlowUi) {
            CouiBottomSheet.addLivingFlowTopBar(
                    this, sheet,
                    getString(R.string.dialog_logs_title),
                    getString(R.string.btn_close),
                    null, 0, null);
        } else {
            CouiBottomSheet.addHeader(
                    this, sheet.card, pal,
                    getString(R.string.dialog_logs_title),
                    null,
                    xspActive ? "API 102" : "Offline");
        }

        ScrollView scroll = CouiBottomSheet.createMaxHeightScrollView(
                this, Math.round(getResources().getDisplayMetrics().heightPixels * (livingFlowUi ? 0.52f : 0.46f)));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!livingFlowUi) {
            scrollLp.topMargin = dp(14);
        }
        LinearLayout bodyCol = new LinearLayout(this);
        bodyCol.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(bodyCol);
        sheet.card.addView(scroll, scrollLp);

        LinearLayout infoCard = CouiBottomSheet.createSubcardGroup(this, pal);
        if (livingFlowUi) {
            addDiagnosticRow(infoCard, pal, "Xposed", xspActive ? "Connected (API 102)" : "Offline", true);
        }
        addDiagnosticRow(infoCard, pal, "Device", devStr, true);
        addDiagnosticRow(infoCard, pal, "Protocol", protoStr, true);
        addDiagnosticRow(infoCard, pal, "Battery", batStr, true);
        addDiagnosticRow(infoCard, pal, "Mode", modeStr, true);
        addDiagnosticRow(infoCard, pal, "Artwork", artStr, false);
        bodyCol.addView(infoCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (!recent.isEmpty()) {
            LinearLayout logCard = CouiBottomSheet.createSubcardGroup(this, pal);
            logCard.setPadding(dp(12), dp(10), dp(12), dp(10));
            TextView logTv = new TextView(this);
            logTv.setText(recent);
            logTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            logTv.setTypeface(Typeface.MONOSPACE);
            logTv.setTextColor(pal.textSecondary);
            logTv.setLineSpacing(0f, 1.25f);
            logCard.addView(logTv);
            LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            logLp.topMargin = dp(10);
            bodyCol.addView(logCard, logLp);
        }

        if (livingFlowUi) {
            CouiBottomSheet.addSingleButton(this, sheet.card, pal,
                    getString(R.string.btn_copy_logs), 1, 14, copyAction);
        } else {
            CouiBottomSheet.addButtonRow(
                    this, sheet.card, pal,
                    getString(R.string.btn_copy_logs), 0, copyAction,
                    getString(R.string.btn_close), 1, v -> sheet.dialog.dismiss());
        }
        sheet.dialog.show();
    }

    private void showResetAllDialog() {
        CouiBottomSheet.Palette pal = CouiBottomSheet.Palette.fromModuleTheme(this);
        CouiBottomSheet.SheetHandle sheet = CouiBottomSheet.create(this, pal);
        final View.OnClickListener resetAction = v -> {
            sheet.dialog.dismiss();
            data = ConfigStore.createDefaults();
            persistImmediate();
            refreshHeadsetStateUi();
            Toast.makeText(this, R.string.toast_reset_done, Toast.LENGTH_SHORT).show();
        };
        if (livingFlowUi) {
            CouiBottomSheet.addLivingFlowTopBar(
                    this, sheet,
                    getString(R.string.dialog_reset_all_title),
                    getString(R.string.btn_cancel),
                    getString(R.string.btn_confirm_reset), 2, resetAction);
            LinearLayout msgCard = CouiBottomSheet.createSubcardGroup(this, pal);
            msgCard.setPadding(dp(18), dp(18), dp(18), dp(18));
            TextView msgTv = new TextView(this);
            msgTv.setText(R.string.dialog_reset_all_msg);
            msgTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            msgTv.setLineSpacing(0f, 1.35f);
            msgTv.setTextColor(pal.textPrimary);
            msgCard.addView(msgTv);
            sheet.card.addView(msgCard, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            sheet.dialog.show();
            return;
        }
        CouiBottomSheet.addHeader(
                this, sheet.card, pal,
                getString(R.string.dialog_reset_all_title),
                getString(R.string.dialog_reset_all_msg),
                null);
        CouiBottomSheet.addButtonRow(
                this, sheet.card, pal,
                getString(R.string.btn_cancel), 0, v -> sheet.dialog.dismiss(),
                getString(R.string.btn_confirm_reset), 2, resetAction);
        sheet.dialog.show();
    }

    private void syncUiState() {
        xspActive = XposedProvider.isConnected();
        suppressUiCallbacks = true;
        try {
            if (xspActive) {
                if (ivStatusIcon != null) ivStatusIcon.setImageResource(R.drawable.ic_status_active);
                if (tvStatusTitle != null) tvStatusTitle.setText(R.string.status_title_active);
                if (tvStatusSubtitle != null) tvStatusSubtitle.setText(R.string.status_sub_active);
                if (tvAboutChannel != null) tvAboutChannel.setText(R.string.channel_xsp);
            } else {
                if (ivStatusIcon != null) ivStatusIcon.setImageResource(R.drawable.ic_status_warn);
                if (tvStatusTitle != null) tvStatusTitle.setText(R.string.status_title_inactive);
                if (tvStatusSubtitle != null) tvStatusSubtitle.setText(R.string.status_sub_inactive);
                if (tvAboutChannel != null) tvAboutChannel.setText(R.string.channel_private_warn);
            }
            if (bannerModuleInactive != null) {
                bannerModuleInactive.setVisibility(xspActive ? View.GONE : View.VISIBLE);
            }
            if (cardAboutModuleStatus != null) {
                boolean dark = ConfigStore.isDarkThemeActive(this);
                int accent = DynamicThemeHelper.getAccent(this);
                int statusColor = xspActive ? accent : getColor(R.color.coui_status_warn_fg);
                int emblemFill = xspActive
                        ? (((dark ? 0x26 : 0x16) << 24) | (accent & 0x00FFFFFF))
                        : (dark ? 0x28FBBF24 : 0x1AD97706);
                if (vAboutStatusEmblem != null) {
                    GradientDrawable emblemBg = new GradientDrawable();
                    emblemBg.setShape(GradientDrawable.OVAL);
                    emblemBg.setColor(emblemFill);
                    vAboutStatusEmblem.setBackground(emblemBg);
                }
                if (ivAboutStatusIcon != null) {
                    ivAboutStatusIcon.setImageResource(xspActive ? R.drawable.ic_status_active : R.drawable.ic_status_warn);
                    ivAboutStatusIcon.setColorFilter(statusColor);
                }
                if (tvAboutStatusTitle != null) {
                    tvAboutStatusTitle.setText(xspActive ? R.string.status_title_active : R.string.status_title_inactive);
                }
                if (tvAboutStatusBadge != null) {
                    tvAboutStatusBadge.setTextColor(statusColor);
                    GradientDrawable badgeBg = new GradientDrawable();
                    badgeBg.setShape(GradientDrawable.RECTANGLE);
                    badgeBg.setCornerRadius(999f * getResources().getDisplayMetrics().density);
                    badgeBg.setColor(emblemFill);
                    tvAboutStatusBadge.setBackground(badgeBg);
                }
                if (tvAboutStatusSubtitle != null) {
                    tvAboutStatusSubtitle.setText(xspActive
                            ? getString(R.string.about_status_sub_active_fmt, getInstalledVersionName())
                            : getString(R.string.status_sub_inactive));
                }
            }

            String cycleLabel = getString(data.cycleMode == ConfigStore.CYCLE_2WAY
                    ? R.string.cycle_mode_2way : R.string.cycle_mode_3way);
            if (!cycleLabel.startsWith("ANC")) {
                cycleLabel = cycleLabel.replace(" ⇄ ", "⇄");
            }
            tvCycleModeVal.setText(cycleLabel);
            swHandover.setChecked(data.handover);

            int catCount = SonyModelImageManager.getCatalogCount(this);
            tvCatalogStatus.setText(catCount > 0
                    ? getString(R.string.pref_sync_catalog_sub_count, catCount)
                    : getString(R.string.pref_sync_catalog_sub_empty));
            tvCustomArtworkStatus.setText(SonyModelImageManager.hasCustomOverride(this)
                    ? R.string.pref_custom_artwork_sub_set : R.string.pref_custom_artwork_sub_none);
            tvModelOverrideVal.setText(data.customModelOverride != null && !data.customModelOverride.trim().isEmpty()
                    ? data.customModelOverride.trim() : getString(R.string.model_override_auto));

            swBridgeEnabled.setChecked(data.bridgeEnabled);
            swMyDevicesPanel.setChecked(data.myDevicesPanel);
            swNativeArtwork.setChecked(data.nativeArtwork);
            swFluidCloudCapsule.setChecked(data.fluidCloudCapsule);
            swQsTile.setChecked(data.qsTile);
            swVendorBypass.setChecked(data.vendorBypass);
            swMelodyChannel.setChecked(data.melodyChannel);
            swOpenCasePopup.setChecked(data.openCasePopup);
            tvOpenPopupModeVal.setText(data.openCasePopupMode == ConfigStore.POPUP_MODE_PAIRING_AND_CONNECT
                    ? R.string.popup_mode_pairing_and_connect : R.string.popup_mode_pairing_only);
            int modeVis = data.openCasePopup ? View.VISIBLE : View.GONE;
            divOpenPopupMode.setVisibility(modeVis);
            rowOpenPopupMode.setVisibility(modeVis);
            swSuppressNativePairing.setChecked(data.suppressNativePairing);

            if (tvAboutUiStyleVal != null) {
                tvAboutUiStyleVal.setText(ConfigStore.isLivingFlowUi(this)
                        ? R.string.ui_style_living_flow : R.string.ui_style_classic);
            }
            tvAboutLanguageVal.setText(getLanguageLabel(ConfigStore.getAppLanguage(this)));

            int dm = ConfigStore.getAppDarkMode(this);
            if (dm == ConfigStore.DARK_MODE_LIGHT) tvAboutDarkModeVal.setText(R.string.dark_mode_light);
            else if (dm == ConfigStore.DARK_MODE_DARK) tvAboutDarkModeVal.setText(R.string.dark_mode_dark);
            else tvAboutDarkModeVal.setText(R.string.dark_mode_system);

            swAboutMonet.setChecked(ConfigStore.isMonetEnabled(this));
            swAboutDebugMode.setChecked(data.debugMode);
        } finally {
            suppressUiCallbacks = false;
        }
    }

    private void persistImmediate() {
        hadLocalKeys = true;
        mainHandler.removeCallbacks(debouncedSync);
        ConfigStore.saveLocal(prefs, data);
        syncUiState();
        syncRemoteAsync();
    }

    private void persistSliderTick() {
        hadLocalKeys = true;
        ConfigStore.saveLocal(prefs, data);
        syncUiState();
        mainHandler.removeCallbacks(debouncedSync);
        mainHandler.postDelayed(debouncedSync, SLIDER_SYNC_DELAY_MS);
    }

    private void syncRemoteAsync() {
        final ConfigStore.SettingsData snapshot = data.copy();
        final boolean tryRestore = !hadLocalKeys && !remoteRestoreTried;
        if (XposedProvider.isConnected() && tryRestore) {
            remoteRestoreTried = true;
        }
        final int gen = syncGeneration.incrementAndGet();
        syncExecutor.execute(() -> {
            if (gen != syncGeneration.get()) return;
            if (tryRestore && XposedProvider.isConnected()) {
                Map<String, Object> remote = XposedProvider.fetchRemotePrefs(ConfigStore.PREFS_NAME);
                if (ConfigStore.hasAnySetting(remote)) {
                    final ConfigStore.SettingsData restored = ConfigStore.loadFromMap(remote);
                    runOnUiThread(() -> {
                        if (!hadLocalKeys) {
                            hadLocalKeys = true;
                            data = restored;
                            ConfigStore.saveLocal(prefs, data);
                            syncUiState();
                        }
                    });
                    return;
                }
            }
            ConfigStore.pushRemote(snapshot);
            runOnUiThread(() -> {
                if (gen == syncGeneration.get()) {
                    xspActive = XposedProvider.isConnected();
                    syncUiState();
                }
            });
        });
    }

    private void switchTab(final int targetTab, boolean animate) {
        switchTab(targetTab, animate, false);
    }

    private void switchTab(final int targetTab, boolean animate, boolean fromNavDrag) {
        if (targetTab < TAB_HEADSET || targetTab > TAB_ABOUT) return;
        if (animate && targetTab == activeTab) return;
        final int oldTab = activeTab;
        final int dir = (targetTab >= oldTab) ? 1 : -1;
        activeTab = targetTab;

        if (animate && oldTab != targetTab) {
            final float dist = (layoutPageContainer != null && layoutPageContainer.getWidth() > 0)
                    ? layoutPageContainer.getWidth() * 0.28f : dp(96);
            for (int i = 0; i < pages.length; i++) {
                final int idx = i;
                final View p = pages[i];
                p.animate().cancel();
                if (i == targetTab) {
                    p.setVisibility(View.VISIBLE);
                    p.setAlpha(0f);
                    p.setTranslationX(dir * dist);
                    if (livingFlowUi) {
                        p.setScaleX(0.97f);
                        p.setScaleY(0.97f);
                        p.animate().translationX(0f).alpha(1f).scaleX(1f).scaleY(1f)
                                .setDuration(320L).setInterpolator(FLUID_EASE)
                                .setUpdateListener(anim -> {
                                    if (vTopHeaderBlur != null) {
                                        vTopHeaderBlur.notifyTargetInvalidated();
                                    }
                                }).start();
                    } else {
                        p.setScaleX(1f);
                        p.setScaleY(1f);
                        p.animate().translationX(0f).alpha(1f).setDuration(300L).setInterpolator(FLUID_EASE)
                                .setUpdateListener(anim -> {
                                    if (vTopHeaderBlur != null) {
                                        vTopHeaderBlur.notifyTargetInvalidated();
                                    }
                                }).start();
                    }
                } else if (i == oldTab) {
                    p.setVisibility(View.VISIBLE);
                    if (livingFlowUi) {
                        p.animate().translationX(-dir * dist).alpha(0f).scaleX(0.985f).scaleY(0.985f)
                                .setDuration(280L).setInterpolator(FLUID_EASE)
                                .withEndAction(() -> {
                                    if (activeTab != idx) {
                                        p.setVisibility(View.GONE);
                                        p.setTranslationX(0f);
                                        p.setAlpha(1f);
                                        p.setScaleX(1f);
                                        p.setScaleY(1f);
                                    }
                                }).start();
                    } else {
                        p.animate().translationX(-dir * dist).alpha(0f).setDuration(270L).setInterpolator(FLUID_EASE)
                                .withEndAction(() -> {
                                    if (activeTab != idx) {
                                        p.setVisibility(View.GONE);
                                        p.setTranslationX(0f);
                                        p.setAlpha(1f);
                                        p.setScaleX(1f);
                                        p.setScaleY(1f);
                                    }
                                }).start();
                    }
                } else {
                    p.setVisibility(View.GONE);
                    p.setTranslationX(0f);
                    p.setAlpha(1f);
                    p.setScaleX(1f);
                    p.setScaleY(1f);
                }
            }
        } else {
            for (int i = 0; i < pages.length; i++) {
                pages[i].animate().cancel();
                pages[i].setTranslationX(0f);
                pages[i].setAlpha(1f);
                pages[i].setScaleX(1f);
                pages[i].setScaleY(1f);
                pages[i].setVisibility(i == targetTab ? View.VISIBLE : View.GONE);
            }
            if (vTopHeaderBlur != null) {
                vTopHeaderBlur.notifyTargetInvalidated();
            }
        }

        if (targetTab == TAB_HEADSET) {
            tvPageTitle.setText(R.string.page_title_headset);
            tvPageSubtitle.setText(R.string.page_headset_sub_v2);
        } else if (targetTab == TAB_INTEGRATION) {
            tvPageTitle.setText(R.string.page_title_integration);
            tvPageSubtitle.setText(R.string.page_subtitle_integration);
        } else {
            tvPageTitle.setText(R.string.page_title_about);
            tvPageSubtitle.setText(R.string.page_subtitle_about);
        }

        int targetScrollY = pages[targetTab] != null ? pages[targetTab].getScrollY() : 0;
        boolean animHeader = animate && oldTab != targetTab;
        if (subtitleTabFadeAnimator != null) {
            subtitleTabFadeAnimator.cancel();
            subtitleTabFadeAnimator = null;
        }
        if (livingFlowUi) {
            if (animHeader) {
                tvPageTitle.animate().cancel();
                tvPageTitle.setTranslationX(dir * dp(12));
                tvPageTitle.animate().translationX(0f).setDuration(260L).setInterpolator(FLUID_EASE).start();

                tvPageSubtitle.animate().cancel();
                tvPageSubtitle.setTranslationX(dir * dp(10));
                tvPageSubtitle.animate().translationX(0f).setDuration(260L).setInterpolator(FLUID_EASE).start();

                headerTitleTabAlpha = 0.25f;
                subtitleTabAlpha = 0.25f;
                syncHeaderToScrollY(targetScrollY, true);
                subtitleTabFadeAnimator = ValueAnimator.ofFloat(0.25f, 1f);
                subtitleTabFadeAnimator.setDuration(260L);
                subtitleTabFadeAnimator.setInterpolator(FLUID_EASE);
                subtitleTabFadeAnimator.addUpdateListener(anim -> {
                    float a = (Float) anim.getAnimatedValue();
                    headerTitleTabAlpha = a;
                    subtitleTabAlpha = a;
                    applyHeaderCollapseProgress(headerCollapseProgress);
                });
                subtitleTabFadeAnimator.start();
            } else {
                tvPageTitle.setTranslationX(0f);
                tvPageSubtitle.setTranslationX(0f);
                headerTitleTabAlpha = 1f;
                subtitleTabAlpha = 1f;
                syncHeaderToScrollY(targetScrollY, false);
            }
        } else if (animHeader) {
            tvPageTitle.animate().cancel();
            tvPageTitle.setTranslationX(dir * dp(18));
            tvPageTitle.setAlpha(0.25f);
            tvPageTitle.animate().translationX(0f).alpha(1f).setDuration(260L).setInterpolator(FLUID_EASE).start();

            tvPageSubtitle.animate().cancel();
            tvPageSubtitle.setTranslationX(dir * dp(14));
            tvPageSubtitle.animate().translationX(0f).setDuration(260L).setInterpolator(FLUID_EASE).start();

            subtitleTabAlpha = 0.25f;
            syncHeaderToScrollY(targetScrollY, true);
            subtitleTabFadeAnimator = ValueAnimator.ofFloat(0.25f, 1f);
            subtitleTabFadeAnimator.setDuration(260L);
            subtitleTabFadeAnimator.setInterpolator(FLUID_EASE);
            subtitleTabFadeAnimator.addUpdateListener(anim -> {
                subtitleTabAlpha = (Float) anim.getAnimatedValue();
                if (tvPageSubtitle != null) {
                    tvPageSubtitle.setAlpha(computeSubtitleAlpha(headerCollapseProgress) * subtitleTabAlpha);
                }
            });
            subtitleTabFadeAnimator.start();
        } else {
            tvPageTitle.setTranslationX(0f);
            tvPageTitle.setAlpha(1f);
            tvPageSubtitle.setTranslationX(0f);
            subtitleTabAlpha = 1f;
            syncHeaderToScrollY(targetScrollY, false);
        }
        updateNavIndicator(oldTab, targetTab, dir, animate, fromNavDrag);
        if (livingFlowUi && vAboutDynamicBg != null) {
            if (targetTab == TAB_ABOUT) {
                playAboutHeroEntrance(animate && oldTab != targetTab);
            } else {
                vAboutDynamicBg.pausePlayback();
            }
        }
    }

    private void playAboutHeroEntrance(boolean animateLogo) {
        if (vAboutDynamicBg != null) {
            vAboutDynamicBg.replayIfIdle();
        }
        if (layoutAboutHeroLogo == null) return;
        layoutAboutHeroLogo.animate().cancel();
        if (animateLogo) {
            layoutAboutHeroLogo.setScaleX(0.86f);
            layoutAboutHeroLogo.setScaleY(0.86f);
            layoutAboutHeroLogo.setAlpha(0.25f);
            layoutAboutHeroLogo.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(650L)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.30f, 0f, 0.10f, 1f))
                    .start();
        } else {
            layoutAboutHeroLogo.setScaleX(1f);
            layoutAboutHeroLogo.setScaleY(1f);
            layoutAboutHeroLogo.setAlpha(1f);
        }
    }

    private void ensureNavPillWidth(int tabIdx) {
        if (vNavSlidingPill == null || navTabs[tabIdx] == null) return;
        int tabW = navTabs[tabIdx].getWidth();
        if (tabW > 0 && vNavSlidingPill.getWidth() != tabW) {
            ViewGroup.LayoutParams lp = vNavSlidingPill.getLayoutParams();
            if (lp != null && lp.width != tabW) {
                lp.width = tabW;
                vNavSlidingPill.setLayoutParams(lp);
            }
        }
    }

    private void setupBottomNavTouch() {
        if (layoutBottomNav == null) return;
        layoutBottomNav.setOnTouchListener((v, ev) -> {
            if (layoutNavItems == null || (!livingFlowUi && vNavSlidingPill == null)
                    || navTabs[0] == null || navTabs[2] == null) {
                return false;
            }
            if (livingFlowUi) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        if (navSpotLight != null) {
                            navSpotLight.onPress(ev.getX(), ev.getY());
                        }
                        if (navElasticController != null) {
                            navElasticController.onDown();
                        }
                        ColorOsLivingFlowEngine.performHaptic(layoutBottomNav, ColorOsLivingFlowEngine.HapticKind.TAP);
                        navDownRawX = ev.getRawX();
                        navDownRawY = ev.getRawY();
                        navDragging = false;
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        if (navSpotLight != null) {
                            navSpotLight.onMove(ev.getX(), ev.getY());
                        }
                        float dx = ev.getRawX() - navDownRawX;
                        float dy = ev.getRawY() - navDownRawY;
                        if (navElasticController != null) {
                            navElasticController.onMove(dx, dy);
                        }
                        if (!navDragging && Math.hypot(dx, dy) > Math.max(dp(5), touchSlop * 0.5f)) {
                            navDragging = true;
                        }
                        int itemsW = layoutNavItems.getWidth();
                        if (navDragging && itemsW > 0) {
                            float localX = Math.max(0f, Math.min(itemsW - 1f, ev.getX() - layoutNavItems.getLeft()));
                            int hoveredTab = Math.max(0, Math.min(2, (int) (localX / Math.max(1f, itemsW / 3f))));
                            if (hoveredTab != activeTab) {
                                ColorOsLivingFlowEngine.performHaptic(
                                        layoutBottomNav, ColorOsLivingFlowEngine.HapticKind.TICK);
                                switchTab(hoveredTab, true, true);
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        if (navSpotLight != null) {
                            navSpotLight.onRelease();
                        }
                        if (navElasticController != null) {
                            navElasticController.onRelease();
                        }
                        int itemsW = layoutNavItems.getWidth();
                        if (!navDragging && ev.getActionMasked() == MotionEvent.ACTION_UP && itemsW > 0) {
                            float localX = Math.max(0f, Math.min(itemsW - 1f, ev.getX() - layoutNavItems.getLeft()));
                            int tappedTab = Math.max(0, Math.min(2, (int) (localX / Math.max(1f, itemsW / 3f))));
                            if (tappedTab != activeTab) {
                                ColorOsLivingFlowEngine.performHaptic(
                                        layoutBottomNav, ColorOsLivingFlowEngine.HapticKind.TICK);
                            }
                            switchTab(tappedTab, true, false);
                        }
                        navDragging = false;
                        return true;
                    }
                }
                return false;
            }
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    if (vNavSlidingPill != null) {
                        vNavSlidingPill.animate().cancel();
                    }
                    layoutBottomNav.animate().cancel();
                    if (navPillMorphAnimator != null) {
                        navPillMorphAnimator.cancel();
                        navPillMorphAnimator = null;
                    }
                    if (!livingFlowUi) {
                        ensureNavPillWidth(0);
                    }
                    int[] loc = new int[2];
                    layoutNavItems.getLocationOnScreen(loc);
                    navItemsRestLeftScreenX = loc[0] - layoutBottomNav.getTranslationX();
                    navItemsWidth = layoutNavItems.getWidth();
                    int tabW = navTabs[0].getWidth();
                    navPillWidth = tabW > 0 ? tabW : (vNavSlidingPill != null ? vNavSlidingPill.getWidth() : 0);
                    navMinPillX = navTabs[0].getLeft();
                    navMaxPillX = navTabs[2].getLeft();
                    navDownRawX = ev.getRawX();
                    navDownRawY = ev.getRawY();
                    lastNavRawX = navDownRawX;
                    lastNavEventMs = ev.getEventTime();
                    navDragVx = 0f;
                    navDragging = false;
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    float rawX = ev.getRawX();
                    float rawY = ev.getRawY();
                    long nowMs = ev.getEventTime();
                    float dtSec = (nowMs - lastNavEventMs) / 1000f;
                    if (dtSec >= 0.002f && dtSec <= 0.12f) {
                        float instVx = (rawX - lastNavRawX) / dtSec;
                        navDragVx = navDragVx * 0.45f + instVx * 0.55f;
                    }
                    lastNavRawX = rawX;
                    lastNavEventMs = nowMs;

                    float dxDown = rawX - navDownRawX;
                    float dyDown = rawY - navDownRawY;
                    if (!navDragging && Math.hypot(dxDown, dyDown) > Math.max(dp(5), touchSlop * 0.5f)) {
                        navDragging = true;
                        if (!livingFlowUi && vNavSlidingPill != null) {
                            vNavSlidingPill.animate().scaleX(1.04f).scaleY(1.04f).setDuration(110L).start();
                        }
                    }
                    if (navDragging && navItemsWidth > 0f) {
                        float fingerLocalX = rawX - navItemsRestLeftScreenX;
                        float desiredPillX = fingerLocalX - navPillWidth * 0.5f;
                        float maxPull = dp(10);
                        float resistDenom = dp(160);
                        float pullX = 0f;
                        if (desiredPillX < navMinPillX) {
                            float normX = (navMinPillX - desiredPillX) / resistDenom;
                            pullX = -maxPull * (normX / (1.0f + normX));
                            if (!livingFlowUi && vNavSlidingPill != null) {
                                vNavSlidingPill.setTranslationX(navMinPillX);
                            }
                        } else if (desiredPillX > navMaxPillX) {
                            float normX = (desiredPillX - navMaxPillX) / resistDenom;
                            pullX = maxPull * (normX / (1.0f + normX));
                            if (!livingFlowUi && vNavSlidingPill != null) {
                                vNavSlidingPill.setTranslationX(navMaxPillX);
                            }
                        } else if (!livingFlowUi && vNavSlidingPill != null) {
                            vNavSlidingPill.setTranslationX(desiredPillX);
                        }
                        float normY = Math.abs(dyDown) / resistDenom;
                        float pullY = (dyDown < 0f ? -1f : 1f) * maxPull * (normY / (1.0f + normY));
                        float stretchX = Math.abs(pullX) / maxPull;
                        float stretchY = Math.abs(pullY) / maxPull;
                        layoutBottomNav.setTranslationX(pullX);
                        layoutBottomNav.setTranslationY(pullY);
                        layoutBottomNav.setScaleX(1.0f + 0.014f * stretchX - 0.008f * stretchY);
                        layoutBottomNav.setScaleY(1.0f + 0.014f * stretchY - 0.008f * stretchX);

                        float clampedLocal = Math.max(0f, Math.min(navItemsWidth - 1f, fingerLocalX));
                        int hoveredTab = Math.max(0, Math.min(2, (int) (clampedLocal / Math.max(1f, navItemsWidth / 3f))));
                        if (hoveredTab != activeTab) {
                            layoutBottomNav.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                            switchTab(hoveredTab, true, true);
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!navDragging) {
                        if (ev.getActionMasked() == MotionEvent.ACTION_UP && navItemsWidth > 0f) {
                            float localX = ev.getRawX() - navItemsRestLeftScreenX;
                            int tappedTab = Math.max(0, Math.min(2, (int) (localX / Math.max(1f, navItemsWidth / 3f))));
                            switchTab(tappedTab, true, false);
                        }
                    } else {
                        navDragging = false;
                        if (!livingFlowUi && vNavSlidingPill != null) {
                            ensureNavPillWidth(activeTab);
                            float targetX = navTabs[activeTab].getLeft();
                            vNavSlidingPill.animate().translationX(targetX).translationY(0f)
                                    .scaleX(1.0f).scaleY(1.0f).setDuration(240L).setInterpolator(FLUID_EASE).start();
                        }
                        layoutBottomNav.animate().translationX(0f).translationY(0f)
                                .scaleX(1.0f).scaleY(1.0f).setDuration(280L).setInterpolator(NAV_SPRING_BACK).start();
                    }
                    return true;
                }
            }
            return false;
        });
    }

    private static int getLivingFlowNavIconRes(int index, boolean selected) {
        if (index == TAB_HEADSET) {
            return selected ? R.drawable.ic_lf_nav_headset_filled : R.drawable.ic_lf_nav_headset_outline;
        } else if (index == TAB_INTEGRATION) {
            return selected ? R.drawable.ic_lf_nav_integration_filled : R.drawable.ic_lf_nav_integration_outline;
        }
        return selected ? R.drawable.ic_lf_nav_about_filled : R.drawable.ic_lf_nav_about_outline;
    }

    private void updateNavIndicator(final int oldTab, final int targetTab, final int dir,
                                    final boolean animate, final boolean fromNavDrag) {
        int activeColor = livingFlowUi
                ? getColor(R.color.lf_nav_active)
                : DynamicThemeHelper.getAccent(this);
        int secondary = livingFlowUi
                ? getColor(R.color.lf_nav_inactive)
                : ConfigStore.resolveThemeColor(this, R.attr.couiTextSecondary, R.color.coui_text_secondary);

        for (int i = 0; i < 3; i++) {
            boolean selected = (i == targetTab);
            if (livingFlowUi) {
                navIcons[i].setImageResource(getLivingFlowNavIconRes(i, selected));
                navIcons[i].setColorFilter(selected ? activeColor : secondary);
                navLabels[i].setTextColor(selected ? activeColor : secondary);
                navLabels[i].setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
                navIcons[i].animate().cancel();
                navLabels[i].animate().cancel();
                navIcons[i].setTranslationX(0f);
                navIcons[i].setTranslationY(0f);
                navIcons[i].setScaleX(1.0f);
                navIcons[i].setScaleY(1.0f);
                navLabels[i].setTranslationX(0f);
                navLabels[i].setAlpha(1.0f);
                continue;
            }
            navIcons[i].setColorFilter(selected ? activeColor : secondary);
            navLabels[i].setTextColor(selected ? activeColor : secondary);
            navLabels[i].setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            navIcons[i].animate().cancel();
            navLabels[i].animate().cancel();
            if (animate && oldTab != targetTab) {
                if (selected) {
                    navIcons[i].setTranslationX(-dir * dp(10));
                    navIcons[i].setTranslationY(dp(2));
                    navIcons[i].setScaleX(0.84f);
                    navIcons[i].setScaleY(0.84f);
                    navIcons[i].animate().translationX(0f).translationY(-dp(1))
                            .scaleX(1.08f).scaleY(1.08f).setDuration(320L).setInterpolator(ICON_SPRING).start();
                    navLabels[i].setTranslationX(-dir * dp(6));
                    navLabels[i].setAlpha(0.55f);
                    navLabels[i].animate().translationX(0f).alpha(1.0f)
                            .setDuration(260L).setInterpolator(FLUID_EASE).start();
                } else {
                    if (i == oldTab) navIcons[i].setTranslationX(-dir * dp(4));
                    navIcons[i].animate().translationX(0f).translationY(0f)
                            .scaleX(1.0f).scaleY(1.0f).setDuration(240L).setInterpolator(FLUID_EASE).start();
                    navLabels[i].setTranslationX(0f);
                    navLabels[i].setAlpha(1.0f);
                }
            } else {
                navIcons[i].setTranslationX(0f);
                navIcons[i].setTranslationY(selected ? -dp(1) : 0f);
                navIcons[i].setScaleX(selected ? 1.08f : 1.0f);
                navIcons[i].setScaleY(selected ? 1.08f : 1.0f);
                navLabels[i].setTranslationX(0f);
                navLabels[i].setAlpha(1.0f);
            }
        }

        if (livingFlowUi || fromNavDrag || vNavSlidingPill == null || navTabs[targetTab] == null) return;
        if (navTabs[targetTab].getWidth() == 0 || vNavSlidingPill.getWidth() == 0) {
            vNavSlidingPill.post(() -> updateNavIndicator(oldTab, targetTab, dir, false, false));
            return;
        }
        ensureNavPillWidth(targetTab);
        float targetX = navTabs[targetTab].getLeft();
        vNavSlidingPill.animate().cancel();
        if (navPillMorphAnimator != null) {
            navPillMorphAnimator.cancel();
            navPillMorphAnimator = null;
        }
        if (animate && oldTab != targetTab) {
            vNavSlidingPill.animate().translationX(targetX).translationY(0f)
                    .setDuration(300L).setInterpolator(FLUID_EASE).start();
            navPillMorphAnimator = ValueAnimator.ofFloat(0f, 1f);
            navPillMorphAnimator.setDuration(300L);
            navPillMorphAnimator.setInterpolator(FLUID_EASE);
            navPillMorphAnimator.addUpdateListener(anim -> {
                float p = (Float) anim.getAnimatedValue();
                double wave = Math.sin(Math.PI * p);
                vNavSlidingPill.setScaleX((float) (1.0 + 0.10 * wave));
                vNavSlidingPill.setScaleY((float) (1.0 - 0.05 * wave));
            });
            navPillMorphAnimator.start();
        } else {
            vNavSlidingPill.setTranslationX(targetX);
            vNavSlidingPill.setTranslationY(0f);
            vNavSlidingPill.setScaleX(1.0f);
            vNavSlidingPill.setScaleY(1.0f);
        }
    }

    private static void setEnabledRecursive(ViewGroup vg, boolean enabled) {
        vg.setEnabled(enabled);
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            child.setEnabled(enabled);
            if (child instanceof ViewGroup) {
                setEnabledRecursive((ViewGroup) child, enabled);
            }
        }
    }

    private String getLanguageLabel(String lang) {
        if (ConfigStore.LANG_ZH_CN.equals(lang)) return getString(R.string.lang_zh_cn);
        if (ConfigStore.LANG_EN.equals(lang)) return getString(R.string.lang_en);
        if (ConfigStore.LANG_ZH_TW.equals(lang)) return getString(R.string.lang_zh_tw);
        return getString(R.string.lang_system);
    }

    private void openUrl(String url) {
        if (!tryStartView(url)) {
            Toast.makeText(this, R.string.toast_open_link_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void openCoolapkProfile(String uid) {
        if (tryStartView(COOLAPK_DEEP_PREFIX + uid)) {
            return;
        }
        if (!tryStartView(COOLAPK_WEB_PREFIX + uid)) {
            Toast.makeText(this, R.string.toast_open_link_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean tryStartView(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private void applyLocaleToResources() {
        String lang = ConfigStore.getEffectiveAppLanguage(this);
        Locale locale = resolveLocale(lang);
        Locale.setDefault(locale);
        Resources res = getResources();
        Configuration cfg = new Configuration(res.getConfiguration());
        cfg.setLocale(locale);
        applyDarkModeToConfig(cfg, ConfigStore.getAppDarkMode(this));
        res.updateConfiguration(cfg, res.getDisplayMetrics());
    }

    public static void applyDarkModeToConfig(Configuration cfg, int darkMode) {
        if (darkMode == ConfigStore.DARK_MODE_LIGHT) {
            cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_NO;
        } else if (darkMode == ConfigStore.DARK_MODE_DARK) {
            cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        }
    }

    public static Locale resolveLocale(String lang) {
        if (ConfigStore.LANG_ZH_CN.equals(lang)) return Locale.SIMPLIFIED_CHINESE;
        if (ConfigStore.LANG_EN.equals(lang)) return Locale.ENGLISH;
        return Locale.TRADITIONAL_CHINESE;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showChangelogDialog() {
        if (livingFlowUi) {
            LivingFlowUpdatePages.showChangelogPage(this);
            return;
        }
        CouiBottomSheet.Palette pal = CouiBottomSheet.Palette.fromModuleTheme(this);
        CouiBottomSheet.SheetHandle sheet = CouiBottomSheet.createCenter(this, pal);
        CouiBottomSheet.addHeader(
                this, sheet.card, pal,
                getString(R.string.dialog_changelog_title),
                null,
                formatVerLabel(getInstalledVersionName()));

        ScrollView scroll = CouiBottomSheet.createMaxHeightScrollView(
                this, Math.round(getResources().getDisplayMetrics().heightPixels * 0.46f));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollLp.topMargin = dp(14);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        sheet.card.addView(scroll, scrollLp);

        int[] verTitles = new int[]{
                R.string.changelog_v104_title,
                R.string.changelog_v103_title,
                R.string.changelog_v102_title,
                R.string.changelog_v101_title,
                R.string.changelog_v100_title
        };
        int[] verBodies = new int[]{
                R.string.changelog_v104_body,
                R.string.changelog_v103_body,
                R.string.changelog_v102_body,
                R.string.changelog_v101_body,
                R.string.changelog_v100_body
        };
        for (int i = 0; i < verTitles.length; i++) {
            addChangelogItem(list, pal, getString(verTitles[i]), getString(verBodies[i]), i < verTitles.length - 1);
        }

        CouiBottomSheet.addSingleButton(this, sheet.card, pal,
                getString(R.string.btn_close), 1, 16, v -> sheet.dialog.dismiss());
        sheet.dialog.show();
    }

    private void addChangelogItem(LinearLayout parent, CouiBottomSheet.Palette pal,
                                  String verTitle, CharSequence bodyText, boolean addBottomMargin) {
        LinearLayout card = CouiBottomSheet.createSubcardGroup(this, pal);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView badge = CouiBottomSheet.createChipBadge(this, pal, verTitle, true, null);
        badge.setClickable(false);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        badge.setPadding(dp(9), dp(3), dp(9), dp(3));
        card.addView(badge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView body = new TextView(this);
        body.setText(bodyText);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        body.setLineSpacing(0f, 1.35f);
        body.setTextColor(pal.textSecondary);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dp(8);
        card.addView(body, bodyLp);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (addBottomMargin) {
            cardLp.bottomMargin = dp(10);
        }
        parent.addView(card, cardLp);
    }

    interface UpdateCheckCallback {
        void onResult(RepoReleaseInfo info);
    }

    static final class RepoReleaseInfo {
        int versionCode;
        String versionName;
        String changelogRaw;
        boolean isHtmlChangelog;
        String downloadUrl;
        String repoPageUrl;
    }

    static RepoReleaseInfo getCachedLatestRelease() {
        return sCachedLatestRelease;
    }

    void openRepoOrUrl(String url) {
        openUrl(url);
    }

    void openRepoPage(RepoReleaseInfo info) {
        String targetUrl = (info != null && info.repoPageUrl != null && !info.repoPageUrl.isEmpty())
                ? info.repoPageUrl : LSPOSED_REPO_PAGE_URL;
        openUrl(targetUrl);
    }

    int getInstalledVersionCode() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) {
                return (int) pi.getLongVersionCode();
            }
            //noinspection deprecation
            return pi.versionCode;
        } catch (Throwable ignored) {
            return 1004;
        }
    }

    String getInstalledVersionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (pi.versionName != null && !pi.versionName.trim().isEmpty()) {
                return pi.versionName.trim();
            }
        } catch (Throwable ignored) {
        }
        return "1.0.4";
    }

    static String formatVerLabel(String ver) {
        if (ver == null || ver.trim().isEmpty()) {
            return "v1.0.4";
        }
        String t = ver.trim();
        if (t.startsWith("v") || t.startsWith("V")) {
            return "v" + t.substring(1);
        }
        return "v" + t;
    }

    private static int parseIntSafe(String raw, int fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int compareVersionNames(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        String ca = a.trim().replaceFirst("^[vV]", "");
        String cb = b.trim().replaceFirst("^[vV]", "");
        String[] pa = ca.split("[^0-9]+");
        String[] pb = cb.split("[^0-9]+");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = (i < pa.length && !pa[i].isEmpty()) ? parseIntSafe(pa[i], 0) : 0;
            int vb = (i < pb.length && !pb[i].isEmpty()) ? parseIntSafe(pb[i], 0) : 0;
            if (va != vb) {
                return va > vb ? 1 : -1;
            }
        }
        return 0;
    }

    boolean isNewerRelease(RepoReleaseInfo info) {
        if (info == null) {
            return false;
        }
        int curCode = getInstalledVersionCode();
        if (info.versionCode > 0 && curCode > 0) {
            return info.versionCode > curCode;
        }
        return compareVersionNames(info.versionName, getInstalledVersionName()) > 0;
    }

    private void applyUpdateChipState(RepoReleaseInfo info) {
        if (info == null) {
            return;
        }
        boolean newer = isNewerRelease(info);
        if (tvAboutUpdateChip != null) {
            tvAboutUpdateChip.setVisibility(View.VISIBLE);
            if (newer) {
                tvAboutUpdateChip.setText(getString(
                        R.string.chip_update_available_fmt, formatVerLabel(info.versionName)));
            } else {
                tvAboutUpdateChip.setText(R.string.chip_update_latest);
            }
            styleUpdateChip(newer);
        }
        if (tvAboutHeroUpdateStatus != null) {
            if (newer) {
                tvAboutHeroUpdateStatus.setText(getString(
                        R.string.about_hero_new_ver_fmt, formatVerLabel(info.versionName)));
                android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
                pill.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                pill.setCornerRadius(999f * getResources().getDisplayMetrics().density);
                pill.setColor(0x34FFFFFF);
                pill.setStroke(Math.max(1, dp(1)), 0x55FFFFFF);
                tvAboutHeroUpdateStatus.setBackground(pill);
            } else {
                tvAboutHeroUpdateStatus.setText(R.string.dialog_update_latest_title);
                tvAboutHeroUpdateStatus.setBackground(null);
            }
        }
    }

    private void styleUpdateChip(boolean highlightAccent) {
        if (tvAboutUpdateChip == null) return;
        if (livingFlowUi) {
            tvAboutUpdateChip.setBackground(null);
            tvAboutUpdateChip.setTextColor(highlightAccent
                    ? DynamicThemeHelper.getAccent(this)
                    : getColor(R.color.lf_text_secondary));
        } else {
            DynamicThemeHelper.applyAccent(tvAboutUpdateChip, this);
        }
    }

    void runRepoUpdateCheck(final UpdateCheckCallback callback) {
        if (tvAboutUpdateChip != null) {
            tvAboutUpdateChip.setVisibility(View.VISIBLE);
            tvAboutUpdateChip.setText(R.string.chip_update_checking);
            styleUpdateChip(false);
        }
        new Thread(() -> {
            final RepoReleaseInfo info = fetchLatestReleaseFromRepo();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (info != null) {
                    sCachedLatestRelease = info;
                    applyUpdateChipState(info);
                } else if (sCachedLatestRelease != null) {
                    applyUpdateChipState(sCachedLatestRelease);
                } else if (tvAboutUpdateChip != null) {
                    tvAboutUpdateChip.setVisibility(View.GONE);
                }
                if (callback != null) {
                    callback.onResult(info);
                }
            });
        }, "SonyPods-UpdateCheckUi").start();
    }

    private void checkUpdateFromLsposedRepo(final boolean manual) {
        if (updateCheckRunning) {
            return;
        }
        updateCheckRunning = true;
        if (tvAboutUpdateChip != null) {
            tvAboutUpdateChip.setVisibility(View.VISIBLE);
            tvAboutUpdateChip.setText(R.string.chip_update_checking);
            styleUpdateChip(false);
        }
        if (manual) {
            Toast.makeText(this, R.string.toast_update_checking, Toast.LENGTH_SHORT).show();
        }
        new Thread(() -> {
            final RepoReleaseInfo info = fetchLatestReleaseFromRepo();
            runOnUiThread(() -> {
                updateCheckRunning = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (info == null) {
                    if (sCachedLatestRelease != null) {
                        applyUpdateChipState(sCachedLatestRelease);
                    } else if (tvAboutUpdateChip != null) {
                        tvAboutUpdateChip.setVisibility(View.GONE);
                    }
                    if (manual) {
                        Toast.makeText(MainActivity.this, R.string.toast_update_failed, Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                sCachedLatestRelease = info;
                applyUpdateChipState(info);
                if (manual) {
                    showUpdateResultDialog(info);
                }
            });
        }, "SonyPods-UpdateCheck").start();
    }

    private static RepoReleaseInfo fetchLatestReleaseFromRepo() {
        RepoReleaseInfo fromLsp = fetchFromLsposedJson();
        if (fromLsp != null) {
            return fromLsp;
        }
        RepoReleaseInfo fromGhLsp = fetchFromGitHubApiFallback(GITHUB_LATEST_API_URL, LSPOSED_REPO_PAGE_URL);
        if (fromGhLsp != null) {
            return fromGhLsp;
        }
        return fetchFromGitHubApiFallback(GITHUB_SOURCE_LATEST_API_URL, "https://github.com/nicochanyo/sonypods-coloros/releases");
    }

    private static String httpGetText(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(9000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "SonyPodsColorOS-UpdateChecker/1.0");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }
            InputStream in = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            reader.close();
            return sb.toString();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void parseTagIntoInfo(String tagName, String releaseName, RepoReleaseInfo out) {
        String tag = tagName != null ? tagName.trim() : "";
        String name = releaseName != null ? releaseName.trim() : "";
        int dash = tag.indexOf('-');
        if (dash > 0) {
            int code = parseIntSafe(tag.substring(0, dash).trim(), 0);
            String ver = tag.substring(dash + 1).trim();
            out.versionCode = code;
            out.versionName = !name.isEmpty() ? name : ver;
        } else {
            out.versionCode = 0;
            out.versionName = !name.isEmpty() ? name : tag;
        }
        if (out.versionName == null || out.versionName.isEmpty()) {
            out.versionName = "1.0.0";
        }
    }

    private static RepoReleaseInfo fetchFromLsposedJson() {
        try {
            String raw = httpGetText(LSPOSED_REPO_JSON_URL);
            JSONObject root = new JSONObject(raw);
            JSONArray releases = root.optJSONArray("releases");
            if (releases == null || releases.length() == 0) {
                return null;
            }
            JSONObject chosen = null;
            for (int i = 0; i < releases.length(); i++) {
                JSONObject r = releases.optJSONObject(i);
                if (r != null && !r.optBoolean("isDraft", false) && !r.optBoolean("isPrerelease", false)) {
                    chosen = r;
                    break;
                }
            }
            if (chosen == null) {
                chosen = releases.optJSONObject(0);
            }
            if (chosen == null) {
                return null;
            }
            RepoReleaseInfo info = new RepoReleaseInfo();
            parseTagIntoInfo(
                    chosen.optString("tagName", root.optString("latestRelease", "")),
                    chosen.optString("name", ""),
                    info);
            info.changelogRaw = chosen.optString("descriptionHTML", "");
            info.isHtmlChangelog = true;
            info.repoPageUrl = LSPOSED_REPO_PAGE_URL;
            JSONArray assets = chosen.optJSONArray("releaseAssets");
            if (assets != null && assets.length() > 0) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String dl = a.optString("downloadUrl", "");
                    String origDl = a.optString("originalDownloadUrl", "");
                    String candidate = !dl.isEmpty() ? dl : origDl;
                    if (!candidate.isEmpty()) {
                        info.downloadUrl = candidate;
                        if (a.optString("name", "").toLowerCase(Locale.ROOT).endsWith(".apk")) {
                            break;
                        }
                    }
                }
            }
            return info;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static RepoReleaseInfo fetchFromGitHubApiFallback(String apiUrl, String pageUrl) {
        try {
            String raw = httpGetText(apiUrl);
            JSONObject rel = new JSONObject(raw);
            RepoReleaseInfo info = new RepoReleaseInfo();
            parseTagIntoInfo(rel.optString("tag_name", ""), rel.optString("name", ""), info);
            info.changelogRaw = rel.optString("body", "");
            info.isHtmlChangelog = false;
            info.repoPageUrl = pageUrl;
            JSONArray assets = rel.optJSONArray("assets");
            if (assets != null && assets.length() > 0) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String dl = a.optString("browser_download_url", "");
                    if (!dl.isEmpty()) {
                        info.downloadUrl = dl;
                        if (a.optString("name", "").toLowerCase(Locale.ROOT).endsWith(".apk")) {
                            break;
                        }
                    }
                }
            }
            return info;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String unescapeUnicodeLiterals(String s) {
        if (s == null || !s.contains("\\u")) {
            return s;
        }
        Matcher m = Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int cp = Integer.parseInt(m.group(1), 16);
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) cp)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String cleanMarkdownInline(String s) {
        if (s == null) return "";
        String r = Normalizer.normalize(s, Normalizer.Form.NFC);
        r = r.replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1");
        r = r.replaceAll("\\*\\*([^*]+)\\*\\*", "$1");
        r = r.replaceAll("__([^_]+)__", "$1");
        r = r.replaceAll("`([^`]+)`", "$1");
        return r.trim();
    }

    private static String matchLanguageCodeFromHeading(String title) {
        String norm = cleanMarkdownInline(title).toLowerCase(Locale.ROOT);
        if (norm.contains("繁體中文") || norm.contains("繁体中文") || norm.contains("traditional chinese") || "zh-tw".equals(norm)) {
            return ConfigStore.LANG_ZH_TW;
        }
        if (norm.contains("简体中文") || norm.contains("簡體中文") || norm.contains("simplified chinese") || "zh-cn".equals(norm)) {
            return ConfigStore.LANG_ZH_CN;
        }
        if ("english".equals(norm) || "en".equals(norm) || norm.startsWith("english ")) {
            return ConfigStore.LANG_EN;
        }
        return null;
    }

    CharSequence formatLocalizedChangelog(RepoReleaseInfo info, CouiBottomSheet.Palette pal) {
        if (info == null || info.changelogRaw == null || info.changelogRaw.trim().isEmpty()) {
            return "";
        }
        String text = unescapeUnicodeLiterals(info.changelogRaw);
        text = text.replace("\r\n", "\n").replace("\r", "\n");

        if (info.isHtmlChangelog) {
            // Convert HTML structure into <br> markers BEFORE Html.fromHtml so line breaks never collapse to spaces
            String prepped = text
                    .replaceAll("(?i)<h[1-6][^>]*>", "<br>@@HDR@@ ")
                    .replaceAll("(?i)</h[1-6]>", "<br>")
                    .replaceAll("(?i)<li[^>]*>", "<br>• ")
                    .replaceAll("(?i)</li>", "<br>")
                    .replaceAll("(?i)<p[^>]*>", "<br>")
                    .replaceAll("(?i)</p>", "<br>")
                    .replaceAll("(?i)<br\\s*/?>", "<br>");
            text = Html.fromHtml(prepped, Html.FROM_HTML_MODE_LEGACY).toString();
        }
        text = Normalizer.normalize(text, Normalizer.Form.NFC);

        String targetLang = ConfigStore.getEffectiveAppLanguage(this);
        String[] lines = text.split("\n");

        List<String> targetLines = new ArrayList<>();
        List<String> allLines = new ArrayList<>();
        boolean inTarget = false;
        boolean foundTarget = false;
        boolean hasLangSections = false;

        for (String rawLine : lines) {
            String line = Normalizer.normalize(rawLine, Normalizer.Form.NFC).trim();
            if (line.isEmpty()) {
                continue;
            }

            boolean isHeading = false;
            if (line.startsWith("@@HDR@@")) {
                isHeading = true;
                line = line.substring("@@HDR@@".length()).trim();
            } else if (line.matches("^#{1,6}(\\s*.*)$")) {
                isHeading = true;
                line = line.replaceFirst("^#{1,6}\\s*", "").trim();
            }

            if (isHeading) {
                String cleanHead = cleanMarkdownInline(line);
                if (cleanHead.isEmpty()) continue;

                String langCode = matchLanguageCodeFromHeading(cleanHead);
                if (langCode != null) {
                    hasLangSections = true;
                    inTarget = langCode.equals(targetLang);
                    if (inTarget) {
                        foundTarget = true;
                    }
                    continue;
                }
                String entry = "\u0001" + cleanHead;
                if (inTarget) targetLines.add(entry);
                allLines.add(entry);
                continue;
            }

            if (line.startsWith(">")) {
                line = line.replaceFirst("^>+\\s*", "").trim();
            }
            if (line.matches("^[-*+·]\\s+.*")) {
                line = "• " + line.substring(2).trim();
            } else if (line.startsWith("• • ")) {
                line = "• " + line.substring(4).trim();
            }
            line = cleanMarkdownInline(line);
            if (line.isEmpty()) continue;

            if (inTarget) targetLines.add(line);
            allLines.add(line);
        }

        List<String> chosen = (hasLangSections && foundTarget && !targetLines.isEmpty()) ? targetLines : allLines;
        SpannableStringBuilder out = new SpannableStringBuilder();
        for (int i = 0; i < chosen.size(); i++) {
            String entry = chosen.get(i);
            boolean isSubHdr = entry.startsWith("\u0001");
            String bodyLine = isSubHdr ? entry.substring(1) : entry;
            if (out.length() > 0) {
                out.append(isSubHdr ? "\n\n" : "\n");
            }
            int start = out.length();
            out.append(bodyLine);
            if (isSubHdr) {
                int end = out.length();
                out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(pal.textPrimary), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return out;
    }

    private void showUpdateResultDialog(final RepoReleaseInfo info) {
        final boolean hasUpdate = isNewerRelease(info);
        final String curVerLabel = formatVerLabel(getInstalledVersionName());
        final String latestVerLabel = formatVerLabel(info.versionName);

        CouiBottomSheet.Palette pal = CouiBottomSheet.Palette.fromModuleTheme(this);
        CouiBottomSheet.SheetHandle sheet = CouiBottomSheet.createCenter(this, pal);
        String latestSubtitle = curVerLabel.equalsIgnoreCase(latestVerLabel)
                ? getString(R.string.dialog_update_latest_msg_fmt, curVerLabel, latestVerLabel)
                : getString(R.string.dialog_update_ahead_msg_fmt, curVerLabel, latestVerLabel);
        CouiBottomSheet.addHeader(
                this, sheet.card, pal,
                hasUpdate
                        ? getString(R.string.dialog_update_available_title_fmt, latestVerLabel)
                        : getString(R.string.dialog_update_latest_title),
                hasUpdate
                        ? getString(R.string.dialog_update_ver_compare_fmt, curVerLabel, latestVerLabel)
                        : latestSubtitle,
                null);

        CharSequence changelog = formatLocalizedChangelog(info, pal);
        if (changelog.length() > 0) {
            ScrollView scroll = CouiBottomSheet.createMaxHeightScrollView(
                    this, Math.round(getResources().getDisplayMetrics().heightPixels * 0.40f));
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            addChangelogItem(list, pal, latestVerLabel, changelog, false);
            scroll.addView(list);

            LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            scrollLp.topMargin = dp(14);
            sheet.card.addView(scroll, scrollLp);
        }

        if (hasUpdate && info.downloadUrl != null && !info.downloadUrl.isEmpty()) {
            CouiBottomSheet.addSingleButton(this, sheet.card, pal,
                    getString(R.string.btn_update_download), 1, 16, v -> {
                        sheet.dialog.dismiss();
                        if (!tryStartView(info.downloadUrl)) {
                            Toast.makeText(MainActivity.this, R.string.toast_open_link_failed, Toast.LENGTH_SHORT).show();
                        }
                    });
        }

        String repoText = getString(R.string.btn_update_open_repo);
        String closeText = getString(R.string.btn_close);
        if (hasUpdate) {
            CouiBottomSheet.addSingleButton(this, sheet.card, pal, repoText, 0,
                    (info.downloadUrl != null && !info.downloadUrl.isEmpty()) ? 10 : 16, v -> {
                        sheet.dialog.dismiss();
                        String targetUrl = (info.repoPageUrl != null && !info.repoPageUrl.isEmpty())
                                ? info.repoPageUrl : LSPOSED_REPO_PAGE_URL;
                        if (!tryStartView(targetUrl)) {
                            Toast.makeText(MainActivity.this, R.string.toast_open_link_failed, Toast.LENGTH_SHORT).show();
                        }
                    });
            CouiBottomSheet.addSingleButton(this, sheet.card, pal, closeText, 0, 10, v -> sheet.dialog.dismiss());
        } else {
            CouiBottomSheet.addButtonRow(this, sheet.card, pal,
                    repoText, 0, v -> {
                        sheet.dialog.dismiss();
                        String targetUrl = (info.repoPageUrl != null && !info.repoPageUrl.isEmpty())
                                ? info.repoPageUrl : LSPOSED_REPO_PAGE_URL;
                        if (!tryStartView(targetUrl)) {
                            Toast.makeText(MainActivity.this, R.string.toast_open_link_failed, Toast.LENGTH_SHORT).show();
                        }
                    },
                    closeText, 1, v -> sheet.dialog.dismiss());
        }
        sheet.dialog.show();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (LivingFlowUpdatePages.popActiveStage(this)) {
            return;
        }
        if (LivingFlowDiagnosticsPage.popActiveStage(this)) {
            return;
        }
        if (SoundConnectSheets.hasActiveStage(this)
                && SoundConnectSheets.popStagePage(SoundConnectSheets.HostStage.fromContext(this))) {
            return;
        }
        super.onBackPressed();
    }
}
