package io.github.nicochanyo.sonypods;

import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core Bluetooth process hook (com.android.bluetooth).
 * Detects Sony headphones, runs SonyTandemSession over RFCOMM, bridges battery/ANC/Speak-to-Chat
 * natively into ColorOS AirPodsManager / OplusRemoteDevicesExtImpl (TRANSPORT_AIRPOD=2),
 * handles extra settings commands, and yields to Sony Sound Connect on handover.
 */
public final class BluetoothHook {
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService POPUP_WORK_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SonyPods-PopupWork");
        t.setDaemon(true);
        return t;
    });
    private static final ExecutorService STATE_PUBLISH_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SonyPods-StatePub");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicReference<ConfigStore.HeadsetState> PENDING_GLOBAL_STATE =
            new AtomicReference<>();
    private static final Map<String, SonyTandemSession> SESSIONS = new ConcurrentHashMap<>();
    private static final long POPUP_COOLDOWN_MS = 8_000L;
    private static final long PAIRING_POPUP_COOLDOWN_MS = 4_000L;
    private static final long DISMISS_POPUP_COOLDOWN_MS = 20_000L;
    private static final long BLE_EDGE_ABSENCE_MS = 2_000L;
    private static final long BOOT_POPUP_GRACE_MS = 45_000L;
    private static final long DISCONNECT_GRACE_MS = 1_500L;
    private static final int OPEN_CASE_MIN_RSSI = -88;
    private static final int FAST_PAIR_MIN_RSSI = -88;
    private static final long CONNECT_POPUP_BATTERY_WAIT_MS = 350L;
    private static final String ACTION_A2DP_CODEC_CONFIG_CHANGED =
            "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED";
    private static final Object POPUP_LOCK = new Object();
    private static final Object SCAN_LOCK = new Object();
    private static final int SCAN_TIER_OFF = 0;
    private static final int SCAN_TIER_LOW_POWER = 1;
    private static final int SCAN_TIER_BALANCED = 2;
    private static final int SCAN_TIER_BURST = 3;
    private static final long PAIRING_SCAN_BURST_MS = 12_000L;
    private static final long PAIRING_SCAN_BALANCED_MS = 45_000L;
    private static final long PAIRING_SCAN_LOW_POWER_REFRESH_MS = 25L * 60_000L;
    private static final long REACTIVE_BOOST_COOLDOWN_MS = 60_000L;

    private static volatile Context sBtContext;
    private static volatile ConfigStore.HookConfig sConfig;
    private static volatile boolean sReceiverRegistered;
    private static volatile long sLastPopupMs;
    private static volatile long sLastDisconnectMs;
    private static volatile long sLastAnySonyBleSeenMs;
    private static volatile long sDismissSuppressUntilMs;
    private static volatile long sLastFpDiscMs;
    private static volatile String sLastFpDiscAddr = "";
    private static volatile long sLastSonyAdvMs;
    private static volatile String sLastSonyAdvModel = "";
    private static volatile Method sScanClientPkgMethod;
    private static volatile Object sAdapterService;
    private static volatile Object sA2dpService;

    private static volatile ScanCallback sPairingScannerCallback;
    private static volatile boolean sPairingScanActive;
    private static volatile int sPairingScanTier = SCAN_TIER_OFF;
    private static volatile long sLastReactiveBoostMs;
    private static volatile Runnable sPairingScanStepDownTask;

    private static final Object PAIR_LOCK = new Object();
    private static volatile boolean sDiscoveringForPair;
    private static volatile String sPendingPairMac = "";
    private static volatile String sPendingPairModel = "";
    private static volatile String sPendingConnectMac = "";
    private static volatile String sPendingConnectModel = "";
    private static volatile BluetoothDevice sPendingExternalPairingDevice;
    private static volatile long sPendingExternalPairingMs;
    private static volatile Runnable sPairTimeoutTask;
    private static volatile boolean sPreDiscoveringForPopup;
    private static volatile String sPreDiscoverTargetModel = "";
    private static volatile BluetoothDevice sPreDiscoveredBredrDevice;
    private static volatile String sPreDiscoveredBredrModel = "";
    private static volatile long sPreDiscoveredBredrMs;
    private static volatile Runnable sPreDiscoverTimeoutTask;

    private static final long BLE_ADV_CACHE_TTL_MS = 3_500L;
    private static final long CROSS_RPA_WINDOW_MS = 3_000L;
    private static final long SCAN_RSP_MERGE_DELAY_MS = 40L;
    private static final Map<String, BleAdvInfo> BLE_ADV_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> RECENT_BREDR_SONY_BY_MODEL = new ConcurrentHashMap<>();
    private static final Set<String> PENDING_CONNECT_POPUP_MACS = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Boolean> IN_GET_REMOTE_UUIDS = new ThreadLocal<>();

    private static final class ParsedSonyAdv {
        boolean isSony;
        boolean hasFastPair;
        boolean isFastPairDiscoverable;
        boolean isLimitedDiscoverable;
        String modelName = "";
    }

    private static final class BleAdvInfo {
        boolean isSony;
        boolean hasFastPair;
        boolean isFastPairDiscoverable;
        boolean isLimitedDiscoverable;
        String modelName = "";
        long lastSeenMs;
        boolean popupScheduled;

        boolean isPairingMode() {
            return isSony && (isFastPairDiscoverable || isLimitedDiscoverable);
        }
    }

    private static final Runnable RELEASE_HANDOVER_TASK = () -> {
        for (SonyTandemSession session : SESSIONS.values()) {
            session.setYielded(false);
        }
    };

    private BluetoothHook() {
    }

    public static void install(ClassLoader cl, ConfigStore.HookConfig cfg) {
        sConfig = cfg;
        int hooked = 0;

        ColorOsNativeAirPodsBridge.install(cl, cfg, new ColorOsNativeAirPodsBridge.NativeControlCallback() {
            @Override
            public void onNoiseModeRequested(BluetoothDevice device, int ancMode) {
                SonyTandemSession target = findTargetSession(safeMac(device));
                if (target != null) {
                    ConfigStore.HeadsetState snap = target.getStateSnapshot();
                    target.setNcAsm(ancMode, snap.asmLevel, snap.voiceFocus);
                }
            }

            @Override
            public void onSpeakToChatRequested(BluetoothDevice device, boolean enabled) {
                SonyTandemSession target = findTargetSession(safeMac(device));
                if (target != null) {
                    target.setSpeakToChat(enabled);
                }
            }
        });

        Class<?> adapterCls = XposedCompat.findClassIfExists("com.android.bluetooth.btservice.AdapterService", cl);
        if (adapterCls != null) {
            XposedCompat.hookAllMethods(adapterCls, "onCreate", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    sAdapterService = param.thisObject;
                    if (param.thisObject instanceof Context) {
                        onBluetoothContextReady((Context) param.thisObject);
                    }
                }
            });
            hooked++;
        }

        Class<?> a2dpCls = XposedCompat.findClassIfExists("com.android.bluetooth.a2dp.A2dpService", cl);
        if (a2dpCls != null) {
            XposedCompat.hookAllConstructors(a2dpCls, new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    sA2dpService = param.thisObject;
                    if (param.thisObject instanceof Context) {
                        onBluetoothContextReady((Context) param.thisObject);
                    }
                }
            });
            XposedCompat.hookAllMethods(a2dpCls, "connectionStateChanged", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    if (param.args != null && param.args.length >= 3 && param.args[0] instanceof BluetoothDevice) {
                        BluetoothDevice dev = (BluetoothDevice) param.args[0];
                        int newState = (param.args[2] instanceof Integer) ? (Integer) param.args[2] : -1;
                        handleA2dpStateChange(dev, newState);
                    }
                }
            });
            hooked++;
        }

        // Fallback hook on Application.onCreate in com.android.bluetooth
        XposedCompat.hookAllMethods(android.app.Application.class, "onCreate", new XposedCompat.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                if (param.thisObject instanceof Context) {
                    onBluetoothContextReady((Context) param.thisObject);
                }
            }
        });

        hooked += hookNativePairingSuppressionAndOpenPopup(cl);

        XposedCompat.log("BluetoothHook installed (targets=" + hooked + ")");
    }

    private static synchronized void onBluetoothContextReady(Context ctx) {
        if (ctx == null) {
            return;
        }
        sBtContext = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        if (sReceiverRegistered) {
            return;
        }
        sReceiverRegistered = true;

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(ACTION_A2DP_CODEC_CONFIG_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(ConfigStore.ACTION_COMMAND);
        filter.addAction(ConfigStore.ACTION_HANDOVER);

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) {
                    return;
                }
                handleBroadcast(context, intent);
            }
        };

        try {
            if (Build.VERSION.SDK_INT >= 33) {
                sBtContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                sBtContext.registerReceiver(receiver, filter);
            }
            XposedCompat.log("BluetoothHook receiver registered");
        } catch (Throwable t) {
            XposedCompat.log("BluetoothHook registerReceiver failed: " + t.getMessage());
        }

        IntentFilter pairingFilter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        pairingFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        BroadcastReceiver pairingReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())) {
                    return;
                }
                ConfigStore.HookConfig cfg = sConfig;
                if (cfg != null) {
                    cfg.reload(false);
                    if (!cfg.suppressNativePairing) {
                        return;
                    }
                }
                BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (dev == null) {
                    return;
                }
                int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
                if (variant != 3 && variant != 2 && variant != -1) {
                    return;
                }
                if (ConfigStore.isRecentActivePair(context, dev, 30_000L)) {
                    try {
                        dev.setPairingConfirmation(true);
                    } catch (Throwable ignored) {
                    }
                    XposedCompat.log("Auto-confirmed Sony pairing request for " + stName(dev));
                    try {
                        abortBroadcast();
                    } catch (Throwable ignored) {
                    }
                } else if ((cfg == null || cfg.openCasePopup) && SonyDeviceDetector.isSonyDevice(dev)) {
                    synchronized (PAIR_LOCK) {
                        sPendingExternalPairingDevice = dev;
                        sPendingExternalPairingMs = SystemClock.elapsedRealtime();
                    }
                    XposedCompat.log("Intercepted Sony pairing request -> launching open-case popup for " + stName(dev));
                    maybeTriggerOpenCasePopup(
                            dev,
                            SonyDeviceDetector.normalizeModelName(stName(dev)),
                            "pairing_request",
                            true);
                }
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                sBtContext.registerReceiver(pairingReceiver, pairingFilter, Context.RECEIVER_EXPORTED);
            } else {
                sBtContext.registerReceiver(pairingReceiver, pairingFilter);
            }
        } catch (Throwable t) {
            XposedCompat.debug("BluetoothHook pairingReceiver register failed: " + t.getMessage());
        }

        ColorOsNativeAirPodsBridge.ensureExtHooksFromCache();
        probeAlreadyConnectedDevices(sBtContext);
        MAIN_HANDLER.postDelayed(() -> updateSonyPairingBleScan(true), 2000L);
    }

    private static void probeAlreadyConnectedDevices(Context ctx) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                return;
            }
            adapter.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    try {
                        List<BluetoothDevice> devs = proxy.getConnectedDevices();
                        if (devs != null) {
                            for (BluetoothDevice d : devs) {
                                if (SonyDeviceDetector.isSonyDevice(d) && !isBleOnlyOrLeAlias(d)) {
                                    startSessionForDevice(d, false);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        adapter.closeProfileProxy(profile, proxy);
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                }
            }, BluetoothProfile.A2DP);
        } catch (Throwable ignored) {
        }
    }

    private static void handleBroadcast(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
            handleA2dpStateChange(dev, state);
        } else if (ACTION_A2DP_CODEC_CONFIG_CHANGED.equals(action)) {
            BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (dev != null && SonyDeviceDetector.isSonyDevice(dev) && !isBleOnlyOrLeAlias(dev)) {
                syncA2dpCodecState(dev);
            }
        } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (dev != null && SonyDeviceDetector.isSonyDevice(dev) && !isBleOnlyOrLeAlias(dev)) {
                String modelNorm = SonyDeviceDetector.normalizeModelName(stName(dev));
                recordClassicSonyMac(modelNorm, safeMac(dev));
                if (isBondedSafe(dev)) {
                    startSessionForDevice(dev, true);
                } else if (SESSIONS.isEmpty()) {
                    updateSonyPairingBleScan(true);
                }
            }
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (dev != null) {
                stopSessionForAddress(safeMac(dev), dev);
                if (SESSIONS.isEmpty()) {
                    updateSonyPairingBleScan(true);
                }
            }
        } else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
            BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (dev != null) {
                handleDeviceFoundForPair(dev);
            }
        } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
            BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
            int prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE);
            if (dev != null) {
                handleBondStateChanged(dev, state, prev);
                if (state == BluetoothDevice.BOND_NONE && SESSIONS.isEmpty()) {
                    updateSonyPairingBleScan(true);
                }
            }
        } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            int btState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (btState == BluetoothAdapter.STATE_ON) {
                updateSonyPairingBleScan(true);
            } else if (btState == BluetoothAdapter.STATE_OFF || btState == BluetoothAdapter.STATE_TURNING_OFF) {
                stopAllSessions();
                stopSonyPairingBleScan();
            }
        } else if (Intent.ACTION_USER_PRESENT.equals(action) || Intent.ACTION_SCREEN_ON.equals(action)) {
            notifySessionsScreenState(true);
            updateSonyPairingBleScan(true);
        } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            notifySessionsScreenState(false);
            stopSonyPairingBleScan();
        } else if (ConfigStore.ACTION_COMMAND.equals(action)) {
            handleModuleCommand(intent);
        } else if (ConfigStore.ACTION_HANDOVER.equals(action)) {
            handleHandover(intent.getBooleanExtra(ConfigStore.EXTRA_HANDOVER_ACTIVE, false));
        }
    }

    private static boolean isScreenInteractiveNow() {
        Context ctx = sBtContext;
        if (ctx == null) {
            return true;
        }
        try {
            PowerManager pm = ctx.getSystemService(PowerManager.class);
            return pm == null || pm.isInteractive();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void notifySessionsScreenState(boolean interactive) {
        for (SonyTandemSession session : SESSIONS.values()) {
            if (session != null) {
                session.setScreenInteractive(interactive);
            }
        }
    }

    private static void handleA2dpStateChange(BluetoothDevice dev, int state) {
        if (dev == null) {
            return;
        }
        if (state == BluetoothProfile.STATE_CONNECTED && SonyDeviceDetector.isSonyDevice(dev) && !isBleOnlyOrLeAlias(dev)) {
            startSessionForDevice(dev, true);
            syncA2dpCodecState(dev);
            MAIN_HANDLER.postDelayed(() -> syncA2dpCodecState(dev), 1200L);
        } else if (state == BluetoothProfile.STATE_DISCONNECTED || state == BluetoothProfile.STATE_DISCONNECTING) {
            stopSessionForAddress(safeMac(dev), dev);
        }
    }

    private static void startSessionForDevice(BluetoothDevice device, boolean allowPopup) {
        if (device == null || isBleOnlyOrLeAlias(device) || !isBondedSafe(device)) {
            return;
        }
        String mac = safeMac(device);
        if (mac.isEmpty()) {
            return;
        }

        if (SESSIONS.containsKey(mac)) {
            return;
        }

        ConfigStore.HookConfig cfg = sConfig;
        if (cfg != null) {
            cfg.reload(false);
        }
        int defAsm = cfg != null ? cfg.defaultAsmLevel : ConfigStore.DEF_ASM_LEVEL;
        boolean defVf = cfg != null && cfg.defaultVoiceFocus;

        SonyTandemSession session = new SonyTandemSession(device, (dev, st) -> onSessionStateUpdated(dev, st), defAsm, defVf);
        SESSIONS.put(mac, session);
        stopSonyPairingBleScan();
        XposedCompat.log("Starting SonyTandemSession for " + mac + " (" + stName(device) + ")");
        session.setScreenInteractive(isScreenInteractiveNow());
        session.start();
        onSessionStateUpdated(device, session.getStateSnapshot());
        syncA2dpCodecState(device);
        MAIN_HANDLER.postDelayed(() -> syncA2dpCodecState(device), 1200L);

        boolean suppressPopupForDirectConnect = false;
        String resolvedModel = SonyDeviceDetector.normalizeModelName(stName(device));
        recordClassicSonyMac(resolvedModel, mac);
        synchronized (PAIR_LOCK) {
            if (mac.equalsIgnoreCase(sPendingPairMac) || mac.equalsIgnoreCase(sPendingConnectMac)) {
                cancelPairTimeoutLocked();
                if (!sPendingPairModel.isEmpty()) {
                    resolvedModel = sPendingPairModel;
                } else if (!sPendingConnectModel.isEmpty()) {
                    resolvedModel = sPendingConnectModel;
                }
                sDiscoveringForPair = false;
                sPendingPairMac = "";
                sPendingPairModel = "";
                sPendingConnectMac = "";
                sPendingConnectModel = "";
                suppressPopupForDirectConnect = true;
            }
        }
        if (suppressPopupForDirectConnect) {
            sendPairStatusBroadcast("connected", resolvedModel, mac, device);
        } else if (allowPopup && isBondedSafe(device)) {
            boolean wantConnectPopup = cfg != null
                    && cfg.openCasePopup
                    && cfg.openCasePopupMode == ConfigStore.POPUP_MODE_PAIRING_AND_CONNECT
                    && SystemClock.elapsedRealtime() >= BOOT_POPUP_GRACE_MS;
            if (wantConnectPopup) {
                PENDING_CONNECT_POPUP_MACS.add(mac);
                final String finalModel = resolvedModel;
                MAIN_HANDLER.postDelayed(() -> {
                    if (PENDING_CONNECT_POPUP_MACS.remove(mac) && SESSIONS.containsKey(mac)) {
                        POPUP_WORK_EXEC.execute(() ->
                                maybeTriggerOpenCasePopup(device, finalModel, "connect", false));
                    }
                }, CONNECT_POPUP_BATTERY_WAIT_MS);
            }
        }
    }

    private static void publishGlobalStateAsync(Context ctx, ConfigStore.HeadsetState state) {
        if (ctx == null || state == null) {
            return;
        }
        final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        PENDING_GLOBAL_STATE.set(state.copy());
        STATE_PUBLISH_EXEC.execute(() -> {
            ConfigStore.HeadsetState latest = PENDING_GLOBAL_STATE.getAndSet(null);
            if (latest != null) {
                ConfigStore.writeGlobalState(appCtx, latest);
            }
        });
    }

    private static void stopSessionForAddress(String mac, BluetoothDevice dev) {
        if (mac == null || mac.isEmpty()) {
            return;
        }
        PENDING_CONNECT_POPUP_MACS.remove(mac);
        SonyTandemSession removed = SESSIONS.remove(mac);
        if (removed != null) {
            long now = SystemClock.elapsedRealtime();
            synchronized (POPUP_LOCK) {
                sLastDisconnectMs = now;
                sLastPopupMs = 0L;
            }
            XposedCompat.log("Stopping SonyTandemSession for " + mac);
            removed.stop();
            BluetoothDevice targetDev = dev != null ? dev : removed.getDevice();
            ColorOsNativeAirPodsBridge.clearDevice(targetDev, mac);
            Context ctx = sBtContext;
            if (ctx != null && SESSIONS.isEmpty()) {
                publishGlobalStateAsync(ctx, new ConfigStore.HeadsetState());
                ConfigStore.writeGlobalCodecState(ctx, "", -1, false, false);
            }
            if (SESSIONS.isEmpty()) {
                MAIN_HANDLER.postDelayed(() -> updateSonyPairingBleScan(true), 400L);
            }
        }
    }

    private static void stopAllSessions() {
        PENDING_CONNECT_POPUP_MACS.clear();
        for (Map.Entry<String, SonyTandemSession> e : SESSIONS.entrySet()) {
            e.getValue().stop();
            ColorOsNativeAirPodsBridge.clearDevice(e.getValue().getDevice(), e.getKey());
        }
        SESSIONS.clear();
        stopSonyPairingBleScan();
        if (sBtContext != null) {
            publishGlobalStateAsync(sBtContext, new ConfigStore.HeadsetState());
            ConfigStore.writeGlobalCodecState(sBtContext, "", -1, false, false);
        }
    }

    private static void onSessionStateUpdated(BluetoothDevice device, ConfigStore.HeadsetState state) {
        Context ctx = sBtContext;
        if (ctx == null || state == null) {
            return;
        }
        ConfigStore.HookConfig cfg = sConfig;
        if (cfg != null) {
            cfg.reload(false);
        }
        ColorOsNativeAirPodsBridge.updateDeviceState(device, state);
        if (state.connected
                && (state.leftBattery >= 0 || state.rightBattery >= 0 || state.singleBattery >= 0)) {
            String mac = safeMac(device);
            if (!mac.isEmpty() && PENDING_CONNECT_POPUP_MACS.remove(mac)) {
                final String model = state.effectiveModelName();
                POPUP_WORK_EXEC.execute(() ->
                        maybeTriggerOpenCasePopup(device, model, "connect", false));
            }
        }
        publishGlobalStateAsync(ctx, state);
    }

    private static void handleModuleCommand(Intent intent) {
        String cmd = intent.getStringExtra(ConfigStore.EXTRA_CMD);
        if (cmd == null) {
            return;
        }
        ConfigStore.HookConfig cfg = sConfig;
        if (cfg != null) {
            cfg.reload(true);
        }

        String targetMac = intent.getStringExtra(ConfigStore.EXTRA_MAC);
        if (ConfigStore.CMD_PAIR_AND_CONNECT.equals(cmd)) {
            String targetModel = intent.getStringExtra(ConfigStore.EXTRA_MODEL);
            handlePairAndConnectCommand(targetMac, targetModel);
            return;
        }
        if (ConfigStore.CMD_DISMISS_PAIRING.equals(cmd)) {
            handleDismissPairingCommand();
            return;
        }
        if (ConfigStore.CMD_SET_CODEC.equals(cmd)) {
            int codecType = intent.getIntExtra(ConfigStore.EXTRA_CODEC_TYPE, -1);
            SonyTandemSession s = findTargetSession(targetMac);
            BluetoothDevice dev = s != null ? s.getDevice()
                    : resolveTargetConnectDevice(BluetoothAdapter.getDefaultAdapter(), targetMac != null ? targetMac : "", "");
            if (dev != null) {
                applyA2dpCodecPreference(dev, codecType);
            }
            if (s != null && s.getStateSnapshot().supportsConnectionMode) {
                s.setConnectionMode(codecType == ConfigStore.CODEC_AAC ? 1 : 0);
            }
            return;
        }

        SonyTandemSession target = findTargetSession(targetMac);
        if (target == null) {
            if (!ConfigStore.CMD_REFRESH.equals(cmd)) {
                XposedCompat.log("CMD " + cmd + " ignored: no active session (mac=" + targetMac + ")");
            }
            return;
        }
        if (!ConfigStore.CMD_REFRESH.equals(cmd)) {
            XposedCompat.log("CMD " + cmd + " mac=" + targetMac);
        }

        if (ConfigStore.CMD_SET_ANC.equals(cmd)) {
            ConfigStore.HeadsetState snap = target.getStateSnapshot();
            int mode = intent.getIntExtra(ConfigStore.EXTRA_ANC_MODE, snap.ancMode);
            int level = intent.getIntExtra(ConfigStore.EXTRA_ASM_LEVEL, snap.asmLevel);
            boolean vf = intent.hasExtra(ConfigStore.EXTRA_VOICE_FOCUS)
                    ? intent.getBooleanExtra(ConfigStore.EXTRA_VOICE_FOCUS, snap.voiceFocus)
                    : snap.voiceFocus;
            target.setNcAsm(mode, level, vf);
        } else if (ConfigStore.CMD_CYCLE_ANC.equals(cmd)) {
            ConfigStore.HeadsetState snap = target.getStateSnapshot();
            int order = cfg != null ? cfg.cycleMode : ConfigStore.CYCLE_3WAY;
            int next = nextCycleMode(snap.ancMode, order);
            target.setNcAsm(next, snap.asmLevel, snap.voiceFocus);
        } else if (ConfigStore.CMD_SET_SPEAK_TO_CHAT.equals(cmd)) {
            target.setSpeakToChat(intent.getBooleanExtra(ConfigStore.EXTRA_BOOL_VAL, false));
        } else if (ConfigStore.CMD_SET_DSEE.equals(cmd)) {
            target.setDsee(intent.getBooleanExtra(ConfigStore.EXTRA_BOOL_VAL, false));
        } else if (ConfigStore.CMD_SET_EQ.equals(cmd)) {
            ConfigStore.HeadsetState snap = target.getStateSnapshot();
            int presetId = intent.getIntExtra(ConfigStore.EXTRA_EQ_PRESET, snap.eqPresetId);
            int[] bands = intent.getIntArrayExtra(ConfigStore.EXTRA_EQ_BANDS);
            target.setEq(presetId, bands);
        } else if (ConfigStore.CMD_SET_SPEAK_DETAIL.equals(cmd)) {
            ConfigStore.HeadsetState snap = target.getStateSnapshot();
            int sens = intent.getIntExtra(ConfigStore.EXTRA_SPEAK_SENSITIVITY, snap.speakSensitivity);
            int out = intent.getIntExtra(ConfigStore.EXTRA_SPEAK_TIMEOUT, snap.speakTimeout);
            target.setSpeakToChatDetail(sens, out);
        } else if (ConfigStore.CMD_SET_TOUCH_SENSOR.equals(cmd)) {
            ConfigStore.HeadsetState snap = target.getStateSnapshot();
            int leftP = intent.getIntExtra(ConfigStore.EXTRA_TOUCH_LEFT, snap.touchLeftPreset);
            int rightP = intent.getIntExtra(ConfigStore.EXTRA_TOUCH_RIGHT, snap.touchRightPreset);
            target.setTouchSensor(leftP, rightP);
        } else if (ConfigStore.CMD_SET_MULTIPOINT.equals(cmd)) {
            target.setMultipoint(intent.getBooleanExtra(ConfigStore.EXTRA_BOOL_VAL, false));
        } else if (ConfigStore.CMD_MULTIPOINT_ACTION.equals(cmd)) {
            String dstMac = intent.getStringExtra(ConfigStore.EXTRA_TARGET_MAC);
            int actionType = intent.getIntExtra(ConfigStore.EXTRA_MULTIPOINT_ACTION,
                    SonyTandemProtocol.PERI_ACTION_CONNECT);
            target.sendMultipointAction(dstMac, actionType);
        } else if (ConfigStore.CMD_SET_CONNECTION_MODE.equals(cmd)) {
            int prior = intent.getIntExtra(ConfigStore.EXTRA_INT_VAL, 0);
            target.setConnectionMode(prior);
            if (target.getDevice() != null) {
                applyA2dpCodecPreference(target.getDevice(),
                        prior == 1 ? ConfigStore.CODEC_AAC : ConfigStore.CODEC_LDAC);
            }
        } else if (ConfigStore.CMD_SET_WEAR_DETECT.equals(cmd)) {
            target.setWearDetect(intent.getBooleanExtra(ConfigStore.EXTRA_BOOL_VAL, true));
        } else if (ConfigStore.CMD_SET_AUTO_POWER_OFF.equals(cmd)) {
            target.setAutoPowerOff(intent.getIntExtra(ConfigStore.EXTRA_INT_VAL, 0x10));
        } else if (ConfigStore.CMD_SET_AMBIENT_PARAMS.equals(cmd)) {
            ConfigStore.HeadsetState snap = target.getStateSnapshot();
            int level = intent.getIntExtra(ConfigStore.EXTRA_ASM_LEVEL, snap.asmLevel);
            boolean vf = intent.getBooleanExtra(ConfigStore.EXTRA_VOICE_FOCUS, snap.voiceFocus);
            target.updateAmbientPreferences(level, vf);
        } else if (ConfigStore.CMD_APPLY_DEFAULTS.equals(cmd)) {
            int defAsm = cfg != null ? cfg.defaultAsmLevel : ConfigStore.DEF_ASM_LEVEL;
            boolean defVf = cfg != null && cfg.defaultVoiceFocus;
            target.updateAmbientPreferences(defAsm, defVf);
        } else if (ConfigStore.CMD_REFRESH.equals(cmd)) {
            target.enqueueRefresh();
            onSessionStateUpdated(target.getDevice(), target.getStateSnapshot());
        }
    }

    private static int nextCycleMode(int current, int cycleOrder) {
        if (cycleOrder == ConfigStore.CYCLE_2WAY) {
            return (current == ConfigStore.ANC_NC) ? ConfigStore.ANC_AMBIENT : ConfigStore.ANC_NC;
        }
        if (current == ConfigStore.ANC_NC) return ConfigStore.ANC_AMBIENT;
        if (current == ConfigStore.ANC_AMBIENT) return ConfigStore.ANC_OFF;
        return ConfigStore.ANC_NC;
    }

    private static void handleHandover(boolean active) {
        ConfigStore.HookConfig cfg = sConfig;
        if (cfg != null) {
            cfg.reload(false);
            if (!cfg.handover) {
                return;
            }
        }
        MAIN_HANDLER.removeCallbacks(RELEASE_HANDOVER_TASK);
        if (active) {
            for (SonyTandemSession session : SESSIONS.values()) {
                session.setYielded(true);
            }
        } else {
            MAIN_HANDLER.postDelayed(RELEASE_HANDOVER_TASK, 2000L);
        }
    }

    private static SonyTandemSession findTargetSession(String mac) {
        if (mac != null && !mac.isEmpty()) {
            SonyTandemSession s = SESSIONS.get(mac.toUpperCase(Locale.US));
            if (s != null) {
                return s;
            }
        }
        for (SonyTandemSession s : SESSIONS.values()) {
            return s;
        }
        return null;
    }

    private static int hookNativePairingSuppressionAndOpenPopup(ClassLoader cl) {
        int hooked = 0;

        Class<?> scanControllerCls = XposedCompat.findClassIfExists("com.android.bluetooth.le_scan.ScanController", cl);
        if (scanControllerCls != null) {
            XposedCompat.hookAllMethods(scanControllerCls, "matchesFilters", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    if (!Boolean.TRUE.equals(param.getResult())) {
                        return;
                    }
                    if (param.args == null || param.args.length < 2 || !(param.args[1] instanceof ScanResult)) {
                        return;
                    }
                    String callingPkg = getScanClientPackage(param.args[0]);
                    if (callingPkg == null || !callingPkg.startsWith("com.google.android.gms")) {
                        return;
                    }
                    ConfigStore.HookConfig cfg = sConfig;
                    if (cfg != null) {
                        cfg.reload(false);
                        if (!cfg.suppressNativePairing) {
                            return;
                        }
                    }
                    ScanResult result = (ScanResult) param.args[1];
                    if (SonyDeviceDetector.isSonyScanResult(result)) {
                        param.setResult(Boolean.FALSE);
                        XposedCompat.debug("Suppressed GMS Fast Pair scan result for Sony device");
                    }
                }
            });

            XposedCompat.hookAllMethods(scanControllerCls, "sendResultsByPendingIntent", new XposedCompat.XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                    ConfigStore.HookConfig cfg = sConfig;
                    if (cfg != null) {
                        cfg.reload(false);
                        if (!cfg.suppressNativePairing) {
                            return;
                        }
                    }
                    if (param.args == null || param.args.length < 2
                            || !(param.args[0] instanceof PendingIntent)
                            || !(param.args[1] instanceof List)) {
                        return;
                    }
                    PendingIntent pi = (PendingIntent) param.args[0];
                    String pkg = pi.getCreatorPackage();
                    if (pkg == null || !pkg.startsWith("com.google.android.gms")) {
                        return;
                    }
                    List<?> rawList = (List<?>) param.args[1];
                    if (rawList.isEmpty()) {
                        return;
                    }
                    ArrayList<ScanResult> kept = new ArrayList<>(rawList.size());
                    boolean strippedAny = false;
                    for (Object item : rawList) {
                        if (!(item instanceof ScanResult)) {
                            continue;
                        }
                        ScanResult r = (ScanResult) item;
                        if (SonyDeviceDetector.isSonyScanResult(r)) {
                            strippedAny = true;
                        } else {
                            kept.add(r);
                        }
                    }
                    if (!strippedAny) {
                        return;
                    }
                    if (kept.isEmpty()) {
                        param.setResult(null);
                    } else {
                        param.args[1] = kept;
                    }
                }
            });

            XposedCompat.hookAllMethods(scanControllerCls, "onScanResultInternal", new XposedCompat.XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                    if (param.args == null || param.args.length < 10
                            || !(param.args[7] instanceof Integer)
                            || !(param.args[9] instanceof byte[])) {
                        return;
                    }
                    String advAddress = param.args[2] instanceof String ? ((String) param.args[2]).trim().toUpperCase(Locale.US) : "";
                    byte[] advData = (byte[]) param.args[9];
                    ParsedSonyAdv parsed = parseSonyBleAdv(advData);
                    if (parsed.isSony && !advAddress.isEmpty()) {
                        SonyDeviceDetector.markRecentSonyBleMac(advAddress);
                    }

                    int rssi = (Integer) param.args[7];
                    boolean isSonyBle = parsed.isSony || parsed.isFastPairDiscoverable
                            || (!advAddress.isEmpty() && SonyDeviceDetector.isRecentSonyBleMac(advAddress));
                    if (isSonyBle && rssi >= OPEN_CASE_MIN_RSSI) {
                        long now = SystemClock.elapsedRealtime();
                        synchronized (POPUP_LOCK) {
                            if (sLastAnySonyBleSeenMs != 0L && (now - sLastAnySonyBleSeenMs) >= BLE_EDGE_ABSENCE_MS) {
                                sLastPopupMs = 0L;
                                sDismissSuppressUntilMs = 0L;
                            }
                            sLastAnySonyBleSeenMs = now;
                        }
                    }

                    if (!SESSIONS.isEmpty() || isPairingOrConnecting()) {
                        return;
                    }
                    if (isSonyBle && rssi >= OPEN_CASE_MIN_RSSI && sPairingScanTier != SCAN_TIER_BURST) {
                        long nowBoost = SystemClock.elapsedRealtime();
                        boolean suppressed;
                        synchronized (POPUP_LOCK) {
                            suppressed = nowBoost < sDismissSuppressUntilMs
                                    || (sLastPopupMs != 0L && (nowBoost - sLastPopupMs) < REACTIVE_BOOST_COOLDOWN_MS);
                        }
                        if (!suppressed && (nowBoost - sLastReactiveBoostMs) >= REACTIVE_BOOST_COOLDOWN_MS) {
                            sLastReactiveBoostMs = nowBoost;
                            MAIN_HANDLER.post(() -> updateSonyPairingBleScanTier(SCAN_TIER_BURST));
                        }
                    }
                    ConfigStore.HookConfig cfg = sConfig;
                    if (cfg != null) {
                        cfg.reload(false);
                        if (!cfg.openCasePopup) {
                            return;
                        }
                    }
                    if (rssi < OPEN_CASE_MIN_RSSI || advAddress.isEmpty()) {
                        return;
                    }
                    handleSonyBleAdvForPopup(advAddress, parsed);
                }
            });
            hooked++;
        }

        Class<?> adapterServiceCls = XposedCompat.findClassIfExists("com.android.bluetooth.btservice.AdapterService", cl);
        if (adapterServiceCls != null) {
            XposedCompat.hookAllMethods(adapterServiceCls, "getRemoteUuids", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    if (Boolean.TRUE.equals(IN_GET_REMOTE_UUIDS.get())) {
                        return;
                    }
                    ConfigStore.HookConfig cfg = sConfig;
                    if (cfg != null) {
                        cfg.reload(false);
                        if (!cfg.suppressNativePairing) {
                            return;
                        }
                    }
                    if (!isCallerGms()) {
                        return;
                    }
                    Object res = param.getResult();
                    if (!(res instanceof ParcelUuid[]) || param.args == null) {
                        return;
                    }
                    IN_GET_REMOTE_UUIDS.set(Boolean.TRUE);
                    try {
                        BluetoothDevice dev = null;
                        for (Object arg : param.args) {
                            if (arg instanceof BluetoothDevice) {
                                dev = (BluetoothDevice) arg;
                                break;
                            }
                        }
                        ParcelUuid[] raw = (ParcelUuid[]) res;
                        if (dev == null || !isSonyDeviceNoUuidLookup(dev, raw)) {
                            return;
                        }
                        ArrayList<ParcelUuid> filtered = new ArrayList<>(raw.length);
                        boolean removed = false;
                        for (ParcelUuid pu : raw) {
                            if (pu != null && (SonyDeviceDetector.UUID_FAST_PAIR_RFCOMM.equals(pu.getUuid())
                                    || SonyDeviceDetector.UUID_FAST_PAIR_SERVICE.equals(pu.getUuid()))) {
                                removed = true;
                            } else if (pu != null) {
                                filtered.add(pu);
                            }
                        }
                        if (removed) {
                            param.setResult(filtered.toArray(new ParcelUuid[0]));
                            XposedCompat.debug("Stripped Fast Pair UUIDs for GMS retroactive pair block: " + stName(dev));
                        }
                    } finally {
                        IN_GET_REMOTE_UUIDS.remove();
                    }
                }
            });
            XposedCompat.hookAllMethods(adapterServiceCls, "getMetadata", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    ConfigStore.HookConfig cfg = sConfig;
                    if (cfg != null) {
                        cfg.reload(false);
                        if (!cfg.suppressNativePairing) {
                            return;
                        }
                    }
                    if (param.args == null || param.args.length < 2
                            || !(param.args[0] instanceof BluetoothDevice)
                            || !(param.args[1] instanceof Integer)) {
                        return;
                    }
                    BluetoothDevice dev = (BluetoothDevice) param.args[0];
                    if (!SonyDeviceDetector.isSonyMacOrBonded(dev)) {
                        return;
                    }
                    Object res = param.getResult();
                    if (!(res instanceof byte[])) {
                        return;
                    }
                    int key = (Integer) param.args[1];
                    if (shouldBlockFastPairMetadata(key, (byte[]) res)) {
                        param.setResult(null);
                    }
                }
            });

            XposedCompat.hookAllMethods(adapterServiceCls, "setMetadata", new XposedCompat.XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                    ConfigStore.HookConfig cfg = sConfig;
                    if (cfg != null) {
                        cfg.reload(false);
                        if (!cfg.suppressNativePairing) {
                            return;
                        }
                    }
                    if (param.args == null || param.args.length < 3) {
                        return;
                    }
                    BluetoothDevice dev = null;
                    int key = -1;
                    byte[] val = null;
                    if (param.args[0] instanceof BluetoothDevice && param.args[1] instanceof Integer) {
                        dev = (BluetoothDevice) param.args[0];
                        key = (Integer) param.args[1];
                        if (param.args[2] instanceof byte[]) {
                            val = (byte[]) param.args[2];
                        }
                    } else if (param.args[0] instanceof Integer && param.args[1] instanceof BluetoothDevice) {
                        key = (Integer) param.args[0];
                        dev = (BluetoothDevice) param.args[1];
                        if (param.args[2] instanceof String) {
                            val = ((String) param.args[2]).getBytes(StandardCharsets.UTF_8);
                        }
                    }
                    if (dev == null || !SonyDeviceDetector.isSonyMacOrBonded(dev)) {
                        return;
                    }
                    if (shouldBlockFastPairMetadata(key, val)) {
                        Method m = param.method instanceof Method ? (Method) param.method : null;
                        if (m != null && m.getReturnType() == boolean.class) {
                            param.setResult(Boolean.FALSE);
                        } else {
                            param.setResult(null);
                        }
                    }
                }
            });
            hooked++;
        }

        return hooked;
    }

    private static String getScanClientPackage(Object scanClient) {
        if (scanClient == null) {
            return null;
        }
        try {
            Method m = sScanClientPkgMethod;
            if (m == null) {
                m = scanClient.getClass().getMethod("getCallingPackage");
                m.setAccessible(true);
                sScanClientPkgMethod = m;
            }
            Object pkg = m.invoke(scanClient);
            return pkg instanceof String ? (String) pkg : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean shouldBlockFastPairMetadata(int key, byte[] payload) {
        if (key == 16 || key == 25) {
            return true;
        }
        if (payload == null || payload.length == 0) {
            return false;
        }
        if (key == 5 || key == 7 || key == 8 || key == 9 || key == 17 || key == 20) {
            String text = new String(payload, StandardCharsets.UTF_8).toLowerCase(Locale.US);
            return text.contains("gms")
                    || text.contains("fastpair")
                    || text.contains("gstatic")
                    || text.contains("google")
                    || text.contains("companion");
        }
        return false;
    }

    /**
     * Parses raw BLE advertising PDUs with strict pairing-mode classification.
     */
    private static ParsedSonyAdv parseSonyBleAdv(byte[] adv) {
        ParsedSonyAdv out = new ParsedSonyAdv();
        if (adv == null || adv.length < 2) {
            return out;
        }
        int pos = 0;
        while (pos < adv.length) {
            int len = adv[pos++] & 0xFF;
            if (len == 0 || pos >= adv.length) {
                break;
            }
            int type = adv[pos] & 0xFF;
            int dataLen = len - 1;
            int dataStart = pos + 1;
            if (dataLen < 0 || dataStart + dataLen > adv.length) {
                break;
            }
            if (type == 0x01 && dataLen >= 1) {
                int flags = adv[dataStart] & 0xFF;
                if ((flags & 0x01) != 0) {
                    out.isLimitedDiscoverable = true;
                }
            } else if ((type == 0x02 || type == 0x03) && dataLen >= 2) {
                for (int off = 0; off + 1 < dataLen; off += 2) {
                    int uuid16 = (adv[dataStart + off] & 0xFF) | ((adv[dataStart + off + 1] & 0xFF) << 8);
                    if (uuid16 == 0xFE2C) {
                        out.hasFastPair = true;
                    }
                }
            } else if (type == 0xFF && dataLen >= 2) {
                int companyId = (adv[dataStart] & 0xFF) | ((adv[dataStart + 1] & 0xFF) << 8);
                if (companyId == SonyDeviceDetector.SONY_MANUFACTURER_ID) {
                    out.isSony = true;
                }
            } else if (type == 0x16 && dataLen >= 2) {
                int uuid16 = (adv[dataStart] & 0xFF) | ((adv[dataStart + 1] & 0xFF) << 8);
                if (uuid16 == 0xFE2C) {
                    out.hasFastPair = true;
                    if (dataLen == 6 && (adv[dataStart + 2] & 0xFF) == 0x00) {
                        byte[] fpIdExt = new byte[] {
                                adv[dataStart + 3],
                                adv[dataStart + 4],
                                adv[dataStart + 5]
                        };
                        String fpModelExt = SonyDeviceDetector.getSonyFastPairModelName(fpIdExt);
                        if (!fpModelExt.isEmpty()) {
                            out.isFastPairDiscoverable = true;
                            out.isSony = true;
                            if (out.modelName.isEmpty()) {
                                out.modelName = fpModelExt;
                            }
                        }
                    }
                    if (dataLen >= 5) {
                        byte[] fpId = new byte[] {
                                adv[dataStart + 2],
                                adv[dataStart + 3],
                                adv[dataStart + 4]
                        };
                        String fpModel = SonyDeviceDetector.getSonyFastPairModelName(fpId);
                        if (!fpModel.isEmpty()) {
                            out.isFastPairDiscoverable = true;
                            out.isSony = true;
                            if (out.modelName.isEmpty()) {
                                out.modelName = fpModel;
                            }
                        } else if (dataLen == 5 && SonyDeviceDetector.isFastPairDiscoverablePayload(fpId)) {
                            out.isFastPairDiscoverable = true;
                        }
                    }
                }
            } else if ((type == 0x08 || type == 0x09) && dataLen > 0) {
                String candidate = new String(adv, dataStart, dataLen, StandardCharsets.UTF_8).trim();
                if (SonyDeviceDetector.isSonyName(candidate)) {
                    out.isSony = true;
                    String norm = SonyDeviceDetector.normalizeModelName(candidate);
                    if (!norm.isEmpty() && !"Sony".equalsIgnoreCase(norm)) {
                        out.modelName = norm;
                    }
                }
            }
            pos = dataStart + dataLen;
        }
        return out;
    }

    private static void handleSonyBleAdvForPopup(String advAddress, ParsedSonyAdv parsed) {
        if (parsed == null || (!parsed.isSony && !parsed.hasFastPair && !parsed.isLimitedDiscoverable)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        boolean fpCorrMatch = false;
        String fpCorrModel = "";
        boolean sonyCorrMatch = false;
        synchronized (POPUP_LOCK) {
            if (parsed.isSony && !parsed.modelName.isEmpty()
                    && !"Sony".equalsIgnoreCase(parsed.modelName)) {
                sLastSonyAdvMs = now;
                sLastSonyAdvModel = parsed.modelName;
                if (sLastFpDiscMs != 0L && (now - sLastFpDiscMs) <= CROSS_RPA_WINDOW_MS) {
                    sonyCorrMatch = true;
                }
            }
            if (parsed.isFastPairDiscoverable) {
                sLastFpDiscMs = now;
                sLastFpDiscAddr = advAddress;
                if (sLastSonyAdvMs != 0L && (now - sLastSonyAdvMs) <= CROSS_RPA_WINDOW_MS
                        && !sLastSonyAdvModel.isEmpty()) {
                    fpCorrMatch = true;
                    fpCorrModel = sLastSonyAdvModel;
                }
            }
        }

        if (BLE_ADV_CACHE.size() > 32) {
            BLE_ADV_CACHE.entrySet().removeIf(e -> (now - e.getValue().lastSeenMs) > BLE_ADV_CACHE_TTL_MS);
        }
        BleAdvInfo info = BLE_ADV_CACHE.get(advAddress);
        if (info == null || (now - info.lastSeenMs) > BLE_ADV_CACHE_TTL_MS) {
            info = new BleAdvInfo();
            BLE_ADV_CACHE.put(advAddress, info);
        }

        final BleAdvInfo merged = info;
        final boolean fireNow;
        final boolean scheduleWait;
        synchronized (merged) {
            merged.lastSeenMs = now;
            if (parsed.isSony) {
                merged.isSony = true;
            }
            if (parsed.hasFastPair) {
                merged.hasFastPair = true;
                if (parsed.isFastPairDiscoverable) {
                    merged.isFastPairDiscoverable = true;
                }
            }
            if (parsed.isLimitedDiscoverable) {
                merged.isLimitedDiscoverable = true;
            }
            if (!parsed.modelName.isEmpty()) {
                merged.modelName = parsed.modelName;
            }
            if (fpCorrMatch) {
                merged.isSony = true;
                if (merged.modelName.isEmpty()) {
                    merged.modelName = fpCorrModel;
                }
            }
            if (sonyCorrMatch) {
                merged.hasFastPair = true;
                merged.isFastPairDiscoverable = true;
            }
            if (!merged.isPairingMode()) {
                return;
            }
            synchronized (POPUP_LOCK) {
                if (isPopupInCooldownOrGraceLocked(now, true)) {
                    return;
                }
            }
            if (!merged.modelName.isEmpty()) {
                merged.popupScheduled = false;
                fireNow = true;
                scheduleWait = false;
            } else if (!merged.popupScheduled) {
                merged.popupScheduled = true;
                fireNow = false;
                scheduleWait = true;
            } else {
                fireNow = false;
                scheduleWait = false;
            }
        }

        if (fireNow) {
            POPUP_WORK_EXEC.execute(() -> dispatchBleAdvPopupNow(advAddress, merged));
        } else if (scheduleWait) {
            MAIN_HANDLER.postDelayed(
                    () -> POPUP_WORK_EXEC.execute(() -> dispatchBleAdvPopupNow(advAddress, merged)),
                    SCAN_RSP_MERGE_DELAY_MS);
        }
    }

    private static void dispatchBleAdvPopupNow(String advAddress, BleAdvInfo merged) {
        if (merged == null || !SESSIONS.isEmpty() || isPairingOrConnecting()) {
            return;
        }
        ConfigStore.HookConfig cfg = sConfig;
        if (cfg != null) {
            cfg.reload(false);
            if (!cfg.openCasePopup) {
                return;
            }
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (POPUP_LOCK) {
            if (isPopupInCooldownOrGraceLocked(now, true)) {
                return;
            }
        }
        String modelHint;
        synchronized (merged) {
            merged.popupScheduled = false;
            if (!merged.isPairingMode() || (now - merged.lastSeenMs) > BLE_ADV_CACHE_TTL_MS) {
                return;
            }
            modelHint = merged.modelName;
        }
        if (modelHint.isEmpty()) {
            String btName = resolveRawBtName(advAddress);
            if (SonyDeviceDetector.isSonyName(btName)) {
                modelHint = SonyDeviceDetector.normalizeModelName(btName);
            }
        }
        BluetoothDevice target = resolveSonyPopupDevice(advAddress, modelHint);
        if (target == null) {
            return;
        }
        String finalModel = !modelHint.isEmpty()
                ? SonyDeviceDetector.normalizeModelName(modelHint)
                : SonyDeviceDetector.normalizeModelName(resolveRawBtName(advAddress));
        if (finalModel.isEmpty()) {
            finalModel = SonyDeviceDetector.normalizeModelName(stName(target));
        }
        if ((!isBleOnlyOrLeAlias(target) && isBondedSafe(target))
                || hasBondedClassicSonyForModel(finalModel)) {
            return;
        }
        maybeTriggerOpenCasePopup(target, finalModel, "ble_adv", true);
    }

    private static boolean hasBondedClassicSonyForModel(String model) {
        if (model == null || model.trim().isEmpty() || "Sony".equalsIgnoreCase(model.trim())) {
            return false;
        }
        String targetNorm = SonyDeviceDetector.normalizeModelName(model);
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                return false;
            }
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    if (d != null && !isBleOnlyOrLeAlias(d) && SonyDeviceDetector.isSonyDevice(d)) {
                        String dNorm = SonyDeviceDetector.normalizeModelName(stName(d));
                        if (targetNorm.equalsIgnoreCase(dNorm)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isPopupInCooldownOrGraceLocked(long now, boolean isPairingModeTrigger) {
        if (sDismissSuppressUntilMs != 0L && now < sDismissSuppressUntilMs) {
            return true;
        }
        long cooldownMs = isPairingModeTrigger ? PAIRING_POPUP_COOLDOWN_MS : POPUP_COOLDOWN_MS;
        if (sLastPopupMs != 0L && (now - sLastPopupMs) < cooldownMs) {
            return true;
        }
        return !isPairingModeTrigger
                && sLastDisconnectMs != 0L
                && (now - sLastDisconnectMs) < DISCONNECT_GRACE_MS;
    }

    private static BluetoothDevice resolveSonyPopupDevice(String bleAddress, String detectedName) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                return null;
            }
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                if (bleAddress != null && !bleAddress.isEmpty()) {
                    for (BluetoothDevice d : bonded) {
                        if (d != null && !isBleOnlyOrLeAlias(d)
                                && bleAddress.equalsIgnoreCase(d.getAddress())
                                && SonyDeviceDetector.isSonyDevice(d)) {
                            return d;
                        }
                    }
                }
                boolean hasSpecificModel = detectedName != null && !detectedName.trim().isEmpty()
                        && !"Sony".equalsIgnoreCase(detectedName.trim());
                if (hasSpecificModel) {
                    String norm = SonyDeviceDetector.normalizeModelName(detectedName);
                    for (BluetoothDevice d : bonded) {
                        if (d != null && !isBleOnlyOrLeAlias(d) && SonyDeviceDetector.isSonyDevice(d)) {
                            String dNorm = SonyDeviceDetector.normalizeModelName(stName(d));
                            if (norm.equalsIgnoreCase(dNorm)) {
                                return d;
                            }
                        }
                    }
                    // Unpaired model: do NOT substitute a different bonded Sony headset.
                    if (bleAddress != null && BluetoothAdapter.checkBluetoothAddress(bleAddress)) {
                        return adapter.getRemoteDevice(bleAddress);
                    }
                } else {
                    BluetoothDevice singleBonded = null;
                    int bondedSonyCount = 0;
                    for (BluetoothDevice d : bonded) {
                        if (d != null && !isBleOnlyOrLeAlias(d) && SonyDeviceDetector.isSonyDevice(d)) {
                            singleBonded = d;
                            bondedSonyCount++;
                        }
                    }
                    if (bondedSonyCount == 1 && singleBonded != null) {
                        return singleBonded;
                    }
                }
            }
            if (bleAddress != null && BluetoothAdapter.checkBluetoothAddress(bleAddress)) {
                return adapter.getRemoteDevice(bleAddress);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void handlePairAndConnectCommand(String mac, String model) {
        Context ctx = sBtContext;
        if (ctx == null) {
            return;
        }
        String cleanMac = mac != null ? mac.trim().toUpperCase(Locale.US) : "";
        String cleanModel = model != null ? model.trim() : "";
        if (!cleanMac.isEmpty() && SESSIONS.containsKey(cleanMac)) {
            SonyTandemSession existing = SESSIONS.get(cleanMac);
            BluetoothDevice dev = existing != null ? existing.getDevice() : null;
            sendPairStatusBroadcast("connected", !cleanModel.isEmpty() ? cleanModel : stName(dev), cleanMac, dev);
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            sendPairStatusBroadcast("failed", cleanModel, cleanMac, null);
            return;
        }

        BluetoothDevice dev = resolveTargetConnectDevice(adapter, cleanMac, cleanModel);
        if (dev != null && dev.getBondState() == BluetoothDevice.BOND_BONDED && !isBleOnlyOrLeAlias(dev)) {
            stopSonyPairingBleScan();
            String devMac = safeMac(dev);
            if (SESSIONS.containsKey(devMac) || isDeviceConnectedSafe(dev)) {
                sendPairStatusBroadcast("connected", !cleanModel.isEmpty() ? cleanModel : stName(dev), devMac, dev);
                return;
            }
            synchronized (PAIR_LOCK) {
                cancelPairTimeoutLocked();
                sDiscoveringForPair = false;
                sPendingPairMac = "";
                sPendingConnectMac = devMac;
                sPendingConnectModel = !cleanModel.isEmpty() ? cleanModel : SonyDeviceDetector.normalizeModelName(stName(dev));
                schedulePairTimeoutLocked(15_000L, cleanModel, devMac);
            }
            sendPairStatusBroadcast("connecting", cleanModel, devMac, dev);
            connectProfilesForDevice(dev);
            XposedCompat.log("Initiated profile reconnect for bonded Sony " + cleanModel + " [" + devMac + "]");
            return;
        }

        // Check if an intercepted native pairing request (BOND_BONDING) is waiting for user confirmation via popup
        BluetoothDevice bondingDev = null;
        synchronized (PAIR_LOCK) {
            BluetoothDevice ext = sPendingExternalPairingDevice;
            if (ext != null && (SystemClock.elapsedRealtime() - sPendingExternalPairingMs) < 25_000L) {
                String extMac = safeMac(ext);
                String extNorm = SonyDeviceDetector.normalizeModelName(stName(ext));
                boolean macMatch = !cleanMac.isEmpty() && cleanMac.equalsIgnoreCase(extMac);
                boolean modelMatch = !cleanModel.isEmpty() && !"Sony".equalsIgnoreCase(cleanModel)
                        && extNorm.equalsIgnoreCase(SonyDeviceDetector.normalizeModelName(cleanModel));
                if (macMatch || modelMatch || cleanMac.isEmpty()) {
                    bondingDev = ext;
                }
            }
        }
        if (bondingDev == null && dev != null && !isBleOnlyOrLeAlias(dev)
                && getBondStateSafe(dev) == BluetoothDevice.BOND_BONDING) {
            bondingDev = dev;
        }
        if (bondingDev != null && getBondStateSafe(bondingDev) == BluetoothDevice.BOND_BONDING) {
            stopSonyPairingBleScan();
            String targetMac = safeMac(bondingDev);
            if (cleanModel.isEmpty() || "Sony".equalsIgnoreCase(cleanModel)) {
                cleanModel = SonyDeviceDetector.normalizeModelName(stName(bondingDev));
            }
            ConfigStore.writeActivePairMarker(ctx, targetMac, cleanModel);
            synchronized (PAIR_LOCK) {
                cancelPairTimeoutLocked();
                sPendingExternalPairingDevice = null;
                sPendingExternalPairingMs = 0L;
                sDiscoveringForPair = false;
                sPendingPairMac = targetMac;
                sPendingPairModel = cleanModel;
                schedulePairTimeoutLocked(18_000L, cleanModel, targetMac);
            }
            sendPairStatusBroadcast("pairing", cleanModel, targetMac, bondingDev);
            try {
                bondingDev.setPairingConfirmation(true);
            } catch (Throwable ignored) {
            }
            XposedCompat.log("Confirmed deferred Sony pairing request for " + cleanModel + " [" + targetMac + "]");
            return;
        }

        // Unbonded initial pairing flow
        if (isConfirmedBredrDevice(dev) && BluetoothAdapter.checkBluetoothAddress(cleanMac)) {
            stopSonyPairingBleScan();
            ConfigStore.writeActivePairMarker(ctx, cleanMac, cleanModel);
            sendPairStatusBroadcast("pairing", cleanModel, cleanMac, dev);
            synchronized (PAIR_LOCK) {
                cancelPairTimeoutLocked();
                sDiscoveringForPair = false;
                sPendingPairMac = cleanMac;
                sPendingPairModel = cleanModel;
                schedulePairTimeoutLocked(18_000L, cleanModel, cleanMac);
            }
            if (!createBondBredr(dev)) {
                synchronized (PAIR_LOCK) {
                    cancelPairTimeoutLocked();
                    sPendingPairMac = "";
                }
                ConfigStore.clearActivePairMarker(ctx);
                sendPairStatusBroadcast("failed", cleanModel, cleanMac, dev);
                MAIN_HANDLER.postDelayed(() -> updateSonyPairingBleScan(false), 1500L);
            } else {
                XposedCompat.log("Started direct BR/EDR bond for " + cleanModel + " [" + cleanMac + "]");
            }
            return;
        }

        // BLE / unknown-type source: check cached Classic MAC ONLY if already bonded (then reconnect),
        // otherwise run Classic BR/EDR discovery to resolve the live BR/EDR address and bond.
        String normModel = !cleanModel.isEmpty() ? SonyDeviceDetector.normalizeModelName(cleanModel) : "";
        String cachedClassicMac = !normModel.isEmpty() ? RECENT_BREDR_SONY_BY_MODEL.get(normModel.toUpperCase(Locale.US)) : null;
        if (cachedClassicMac != null && BluetoothAdapter.checkBluetoothAddress(cachedClassicMac)) {
            try {
                BluetoothDevice cachedDev = adapter.getRemoteDevice(cachedClassicMac);
                if (cachedDev != null && cachedDev.getBondState() == BluetoothDevice.BOND_BONDED
                        && !isBleOnlyOrLeAlias(cachedDev)) {
                    stopSonyPairingBleScan();
                    synchronized (PAIR_LOCK) {
                        cancelPairTimeoutLocked();
                        sDiscoveringForPair = false;
                        sPendingPairMac = "";
                        sPendingConnectMac = cachedClassicMac;
                        sPendingConnectModel = cleanModel;
                        schedulePairTimeoutLocked(15_000L, cleanModel, cachedClassicMac);
                    }
                    sendPairStatusBroadcast("connecting", cleanModel, cachedClassicMac, cachedDev);
                    connectProfilesForDevice(cachedDev);
                    XposedCompat.log("Initiated cached Classic reconnect for bonded Sony " + cleanModel + " [" + cachedClassicMac + "]");
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        // Pre-discovered Classic BR/EDR device ready for instant zero-discovery bond, or promote active pre-discovery
        synchronized (PAIR_LOCK) {
            long nowPre = SystemClock.elapsedRealtime();
            BluetoothDevice preDev = sPreDiscoveredBredrDevice;
            String preModel = sPreDiscoveredBredrModel;
            long preMs = sPreDiscoveredBredrMs;
            if (preDev != null && (nowPre - preMs) < 25_000L && !isBleOnlyOrLeAlias(preDev)) {
                String preNorm = !preModel.isEmpty()
                        ? preModel : SonyDeviceDetector.normalizeModelName(stName(preDev));
                boolean matchesModel = cleanModel.isEmpty()
                        || "Sony".equalsIgnoreCase(cleanModel)
                        || preNorm.isEmpty()
                        || "Sony".equalsIgnoreCase(preNorm)
                        || SonyDeviceDetector.normalizeModelName(cleanModel).equalsIgnoreCase(preNorm);
                if (matchesModel) {
                    sPreDiscoveredBredrDevice = null;
                    sPreDiscoveredBredrModel = "";
                    sPreDiscoveredBredrMs = 0L;
                    sPreDiscoveringForPopup = false;
                    sPreDiscoverTargetModel = "";
                    cancelPreDiscoverTimeoutLocked();
                    String preMac = safeMac(preDev);
                    if (BluetoothAdapter.checkBluetoothAddress(preMac)) {
                        stopSonyPairingBleScan();
                        String bondModel = !cleanModel.isEmpty() && !"Sony".equalsIgnoreCase(cleanModel)
                                ? cleanModel : (!preNorm.isEmpty() ? preNorm : "Sony");
                        ConfigStore.writeActivePairMarker(ctx, preMac, bondModel);
                        if (getBondStateSafe(preDev) == BluetoothDevice.BOND_BONDED) {
                            ConfigStore.clearActivePairMarker(ctx);
                            sDiscoveringForPair = false;
                            sPendingPairMac = "";
                            sPendingPairModel = "";
                            sPendingConnectMac = preMac;
                            sPendingConnectModel = bondModel;
                            schedulePairTimeoutLocked(15_000L, bondModel, preMac);
                            sendPairStatusBroadcast("connecting", bondModel, preMac, preDev);
                            connectProfilesForDevice(preDev);
                            XposedCompat.log("Instant reconnect via pre-discovered Sony " + bondModel + " [" + preMac + "]");
                            return;
                        }
                        sDiscoveringForPair = false;
                        sPendingPairMac = preMac;
                        sPendingPairModel = bondModel;
                        schedulePairTimeoutLocked(18_000L, bondModel, preMac);
                        sendPairStatusBroadcast("pairing", bondModel, preMac, preDev);
                        try {
                            if (adapter.isDiscovering()) {
                                adapter.cancelDiscovery();
                            }
                        } catch (Throwable ignored) {
                        }
                        if (!createBondBredr(preDev)) {
                            cancelPairTimeoutLocked();
                            sPendingPairMac = "";
                            sPendingPairModel = "";
                            ConfigStore.clearActivePairMarker(ctx);
                            sendPairStatusBroadcast("failed", bondModel, preMac, preDev);
                            MAIN_HANDLER.postDelayed(() -> updateSonyPairingBleScan(false), 1500L);
                        } else {
                            XposedCompat.log("Started instant BR/EDR bond from pre-discovery: "
                                    + bondModel + " [" + preMac + "]");
                        }
                        return;
                    }
                }
            }

            if (sPreDiscoveringForPopup) {
                boolean activelyDiscovering = false;
                try {
                    activelyDiscovering = adapter.isDiscovering();
                } catch (Throwable ignored) {
                }
                sPreDiscoveringForPopup = false;
                sPreDiscoverTargetModel = "";
                cancelPreDiscoverTimeoutLocked();
                if (activelyDiscovering) {
                    sPendingPairMac = "";
                    sPendingPairModel = cleanModel;
                    sDiscoveringForPair = true;
                    schedulePairTimeoutLocked(12_000L, cleanModel, cleanMac);
                    ConfigStore.writeActivePairMarker(ctx, cleanMac, cleanModel);
                    sendPairStatusBroadcast("pairing", cleanModel, cleanMac, dev);
                    XposedCompat.log("Promoted active BR/EDR pre-discovery to pairing for " + cleanModel);
                    return;
                }
            }
        }

        stopSonyPairingBleScan();
        ConfigStore.writeActivePairMarker(ctx, cleanMac, cleanModel);
        sendPairStatusBroadcast("pairing", cleanModel, cleanMac, dev);
        synchronized (PAIR_LOCK) {
            cancelPairTimeoutLocked();
            sPendingPairMac = "";
            sPendingPairModel = cleanModel;
            sDiscoveringForPair = true;
            schedulePairTimeoutLocked(12_000L, cleanModel, cleanMac);
        }
        try {
            if (adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
            boolean started = adapter.startDiscovery();
            XposedCompat.log("Started BR/EDR discovery to pair " + cleanModel + " (started=" + started + ")");
            if (!started) {
                synchronized (PAIR_LOCK) {
                    cancelPairTimeoutLocked();
                    sDiscoveringForPair = false;
                }
                ConfigStore.clearActivePairMarker(ctx);
                sendPairStatusBroadcast("failed", cleanModel, cleanMac, null);
                MAIN_HANDLER.postDelayed(() -> updateSonyPairingBleScan(false), 1500L);
            }
        } catch (Throwable t) {
            synchronized (PAIR_LOCK) {
                cancelPairTimeoutLocked();
                sDiscoveringForPair = false;
            }
            ConfigStore.clearActivePairMarker(ctx);
            sendPairStatusBroadcast("failed", cleanModel, cleanMac, null);
            MAIN_HANDLER.postDelayed(() -> updateSonyPairingBleScan(false), 1500L);
        }
    }

    private static BluetoothDevice resolveTargetConnectDevice(BluetoothAdapter adapter, String mac, String model) {
        if (adapter == null) return null;
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                if (!mac.isEmpty()) {
                    for (BluetoothDevice d : bonded) {
                        if (d != null && !isBleOnlyOrLeAlias(d) && mac.equalsIgnoreCase(d.getAddress())) {
                            return d;
                        }
                    }
                }
                if (!model.isEmpty() && !"Sony".equalsIgnoreCase(model)) {
                    String norm = SonyDeviceDetector.normalizeModelName(model);
                    for (BluetoothDevice d : bonded) {
                        if (d != null && !isBleOnlyOrLeAlias(d) && SonyDeviceDetector.isSonyDevice(d)) {
                            String dn = SonyDeviceDetector.normalizeModelName(stName(d));
                            if (norm.equalsIgnoreCase(dn)) {
                                return d;
                            }
                        }
                    }
                }
            }
            if (!mac.isEmpty() && BluetoothAdapter.checkBluetoothAddress(mac)) {
                return adapter.getRemoteDevice(mac);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void startPreDiscoveryForPairingPopup(String model) {
        String cleanModel = model != null ? model.trim() : "";
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled() || !SESSIONS.isEmpty()) {
            return;
        }
        if (hasBondedClassicSonyForModel(cleanModel)) {
            return;
        }
        synchronized (PAIR_LOCK) {
            if (sDiscoveringForPair || !sPendingPairMac.isEmpty()
                    || !sPendingConnectMac.isEmpty() || sPreDiscoveringForPopup) {
                return;
            }
            BluetoothDevice cached = sPreDiscoveredBredrDevice;
            if (cached != null && (SystemClock.elapsedRealtime() - sPreDiscoveredBredrMs) < 25_000L
                    && getBondStateSafe(cached) != BluetoothDevice.BOND_BONDED) {
                String cNorm = !sPreDiscoveredBredrModel.isEmpty()
                        ? sPreDiscoveredBredrModel
                        : SonyDeviceDetector.normalizeModelName(stName(cached));
                if (cleanModel.isEmpty() || "Sony".equalsIgnoreCase(cleanModel)
                        || SonyDeviceDetector.normalizeModelName(cleanModel).equalsIgnoreCase(cNorm)) {
                    return;
                }
            }
            sPreDiscoveringForPopup = true;
            sPreDiscoverTargetModel = cleanModel;
            sPreDiscoveredBredrDevice = null;
            sPreDiscoveredBredrModel = "";
            sPreDiscoveredBredrMs = 0L;
            cancelPreDiscoverTimeoutLocked();
            Runnable task = () -> {
                boolean wasPre;
                synchronized (PAIR_LOCK) {
                    wasPre = sPreDiscoveringForPopup;
                    sPreDiscoveringForPopup = false;
                    sPreDiscoverTargetModel = "";
                    sPreDiscoverTimeoutTask = null;
                }
                if (wasPre) {
                    try {
                        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
                        if (a != null && a.isDiscovering()) {
                            a.cancelDiscovery();
                        }
                    } catch (Throwable ignored) {
                    }
                    if (SESSIONS.isEmpty()) {
                        MAIN_HANDLER.postDelayed(() -> updateSonyPairingBleScan(true), 200L);
                    }
                }
            };
            sPreDiscoverTimeoutTask = task;
            MAIN_HANDLER.postDelayed(task, 6_000L);
        }

        stopSonyPairingBleScan();
        try {
            boolean started = adapter.isDiscovering() || adapter.startDiscovery();
            XposedCompat.debug("Pairing popup BR/EDR pre-discovery started=" + started + " model=" + cleanModel);
            if (!started) {
                synchronized (PAIR_LOCK) {
                    sPreDiscoveringForPopup = false;
                    sPreDiscoverTargetModel = "";
                    cancelPreDiscoverTimeoutLocked();
                }
                if (SESSIONS.isEmpty()) {
                    MAIN_HANDLER.postDelayed(() -> updateSonyPairingBleScan(true), 200L);
                }
            }
        } catch (Throwable t) {
            synchronized (PAIR_LOCK) {
                sPreDiscoveringForPopup = false;
                sPreDiscoverTargetModel = "";
                cancelPreDiscoverTimeoutLocked();
            }
            if (SESSIONS.isEmpty()) {
                MAIN_HANDLER.postDelayed(() -> updateSonyPairingBleScan(true), 200L);
            }
        }
    }

    private static void cancelPreDiscoverTimeoutLocked() {
        Runnable prev = sPreDiscoverTimeoutTask;
        if (prev != null) {
            MAIN_HANDLER.removeCallbacks(prev);
            sPreDiscoverTimeoutTask = null;
        }
    }

    private static void handleDeviceFoundForPair(BluetoothDevice found) {
        String pendingModel;
        boolean forPair;
        boolean forPre;
        synchronized (PAIR_LOCK) {
            forPair = sDiscoveringForPair;
            forPre = !forPair && sPreDiscoveringForPopup;
            if (!forPair && !forPre) {
                return;
            }
            pendingModel = forPair ? sPendingPairModel : sPreDiscoverTargetModel;
        }
        String foundMac = safeMac(found);
        if (isBleOnlyOrLeAlias(found)
                || getDeviceTypeSafe(found) == BluetoothDevice.DEVICE_TYPE_LE
                || isLikelyRandomBleAddress(foundMac)) {
            return;
        }
        if (!SonyDeviceDetector.isSonyDevice(found)) {
            return;
        }
        String foundName = stName(found);
        String foundNorm = SonyDeviceDetector.normalizeModelName(foundName);
        if (!pendingModel.isEmpty() && !"Sony".equalsIgnoreCase(pendingModel)) {
            String targetNorm = SonyDeviceDetector.normalizeModelName(pendingModel);
            if (!foundNorm.isEmpty() && !foundNorm.equalsIgnoreCase("Sony")
                    && !foundNorm.equalsIgnoreCase(targetNorm)) {
                return;
            }
        }
        recordClassicSonyMac(foundNorm, foundMac);

        boolean promotedToPair = false;
        synchronized (PAIR_LOCK) {
            if (sDiscoveringForPair) {
                sDiscoveringForPair = false;
                sPendingPairMac = foundMac;
                sPreDiscoveringForPopup = false;
                sPreDiscoverTargetModel = "";
                cancelPreDiscoverTimeoutLocked();
                cancelPairTimeoutLocked();
                schedulePairTimeoutLocked(18_000L, pendingModel, foundMac);
                promotedToPair = true;
            } else if (sPreDiscoveringForPopup) {
                sPreDiscoveringForPopup = false;
                sPreDiscoverTargetModel = "";
                cancelPreDiscoverTimeoutLocked();
                sPreDiscoveredBredrDevice = found;
                sPreDiscoveredBredrModel = foundNorm;
                sPreDiscoveredBredrMs = SystemClock.elapsedRealtime();
            } else {
                return;
            }
        }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
        } catch (Throwable ignored) {
        }
        if (!promotedToPair) {
            XposedCompat.debug("Pre-discovered BR/EDR Sony device for popup: " + stName(found) + " [" + foundMac + "]");
            if (SESSIONS.isEmpty()) {
                MAIN_HANDLER.postDelayed(() -> updateSonyPairingBleScan(true), 200L);
            }
            return;
        }
        XposedCompat.log("Found BR/EDR Sony device for pairing: " + stName(found) + " [" + foundMac + "]");
        if (found.getBondState() == BluetoothDevice.BOND_BONDED) {
            ConfigStore.clearActivePairMarker(sBtContext);
            synchronized (PAIR_LOCK) {
                sPendingPairMac = "";
                sPendingConnectMac = foundMac;
                sPendingConnectModel = pendingModel;
            }
            sendPairStatusBroadcast("connecting", pendingModel, foundMac, found);
            connectProfilesForDevice(found);
        } else {
            ConfigStore.writeActivePairMarker(sBtContext, foundMac, pendingModel);
            if (!createBondBredr(found)) {
                synchronized (PAIR_LOCK) {
                    cancelPairTimeoutLocked();
                    sPendingPairMac = "";
                }
                ConfigStore.clearActivePairMarker(sBtContext);
                sendPairStatusBroadcast("failed", pendingModel, foundMac, found);
                MAIN_HANDLER.postDelayed(() -> updateSonyPairingBleScan(false), 1500L);
            }
        }
    }

    private static void handleDismissPairingCommand() {
        BluetoothDevice dev = null;
        boolean wasDiscovering;
        boolean wasPreDiscovering;
        synchronized (PAIR_LOCK) {
            if (sPendingExternalPairingDevice != null
                    && (SystemClock.elapsedRealtime() - sPendingExternalPairingMs) < 25_000L) {
                dev = sPendingExternalPairingDevice;
            }
            sPendingExternalPairingDevice = null;
            sPendingExternalPairingMs = 0L;
            wasDiscovering = sDiscoveringForPair;
            wasPreDiscovering = sPreDiscoveringForPopup;
            sDiscoveringForPair = false;
            sPreDiscoveringForPopup = false;
            sPreDiscoverTargetModel = "";
            sPreDiscoveredBredrDevice = null;
            sPreDiscoveredBredrModel = "";
            sPreDiscoveredBredrMs = 0L;
            sPendingPairMac = "";
            sPendingPairModel = "";
            sPendingConnectMac = "";
            sPendingConnectModel = "";
            cancelPreDiscoverTimeoutLocked();
            cancelPairTimeoutLocked();
        }
        synchronized (POPUP_LOCK) {
            long now = SystemClock.elapsedRealtime();
            sLastPopupMs = now;
            sDismissSuppressUntilMs = now + DISMISS_POPUP_COOLDOWN_MS;
        }
        ConfigStore.clearActivePairMarker(sBtContext);
        if (wasDiscovering || wasPreDiscovering) {
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null && adapter.isDiscovering()) {
                    adapter.cancelDiscovery();
                }
            } catch (Throwable ignored) {
            }
            if (SESSIONS.isEmpty()) {
                MAIN_HANDLER.postDelayed(() -> updateSonyPairingBleScan(true), 300L);
            }
        }
        if (dev != null && getBondStateSafe(dev) == BluetoothDevice.BOND_BONDING) {
            try {
                dev.setPairingConfirmation(false);
            } catch (Throwable ignored) {
            }
            try {
                Method cancel = dev.getClass().getMethod("cancelBondProcess");
                cancel.invoke(dev);
            } catch (Throwable ignored) {
            }
            XposedCompat.log("Cancelled dismissed Sony pairing request for " + stName(dev));
        }
    }

    private static int getBondStateSafe(BluetoothDevice dev) {
        if (dev == null) return BluetoothDevice.BOND_NONE;
        try {
            return dev.getBondState();
        } catch (Throwable ignored) {
            return BluetoothDevice.BOND_NONE;
        }
    }

    private static void handleBondStateChanged(BluetoothDevice dev, int state, int prevState) {
        String mac = safeMac(dev);
        String model;
        synchronized (PAIR_LOCK) {
            if (sPendingExternalPairingDevice != null
                    && mac.equalsIgnoreCase(safeMac(sPendingExternalPairingDevice))
                    && (state == BluetoothDevice.BOND_BONDED || state == BluetoothDevice.BOND_NONE)) {
                sPendingExternalPairingDevice = null;
                sPendingExternalPairingMs = 0L;
            }
            if (sPendingPairMac.isEmpty() || !sPendingPairMac.equalsIgnoreCase(mac)) {
                return;
            }
            model = sPendingPairModel;
        }
        if (state == BluetoothDevice.BOND_BONDED) {
            ConfigStore.clearActivePairMarker(sBtContext);
            if (isBleOnlyOrLeAlias(dev)) {
                synchronized (PAIR_LOCK) {
                    cancelPairTimeoutLocked();
                    sPendingPairMac = "";
                    sPendingPairModel = "";
                    sPendingConnectMac = "";
                    sPendingConnectModel = "";
                }
                XposedCompat.log("Ignored LE-only Sony bond for " + mac);
                sendPairStatusBroadcast("failed", model, mac, dev);
                MAIN_HANDLER.postDelayed(() -> updateSonyPairingBleScan(false), 1500L);
                return;
            }
            synchronized (PAIR_LOCK) {
                cancelPairTimeoutLocked();
                sPendingPairMac = "";
                sPendingConnectMac = mac;
                sPendingConnectModel = !model.isEmpty() ? model : SonyDeviceDetector.normalizeModelName(stName(dev));
                schedulePairTimeoutLocked(15_000L, sPendingConnectModel, mac);
            }
            recordClassicSonyMac(
                    !model.isEmpty() ? SonyDeviceDetector.normalizeModelName(model)
                            : SonyDeviceDetector.normalizeModelName(stName(dev)),
                    mac);
            sendPairStatusBroadcast("connecting", !model.isEmpty() ? model : stName(dev), mac, dev);
            connectProfilesForDevice(dev);
            MAIN_HANDLER.postDelayed(() -> {
                if (!SESSIONS.containsKey(mac) && !isDeviceConnectedSafe(dev)) {
                    connectProfilesForDevice(dev);
                }
            }, 1200L);
        } else if (state == BluetoothDevice.BOND_NONE && prevState == BluetoothDevice.BOND_BONDING) {
            ConfigStore.clearActivePairMarker(sBtContext);
            synchronized (PAIR_LOCK) {
                cancelPairTimeoutLocked();
                sPendingPairMac = "";
                sPendingPairModel = "";
            }
            sendPairStatusBroadcast("failed", model, mac, dev);
            MAIN_HANDLER.postDelayed(() -> updateSonyPairingBleScan(false), 1500L);
        }
    }

    private static boolean createBondBredr(BluetoothDevice dev) {
        try {
            Method createTransport = dev.getClass().getMethod("createBond", int.class);
            Object res = createTransport.invoke(dev, 1); // BluetoothDevice.TRANSPORT_BREDR = 1
            return Boolean.TRUE.equals(res);
        } catch (Throwable ignored) {
        }
        try {
            return dev.createBond();
        } catch (Throwable t) {
            XposedCompat.log("createBond failed: " + t.getMessage());
            return false;
        }
    }

    private static void connectProfilesForDevice(BluetoothDevice dev) {
        if (dev == null) return;
        Object adapterSvc = sAdapterService;
        if (adapterSvc == null) {
            try {
                Class<?> adapterCls = Class.forName("com.android.bluetooth.btservice.AdapterService",
                        false, dev.getClass().getClassLoader());
                Method getSvc = adapterCls.getMethod("getAdapterService");
                adapterSvc = getSvc.invoke(null);
            } catch (Throwable ignored) {
            }
        }
        if (adapterSvc != null) {
            try {
                Method m = adapterSvc.getClass().getMethod("connectAllEnabledProfiles", BluetoothDevice.class);
                m.invoke(adapterSvc, dev);
            } catch (Throwable t) {
                XposedCompat.debug("connectAllEnabledProfiles failed: " + t.getMessage());
            }
        }
        Object a2dpSvc = sA2dpService;
        if (a2dpSvc != null) {
            try {
                Method m = a2dpSvc.getClass().getMethod("connect", BluetoothDevice.class);
                m.invoke(a2dpSvc, dev);
            } catch (Throwable ignored) {
            }
        }
    }

    private static Object resolveA2dpService(BluetoothDevice dev) {
        if (sA2dpService != null) {
            return sA2dpService;
        }
        try {
            ClassLoader cl = dev != null ? dev.getClass().getClassLoader() : BluetoothHook.class.getClassLoader();
            Class<?> cls = Class.forName("com.android.bluetooth.a2dp.A2dpService", false, cl);
            Method m = cls.getMethod("getA2dpService");
            Object svc = m.invoke(null);
            if (svc != null) {
                sA2dpService = svc;
                return svc;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void syncA2dpCodecState(BluetoothDevice dev) {
        Context ctx = sBtContext;
        if (ctx == null || dev == null) {
            return;
        }
        Object a2dpSvc = resolveA2dpService(dev);
        if (a2dpSvc == null) {
            return;
        }
        try {
            Method getStatus = a2dpSvc.getClass().getMethod("getCodecStatus", BluetoothDevice.class);
            Object statusObj = getStatus.invoke(a2dpSvc, dev);
            if (!(statusObj instanceof BluetoothCodecStatus)) {
                return;
            }
            BluetoothCodecStatus status = (BluetoothCodecStatus) statusObj;
            BluetoothCodecConfig activeCfg = status.getCodecConfig();
            int activeType = activeCfg != null ? activeCfg.getCodecType() : -1;

            boolean supportsLdac = activeType == ConfigStore.CODEC_LDAC;
            boolean supportsAac = activeType == ConfigStore.CODEC_AAC;
            try {
                Method getSelectable = status.getClass().getMethod("getCodecsSelectableCapabilities");
                Object capsObj = getSelectable.invoke(status);
                if (capsObj instanceof List) {
                    for (Object item : (List<?>) capsObj) {
                        if (item instanceof BluetoothCodecConfig) {
                            int t = ((BluetoothCodecConfig) item).getCodecType();
                            if (t == ConfigStore.CODEC_LDAC) supportsLdac = true;
                            if (t == ConfigStore.CODEC_AAC) supportsAac = true;
                        }
                    }
                } else if (capsObj instanceof BluetoothCodecConfig[]) {
                    for (BluetoothCodecConfig cfg : (BluetoothCodecConfig[]) capsObj) {
                        if (cfg != null) {
                            int t = cfg.getCodecType();
                            if (t == ConfigStore.CODEC_LDAC) supportsLdac = true;
                            if (t == ConfigStore.CODEC_AAC) supportsAac = true;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            ConfigStore.writeGlobalCodecState(ctx, safeMac(dev), activeType, supportsLdac, supportsAac);
        } catch (Throwable t) {
            XposedCompat.debug("syncA2dpCodecState failed: " + t.getMessage());
        }
    }

    private static void applyA2dpCodecPreference(BluetoothDevice dev, int codecType) {
        if (dev == null || (codecType != ConfigStore.CODEC_LDAC && codecType != ConfigStore.CODEC_AAC)) {
            return;
        }
        Object a2dpSvc = resolveA2dpService(dev);
        if (a2dpSvc == null) {
            return;
        }
        try {
            try {
                Method optM = a2dpSvc.getClass().getMethod("setOptionalCodecsEnabled", BluetoothDevice.class, int.class);
                optM.invoke(a2dpSvc, dev, 1);
            } catch (Throwable ignored) {
            }

            BluetoothCodecConfig cfg = new BluetoothCodecConfig.Builder()
                    .setCodecType(codecType)
                    .setCodecPriority(1_000_000)
                    .setSampleRate(0)
                    .setBitsPerSample(0)
                    .setChannelMode(0)
                    .setCodecSpecific1(0L)
                    .build();
            Method setPref = a2dpSvc.getClass().getMethod("setCodecConfigPreference", BluetoothDevice.class, BluetoothCodecConfig.class);
            setPref.invoke(a2dpSvc, dev, cfg);
            XposedCompat.log("Switched A2DP codec -> " + ConfigStore.codecDisplayName(codecType) + " for " + stName(dev));
            MAIN_HANDLER.postDelayed(() -> syncA2dpCodecState(dev), 500L);
            MAIN_HANDLER.postDelayed(() -> syncA2dpCodecState(dev), 1500L);
        } catch (Throwable t) {
            XposedCompat.log("applyA2dpCodecPreference failed: " + t.getMessage());
        }
    }

    private static void schedulePairTimeoutLocked(long timeoutMs, String model, String mac) {
        cancelPairTimeoutLocked();
        Runnable task = () -> {
            synchronized (PAIR_LOCK) {
                if (sDiscoveringForPair) {
                    sDiscoveringForPair = false;
                    try {
                        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
                        if (a != null && a.isDiscovering()) a.cancelDiscovery();
                    } catch (Throwable ignored) {
                    }
                }
                sPendingPairMac = "";
                sPendingPairModel = "";
                sPendingConnectMac = "";
                sPendingConnectModel = "";
                sPairTimeoutTask = null;
            }
            synchronized (POPUP_LOCK) {
                sLastPopupMs = SystemClock.elapsedRealtime();
            }
            ConfigStore.clearActivePairMarker(sBtContext);
            sendPairStatusBroadcast("failed", model, mac, null);
            MAIN_HANDLER.postDelayed(() -> updateSonyPairingBleScan(false), 1500L);
        };
        sPairTimeoutTask = task;
        MAIN_HANDLER.postDelayed(task, timeoutMs);
    }

    private static void cancelPairTimeoutLocked() {
        Runnable prev = sPairTimeoutTask;
        if (prev != null) {
            MAIN_HANDLER.removeCallbacks(prev);
            sPairTimeoutTask = null;
        }
    }

    private static void sendPairStatusBroadcast(String pairState, String model, String mac, BluetoothDevice dev) {
        Context ctx = sBtContext;
        if (ctx == null) return;
        try {
            Intent out = new Intent(ConfigStore.ACTION_PAIR_STATUS);
            out.setPackage("com.heytap.mydevices");
            out.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            out.putExtra(ConfigStore.EXTRA_PAIR_STATE, pairState);
            if (model != null && !model.isEmpty()) out.putExtra(ConfigStore.EXTRA_MODEL, model);
            if (mac != null && !mac.isEmpty()) out.putExtra(ConfigStore.EXTRA_MAC, mac);
            if (dev != null) out.putExtra(ConfigStore.EXTRA_DEVICE, dev);
            ctx.sendBroadcast(out);
            XposedCompat.log("Pair status -> " + pairState + " (" + model + " [" + mac + "])");
        } catch (Throwable t) {
            XposedCompat.log("sendPairStatusBroadcast failed: " + t.getMessage());
        }
    }

    private static int getDeviceTypeSafe(BluetoothDevice dev) {
        try {
            return dev.getType();
        } catch (Throwable ignored) {
            return BluetoothDevice.DEVICE_TYPE_UNKNOWN;
        }
    }

    private static boolean isDeviceConnectedSafe(BluetoothDevice dev) {
        if (dev == null) return false;
        try {
            Method m = dev.getClass().getMethod("isConnected");
            return Boolean.TRUE.equals(m.invoke(dev));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void maybeTriggerOpenCasePopup(BluetoothDevice device, String modelName, String source) {
        maybeTriggerOpenCasePopup(device, modelName, source, false);
    }

    private static void maybeTriggerOpenCasePopup(
            BluetoothDevice device,
            String modelName,
            String source,
            boolean isPairingModeTrigger
    ) {
        Context ctx = sBtContext;
        if (ctx == null || device == null) {
            return;
        }
        ConfigStore.HookConfig cfg = sConfig;
        if (cfg != null) {
            cfg.reload(false);
            if (!cfg.openCasePopup) {
                return;
            }
        }
        try {
            PowerManager pm = ctx.getSystemService(PowerManager.class);
            if (pm != null && !pm.isInteractive()) {
                return;
            }
            KeyguardManager km = ctx.getSystemService(KeyguardManager.class);
            if (km != null && km.isKeyguardLocked()) {
                return;
            }
        } catch (Throwable ignored) {
        }

        if (isPairingOrConnecting() && !"pairing_request".equals(source)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (POPUP_LOCK) {
            if (isPopupInCooldownOrGraceLocked(now, isPairingModeTrigger)) {
                return;
            }
            sLastPopupMs = now;
        }

        String resolvedModel = (modelName == null || modelName.isEmpty())
                ? SonyDeviceDetector.normalizeModelName(stName(device))
                : modelName;
        String mac = safeMac(device);
        int bondState = isBleOnlyOrLeAlias(device) ? BluetoothDevice.BOND_NONE : getBondStateSafe(device);
        boolean isConnected = SESSIONS.containsKey(mac);

        Intent intent = new Intent(ConfigStore.ACTION_SHOW_OPEN_POPUP);
        intent.setPackage("com.heytap.mydevices");
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        intent.putExtra(ConfigStore.EXTRA_DEVICE, device);
        intent.putExtra(ConfigStore.EXTRA_MODEL, resolvedModel);
        intent.putExtra(ConfigStore.EXTRA_BOND_STATE, bondState);
        intent.putExtra(ConfigStore.EXTRA_IS_CONNECTED, isConnected);
        try {
            ctx.sendBroadcast(intent);
            XposedCompat.log("Dispatched open-case popup (" + source + ", bonded="
                    + (bondState == BluetoothDevice.BOND_BONDED) + ", connected=" + isConnected
                    + ") for " + resolvedModel + " [" + mac + "]");
            if (isPairingModeTrigger
                    && bondState != BluetoothDevice.BOND_BONDED
                    && isBleOnlyOrLeAlias(device)) {
                startPreDiscoveryForPairingPopup(resolvedModel);
            }
        } catch (Throwable t) {
            XposedCompat.log("Open-case popup dispatch failed: " + t.getMessage());
        }
    }

    static boolean isPairingScanActive() {
        return sPairingScanActive && sPairingScanTier == SCAN_TIER_BURST;
    }

    private static String scanTierName(int tier) {
        switch (tier) {
            case SCAN_TIER_BURST:
                return "BURST(LOW_LATENCY)";
            case SCAN_TIER_BALANCED:
                return "BALANCED";
            case SCAN_TIER_LOW_POWER:
                return "LOW_POWER";
            default:
                return "OFF";
        }
    }

    private static boolean canRunPairingBleScan(BluetoothAdapter adapter) {
        Context ctx = sBtContext;
        if (ctx == null || adapter == null || !SESSIONS.isEmpty() || isPairingOrConnecting()) {
            return false;
        }
        ConfigStore.HookConfig cfg = sConfig;
        if (cfg != null) {
            cfg.reload(false);
            if (!cfg.openCasePopup) {
                return false;
            }
        }
        try {
            if (!adapter.isEnabled()) {
                return false;
            }
            PowerManager pm = ctx.getSystemService(PowerManager.class);
            if (pm != null && !pm.isInteractive()) {
                return false;
            }
            KeyguardManager km = ctx.getSystemService(KeyguardManager.class);
            if (km != null && km.isKeyguardLocked()) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
        return true;
    }

    private static void updateSonyPairingBleScan(boolean burstLowLatency) {
        updateSonyPairingBleScanTier(burstLowLatency ? SCAN_TIER_BURST : SCAN_TIER_BALANCED);
    }

    private static void updateSonyPairingBleScanTier(int targetTier) {
        if (targetTier <= SCAN_TIER_OFF) {
            stopSonyPairingBleScan();
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (!canRunPairingBleScan(adapter)) {
            stopSonyPairingBleScan();
            return;
        }
        synchronized (SCAN_LOCK) {
            if (sPairingScanActive && sPairingScanTier == targetTier) {
                scheduleStepDownLocked(targetTier);
                return;
            }
            stopSonyPairingBleScanLocked(adapter);
            try {
                BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
                if (scanner == null) {
                    return;
                }
                List<ScanFilter> filters = new ArrayList<>(2);
                filters.add(new ScanFilter.Builder()
                        .setManufacturerData(SonyDeviceDetector.SONY_MANUFACTURER_ID, new byte[]{0}, new byte[]{0})
                        .build());
                filters.add(new ScanFilter.Builder()
                        .setServiceData(SonyDeviceDetector.FAST_PAIR_SERVICE_UUID, new byte[]{0}, new byte[]{0})
                        .build());
                int scanMode;
                if (targetTier == SCAN_TIER_BURST) {
                    scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY;
                } else if (targetTier == SCAN_TIER_BALANCED) {
                    scanMode = ScanSettings.SCAN_MODE_BALANCED;
                } else {
                    scanMode = ScanSettings.SCAN_MODE_LOW_POWER;
                }
                int matchMode = (targetTier == SCAN_TIER_BURST)
                        ? ScanSettings.MATCH_MODE_AGGRESSIVE
                        : ScanSettings.MATCH_MODE_STICKY;
                ScanSettings.Builder sb = new ScanSettings.Builder()
                        .setScanMode(scanMode)
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .setMatchMode(matchMode)
                        .setNumOfMatches(ScanSettings.MATCH_NUM_FEW_ADVERTISEMENT)
                        .setReportDelay(0L);
                if (Build.VERSION.SDK_INT >= 26) {
                    sb.setLegacy(true);
                    sb.setPhy(BluetoothDevice.PHY_LE_1M);
                }
                ScanSettings settings = sb.build();
                ScanCallback cb = new ScanCallback() {
                };
                sPairingScannerCallback = cb;
                // Mark active before startScan() so ColorOS CustomScanStrategy hooks apply on initial setup
                sPairingScanActive = true;
                sPairingScanTier = targetTier;
                scanner.startScan(filters, settings, cb);
                scheduleStepDownLocked(targetTier);
                XposedCompat.debug("Sony pairing BLE scan -> tier=" + scanTierName(targetTier));
            } catch (Throwable t) {
                sPairingScanActive = false;
                sPairingScanTier = SCAN_TIER_OFF;
                sPairingScannerCallback = null;
                cancelStepDownLocked();
                XposedCompat.debug("updateSonyPairingBleScanTier failed: " + t.getMessage());
            }
        }
    }

    private static void stopSonyPairingBleScan() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        synchronized (SCAN_LOCK) {
            stopSonyPairingBleScanLocked(adapter);
        }
    }

    private static void stopSonyPairingBleScanLocked(BluetoothAdapter adapter) {
        cancelStepDownLocked();
        if (!sPairingScanActive) {
            sPairingScanTier = SCAN_TIER_OFF;
            return;
        }
        sPairingScanActive = false;
        sPairingScanTier = SCAN_TIER_OFF;
        ScanCallback cb = sPairingScannerCallback;
        sPairingScannerCallback = null;
        if (cb != null && adapter != null) {
            try {
                if (adapter.isEnabled()) {
                    BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
                    if (scanner != null) {
                        scanner.stopScan(cb);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void scheduleStepDownLocked(int currentTier) {
        cancelStepDownLocked();
        Runnable task;
        long delayMs;
        if (currentTier == SCAN_TIER_BURST) {
            delayMs = PAIRING_SCAN_BURST_MS;
            task = () -> {
                boolean shouldStep;
                synchronized (SCAN_LOCK) {
                    shouldStep = sPairingScanActive && sPairingScanTier == SCAN_TIER_BURST;
                }
                if (shouldStep) {
                    updateSonyPairingBleScanTier(SCAN_TIER_BALANCED);
                }
            };
        } else if (currentTier == SCAN_TIER_BALANCED) {
            delayMs = PAIRING_SCAN_BALANCED_MS;
            task = () -> {
                boolean shouldStep;
                synchronized (SCAN_LOCK) {
                    shouldStep = sPairingScanActive && sPairingScanTier == SCAN_TIER_BALANCED;
                }
                if (shouldStep) {
                    updateSonyPairingBleScanTier(SCAN_TIER_LOW_POWER);
                }
            };
        } else if (currentTier == SCAN_TIER_LOW_POWER) {
            delayMs = PAIRING_SCAN_LOW_POWER_REFRESH_MS;
            task = () -> {
                boolean shouldRefresh;
                synchronized (SCAN_LOCK) {
                    shouldRefresh = sPairingScanActive && sPairingScanTier == SCAN_TIER_LOW_POWER;
                    if (shouldRefresh) {
                        stopSonyPairingBleScanLocked(BluetoothAdapter.getDefaultAdapter());
                    }
                }
                if (shouldRefresh) {
                    MAIN_HANDLER.postDelayed(() -> {
                        if (canRunPairingBleScan(BluetoothAdapter.getDefaultAdapter())) {
                            updateSonyPairingBleScanTier(SCAN_TIER_LOW_POWER);
                        }
                    }, 80L);
                }
            };
        } else {
            return;
        }
        sPairingScanStepDownTask = task;
        MAIN_HANDLER.postDelayed(task, delayMs);
    }

    private static void cancelStepDownLocked() {
        Runnable prev = sPairingScanStepDownTask;
        if (prev != null) {
            MAIN_HANDLER.removeCallbacks(prev);
            sPairingScanStepDownTask = null;
        }
    }

    private static boolean isBleOnlyOrLeAlias(BluetoothDevice dev) {
        if (dev == null) {
            return true;
        }
        if (getDeviceTypeSafe(dev) == BluetoothDevice.DEVICE_TYPE_LE) {
            return true;
        }
        try {
            String rawName = dev.getName();
            if (rawName != null) {
                String lower = rawName.trim().toLowerCase(Locale.US);
                if (lower.startsWith("le_") || lower.startsWith("le-")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isPairingOrConnecting() {
        synchronized (PAIR_LOCK) {
            return sDiscoveringForPair
                    || sPreDiscoveringForPopup
                    || !sPendingPairMac.isEmpty()
                    || !sPendingConnectMac.isEmpty();
        }
    }

    private static boolean isConfirmedBredrDevice(BluetoothDevice dev) {
        if (dev == null || isBleOnlyOrLeAlias(dev)) {
            return false;
        }
        int t = getDeviceTypeSafe(dev);
        if (t != BluetoothDevice.DEVICE_TYPE_CLASSIC && t != BluetoothDevice.DEVICE_TYPE_DUAL) {
            return false;
        }
        return !isLikelyRandomBleAddress(safeMac(dev));
    }

    private static boolean isLikelyRandomBleAddress(String mac) {
        if (mac == null || mac.length() < 2) {
            return false;
        }
        try {
            int b0 = Integer.parseInt(mac.substring(0, 2), 16);
            int top2 = b0 & 0xC0;
            return top2 == 0x40 || top2 == 0xC0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void recordClassicSonyMac(String model, String mac) {
        if (model == null || mac == null || mac.isEmpty()) {
            return;
        }
        String norm = SonyDeviceDetector.normalizeModelName(model);
        if (norm.isEmpty() || "Sony".equalsIgnoreCase(norm) || isLikelyRandomBleAddress(mac)) {
            return;
        }
        RECENT_BREDR_SONY_BY_MODEL.put(norm.toUpperCase(Locale.US), mac.toUpperCase(Locale.US));
    }

    private static boolean isBondedSafe(BluetoothDevice dev) {
        if (dev == null) return false;
        try {
            return dev.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String resolveRawBtName(String mac) {
        if (mac == null || mac.isEmpty()) {
            return "";
        }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && BluetoothAdapter.checkBluetoothAddress(mac)) {
                String n = adapter.getRemoteDevice(mac).getName();
                return n != null ? n.trim() : "";
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static boolean isSonyDeviceNoUuidLookup(BluetoothDevice dev, ParcelUuid[] rawUuids) {
        if (dev == null) {
            return false;
        }
        String mac = safeMac(dev);
        if (!mac.isEmpty() && SonyDeviceDetector.isKnownSonyMac(mac)) {
            return true;
        }
        if (rawUuids != null) {
            for (ParcelUuid pu : rawUuids) {
                if (pu != null && SonyDeviceDetector.isSonyUuid(pu.getUuid())) {
                    SonyDeviceDetector.markKnownSony(mac);
                    return true;
                }
            }
        }
        try {
            if (SonyDeviceDetector.isSonyName(dev.getName())) {
                SonyDeviceDetector.markKnownSony(mac);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isCallerGms() {
        Context ctx = sBtContext;
        if (ctx == null) {
            return false;
        }
        try {
            int uid = Binder.getCallingUid();
            String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
            if (pkgs != null) {
                for (String pkg : pkgs) {
                    if (pkg != null && pkg.startsWith("com.google.android.gms")) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String safeMac(BluetoothDevice dev) {
        try {
            String a = dev.getAddress();
            return a != null ? a.toUpperCase(Locale.US) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String stName(BluetoothDevice dev) {
        try {
            String n = dev.getName();
            return n != null ? n : "Sony";
        } catch (Throwable ignored) {
            return "Sony";
        }
    }
}
