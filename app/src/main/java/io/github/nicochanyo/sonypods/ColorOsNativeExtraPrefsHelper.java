package io.github.nicochanyo.sonypods;

import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;

/**
 * Injects native ColorOS COUI preference rows for Sony Extra Features (Ambient Level,
 * Voice Focus, Speak-to-Chat, DSEE, and module launcher) into {@code com.heytap.mydevices}'s
 * {@code AirpodsDetailPreferencesFragment} and {@code AirpodsSettingPreferencesFragment}.
 */
public final class ColorOsNativeExtraPrefsHelper {
    private static final String KEY_CAT_EXTRA = "cat_sony_extra";
    private static final String KEY_CAT_SOUND = "sony_cat_sound";
    private static final String KEY_CAT_INTERACTION = "sony_cat_interaction";
    private static final String KEY_CAT_CONNECTION = "sony_cat_connection";
    private static final String KEY_CAT_POWER = "sony_cat_power";
    private static final String KEY_OPEN_MODULE = "sony_extra_open_module";
    private static final String KEY_DISCONNECT_GAP = "sony_extra_disconnect_gap";

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static volatile ConfigStore.HookConfig sCfg;
    private static volatile ClassLoader sClassLoader;
    private static volatile boolean sStateReceiverRegistered;
    private static final List<WeakReference<Object>> ACTIVE_FRAGMENTS = new ArrayList<>();
    private static final WeakHashMap<Object, Long> INITIAL_TOP_LOCK_UNTIL = new WeakHashMap<>();
    private static final WeakHashMap<Object, Boolean> TOUCH_LISTENER_ATTACHED = new WeakHashMap<>();

    private ColorOsNativeExtraPrefsHelper() {
    }

