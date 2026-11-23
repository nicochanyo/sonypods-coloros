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
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
    private Switch swAboutMonet;
    private Switch swAboutDebugMode;
    private boolean updateCheckRunning;
    private static RepoReleaseInfo sCachedLatestRelease;
    private static boolean sSilentUpdateCheckDone;

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
        setupEdgeToEdge();
        setContentView(R.layout.activity_main);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        ConfigStore.AppPrefs opened = ConfigStore.openAppPrefs(this);
        prefs = opened.prefs;
        xspActive = opened.xspActive;
        hadLocalKeys = opened.hadLocalKeys;
        data = ConfigStore.loadFromPrefs(prefs);

        if (sPendingTabAfterRecreate >= TAB_HEADSET && sPendingTabAfterRecreate <= TAB_ABOUT) {
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
        refreshHeadsetStateUi();
    }

    @Override
    protected void onPause() {
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
                || !isPointInView(layoutPageContainer, rawX, rawY)) {
            return false;
        }
        if (isPointInView(layoutBottomNavContainer, rawX, rawY)) {
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
        final int headerPadH = dp(24);
        final int headerTopBase = dp(28);
        final int headerBotBase = dp(16);
        headerTitleShiftPx = dp(24);
        headerSubtitleShiftPx = dp(26);
        headerCollapseRangePx = dp(64);

        layoutHeader.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()).top
                    : insets.getSystemWindowInsetTop();
            statusBarTopPx = top;
            v.setPadding(headerPadH, top + headerTopBase, headerPadH, headerBotBase);
            if (vTopHeaderBlur != null) {
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

        final int navBotBase = dp(14);
        layoutBottomNavContainer.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars()).bottom
                    : insets.getSystemWindowInsetBottom();
            v.setPadding(0, 0, 0, bottom + navBotBase);
            if (vBottomNavShadow != null) {
                ViewGroup.LayoutParams lp = vBottomNavShadow.getLayoutParams();
                int targetH = bottom + dp(104);
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
        if (vTopHeaderBlur != null && tvPageTitle != null && tvPageTitle.getHeight() > 0) {
            int collapsedTitleH = Math.round(tvPageTitle.getHeight() * COLLAPSED_TITLE_SCALE);
            int targetBlurH = statusBarTopPx + dp(4) + collapsedTitleH + dp(56);
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
        if (tvPageTitle != null) {
            float pivotX = (tvPageTitle.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL)
                    ? tvPageTitle.getWidth() : 0f;
            tvPageTitle.setPivotX(pivotX);
            tvPageTitle.setPivotY(0f);
            float scale = 1f - (1f - COLLAPSED_TITLE_SCALE) * headerCollapseProgress;
            tvPageTitle.setScaleX(scale);
            tvPageTitle.setScaleY(scale);
            tvPageTitle.setTranslationY(-headerTitleShiftPx * headerCollapseProgress);
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
            tvPageSubtitle.setTranslationY(-neededTravelPx * subT);
            tvPageSubtitle.setAlpha(computeSubtitleAlpha(headerCollapseProgress) * subtitleTabAlpha);
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
        findViewById(R.id.rowClearArtworkCache).setOnClickListener(v -> {
            Toast.makeText(this, R.string.toast_restarting_scopes, Toast.LENGTH_SHORT).show();
            ScopeRestarter.clearMyDevicesArtworkCacheAndRestartAsync((ok, msg) -> Toast.makeText(
                    this,
                    ok ? getString(R.string.toast_restart_done) : getString(R.string.toast_restart_failed, msg),
                    Toast.LENGTH_SHORT).show());
        });
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

    private void bindAboutViews() {
        tvSysVersion = findViewById(R.id.tvSysVersion);
        tvDeviceModel = findViewById(R.id.tvDeviceModel);
        tvAboutChannel = findViewById(R.id.tvAboutChannel);
        tvAboutUpdateChip = findViewById(R.id.tvAboutUpdateChip);
        tvAboutLanguageVal = findViewById(R.id.tvAboutLanguageVal);
        tvAboutDarkModeVal = findViewById(R.id.tvAboutDarkModeVal);
        swAboutMonet = findViewById(R.id.swAboutMonet);
        swAboutDebugMode = findViewById(R.id.swAboutDebugMode);

        tvSysVersion.setText("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        tvDeviceModel.setText(Build.MANUFACTURER + " " + Build.MODEL);

        View rowAboutLanguage = findViewById(R.id.rowAboutLanguage);
        CouiBottomSheet.trackTouchPoint(rowAboutLanguage);
        rowAboutLanguage.setOnClickListener(v -> showLanguageDialog(v));

        View rowAboutDarkMode = findViewById(R.id.rowAboutDarkMode);
        CouiBottomSheet.trackTouchPoint(rowAboutDarkMode);
        rowAboutDarkMode.setOnClickListener(v -> showDarkModeDialog(v));
        findViewById(R.id.rowAboutChangelog).setOnClickListener(v -> showChangelogDialog());
        findViewById(R.id.rowAboutCheckUpdate).setOnClickListener(v -> checkUpdateFromLsposedRepo(true));

        findViewById(R.id.rowAboutMonet).setOnClickListener(v -> swAboutMonet.toggle());
        swAboutMonet.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressUiCallbacks) return;
            ConfigStore.setMonetEnabled(this, isChecked);
            DynamicThemeHelper.applyAccent(findViewById(android.R.id.content), this);
            updateNavIndicator(activeTab, activeTab, 0, false, false);
            refreshHeadsetStateUi();
        });

        findViewById(R.id.rowAboutDebugMode).setOnClickListener(v -> swAboutDebugMode.toggle());
        swAboutDebugMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressUiCallbacks) return;
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
        CouiBottomSheet.addHeader(
                this, sheet.card, pal,
                getString(R.string.dialog_model_override_title),
                getString(R.string.dialog_model_override_sub),
                null);

        final String curModel = data.customModelOverride != null ? data.customModelOverride.trim() : "";
        final EditText input = CouiBottomSheet.addPillInputBox(this, sheet.card, pal, curModel,
                getString(R.string.dialog_model_override_hint));

        final String autoLabel = getString(R.string.model_override_auto);
        final String[] presets = {
                "", "WF-1000XM4", "WF-1000XM5",
                "WH-1000XM4", "WH-1000XM5", "LinkBuds S", "ULT WEAR"
        };
        final String[] labels = new String[presets.length];
        for (int i = 0; i < presets.length; i++) {
            labels[i] = presets[i].isEmpty() ? autoLabel : presets[i];
        }
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
        final TextView countBadge = CouiBottomSheet.addHeader(
                this, sheet.card, pal,
                getString(R.string.dialog_restart_custom_title),
                getString(R.string.dialog_restart_custom_sub),
                "5 / " + pkgs.length);

        ScrollView scroll = CouiBottomSheet.createMaxHeightScrollView(
                this, Math.round(getResources().getDisplayMetrics().heightPixels * 0.44f));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollLp.topMargin = dp(14);
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

        CouiBottomSheet.addButtonRow(
                this, sheet.card, pal,
                getString(R.string.btn_cancel), 0, v -> sheet.dialog.dismiss(),
                getString(R.string.btn_restart_selected), 1, v -> {
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
                });
        sheet.dialog.show();
    }

    private void confirmAndRestartSelected(List<String> selected) {
        CouiBottomSheet.Palette pal = CouiBottomSheet.Palette.fromModuleTheme(this);
        CouiBottomSheet.SheetHandle sheet = CouiBottomSheet.create(this, pal);
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
        CouiBottomSheet.showPopupMenu(this, anchor, CouiBottomSheet.Palette.fromModuleTheme(this),
                labels, cur, which -> {
                    data.cycleMode = which == 1 ? ConfigStore.CYCLE_2WAY : ConfigStore.CYCLE_3WAY;
                    persistImmediate();
                });
    }

    private void showOpenPopupModeDialog(View anchor) {
        String[] labels = {
                getString(R.string.popup_mode_pairing_only_desc),
                getString(R.string.popup_mode_pairing_and_connect_desc)
        };
        int cur = data.openCasePopupMode == ConfigStore.POPUP_MODE_PAIRING_AND_CONNECT ? 1 : 0;
        CouiBottomSheet.showPopupMenu(this, anchor, CouiBottomSheet.Palette.fromModuleTheme(this),
                labels, cur, which -> {
                    data.openCasePopupMode = which == 1
                            ? ConfigStore.POPUP_MODE_PAIRING_AND_CONNECT
                            : ConfigStore.POPUP_MODE_PAIRING_ONLY;
                    persistImmediate();
                });
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
        CouiBottomSheet.showPopupMenu(this, anchor, CouiBottomSheet.Palette.fromModuleTheme(this),
                names, idx, which -> {
                    String chosen = codes[which];
                    if (!chosen.equals(cur)) {
                        ConfigStore.setAppLanguage(this, chosen);
                        sPendingTabAfterRecreate = activeTab;
                        recreate();
                    }
                });
    }

    private void showDarkModeDialog(View anchor) {
        String[] names = {
                getString(R.string.dark_mode_system),
                getString(R.string.dark_mode_light),
                getString(R.string.dark_mode_dark)
        };
        int cur = ConfigStore.getAppDarkMode(this);
        CouiBottomSheet.showPopupMenu(this, anchor, CouiBottomSheet.Palette.fromModuleTheme(this),
                names, cur, which -> {
                    if (which != cur) {
                        ConfigStore.setAppDarkMode(this, which);
                        sPendingTabAfterRecreate = activeTab;
                        recreate();
                    }
                });
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
        CouiBottomSheet.addHeader(
                this, sheet.card, pal,
                getString(R.string.dialog_logs_title),
                null,
                xspActive ? "API 102" : "Offline");

        ScrollView scroll = CouiBottomSheet.createMaxHeightScrollView(
                this, Math.round(getResources().getDisplayMetrics().heightPixels * 0.46f));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollLp.topMargin = dp(14);
        LinearLayout bodyCol = new LinearLayout(this);
        bodyCol.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(bodyCol);
        sheet.card.addView(scroll, scrollLp);

        LinearLayout infoCard = CouiBottomSheet.createSubcardGroup(this, pal);
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

        CouiBottomSheet.addButtonRow(
                this, sheet.card, pal,
                getString(R.string.btn_copy_logs), 0, v -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("SonyPods Diagnostics", copyText));
                        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show();
                    }
                },
                getString(R.string.btn_close), 1, v -> sheet.dialog.dismiss());
        sheet.dialog.show();
    }

    private void showResetAllDialog() {
        CouiBottomSheet.Palette pal = CouiBottomSheet.Palette.fromModuleTheme(this);
        CouiBottomSheet.SheetHandle sheet = CouiBottomSheet.create(this, pal);
        CouiBottomSheet.addHeader(
                this, sheet.card, pal,
                getString(R.string.dialog_reset_all_title),
                getString(R.string.dialog_reset_all_msg),
                null);
        CouiBottomSheet.addButtonRow(
                this, sheet.card, pal,
                getString(R.string.btn_cancel), 0, v -> sheet.dialog.dismiss(),
                getString(R.string.btn_confirm_reset), 2, v -> {
                    sheet.dialog.dismiss();
                    data = ConfigStore.createDefaults();
                    persistImmediate();
                    refreshHeadsetStateUi();
                    Toast.makeText(this, R.string.toast_reset_done, Toast.LENGTH_SHORT).show();
                });
        sheet.dialog.show();
    }

    private void syncUiState() {
        xspActive = XposedProvider.isConnected();
        suppressUiCallbacks = true;
        try {
            if (xspActive) {
                ivStatusIcon.setImageResource(R.drawable.ic_status_active);
                tvStatusTitle.setText(R.string.status_title_active);
                tvStatusSubtitle.setText(R.string.status_sub_active);
                tvAboutChannel.setText(R.string.channel_xsp);
            } else {
                ivStatusIcon.setImageResource(R.drawable.ic_status_warn);
                tvStatusTitle.setText(R.string.status_title_inactive);
                tvStatusSubtitle.setText(R.string.status_sub_inactive);
                tvAboutChannel.setText(R.string.channel_private_warn);
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
                    p.animate().translationX(0f).alpha(1f).setDuration(300L).setInterpolator(FLUID_EASE)
                            .setUpdateListener(anim -> {
                                if (vTopHeaderBlur != null) {
                                    vTopHeaderBlur.notifyTargetInvalidated();
                                }
                            }).start();
                } else if (i == oldTab) {
                    p.setVisibility(View.VISIBLE);
                    p.animate().translationX(-dir * dist).alpha(0f).setDuration(270L).setInterpolator(FLUID_EASE)
                            .withEndAction(() -> {
                                if (activeTab != idx) {
                                    p.setVisibility(View.GONE);
                                    p.setTranslationX(0f);
                                    p.setAlpha(1f);
                                }
                            }).start();
                } else {
                    p.setVisibility(View.GONE);
                    p.setTranslationX(0f);
                    p.setAlpha(1f);
                }
            }
        } else {
            for (int i = 0; i < pages.length; i++) {
                pages[i].animate().cancel();
                pages[i].setTranslationX(0f);
                pages[i].setAlpha(1f);
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
        if (animHeader) {
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
            if (layoutNavItems == null || vNavSlidingPill == null || navTabs[0] == null || navTabs[2] == null) {
                return false;
            }
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    vNavSlidingPill.animate().cancel();
                    layoutBottomNav.animate().cancel();
                    if (navPillMorphAnimator != null) {
                        navPillMorphAnimator.cancel();
                        navPillMorphAnimator = null;
                    }
                    ensureNavPillWidth(0);
                    int[] loc = new int[2];
                    layoutNavItems.getLocationOnScreen(loc);
                    navItemsRestLeftScreenX = loc[0] - layoutBottomNav.getTranslationX();
                    navItemsWidth = layoutNavItems.getWidth();
                    int tabW = navTabs[0].getWidth();
                    navPillWidth = tabW > 0 ? tabW : vNavSlidingPill.getWidth();
                    navMinPillX = navTabs[0].getLeft();
                    navMaxPillX = navTabs[2].getLeft();
                    navDownRawX = ev.getRawX();
                    navDownRawY = ev.getRawY();
                    navDragging = false;
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    float rawX = ev.getRawX();
                    float rawY = ev.getRawY();
                    float dxDown = rawX - navDownRawX;
                    float dyDown = rawY - navDownRawY;
                    if (!navDragging && Math.hypot(dxDown, dyDown) > Math.max(dp(5), touchSlop * 0.5f)) {
                        navDragging = true;
                        vNavSlidingPill.animate().scaleX(1.04f).scaleY(1.04f).setDuration(110L).start();
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
                            vNavSlidingPill.setTranslationX(navMinPillX);
                        } else if (desiredPillX > navMaxPillX) {
                            float normX = (desiredPillX - navMaxPillX) / resistDenom;
                            pullX = maxPull * (normX / (1.0f + normX));
                            vNavSlidingPill.setTranslationX(navMaxPillX);
                        } else {
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
                        ensureNavPillWidth(activeTab);
                        float targetX = navTabs[activeTab].getLeft();
                        vNavSlidingPill.animate().translationX(targetX).translationY(0f)
                                .scaleX(1.0f).scaleY(1.0f).setDuration(240L).setInterpolator(FLUID_EASE).start();
                        layoutBottomNav.animate().translationX(0f).translationY(0f)
                                .scaleX(1.0f).scaleY(1.0f).setDuration(280L).setInterpolator(NAV_SPRING_BACK).start();
                    }
                    return true;
                }
            }
            return false;
        });
    }

    private void updateNavIndicator(final int oldTab, final int targetTab, final int dir,
                                    final boolean animate, final boolean fromNavDrag) {
        int accent = DynamicThemeHelper.getAccent(this);
        int secondary = ConfigStore.resolveThemeColor(this, R.attr.couiTextSecondary, R.color.coui_text_secondary);

        for (int i = 0; i < 3; i++) {
            boolean selected = (i == targetTab);
            navIcons[i].setColorFilter(selected ? accent : secondary);
            navLabels[i].setTextColor(selected ? accent : secondary);
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

        if (fromNavDrag || vNavSlidingPill == null || navTabs[targetTab] == null) return;
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
                R.string.changelog_v101_title,
                R.string.changelog_v100_title
        };
        int[] verBodies = new int[]{
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

    private static final class RepoReleaseInfo {
        int versionCode;
        String versionName;
        String changelogRaw;
        boolean isHtmlChangelog;
        String downloadUrl;
        String repoPageUrl;
    }

    private int getInstalledVersionCode() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) {
                return (int) pi.getLongVersionCode();
            }
            //noinspection deprecation
            return pi.versionCode;
        } catch (Throwable ignored) {
            return 1001;
        }
    }

    private String getInstalledVersionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (pi.versionName != null && !pi.versionName.trim().isEmpty()) {
                return pi.versionName.trim();
            }
        } catch (Throwable ignored) {
        }
        return "1.0.1";
    }

    private static String formatVerLabel(String ver) {
        if (ver == null || ver.trim().isEmpty()) {
            return "v1.0.1";
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

    private boolean isNewerRelease(RepoReleaseInfo info) {
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
        if (tvAboutUpdateChip == null || info == null) {
            return;
        }
        tvAboutUpdateChip.setVisibility(View.VISIBLE);
        if (isNewerRelease(info)) {
            tvAboutUpdateChip.setText(getString(
                    R.string.chip_update_available_fmt, formatVerLabel(info.versionName)));
        } else {
            tvAboutUpdateChip.setText(R.string.chip_update_latest);
        }
        DynamicThemeHelper.applyAccent(tvAboutUpdateChip, this);
    }

    private void checkUpdateFromLsposedRepo(final boolean manual) {
        if (updateCheckRunning) {
            return;
        }
        updateCheckRunning = true;
        if (tvAboutUpdateChip != null) {
            tvAboutUpdateChip.setVisibility(View.VISIBLE);
            tvAboutUpdateChip.setText(R.string.chip_update_checking);
            DynamicThemeHelper.applyAccent(tvAboutUpdateChip, this);
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

    private CharSequence formatLocalizedChangelog(RepoReleaseInfo info, CouiBottomSheet.Palette pal) {
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
}
