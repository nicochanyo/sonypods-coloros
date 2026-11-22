package io.github.nicochanyo.sonypods;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Html;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.Button;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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

    // Header
    private View layoutHeader;
    private TextView tvPageTitle;
    private TextView tvPageSubtitle;

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
    private View rowSpeakToChat;
    private Switch swSpeakToChat;
    private View rowDsee;
    private Switch swDsee;
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
    protected void onDestroy() {
        XposedProvider.setConnectionListener(null);
        mainHandler.removeCallbacks(debouncedSync);
        if (navPillMorphAnimator != null) {
            navPillMorphAnimator.cancel();
            navPillMorphAnimator = null;
        }
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
        if (layoutPageContainer == null || !isPointInView(layoutPageContainer, rawX, rawY)) {
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
        final int headerTopBase = dp(38);
        final int headerBotBase = dp(22);

        layoutHeader.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()).top
                    : insets.getSystemWindowInsetTop();
            v.setPadding(headerPadH, top + headerTopBase, headerPadH, headerBotBase);
            return insets;
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

    private void bindViews() {
        layoutHeader = findViewById(R.id.layoutHeader);
        tvPageTitle = findViewById(R.id.tvPageTitle);
        tvPageSubtitle = findViewById(R.id.tvPageSubtitle);

        layoutPageContainer = findViewById(R.id.layoutPageContainer);
        pages[TAB_HEADSET] = findViewById(R.id.pageHeadset);
        pages[TAB_INTEGRATION] = findViewById(R.id.pageIntegration);
        pages[TAB_ABOUT] = findViewById(R.id.pageAbout);

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
        rowSpeakToChat = findViewById(R.id.rowSpeakToChat);
        swSpeakToChat = findViewById(R.id.swSpeakToChat);
        rowDsee = findViewById(R.id.rowDsee);
        swDsee = findViewById(R.id.swDsee);
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

        rowSpeakToChat.setOnClickListener(v -> swSpeakToChat.toggle());
        swSpeakToChat.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressUiCallbacks) return;
            headsetState.speakToChat = isChecked;
            ConfigStore.sendCommandBroadcast(this, ConfigStore.CMD_SET_SPEAK_TO_CHAT,
                    headsetState.mac, headsetState.ancMode, headsetState.asmLevel, headsetState.voiceFocus, isChecked);
        });

        rowDsee.setOnClickListener(v -> swDsee.toggle());
        swDsee.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressUiCallbacks) return;
            headsetState.dsee = isChecked;
            ConfigStore.sendCommandBroadcast(this, ConfigStore.CMD_SET_DSEE,
                    headsetState.mac, headsetState.ancMode, headsetState.asmLevel, headsetState.voiceFocus, isChecked);
        });

        findViewById(R.id.rowCycleMode).setOnClickListener(v -> showCycleModeDialog());
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
        rowOpenPopupMode.setOnClickListener(v -> showOpenPopupModeDialog());
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

        findViewById(R.id.rowAboutLanguage).setOnClickListener(v -> showLanguageDialog());
        findViewById(R.id.rowAboutDarkMode).setOnClickListener(v -> showDarkModeDialog());
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
            swSpeakToChat.setChecked(headsetState.speakToChat);
            swDsee.setChecked(headsetState.dsee);

            rowSpeakToChat.setAlpha(headsetState.connected ? 1.0f : 0.55f);
            swSpeakToChat.setEnabled(headsetState.connected);
            rowDsee.setAlpha(headsetState.connected ? 1.0f : 0.55f);
            swDsee.setEnabled(headsetState.connected);

            updateArtworkPreviewAsync(effectiveModel, headsetState.colorCode);
        } finally {
            suppressUiCallbacks = false;
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
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.dialog_model_override_hint);
        if (data.customModelOverride != null) {
            input.setText(data.customModelOverride);
            input.setSelection(data.customModelOverride.length());
        }
        int pad = dp(20);
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        container.setPadding(pad, dp(8), pad, 0);
        container.addView(input);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_model_override_title)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    data.customModelOverride = input.getText().toString().trim();
                    persistImmediate();
                    syncUiState();
                    refreshHeadsetStateUi();
                })
                .setNeutralButton(R.string.model_override_auto, (d, w) -> {
                    data.customModelOverride = "";
                    persistImmediate();
                    syncUiState();
                    refreshHeadsetStateUi();
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
        DynamicThemeHelper.tintAlertDialog(dlg, this);
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
        final String[] labels = {
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

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_restart_custom_title)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.btn_restart_selected, (dialog, which) -> {
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
                    if (hasSysUi) {
                        confirmAndRestartSelected(selected);
                    } else {
                        executeRestartSelected(selected);
                    }
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
        DynamicThemeHelper.tintAlertDialog(dlg, this);
    }

    private void confirmAndRestartSelected(List<String> selected) {
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_restart_custom_title)
                .setMessage(R.string.dialog_restart_confirm_sysui)
                .setPositiveButton(R.string.btn_restart_selected, (d, w) -> executeRestartSelected(selected))
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
        DynamicThemeHelper.tintAlertDialog(dlg, this);
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

    private void showCycleModeDialog() {
        String[] labels = {getString(R.string.cycle_mode_3way), getString(R.string.cycle_mode_2way)};
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_cycle_mode_title)
                .setSingleChoiceItems(labels, data.cycleMode == ConfigStore.CYCLE_2WAY ? 1 : 0, (dialog, which) -> {
                    data.cycleMode = which == 1 ? ConfigStore.CYCLE_2WAY : ConfigStore.CYCLE_3WAY;
                    persistImmediate();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
        DynamicThemeHelper.tintAlertDialog(dlg, this);
    }

    private void showOpenPopupModeDialog() {
        String[] labels = {
                getString(R.string.popup_mode_pairing_only_desc),
                getString(R.string.popup_mode_pairing_and_connect_desc)
        };
        int cur = data.openCasePopupMode == ConfigStore.POPUP_MODE_PAIRING_AND_CONNECT ? 1 : 0;
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_open_popup_mode_title)
                .setSingleChoiceItems(labels, cur, (dialog, which) -> {
                    data.openCasePopupMode = which == 1
                            ? ConfigStore.POPUP_MODE_PAIRING_AND_CONNECT
                            : ConfigStore.POPUP_MODE_PAIRING_ONLY;
                    persistImmediate();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
        DynamicThemeHelper.tintAlertDialog(dlg, this);
    }

    private void showLanguageDialog() {
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
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_language_title)
                .setSingleChoiceItems(names, idx, (dialog, which) -> {
                    String chosen = codes[which];
                    dialog.dismiss();
                    if (!chosen.equals(cur)) {
                        ConfigStore.setAppLanguage(this, chosen);
                        sPendingTabAfterRecreate = activeTab;
                        recreate();
                    }
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
        DynamicThemeHelper.tintAlertDialog(dlg, this);
    }

    private void showDarkModeDialog() {
        String[] names = {
                getString(R.string.dark_mode_system),
                getString(R.string.dark_mode_light),
                getString(R.string.dark_mode_dark)
        };
        int cur = ConfigStore.getAppDarkMode(this);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_dark_mode_title)
                .setSingleChoiceItems(names, cur, (dialog, which) -> {
                    ConfigStore.setAppDarkMode(this, which);
                    dialog.dismiss();
                    sPendingTabAfterRecreate = activeTab;
                    recreate();
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
        DynamicThemeHelper.tintAlertDialog(dlg, this);
    }

    private void showDiagnosticsDialog() {
        ConfigStore.HeadsetState st = ConfigStore.readGlobalState(this);
        StringBuilder sb = new StringBuilder();
        sb.append("Xposed Service: ").append(xspActive ? "Connected (API 102)" : "Not Connected").append('\n');
        sb.append("Headset Connected: ").append(st.connected).append('\n');
        sb.append("Name / Model / MAC: ").append(st.name).append(" / ").append(st.effectiveModelName())
                .append(" / ").append(st.mac).append('\n');
        sb.append("Color Code: ").append(st.colorCode >= 0 ? String.format(Locale.US, "0x%02X", st.colorCode) : "unknown").append('\n');
        sb.append("Protocol: Tandem V").append(st.protocolVersion)
                .append(" (NCASM=0x").append(Integer.toHexString(st.ncAsmInquiredType))
                .append(", S2C=0x").append(Integer.toHexString(Math.max(0, st.speakToChatInquiredType)))
                .append(")\n");
        sb.append("Battery: L=").append(st.leftBattery).append("% R=").append(st.rightBattery)
                .append("% Case=").append(st.caseBattery).append("% Single=").append(st.singleBattery).append("%\n");
        sb.append("Mode: ").append(st.formatModeLabel(this))
                .append(" (ASM=").append(st.asmLevel)
                .append(", VF=").append(st.voiceFocus)
                .append(", S2C=").append(st.speakToChat)
                .append(", DSEE=").append(st.dsee).append(")\n");
        sb.append("Catalog Cached: ").append(SonyModelImageManager.getCatalogCount(this))
                .append(", Custom PNG: ").append(SonyModelImageManager.hasCustomOverride(this)).append('\n');
        String recent = XposedCompat.dumpRecentLogs();
        if (!recent.isEmpty()) {
            sb.append("\n--- App Process Logs ---\n").append(recent);
        }

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_logs_title)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.btn_close, null)
                .show();
        DynamicThemeHelper.tintAlertDialog(dlg, this);
    }

    private void showResetAllDialog() {
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_reset_all_title)
                .setMessage(R.string.dialog_reset_all_msg)
                .setPositiveButton(R.string.btn_confirm_reset, (d, w) -> {
                    data = ConfigStore.createDefaults();
                    persistImmediate();
                    refreshHeadsetStateUi();
                    Toast.makeText(this, R.string.toast_reset_done, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
        DynamicThemeHelper.tintAlertDialog(dlg, this);
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

            tvCycleModeVal.setText(data.cycleMode == ConfigStore.CYCLE_2WAY
                    ? R.string.cycle_mode_2way : R.string.cycle_mode_3way);
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
                    p.animate().translationX(0f).alpha(1f).setDuration(300L).setInterpolator(FLUID_EASE).start();
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

        if (animate && oldTab != targetTab) {
            tvPageTitle.animate().cancel();
            tvPageTitle.setTranslationX(dir * dp(18));
            tvPageTitle.setAlpha(0.25f);
            tvPageTitle.animate().translationX(0f).alpha(1f).setDuration(260L).setInterpolator(FLUID_EASE).start();
            tvPageSubtitle.animate().cancel();
            tvPageSubtitle.setTranslationX(dir * dp(14));
            tvPageSubtitle.setAlpha(0.25f);
            tvPageSubtitle.animate().translationX(0f).alpha(1f).setDuration(260L).setInterpolator(FLUID_EASE).start();
        } else {
            tvPageTitle.setTranslationX(0f);
            tvPageTitle.setAlpha(1f);
            tvPageSubtitle.setTranslationX(0f);
            tvPageSubtitle.setAlpha(1f);
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
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_coui_card);
        root.setPadding(dp(20), dp(20), dp(20), dp(18));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(R.string.dialog_changelog_title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvTitle.setTextColor(ConfigStore.resolveThemeColor(this, R.attr.couiTextPrimary, R.color.coui_text_primary));
        root.addView(tvTitle);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollLp.topMargin = dp(14);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);

        int[] verTitles = new int[]{
                R.string.changelog_v100_title
        };
        int[] verBodies = new int[]{
                R.string.changelog_v100_body
        };
        for (int i = 0; i < verTitles.length; i++) {
            addChangelogItem(list, verTitles[i], verBodies[i], i < verTitles.length - 1);
        }

        root.addView(scroll, scrollLp);

        Button btnClose = new Button(this);
        btnClose.setText(R.string.btn_close);
        btnClose.setAllCaps(false);
        btnClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        btnClose.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btnClose.setTextColor(DynamicThemeHelper.getOnAccent(this));
        btnClose.setBackgroundResource(R.drawable.bg_coui_btn_primary);
        btnClose.setStateListAnimator(null);
        btnClose.setTag("accent_btn");
        btnClose.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        btnLp.topMargin = dp(16);
        root.addView(btnClose, btnLp);

        DynamicThemeHelper.applyAccent(root, this);
        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.round(getResources().getDisplayMetrics().widthPixels * 0.90f);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void addChangelogItem(LinearLayout parent, int verTitleRes, int bodyRes, boolean addBottomMargin) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_coui_subcard);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView badge = new TextView(this);
        badge.setText(verTitleRes);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        badge.setTextColor(DynamicThemeHelper.getAccent(this));
        badge.setBackgroundResource(R.drawable.bg_coui_chip);
        badge.setTag("accent_chip");
        badge.setPadding(dp(9), dp(3), dp(9), dp(3));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.addView(badge, badgeLp);

        TextView body = new TextView(this);
        body.setText(bodyRes);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        body.setLineSpacing(0f, 1.32f);
        body.setTextColor(ConfigStore.resolveThemeColor(this, R.attr.couiTextSecondary, R.color.coui_text_secondary));
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
            return 1000;
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
        return "1.0.0";
    }

    private static String formatVerLabel(String ver) {
        if (ver == null || ver.trim().isEmpty()) {
            return "v1.0.0";
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

    private String formatLocalizedChangelog(RepoReleaseInfo info) {
        if (info == null || info.changelogRaw == null || info.changelogRaw.trim().isEmpty()) {
            return "";
        }
        String text = info.changelogRaw;
        if (info.isHtmlChangelog) {
            String prepped = text
                    .replaceAll("(?i)<h[1-6][^>]*>", "\n### ")
                    .replaceAll("(?i)</h[1-6]>", "\n")
                    .replaceAll("(?i)<li[^>]*>", "\n• ")
                    .replaceAll("(?i)</li>", "")
                    .replaceAll("(?i)</p>", "\n")
                    .replaceAll("(?i)<br\\s*/?>", "\n");
            text = Html.fromHtml(prepped, Html.FROM_HTML_MODE_LEGACY).toString();
        } else {
            text = text.replace("\r\n", "\n");
        }

        String lang = ConfigStore.getEffectiveAppLanguage(this);
        String targetMarker;
        if (ConfigStore.LANG_ZH_CN.equals(lang)) {
            targetMarker = "简体中文";
        } else if (ConfigStore.LANG_EN.equals(lang)) {
            targetMarker = "English";
        } else {
            targetMarker = "繁體中文";
        }

        String[] lines = text.split("\n");
        StringBuilder section = new StringBuilder();
        StringBuilder allCleaned = new StringBuilder();
        boolean inTarget = false;
        boolean foundTarget = false;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("###")) {
                String headerTitle = line.replaceFirst("^#+\\s*", "").trim();
                if (headerTitle.equalsIgnoreCase(targetMarker)) {
                    inTarget = true;
                    foundTarget = true;
                } else if (inTarget) {
                    break;
                }
                continue;
            }
            if (line.startsWith("- ") || line.startsWith("* ")) {
                line = "• " + line.substring(2).trim();
            }
            if (inTarget) {
                if (section.length() > 0) section.append('\n');
                section.append(line);
            }
            if (allCleaned.length() > 0) allCleaned.append('\n');
            allCleaned.append(line);
        }

        String chosen = (foundTarget && section.length() > 0) ? section.toString() : allCleaned.toString();
        return chosen.trim();
    }

    private void showUpdateResultDialog(final RepoReleaseInfo info) {
        final boolean hasUpdate = isNewerRelease(info);
        final String curVerLabel = formatVerLabel(getInstalledVersionName());
        final String latestVerLabel = formatVerLabel(info.versionName);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_coui_card);
        root.setPadding(dp(20), dp(20), dp(20), dp(18));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(hasUpdate
                ? getString(R.string.dialog_update_available_title_fmt, latestVerLabel)
                : getString(R.string.dialog_update_latest_title));
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvTitle.setTextColor(ConfigStore.resolveThemeColor(this, R.attr.couiTextPrimary, R.color.coui_text_primary));
        root.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText(hasUpdate
                ? getString(R.string.dialog_update_ver_compare_fmt, curVerLabel, latestVerLabel)
                : getString(R.string.dialog_update_latest_msg_fmt, curVerLabel, latestVerLabel));
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tvSub.setLineSpacing(0f, 1.25f);
        tvSub.setTextColor(ConfigStore.resolveThemeColor(this, R.attr.couiTextSecondary, R.color.coui_text_secondary));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        root.addView(tvSub, subLp);

        String changelog = formatLocalizedChangelog(info);
        if (!changelog.isEmpty()) {
            ScrollView scroll = new ScrollView(this);
            scroll.setVerticalScrollBarEnabled(false);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.bg_coui_subcard);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));

            TextView badge = new TextView(this);
            badge.setText(latestVerLabel);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            badge.setTextColor(DynamicThemeHelper.getAccent(this));
            badge.setBackgroundResource(R.drawable.bg_coui_chip);
            badge.setTag("accent_chip");
            badge.setPadding(dp(9), dp(3), dp(9), dp(3));
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            card.addView(badge, badgeLp);

            TextView body = new TextView(this);
            body.setText(changelog);
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            body.setLineSpacing(0f, 1.32f);
            body.setTextColor(ConfigStore.resolveThemeColor(this, R.attr.couiTextSecondary, R.color.coui_text_secondary));
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bodyLp.topMargin = dp(8);
            card.addView(body, bodyLp);

            scroll.addView(card);
            LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            scrollLp.topMargin = dp(14);
            root.addView(scroll, scrollLp);
        }

        if (hasUpdate && info.downloadUrl != null && !info.downloadUrl.isEmpty()) {
            Button btnDownload = new Button(this);
            btnDownload.setText(R.string.btn_update_download);
            btnDownload.setAllCaps(false);
            btnDownload.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            btnDownload.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            btnDownload.setTextColor(DynamicThemeHelper.getOnAccent(this));
            btnDownload.setBackgroundResource(R.drawable.bg_coui_btn_primary);
            btnDownload.setStateListAnimator(null);
            btnDownload.setTag("accent_btn");
            btnDownload.setOnClickListener(v -> {
                dialog.dismiss();
                if (!tryStartView(info.downloadUrl)) {
                    Toast.makeText(MainActivity.this, R.string.toast_open_link_failed, Toast.LENGTH_SHORT).show();
                }
            });
            LinearLayout.LayoutParams dlLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            dlLp.topMargin = dp(16);
            root.addView(btnDownload, dlLp);
        }

        Button btnRepo = new Button(this);
        btnRepo.setText(R.string.btn_update_open_repo);
        btnRepo.setAllCaps(false);
        btnRepo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        btnRepo.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btnRepo.setTextColor(ConfigStore.resolveThemeColor(this, R.attr.couiTextPrimary, R.color.coui_text_primary));
        btnRepo.setBackgroundResource(R.drawable.bg_coui_btn_secondary);
        btnRepo.setStateListAnimator(null);
        btnRepo.setOnClickListener(v -> {
            dialog.dismiss();
            String targetUrl = (info.repoPageUrl != null && !info.repoPageUrl.isEmpty())
                    ? info.repoPageUrl : LSPOSED_REPO_PAGE_URL;
            if (!tryStartView(targetUrl)) {
                Toast.makeText(MainActivity.this, R.string.toast_open_link_failed, Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams repoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        repoLp.topMargin = dp(hasUpdate ? 10 : 16);
        root.addView(btnRepo, repoLp);

        Button btnClose = new Button(this);
        btnClose.setText(R.string.btn_close);
        btnClose.setAllCaps(false);
        btnClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        btnClose.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (hasUpdate) {
            btnClose.setTextColor(ConfigStore.resolveThemeColor(this, R.attr.couiTextSecondary, R.color.coui_text_secondary));
            btnClose.setBackgroundResource(R.drawable.bg_coui_btn_secondary);
        } else {
            btnClose.setTextColor(DynamicThemeHelper.getOnAccent(this));
            btnClose.setBackgroundResource(R.drawable.bg_coui_btn_primary);
            btnClose.setTag("accent_btn");
        }
        btnClose.setStateListAnimator(null);
        btnClose.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        closeLp.topMargin = dp(10);
        root.addView(btnClose, closeLp);

        DynamicThemeHelper.applyAccent(root, this);
        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.round(getResources().getDisplayMetrics().widthPixels * 0.90f);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }
}