    public static void install(ClassLoader cl, ConfigStore.HookConfig cfg) {
        sClassLoader = cl;
        sCfg = cfg;

        Class<?> detailFragCls = XposedCompat.findClassIfExists(
                "com.heytap.mydevices.plugin.airpods.fragment.AirpodsDetailPreferencesFragment", cl);
        if (detailFragCls != null) {
            XposedCompat.XC_MethodHook detailHook = new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    injectOrSyncDetailFragment(param.thisObject);
                }
            };
            XposedCompat.XC_MethodHook detailAsyncHook = new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    final Object frag = param.thisObject;
                    injectOrSyncDetailFragment(frag);
                    MAIN_HANDLER.post(() -> injectOrSyncDetailFragment(frag));
                }
            };
            XposedCompat.hookAllMethods(detailFragCls, "onCreatePreferences", detailHook);
            XposedCompat.hookAllMethods(detailFragCls, "onViewCreated", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    SoundConnectSheets.resetHostStage(resolveHostStage(param.thisObject, null));
                    injectOrSyncDetailFragment(param.thisObject);
                    armInitialScrollToTopIfSony(param.thisObject);
                }
            });
            XposedCompat.hookAllMethods(detailFragCls, "initAirpodsCardAppearance", detailHook);
            XposedCompat.hookAllMethods(detailFragCls, "onHostPanelShow", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    SoundConnectSheets.resetHostStage(resolveHostStage(param.thisObject, null));
                    injectOrSyncDetailFragment(param.thisObject);
                    if (param.args == null || param.args.length == 0 || !Boolean.FALSE.equals(param.args[0])) {
                        armInitialScrollToTopIfSony(param.thisObject);
                    }
                }
            });
            XposedCompat.hookAllMethods(detailFragCls, "onHostPanelStop", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    SoundConnectSheets.resetHostStage(resolveHostStage(param.thisObject, null));
                }
            });
            XposedCompat.hookAllMethods(detailFragCls, "applyConnectedUi", detailAsyncHook);
            XposedCompat.hookAllMethods(detailFragCls, "applyDisconnectedUi", detailAsyncHook);
            XposedCompat.hookAllMethods(detailFragCls, "setDisconnectSectionVisible", detailAsyncHook);
        }

        Class<?> settingFragCls = XposedCompat.findClassIfExists(
                "com.heytap.mydevices.plugin.airpods.ui.AirpodsSettingPreferencesFragment", cl);
        if (settingFragCls != null) {
            XposedCompat.XC_MethodHook settingHook = new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    injectOrSyncSettingFragment(param.thisObject);
                }
            };
            XposedCompat.hookAllMethods(settingFragCls, "onCreatePreferences", settingHook);
            XposedCompat.hookAllMethods(settingFragCls, "onViewCreated", settingHook);
            XposedCompat.hookAllMethods(settingFragCls, "setConversationVisible", settingHook);
            XposedCompat.hookAllMethods(settingFragCls, "setDisconnectSectionVisible", settingHook);
        }

        XposedCompat.log("ColorOsNativeExtraPrefsHelper installed");
    }

    private static void injectOrSyncDetailFragment(Object frag) {
        if (!isPanelEnabled() || frag == null) return;
        Context ctx = resolveFragContext(frag);
        if (ctx == null) return;
        String mac = resolveDetailMac(frag);
        if (!isSonyMac(ctx, mac)) {
            return;
        }
        ensureStateReceiver(ctx);
        trackFragment(frag);
        injectAndSync(frag, ctx, mac, false);
    }

    private static void armInitialScrollToTopIfSony(Object frag) {
        if (!isPanelEnabled() || frag == null) return;
        Context ctx = resolveFragContext(frag);
        if (ctx == null) return;
        String mac = resolveDetailMac(frag);
        if (!isSonyMac(ctx, mac)) {
            return;
        }
        synchronized (INITIAL_TOP_LOCK_UNTIL) {
            INITIAL_TOP_LOCK_UNTIL.put(frag, SystemClock.elapsedRealtime() + 750L);
        }
        enforceScrollToTopIfArmed(frag);
        MAIN_HANDLER.post(() -> enforceScrollToTopIfArmed(frag));
        MAIN_HANDLER.postDelayed(() -> enforceScrollToTopIfArmed(frag), 100L);
        MAIN_HANDLER.postDelayed(() -> enforceScrollToTopIfArmed(frag), 250L);
        MAIN_HANDLER.postDelayed(() -> enforceScrollToTopIfArmed(frag), 450L);
    }

    private static void enforceScrollToTopIfArmed(Object frag) {
        if (frag == null) return;
        try {
            Field hbField = findField(frag.getClass(), "mHostBinding");
            Object hb = hbField != null ? hbField.get(frag) : null;
            View scrollView = null;
            if (hb != null) {
                Field fField = findField(hb.getClass(), "f");
                Object svObj = fField != null ? fField.get(hb) : null;
                if (svObj instanceof View) {
                    scrollView = (View) svObj;
                }
            }

            View rv = null;
            try {
                Method getList = findMethod(frag.getClass(), "getListView");
                Object rvObj = getList != null ? getList.invoke(frag) : null;
                if (rvObj instanceof View) {
                    rv = (View) rvObj;
                }
            } catch (Throwable ignored) {
            }

            if (rv != null) {
                rv.setFocusable(false);
                rv.setFocusableInTouchMode(false);
            }
            if (scrollView instanceof ViewGroup && ((ViewGroup) scrollView).getChildCount() > 0) {
                View firstChild = ((ViewGroup) scrollView).getChildAt(0);
                if (firstChild instanceof ViewGroup) {
                    firstChild.setFocusable(true);
                    firstChild.setFocusableInTouchMode(true);
                    ((ViewGroup) firstChild).setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
                }
            }
            if (scrollView != null) {
                synchronized (TOUCH_LISTENER_ATTACHED) {
                    if (!Boolean.TRUE.equals(TOUCH_LISTENER_ATTACHED.get(frag))) {
                        TOUCH_LISTENER_ATTACHED.put(frag, Boolean.TRUE);
                        scrollView.setOnTouchListener((v, ev) -> {
                            if (ev != null && ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                                synchronized (INITIAL_TOP_LOCK_UNTIL) {
                                    INITIAL_TOP_LOCK_UNTIL.put(frag, 0L);
                                }
                            }
                            return false;
                        });
                    }
                }
            }

            long untilMs;
            synchronized (INITIAL_TOP_LOCK_UNTIL) {
                Long val = INITIAL_TOP_LOCK_UNTIL.get(frag);
                untilMs = val != null ? val : 0L;
            }
            if (SystemClock.elapsedRealtime() <= untilMs) {
                if (rv != null) {
                    try {
                        Method stp = findMethod(rv.getClass(), "scrollToPosition", int.class);
                        if (stp != null) stp.invoke(rv, 0);
                    } catch (Throwable ignored) {
                    }
                }
                if (scrollView != null) {
                    scrollView.scrollTo(0, 0);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String resolveDetailMac(Object frag) {
        String mac = readStringField(frag, "mMac");
        if (!mac.isEmpty()) {
            return mac;
        }
        try {
            Field vmField = findField(frag.getClass(), "viewModel");
            Object vm = vmField != null ? vmField.get(frag) : null;
            if (vm != null) {
                Method nMethod = findMethod(vm.getClass(), "n");
                Object res = nMethod != null ? nMethod.invoke(vm) : null;
                if (res instanceof String && !((String) res).trim().isEmpty()) {
                    return ((String) res).trim();
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Method getAct = findMethod(frag.getClass(), "getActivity");
            Object act = getAct != null ? getAct.invoke(frag) : null;
            if (act instanceof Activity) {
                Intent intent = ((Activity) act).getIntent();
                if (intent != null) {
                    String[] keys = {"device_mac_info", "device_id", "address"};
                    for (String k : keys) {
                        String val = intent.getStringExtra(k);
                        if (val != null && !val.trim().isEmpty()) {
                            return val.trim();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static void injectOrSyncSettingFragment(Object frag) {
        if (!isPanelEnabled() || frag == null) return;
        Context ctx = resolveFragContext(frag);
        if (ctx == null) return;
        BluetoothDevice dev = resolveSettingDevice(frag);
        String mac = safeAddress(dev);
        if (!isSonyDeviceOrMac(ctx, dev, mac)) {
            return;
        }
        ensureStateReceiver(ctx);
        trackFragment(frag);
        injectAndSync(frag, ctx, mac, true);
    }

    private static synchronized void ensureStateReceiver(Context ctx) {
        if (sStateReceiverRegistered || ctx == null) {
            return;
        }
        Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        sStateReceiverRegistered = true;
        IntentFilter filter = new IntentFilter(ConfigStore.ACTION_STATE_CHANGED);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                syncAllTrackedFragments();
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                appCtx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                appCtx.registerReceiver(receiver, filter);
            }
        } catch (Throwable t) {
            sStateReceiverRegistered = false;
        }
    }

    private static synchronized void trackFragment(Object frag) {
        Iterator<WeakReference<Object>> it = ACTIVE_FRAGMENTS.iterator();
        while (it.hasNext()) {
            Object existing = it.next().get();
            if (existing == null) {
                it.remove();
            } else if (existing == frag) {
                return;
            }
        }
        ACTIVE_FRAGMENTS.add(new WeakReference<>(frag));
    }

    private static synchronized void syncAllTrackedFragments() {
        Iterator<WeakReference<Object>> it = ACTIVE_FRAGMENTS.iterator();
        while (it.hasNext()) {
            Object frag = it.next().get();
            if (frag == null) {
                it.remove();
                continue;
            }
            String clsName = frag.getClass().getName();
            if (clsName.contains("AirpodsDetailPreferencesFragment")) {
                injectOrSyncDetailFragment(frag);
            } else if (clsName.contains("AirpodsSettingPreferencesFragment")) {
                injectOrSyncSettingFragment(frag);
            }
        }
    }

    private static void injectAndSync(Object frag, Context ctx, String mac, boolean isSettingPage) {
        ClassLoader cl = sClassLoader != null ? sClassLoader : frag.getClass().getClassLoader();
        try {
            Method getScreen = findMethod(frag.getClass(), "getPreferenceScreen");
            Object screen = getScreen != null ? getScreen.invoke(frag) : null;
            if (screen == null) {
                return;
            }

            Object cat = findPref(frag, screen, KEY_CAT_EXTRA);
            if (cat == null) {
                cat = buildAndInsertCategory(frag, screen, ctx, cl, isSettingPage);
                if (cat == null) {
                    return;
                }
            }

            ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
            boolean matchingConnected = st.connected
                    && (mac == null || mac.isEmpty() || st.mac == null || st.mac.isEmpty()
                    || mac.equalsIgnoreCase(st.mac));

            ConfigStore.CodecState cs = ConfigStore.readGlobalCodecState(ctx);
            boolean showSound = matchingConnected && SoundConnectSheets.hasSoundFeatures(st);
            boolean showInteraction = matchingConnected && SoundConnectSheets.hasInteractionFeatures(st);
            boolean showConnection = matchingConnected && SoundConnectSheets.hasConnectionFeatures(st, cs, mac);
            boolean showPower = matchingConnected && SoundConnectSheets.hasPowerFeatures(st);
            boolean showExtraControls = showSound || showInteraction || showConnection || showPower;
            setPrefVisible(cat, true);

            Object soundPref = findPref(frag, cat, KEY_CAT_SOUND);
            Object interPref = findPref(frag, cat, KEY_CAT_INTERACTION);
            Object connPref = findPref(frag, cat, KEY_CAT_CONNECTION);
            Object powerPref = findPref(frag, cat, KEY_CAT_POWER);
            Object openModPref = findPref(frag, cat, KEY_OPEN_MODULE);

            if (soundPref != null) {
                setPrefVisible(soundPref, showSound);
                setPrefEnabled(soundPref, showSound);
                setCouiAssignment(soundPref, "");
            }
            if (interPref != null) {
                setPrefVisible(interPref, showInteraction);
                setPrefEnabled(interPref, showInteraction);
                setCouiAssignment(interPref, "");
            }
            if (connPref != null) {
                setPrefVisible(connPref, showConnection);
                setPrefEnabled(connPref, showConnection);
                setCouiAssignment(connPref, "");
            }
            if (powerPref != null) {
                setPrefVisible(powerPref, showPower);
                setPrefEnabled(powerPref, showPower);
                if (showPower) {
                    setCouiAssignment(powerPref, SoundConnectSheets.formatPowerSummary(ctx, st));
                }
            }
            if (openModPref != null) {
                setPrefVisible(openModPref, true);
                setPrefEnabled(openModPref, true);
                if (!showExtraControls && !matchingConnected) {
                    setPrefSummary(openModPref, locText(ctx,
                            "開啟 SonyPods ColorOS 模組設定",
                            "打开 SonyPods ColorOS 模块设置",
                            "Open SonyPods ColorOS settings"));
                } else {
                    setPrefSummary(openModPref, locText(ctx,
                            "圖示、切換順序與系統整合",
                            "图标、切换顺序与系统整合",
                            "Artwork, cycle order, and integration"));
                }
            }
            Object gapPref = findPref(frag, screen, KEY_DISCONNECT_GAP);
            if (gapPref != null) {
                Object discCat = findPref(frag, screen, "cat_airpods_disconnect");
                boolean showGap = discCat != null && readBooleanField(discCat, "z", false);
                setPrefVisible(gapPref, showGap);
            }
            if (!isSettingPage) {
                enforceScrollToTopIfArmed(frag);
            }
        } catch (Throwable t) {
            XposedCompat.debug("ColorOsNativeExtraPrefsHelper injectAndSync failed: " + t.getMessage());
        }
    }

    private static Object buildAndInsertCategory(
            Object frag,
            Object screen,
            Context ctx,
            ClassLoader cl,
            boolean isSettingPage
    ) {
        try {
            Context rawPrefCtx = null;
            Method getPrefMgr = findMethod(frag.getClass(), "getPreferenceManager");
            Object pm = getPrefMgr != null ? getPrefMgr.invoke(frag) : null;
            if (pm != null) {
                Method getPmCtx = findMethod(pm.getClass(), "getContext");
                if (getPmCtx != null) {
                    Object c = getPmCtx.invoke(pm);
                    if (c instanceof Context) rawPrefCtx = (Context) c;
                }
            }
            final Context prefCtx = rawPrefCtx != null ? rawPrefCtx : ctx;

            Class<?> catCls = XposedCompat.findClassIfExists(
                    "com.coui.appcompat.preference.COUIPreferenceCategory", cl);
            Class<?> jumpCls = XposedCompat.findClassIfExists(
                    "com.coui.appcompat.preference.COUIJumpPreference", cl);
            if (catCls == null || jumpCls == null) {
                return null;
            }

            // Assign deterministic ordering to existing top-level screen children so cat_sony_extra + spacer sit above cat_airpods_disconnect
            int count = getPrefCount(screen);
            int disconnectOrder = -1;
            int extraOrder = -1;
            for (int i = 0; i < count; i++) {
                Object child = getPrefAt(screen, i);
                if (child == null) continue;
                int childOrder = i * 100;
                setPrefOrder(child, childOrder);
                String childKey = readStringField(child, "m");
                if ("cat_airpods_disconnect".equals(childKey)) {
                    disconnectOrder = childOrder;
                    extraOrder = disconnectOrder - 50;
                }
            }
            if (disconnectOrder < 0) {
                disconnectOrder = count * 100;
                extraOrder = disconnectOrder - 50;
            }
            if (extraOrder < 0) extraOrder = 150;

            Constructor<?> catCtor = catCls.getConstructor(Context.class, AttributeSet.class);
            Object cat = catCtor.newInstance(prefCtx, null);
            setPrefKey(cat, KEY_CAT_EXTRA);
            setPrefTitle(cat, locText(prefCtx, "附加功能", "附加功能", "Extra Features"));
            setPrefOrder(cat, extraOrder);
            addPref(screen, cat);

            Class<?> bottomCls = XposedCompat.findClassIfExists(
                    "com.coui.appcompat.preference.COUIBottomPreference", cl);
            if (bottomCls != null) {
                try {
                    Object gapPref = null;
                    try {
                        Constructor<?> c2 = bottomCls.getConstructor(Context.class, AttributeSet.class);
                        gapPref = c2.newInstance(prefCtx, null);
                    } catch (Throwable ignored) {
                        Constructor<?> c1 = bottomCls.getConstructor(Context.class);
                        gapPref = c1.newInstance(prefCtx);
                    }
                    if (gapPref != null) {
                        setPrefKey(gapPref, KEY_DISCONNECT_GAP);
                        setPrefOrder(gapPref, disconnectOrder - 20);
                        setPrefPersistent(gapPref, false);
                        setPrefIconSpaceReserved(gapPref, false);
                        addPref(screen, gapPref);
                    }
                } catch (Throwable ignored) {
                }
            }

            Constructor<?> jumpCtor = jumpCls.getConstructor(Context.class);

            // 1. Sound & Noise Control (Ambient level, Voice focus, EQ, DSEE)
            addCategoryJumpPref(cat, jumpCtor, prefCtx, cl, KEY_CAT_SOUND, 10,
                    locText(prefCtx, "聲音與降噪", "声音与降噪", "Sound & Noise Control"),
                    locText(prefCtx, "環境音、等化器與音質增強",
                            "环境音、均衡器与音质增强",
                            "Ambient sound, EQ, and DSEE"),
                    () -> {
                        Context uiCtx = resolveActivityUiContext(frag, prefCtx);
                        SoundConnectSheets.showSoundAndEqSheet(
                                uiCtx, prefCtx, CouiBottomSheet.Palette.fromSystemOverlay(uiCtx),
                                resolveHostStage(frag, uiCtx), null);
                    });

            // 2. Touch & Smart Controls (Touch sensors, Speak-to-Chat details, Wear detect)
            addCategoryJumpPref(cat, jumpCtor, prefCtx, cl, KEY_CAT_INTERACTION, 20,
                    locText(prefCtx, "觸碰與智慧互動", "触控与智能交互", "Touch & Smart Controls"),
                    locText(prefCtx, "觸控操作、說話至聊天與佩戴",
                            "触控操作、智能免摘与佩戴",
                            "Touch controls, Speak-to-Chat, wear"),
                    () -> {
                        Context uiCtx = resolveActivityUiContext(frag, prefCtx);
                        SoundConnectSheets.showTouchAndSmartSheet(
                                uiCtx, prefCtx, CouiBottomSheet.Palette.fromSystemOverlay(uiCtx),
                                resolveHostStage(frag, uiCtx), null);
                    });

            // 3. Connection & Multipoint (2-device multipoint + unified LDAC / AAC connection quality)
            addCategoryJumpPref(cat, jumpCtor, prefCtx, cl, KEY_CAT_CONNECTION, 30,
                    locText(prefCtx, "連線與多設備", "连接与多设备", "Connection & Multipoint"),
                    locText(prefCtx, "雙裝置連線與音訊編碼品質",
                            "双设备连接与音频编码质量",
                            "Multipoint connection and audio codec"),
                    () -> {
                        Context uiCtx = resolveActivityUiContext(frag, prefCtx);
                        SoundConnectSheets.showConnectionAndMultipointSheet(
                                uiCtx, prefCtx, CouiBottomSheet.Palette.fromSystemOverlay(uiCtx),
                                resolveHostStage(frag, uiCtx), null);
                    });

            // 4. Auto Power Off (Single-line row with value shown on right via couiAssignment)
            addCategoryJumpPref(cat, jumpCtor, prefCtx, cl, KEY_CAT_POWER, 40,
                    locText(prefCtx, "自動關機", "自动关机", "Auto Power Off"),
                    null,
                    () -> {
                        Context uiCtx = resolveActivityUiContext(frag, prefCtx);
                        SoundConnectSheets.showPowerAndSystemSheet(
                                uiCtx, prefCtx, CouiBottomSheet.Palette.fromSystemOverlay(uiCtx),
                                resolveHostStage(frag, uiCtx), null);
                    });

            // 5. Open Module Settings
            addCategoryJumpPref(cat, jumpCtor, prefCtx, cl, KEY_OPEN_MODULE, 50,
                    locText(prefCtx, "更多設定", "更多设置", "More Settings"),
                    null,
                    () -> launchModuleApp(prefCtx));

            return cat;
        } catch (Throwable t) {
            XposedCompat.debug("buildAndInsertCategory failed (" + isSettingPage + "): " + t.getMessage());
            return null;
        }
    }

    private static void addCategoryJumpPref(
            Object cat, Constructor<?> jumpCtor, Context prefCtx, ClassLoader cl,
            String key, int order, String title, String summary, Runnable onClick
    ) throws Throwable {
        Object pref = jumpCtor.newInstance(prefCtx);
        setPrefKey(pref, key);
        setPrefTitle(pref, title);
        if (summary != null) {
            setPrefSummary(pref, summary);
        }
        setPrefOrder(pref, order);
        setPrefPersistent(pref, false);
        setPrefIconSpaceReserved(pref, false);
        setOnClickListener(pref, cl, onClick);
        addPref(cat, pref);
    }

    private static Context resolveActivityUiContext(Object frag, Context fallback) {
        try {
            Method getAct = findMethod(frag.getClass(), "getActivity");
            Object act = getAct != null ? getAct.invoke(frag) : null;
            if (act instanceof Context) {
                return (Context) act;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static SoundConnectSheets.HostStage resolveHostStage(Object frag, Context uiCtx) {
        if (frag == null) {
            return SoundConnectSheets.HostStage.fromContext(uiCtx);
        }
        Context ctx = uiCtx != null ? uiCtx : resolveActivityUiContext(frag, resolveFragContext(frag));
        try {
            Field hbField = findField(frag.getClass(), "mHostBinding");
            Object hb = hbField != null ? hbField.get(frag) : null;
            if (hb != null) {
                Field aField = findField(hb.getClass(), "a");
                Field fField = findField(hb.getClass(), "f");
                Object rootObj = aField != null ? aField.get(hb) : null;
                Object svObj = fField != null ? fField.get(hb) : null;
                if (rootObj instanceof FrameLayout) {
                    Dialog hostDialog = null;
                    try {
                        Method findDlg = findMethod(frag.getClass(), "findHostBottomSheetDialog");
                        Object dObj = findDlg != null ? findDlg.invoke(frag) : null;
                        if (dObj instanceof Dialog) {
                            hostDialog = (Dialog) dObj;
                        }
                    } catch (Throwable ignored) {
                    }
                    View level1 = (svObj instanceof View) ? (View) svObj : null;
                    return SoundConnectSheets.HostStage.forEmbeddedPanel(
                            (FrameLayout) rootObj, level1, hostDialog, ctx);
                }
            }
        } catch (Throwable ignored) {
        }
        return SoundConnectSheets.HostStage.fromContext(ctx);
    }

    private static void launchModuleApp(Context ctx) {
        try {
            Intent intent = new Intent();
            intent.setClassName(ConfigStore.PKG_NAME, ConfigStore.PKG_NAME + ".MainActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Throwable t) {
            XposedCompat.debug("launchModuleApp failed: " + t.getMessage());
        }
    }

    private static void setOnClickListener(Object pref, ClassLoader cl, Runnable action) {
        try {
            Class<?> listenerCls = XposedCompat.findClassIfExists(
                    "androidx.preference.Preference$d", cl);
            if (listenerCls == null) return;
            Object proxy = Proxy.newProxyInstance(
                    cl,
                    new Class<?>[]{listenerCls},
                    (p, method, args) -> {
                        if ("a".equals(method.getName())) {
                            action.run();
                            return Boolean.TRUE;
                        }
                        return Boolean.FALSE;
                    });
            Method m = findMethod(pref.getClass(), "H0", listenerCls);
            if (m != null) m.invoke(pref, proxy);
        } catch (Throwable ignored) {
        }
    }

    private static Object findPref(Object frag, Object groupOrNull, String key) {
        try {
            if (groupOrNull != null) {
                Method x0 = findMethod(groupOrNull.getClass(), "X0", CharSequence.class);
                if (x0 != null) {
                    Object res = x0.invoke(groupOrNull, key);
                    if (res != null) return res;
                }
            }
            Method fp = findMethod(frag.getClass(), "findPreference", CharSequence.class);
            if (fp != null) {
                return fp.invoke(frag, key);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int getPrefCount(Object group) {
        try {
            Method m = findMethod(group.getClass(), "b1");
            Object res = m != null ? m.invoke(group) : null;
            return res instanceof Integer ? (Integer) res : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static Object getPrefAt(Object group, int idx) {
        try {
            Method m = findMethod(group.getClass(), "a1", int.class);
            return m != null ? m.invoke(group, idx) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void addPref(Object group, Object pref) {
        try {
            Class<?> prefBaseCls = XposedCompat.findClassIfExists("androidx.preference.Preference",
                    group.getClass().getClassLoader());
            Method m = prefBaseCls != null ? findMethod(group.getClass(), "W0", prefBaseCls) : null;
            if (m != null) m.invoke(group, pref);
        } catch (Throwable ignored) {
        }
    }

    private static void setPrefKey(Object pref, String key) {
        invokeVoid(pref, "D0", String.class, key);
    }

    private static void setPrefTitle(Object pref, CharSequence title) {
        invokeVoid(pref, "O0", CharSequence.class, title);
    }

    private static void setPrefSummary(Object pref, CharSequence summary) {
        invokeVoid(pref, "L0", CharSequence.class, summary);
    }

    private static void setPrefOrder(Object pref, int order) {
        try {
            Method m = findMethod(pref.getClass(), "I0", int.class);
            if (m != null) m.invoke(pref, order);
        } catch (Throwable ignored) {
        }
    }

    private static void setPrefPersistent(Object pref, boolean persistent) {
        invokeBool(pref, "J0", persistent);
    }

    private static void setPrefIconSpaceReserved(Object pref, boolean reserved) {
        invokeBool(pref, "B0", reserved);
    }

    private static void setPrefVisible(Object pref, boolean visible) {
        invokeBool(pref, "P0", visible);
    }

    private static void setPrefEnabled(Object pref, boolean enabled) {
        invokeBool(pref, "x0", enabled);
    }

    private static void setCouiAssignment(Object pref, CharSequence value) {
        invokeVoid(pref, "Z0", CharSequence.class, value);
    }

    private static void invokeVoid(Object target, String name, Class<?> argCls, Object arg) {
        try {
            Method m = findMethod(target.getClass(), name, argCls);
            if (m != null) m.invoke(target, arg);
        } catch (Throwable ignored) {
        }
    }

    private static void invokeBool(Object target, String name, boolean val) {
        try {
            Method m = findMethod(target.getClass(), name, boolean.class);
            if (m != null) m.invoke(target, val);
        } catch (Throwable ignored) {
        }
    }

    private static Context resolveFragContext(Object frag) {
        try {
            Method m = findMethod(frag.getClass(), "getContext");
            Object c = m != null ? m.invoke(frag) : null;
            if (c instanceof Context) return (Context) c;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static BluetoothDevice resolveSettingDevice(Object frag) {
        try {
            Method getVm = findMethod(frag.getClass(), "getViewModel");
            Object vm = getVm != null ? getVm.invoke(frag) : null;
            if (vm != null) {
                Method getBt = findMethod(vm.getClass(), "getBluetoothDevice");
                Object dev = getBt != null ? getBt.invoke(vm) : null;
                if (dev instanceof BluetoothDevice) return (BluetoothDevice) dev;
            }
            Method getAct = findMethod(frag.getClass(), "getActivity");
            Object act = getAct != null ? getAct.invoke(frag) : null;
            if (act instanceof Activity) {
                Intent intent = ((Activity) act).getIntent();
                if (intent != null) {
                    Object extraDev = null;
                    try {
                        extraDev = intent.getParcelableExtra("device");
                        if (!(extraDev instanceof BluetoothDevice)) {
                            extraDev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        }
                    } catch (Throwable ignored) {
                    }
                    if (extraDev instanceof BluetoothDevice) {
                        return (BluetoothDevice) extraDev;
                    }
                    String[] macKeys = {"address", "device_mac_info", "device_id"};
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    for (String k : macKeys) {
                        String addr = intent.getStringExtra(k);
                        if (addr != null) {
                            addr = addr.trim().toUpperCase(Locale.US);
                            if (adapter != null && BluetoothAdapter.checkBluetoothAddress(addr)) {
                                return adapter.getRemoteDevice(addr);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isSonyDeviceOrMac(Context ctx, BluetoothDevice dev, String mac) {
        if (dev != null && SonyDeviceDetector.isSonyMacOrBonded(dev)) {
            return true;
        }
        return isSonyMac(ctx, mac);
    }

    private static boolean isSonyMac(Context ctx, String mac) {
        if (mac == null || mac.trim().isEmpty()) return false;
        String upper = mac.trim().toUpperCase(Locale.US);
        if (ctx != null) {
            try {
                String raw = Settings.Global.getString(ctx.getContentResolver(), ConfigStore.GLOBAL_KEY_STATE);
                if (raw != null && !raw.isEmpty()) {
                    ConfigStore.HeadsetState rawSt = ConfigStore.HeadsetState.deserialize(raw);
                    if (rawSt.mac != null && upper.equalsIgnoreCase(rawSt.mac)) {
                        SonyDeviceDetector.markKnownSony(upper);
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (SonyDeviceDetector.findBondedSonyDeviceByMac(upper) != null) {
            SonyDeviceDetector.markKnownSony(upper);
            return true;
        }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && BluetoothAdapter.checkBluetoothAddress(upper)) {
                BluetoothDevice dev = adapter.getRemoteDevice(upper);
                if (SonyDeviceDetector.isSonyMacOrBonded(dev)) {
                    SonyDeviceDetector.markKnownSony(upper);
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isPanelEnabled() {
        ConfigStore.HookConfig cfg = sCfg;
        if (cfg == null) return true;
        cfg.reload(false);
        return cfg.myDevicesPanel;
    }

    static String locText(Context ctx, String tw, String cn, String en) {
        Locale loc;
        try {
            loc = ctx != null ? ctx.getResources().getConfiguration().getLocales().get(0) : Locale.getDefault();
        } catch (Throwable ignored) {
            loc = Locale.getDefault();
        }
        if (loc == null || !"zh".equalsIgnoreCase(loc.getLanguage())) {
            return en;
        }
        String script = loc.getScript();
        String country = loc.getCountry();
        if ("Hans".equalsIgnoreCase(script) || "CN".equalsIgnoreCase(country) || "SG".equalsIgnoreCase(country)) {
            return cn;
        }
        return tw;
    }

    private static String safeAddress(BluetoothDevice dev) {
        if (dev == null) return "";
        try {
            String a = dev.getAddress();
            return a != null ? a.trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String readStringField(Object obj, String name) {
        try {
            Field f = findField(obj.getClass(), name);
            Object v = f != null ? f.get(obj) : null;
            return v instanceof String ? (String) v : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean readBooleanField(Object obj, String name, boolean def) {
        try {
            Field f = findField(obj.getClass(), name);
            Object v = f != null ? f.get(obj) : null;
            return v instanceof Boolean ? (Boolean) v : def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }
}
