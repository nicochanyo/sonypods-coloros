package io.github.nicochanyo.sonypods;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

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
    private static final String KEY_ASM_LEVEL = "sony_extra_asm_level";
    private static final String KEY_VOICE_FOCUS = "sony_extra_voice_focus";
    private static final String KEY_SPEAK_TO_CHAT = "sony_extra_speak_to_chat";
    private static final String KEY_DSEE = "sony_extra_dsee";
    private static final String KEY_AUDIO_CODEC = "sony_extra_audio_codec";
    private static final String KEY_OPEN_MODULE = "sony_extra_open_module";
    private static final String KEY_DISCONNECT_GAP = "sony_extra_disconnect_gap";

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static volatile ConfigStore.HookConfig sCfg;
    private static volatile ClassLoader sClassLoader;
    private static volatile boolean sStateReceiverRegistered;
    private static final List<WeakReference<Object>> ACTIVE_FRAGMENTS = new ArrayList<>();
    private static final WeakHashMap<Object, Long> INITIAL_TOP_LOCK_UNTIL = new WeakHashMap<>();
    private static final WeakHashMap<Object, Boolean> TOUCH_LISTENER_ATTACHED = new WeakHashMap<>();

    private static volatile boolean sUpdatingFromState;

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
                    injectOrSyncDetailFragment(param.thisObject);
                    armInitialScrollToTopIfSony(param.thisObject);
                }
            });
            XposedCompat.hookAllMethods(detailFragCls, "initAirpodsCardAppearance", detailHook);
            XposedCompat.hookAllMethods(detailFragCls, "onHostPanelShow", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    injectOrSyncDetailFragment(param.thisObject);
                    if (param.args == null || param.args.length == 0 || !Boolean.FALSE.equals(param.args[0])) {
                        armInitialScrollToTopIfSony(param.thisObject);
                    }
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
            boolean codecMacMatch = cs.mac.isEmpty()
                    || (mac != null && !mac.isEmpty() && cs.mac.equalsIgnoreCase(mac))
                    || (st.mac != null && !st.mac.isEmpty() && cs.mac.equalsIgnoreCase(st.mac));
            boolean showCodec = matchingConnected && codecMacMatch && cs.supportsLdac && cs.supportsAac;

            boolean showExtraControls = matchingConnected
                    && (st.supportsNcAsm || st.supportsSpeakToChat || st.supportsDsee || showCodec);
            setPrefVisible(cat, true);

            Object asmPref = findPref(frag, cat, KEY_ASM_LEVEL);
            Object vfPref = findPref(frag, cat, KEY_VOICE_FOCUS);
            Object stcPref = findPref(frag, cat, KEY_SPEAK_TO_CHAT);
            Object dseePref = findPref(frag, cat, KEY_DSEE);
            Object codecPref = findPref(frag, cat, KEY_AUDIO_CODEC);
            Object openModPref = findPref(frag, cat, KEY_OPEN_MODULE);

            sUpdatingFromState = true;
            try {
                if (asmPref != null) {
                    boolean showAsm = matchingConnected && st.supportsNcAsm;
                    setPrefVisible(asmPref, showAsm);
                    setPrefEnabled(asmPref, showAsm);
                    if (showAsm) {
                        setCouiAssignment(asmPref, st.asmLevel + " / 20");
                    }
                }
                if (vfPref != null) {
                    boolean showVf = matchingConnected && st.supportsNcAsm;
                    setPrefVisible(vfPref, showVf);
                    setPrefEnabled(vfPref, showVf);
                    setSwitchChecked(vfPref, st.voiceFocus);
                }
                if (stcPref != null) {
                    boolean nativeConvVisible = false;
                    if (isSettingPage) {
                        Object nativeConv = findPref(frag, screen, "pref_airpods_conversation_aware");
                        nativeConvVisible = nativeConv != null && readBooleanField(nativeConv, "z", false);
                    }
                    boolean showStc = matchingConnected && st.supportsSpeakToChat && !nativeConvVisible;
                    setPrefVisible(stcPref, showStc);
                    setPrefEnabled(stcPref, showStc);
                    setSwitchChecked(stcPref, st.speakToChat);
                }
                if (dseePref != null) {
                    boolean showDsee = matchingConnected && st.supportsDsee;
                    setPrefVisible(dseePref, showDsee);
                    setPrefEnabled(dseePref, showDsee);
                    setSwitchChecked(dseePref, st.dsee);
                }
                if (codecPref != null) {
                    setPrefVisible(codecPref, showCodec);
                    setPrefEnabled(codecPref, showCodec);
                    if (showCodec) {
                        String codecLabel = ConfigStore.codecDisplayName(cs.activeCodec);
                        setCouiAssignment(codecPref, codecLabel);
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
                                "圖資設定、切換模式與系統整合",
                                "图标设置、切换模式与系统整合",
                                "Artwork, cycle order, and system integration"));
                    }
                }
                Object gapPref = findPref(frag, screen, KEY_DISCONNECT_GAP);
                if (gapPref != null) {
                    Object discCat = findPref(frag, screen, "cat_airpods_disconnect");
                    boolean showGap = discCat != null && readBooleanField(discCat, "z", false);
                    setPrefVisible(gapPref, showGap);
                }
            } finally {
                sUpdatingFromState = false;
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
            Class<?> swCls = XposedCompat.findClassIfExists(
                    "com.coui.appcompat.preference.COUISwitchPreference", cl);
            Class<?> jumpCls = XposedCompat.findClassIfExists(
                    "com.coui.appcompat.preference.COUIJumpPreference", cl);
            if (catCls == null || swCls == null || jumpCls == null) {
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
            Constructor<?> swCtor = swCls.getConstructor(Context.class);

            // 1. Ambient Sound Level
            Object asmPref = jumpCtor.newInstance(prefCtx);
            setPrefKey(asmPref, KEY_ASM_LEVEL);
            setPrefTitle(asmPref, locText(prefCtx, "通透強度", "通透强度", "Ambient Level"));
            setPrefSummary(asmPref, locText(prefCtx, "調整環境音通透等級（1 ~ 20）",
                    "调整环境音通透等级（1 ~ 20）", "Adjust ambient sound level (1 ~ 20)"));
            setPrefOrder(asmPref, 10);
            setPrefPersistent(asmPref, false);
            setPrefIconSpaceReserved(asmPref, false);
            setOnClickListener(asmPref, cl, () -> showAsmLevelDialog(frag, prefCtx));
            addPref(cat, asmPref);

            // 2. Voice Focus
            Object vfPref = swCtor.newInstance(prefCtx);
            setPrefKey(vfPref, KEY_VOICE_FOCUS);
            setPrefTitle(vfPref, locText(prefCtx, "專注語音", "专注语音", "Voice Focus"));
            setPrefSummary(vfPref, locText(prefCtx, "開啟環境音時強化人聲並降低背景噪音",
                    "开启环境音时强化人声并降低背景噪音", "Emphasize voices over background noise"));
            setPrefOrder(vfPref, 20);
            setPrefPersistent(vfPref, false);
            setPrefIconSpaceReserved(vfPref, false);
            setOnChangeListener(vfPref, cl, newVal -> {
                ConfigStore.HeadsetState st = ConfigStore.readGlobalState(prefCtx);
                if (st.connected) {
                    ConfigStore.sendCommandBroadcast(
                            prefCtx, ConfigStore.CMD_SET_AMBIENT_PARAMS, st.mac,
                            st.ancMode, st.asmLevel, newVal, false);
                }
            });
            addPref(cat, vfPref);

            // 3. Speak-to-Chat
            Object stcPref = swCtor.newInstance(prefCtx);
            setPrefKey(stcPref, KEY_SPEAK_TO_CHAT);
            setPrefTitle(stcPref, locText(prefCtx, "說話時自動暫停", "说话时自动暂停", "Speak-to-Chat"));
            setPrefSummary(stcPref, locText(prefCtx, "開口說話時自動切為環境音並暫停播放",
                    "开口说话时自动切为环境音并暂停播放", "Pause audio and enable ambient while talking"));
            setPrefOrder(stcPref, 30);
            setPrefPersistent(stcPref, false);
            setPrefIconSpaceReserved(stcPref, false);
            setOnChangeListener(stcPref, cl, newVal -> {
                ConfigStore.HeadsetState st = ConfigStore.readGlobalState(prefCtx);
                if (st.connected) {
                    ConfigStore.sendCommandBroadcast(
                            prefCtx, ConfigStore.CMD_SET_SPEAK_TO_CHAT, st.mac,
                            st.ancMode, st.asmLevel, st.voiceFocus, newVal);
                }
            });
            addPref(cat, stcPref);

            // 4. DSEE
            Object dseePref = swCtor.newInstance(prefCtx);
            setPrefKey(dseePref, KEY_DSEE);
            setPrefTitle(dseePref, locText(prefCtx, "DSEE 音質還原", "DSEE 音质还原", "DSEE Upscaling"));
            setPrefSummary(dseePref, locText(prefCtx, "即時補償壓縮音源的高頻細節",
                    "实时补偿压缩音源的高频细节", "Restore high-frequency detail in compressed audio"));
            setPrefOrder(dseePref, 40);
            setPrefPersistent(dseePref, false);
            setPrefIconSpaceReserved(dseePref, false);
            setOnChangeListener(dseePref, cl, newVal -> {
                ConfigStore.HeadsetState st = ConfigStore.readGlobalState(prefCtx);
                if (st.connected) {
                    ConfigStore.sendCommandBroadcast(
                            prefCtx, ConfigStore.CMD_SET_DSEE, st.mac,
                            st.ancMode, st.asmLevel, st.voiceFocus, newVal);
                }
            });
            addPref(cat, dseePref);

            // 5. Audio Codec (LDAC / AAC)
            Object codecPref = jumpCtor.newInstance(prefCtx);
            setPrefKey(codecPref, KEY_AUDIO_CODEC);
            setPrefTitle(codecPref, locText(prefCtx, "音訊編碼", "音频编码", "Audio Codec"));
            setPrefSummary(codecPref, locText(prefCtx,
                    "切換高解析 LDAC 與穩定低功耗 AAC",
                    "切换高解析 LDAC 与稳定低功耗 AAC",
                    "Switch between LDAC and AAC"));
            setPrefOrder(codecPref, 45);
            setPrefPersistent(codecPref, false);
            setPrefIconSpaceReserved(codecPref, false);
            setOnClickListener(codecPref, cl, () -> showCodecDialog(frag, prefCtx));
            addPref(cat, codecPref);

            // 6. Open Module Settings
            Object openModPref = jumpCtor.newInstance(prefCtx);
            setPrefKey(openModPref, KEY_OPEN_MODULE);
            setPrefTitle(openModPref, locText(prefCtx, "更多設定", "更多设置", "More Settings"));
            setPrefOrder(openModPref, 50);
            setPrefPersistent(openModPref, false);
            setPrefIconSpaceReserved(openModPref, false);
            setOnClickListener(openModPref, cl, () -> launchModuleApp(prefCtx));
            addPref(cat, openModPref);

            return cat;
        } catch (Throwable t) {
            XposedCompat.debug("buildAndInsertCategory failed (" + isSettingPage + "): " + t.getMessage());
            return null;
        }
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

    private static void showAsmLevelDialog(Object frag, Context ctx) {
        try {
            Context uiCtx = resolveActivityUiContext(frag, ctx);

            ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
            int curLevel = ConfigStore.clampAsmLevel(st.asmLevel);
            float d = uiCtx.getResources().getDisplayMetrics().density;
            boolean isNight = (uiCtx.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

            LinearLayout container = new LinearLayout(uiCtx);
            container.setOrientation(LinearLayout.VERTICAL);
            int hPad = Math.round(24f * d);
            int vPad = Math.round(16f * d);
            container.setPadding(hPad, vPad, hPad, Math.round(8f * d));

            TextView valTv = new TextView(uiCtx);
            valTv.setTextSize(16f);
            valTv.setTextColor(isNight ? 0xFFF2F2F2 : 0xFF1A1A1A);
            valTv.setText(locText(ctx, "通透等級：", "通透等级：", "Level: ") + curLevel + " / 20");
            valTv.setPadding(0, 0, 0, Math.round(12f * d));
            container.addView(valTv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            SeekBar seekBar = new SeekBar(uiCtx);
            seekBar.setMax(19);
            seekBar.setProgress(curLevel - 1);
            seekBar.setProgressTintList(ColorStateList.valueOf(0xFF00BFA5));
            seekBar.setThumbTintList(ColorStateList.valueOf(0xFF00BFA5));
            seekBar.setProgressBackgroundTintList(
                    ColorStateList.valueOf(isNight ? 0x4DFFFFFF : 0x33000000));
            final int[] chosen = new int[]{curLevel};
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    int lvl = progress + 1;
                    chosen[0] = lvl;
                    valTv.setText(locText(ctx, "通透等級：", "通透等级：", "Level: ") + lvl + " / 20");
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    ConfigStore.HeadsetState latest = ConfigStore.readGlobalState(ctx);
                    if (latest.connected) {
                        ConfigStore.sendCommandBroadcast(
                                ctx, ConfigStore.CMD_SET_AMBIENT_PARAMS, latest.mac,
                                latest.ancMode, chosen[0], latest.voiceFocus, false);
                    }
                }
            });
            LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            int sbMarginV = Math.round(8f * d);
            sbLp.topMargin = sbMarginV;
            sbLp.bottomMargin = sbMarginV;
            container.addView(seekBar, sbLp);

            int themeRes = isNight
                    ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                    : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
            AlertDialog dlg = new AlertDialog.Builder(uiCtx, themeRes)
                    .setTitle(locText(ctx, "通透強度", "通透强度", "Ambient Level"))
                    .setView(container)
                    .setNegativeButton(locText(ctx, "取消", "取消", "Cancel"), null)
                    .setPositiveButton(locText(ctx, "確定", "确定", "OK"), (dialog, which) -> {
                        ConfigStore.HeadsetState latest = ConfigStore.readGlobalState(ctx);
                        if (latest.connected) {
                            ConfigStore.sendCommandBroadcast(
                                    ctx, ConfigStore.CMD_SET_AMBIENT_PARAMS, latest.mac,
                                    latest.ancMode, chosen[0], latest.voiceFocus, false);
                        }
                    })
                    .show();
            Button pos = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            if (pos != null) pos.setTextColor(0xFF00A896);
            Button neg = dlg.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (neg != null) neg.setTextColor(isNight ? 0xFFB8B8B8 : 0xFF666666);
        } catch (Throwable t) {
            XposedCompat.debug("showAsmLevelDialog failed: " + t.getMessage());
        }
    }

    private static void showCodecDialog(Object frag, Context ctx) {
        try {
            Context uiCtx = resolveActivityUiContext(frag, ctx);
            boolean isNight = (uiCtx.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
            ConfigStore.CodecState cs = ConfigStore.readGlobalCodecState(ctx);

            CharSequence[] items = new CharSequence[]{
                    locText(ctx, "LDAC（高解析音質）", "LDAC（高解析音质）", "LDAC (High Quality)"),
                    locText(ctx, "AAC（穩定連線與省電）", "AAC（稳定连接与省电）", "AAC (Stable & Battery)")
            };
            int checked = cs.activeCodec == ConfigStore.CODEC_AAC ? 1 : 0;
            int themeRes = isNight
                    ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                    : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;

            AlertDialog dlg = new AlertDialog.Builder(uiCtx, themeRes)
                    .setTitle(locText(ctx, "音訊編碼", "音频编码", "Audio Codec"))
                    .setSingleChoiceItems(items, checked, (dialog, which) -> {
                        int chosenCodec = which == 1 ? ConfigStore.CODEC_AAC : ConfigStore.CODEC_LDAC;
                        String targetMac = !cs.mac.isEmpty() ? cs.mac : st.mac;
                        ConfigStore.sendCodecCommandBroadcast(ctx, targetMac, chosenCodec);
                        dialog.dismiss();
                    })
                    .setNegativeButton(locText(ctx, "取消", "取消", "Cancel"), null)
                    .show();
            Button neg = dlg.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (neg != null) neg.setTextColor(isNight ? 0xFFB8B8B8 : 0xFF666666);
        } catch (Throwable t) {
            XposedCompat.debug("showCodecDialog failed: " + t.getMessage());
        }
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

    private interface BoolCallback {
        void onChanged(boolean newValue);
    }

    private static void setOnChangeListener(Object pref, ClassLoader cl, BoolCallback cb) {
        try {
            Class<?> listenerCls = XposedCompat.findClassIfExists(
                    "androidx.preference.Preference$c", cl);
            if (listenerCls == null) return;
            Object proxy = Proxy.newProxyInstance(
                    cl,
                    new Class<?>[]{listenerCls},
                    (p, method, args) -> {
                        if ("a".equals(method.getName()) && !sUpdatingFromState
                                && args != null && args.length >= 2 && args[1] instanceof Boolean) {
                            cb.onChanged((Boolean) args[1]);
                        }
                        return Boolean.TRUE;
                    });
            Method m = findMethod(pref.getClass(), "G0", listenerCls);
            if (m != null) m.invoke(pref, proxy);
        } catch (Throwable ignored) {
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

    private static void setSwitchChecked(Object pref, boolean checked) {
        invokeBool(pref, "W0", checked);
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
