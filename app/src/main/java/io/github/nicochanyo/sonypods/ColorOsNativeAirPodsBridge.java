package io.github.nicochanyo.sonypods;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanSettings;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Native ColorOS 16/17 Binder bridge inside {@code com.android.bluetooth}.
 *
 * <p>Routes Sony headphones directly through ColorOS's built-in {@code AirPodsManager}
 * and {@code OplusRemoteDevicesExtImpl} pipeline (protocol transport {@code TRANSPORT_AIRPOD = 2}),
 * eliminating custom popups, fake battery cards, and charging-icon glitches.</p>
 */
public final class ColorOsNativeAirPodsBridge {
    public static final int TRANSPORT_AIRPOD = 2;

    // AirPodsManager noise mode encoding: 1=OFF, 2=ANC, 3=AMBIENT
    public static final int OPLUS_NOISE_OFF = 1;
    public static final int OPLUS_NOISE_ANC = 2;
    public static final int OPLUS_NOISE_AMBIENT = 3;

    // AirPodsManager extend feature types
    public static final int FEAT_BATTERY = 0x0001;
    public static final int FEAT_HEADSET = 0x0002;
    public static final int FEAT_ANC = 0x0004;
    public static final int FEAT_SPEAK_TO_CHAT = 0x0010;
    public static final int FEAT_OPLUS_SPEAK_TO_CHAT = 0x0020;
    public static final int FEAT_MODE_OFF = 0x0200;
    public static final int FEAT_MODE_TRANSPARENT = 0x0400;
    public static final int FEAT_WEAR_DETECT = 0x0800;
    public static final int FEAT_EAR_STATUS = 0x1000;
    public static final int FEAT_ONE_POD_NC = 0x2000;

    // Battery charging states in AirPodsManager int[6]
    public static final int CHG_UNKNOWN = 0;
    public static final int CHG_CHARGING = 1;
    public static final int CHG_NOT_CHARGING = 2;
    public static final int CHG_DISCONNECTED = 4;

    public interface NativeControlCallback {
        void onNoiseModeRequested(BluetoothDevice device, int ancMode);
        void onSpeakToChatRequested(BluetoothDevice device, boolean enabled);
    }

    private static final Map<String, ConfigStore.HeadsetState> STATES_BY_MAC = new ConcurrentHashMap<>();
    private static final Map<String, BluetoothDevice> DEVICES_BY_MAC = new ConcurrentHashMap<>();
    private static final Map<String, Long> FIRST_PARTIAL_BAT_MS_BY_MAC = new ConcurrentHashMap<>();
    private static final long DUAL_EAR_SYNC_GRACE_MS = 350L;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean EXT_HOOKS_INSTALLED = new AtomicBoolean(false);

    private static volatile ClassLoader sBtClassLoader;
    private static volatile ConfigStore.HookConfig sCfg;
    private static volatile NativeControlCallback sCallback;
    private static volatile Object sRemoteDevicesExt;
    private static volatile Method sGetOplusFeature;
    private static volatile Object sOplusRemoteDefault;
    private static volatile Object sOplusExtendDefault;

    private ColorOsNativeAirPodsBridge() {
    }

    public static void install(ClassLoader cl, ConfigStore.HookConfig cfg, NativeControlCallback callback) {
        sBtClassLoader = cl;
        sCfg = cfg;
        sCallback = callback;

        tryInstallExtHooks(cl);

        Class<?> cacheCls = XposedCompat.findClassIfExists("com.oplus.bluetooth.common.OplusFeatureCache", cl);
        if (cacheCls != null) {
            XposedCompat.hookAllMethods(cacheCls, "set", new XposedCompat.XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                    if (param.args == null || param.args.length < 1 || param.args[0] == null) {
                        return;
                    }
                    Object impl = param.args[0];
                    String clsName = impl.getClass().getName();
                    if (clsName.contains("OplusRemoteDevicesExtImpl")) {
                        sRemoteDevicesExt = impl;
                    }
                    if (!EXT_HOOKS_INSTALLED.get()) {
                        tryInstallExtHooks(impl.getClass().getClassLoader());
                    }
                }
            });
        }

        ensureExtHooksFromCache();
        XposedCompat.log("ColorOsNativeAirPodsBridge installed (extActive=" + EXT_HOOKS_INSTALLED.get() + ")");
    }

    /**
     * Resolves {@code oplus_bluetooth_common_ext.jar}'s dynamic {@link ClassLoader} from
     * {@code OplusFeatureCache} when not visible on the initial {@code com.android.bluetooth} loader.
     */
    public static void ensureExtHooksFromCache() {
        ClassLoader cl = sBtClassLoader;
        if (cl == null) {
            return;
        }
        try {
            if (sGetOplusFeature == null || sOplusRemoteDefault == null || sOplusExtendDefault == null) {
                Class<?> cacheCls = Class.forName("com.oplus.bluetooth.common.OplusFeatureCache", false, cl);
                Class<?> remoteIface = Class.forName("com.oplus.bluetooth.common.interfaces.IOplusRemoteDevice", false, cl);
                sOplusRemoteDefault = remoteIface.getField("DEFAULT").get(null);
                Class<?> extendIface = Class.forName("com.oplus.bluetooth.common.interfaces.IOplusExtendFeature", false, cl);
                sOplusExtendDefault = extendIface.getField("DEFAULT").get(null);
                for (Method m : cacheCls.getMethods()) {
                    if ("get".equals(m.getName()) && m.getParameterCount() == 1) {
                        sGetOplusFeature = m;
                        break;
                    }
                }
            }
            if (sGetOplusFeature != null) {
                if (sOplusRemoteDefault != null) {
                    Object resolvedRemote = sGetOplusFeature.invoke(null, sOplusRemoteDefault);
                    if (resolvedRemote != null && resolvedRemote.getClass().getName().contains("OplusRemoteDevicesExtImpl")) {
                        sRemoteDevicesExt = resolvedRemote;
                        if (!EXT_HOOKS_INSTALLED.get()) {
                            tryInstallExtHooks(resolvedRemote.getClass().getClassLoader());
                        }
                    }
                }
                if (!EXT_HOOKS_INSTALLED.get() && sOplusExtendDefault != null) {
                    Object resolvedExtend = sGetOplusFeature.invoke(null, sOplusExtendDefault);
                    if (resolvedExtend != null && resolvedExtend.getClass().getName().contains("AirPodsManager")) {
                        tryInstallExtHooks(resolvedExtend.getClass().getClassLoader());
                    }
                }
            }
        } catch (Throwable t) {
            XposedCompat.debug("ensureExtHooksFromCache failed: " + t.getMessage());
        }
    }

    private static void tryInstallExtHooks(ClassLoader extCl) {
        if (extCl == null || EXT_HOOKS_INSTALLED.get()) {
            return;
        }
        Class<?> mgrCls = XposedCompat.findClassIfExists(
                "com.oplus.bluetooth.feature.airpods.AirPodsManager", extCl);
        Class<?> propsCls = XposedCompat.findClassIfExists(
                "com.oplus.bluetooth.btservice.OplusRemoteDevicesExtImpl$OplusDeviceProperties", extCl);
        Class<?> extCls = XposedCompat.findClassIfExists(
                "com.oplus.bluetooth.btservice.OplusRemoteDevicesExtImpl", extCl);
        Class<?> scanStrategyCls = XposedCompat.findClassIfExists(
                "com.oplus.bluetooth.feature.lescan.CustomScanStrategy", extCl);

        if (mgrCls == null && propsCls == null && extCls == null && scanStrategyCls == null) {
            return;
        }
        if (!EXT_HOOKS_INSTALLED.compareAndSet(false, true)) {
            return;
        }

        int hAirpods = mgrCls != null ? hookAirPodsManager(mgrCls) : 0;
        int hProps = propsCls != null ? hookOplusDeviceProperties(propsCls) : 0;
        int hExt = extCls != null ? hookOplusRemoteDevicesExt(extCls) : 0;
        int hScan = scanStrategyCls != null ? hookCustomScanStrategy(scanStrategyCls) : 0;

        XposedCompat.log("ColorOsNativeAirPodsBridge ext hooks active (airpods=" + hAirpods
                + ", props=" + hProps + ", remoteExt=" + hExt + ", scanStrategy=" + hScan + ")");
    }

    private static int hookCustomScanStrategy(Class<?> scanStrategyCls) {
        int count = 0;
        try {
            XposedCompat.hookAllMethods(scanStrategyCls, "getScanIntervalMillisCustom", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    if (!BluetoothHook.isPairingScanActive()) {
                        return;
                    }
                    if (param.args == null || param.args.length < 2
                            || !(param.args[1] instanceof ScanSettings)) {
                        return;
                    }
                    ScanSettings ss = (ScanSettings) param.args[1];
                    if (ss.getScanMode() != ScanSettings.SCAN_MODE_LOW_LATENCY) {
                        return;
                    }
                    Object res = param.getResult();
                    if (res instanceof Integer && ((Integer) res) > 300) {
                        param.setResult(300);
                    }
                }
            });
            count++;
        } catch (Throwable t) {
            XposedCompat.debug("hook getScanIntervalMillisCustom failed: " + t.getMessage());
        }
        try {
            XposedCompat.hookAllMethods(scanStrategyCls, "getScanWindowMillisCustom", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    if (!BluetoothHook.isPairingScanActive()) {
                        return;
                    }
                    if (param.args == null || param.args.length < 2
                            || !(param.args[1] instanceof ScanSettings)) {
                        return;
                    }
                    ScanSettings ss = (ScanSettings) param.args[1];
                    if (ss.getScanMode() != ScanSettings.SCAN_MODE_LOW_LATENCY) {
                        return;
                    }
                    Object res = param.getResult();
                    if (res instanceof Integer) {
                        int val = (Integer) res;
                        if (val > 0 && val < 80) {
                            param.setResult(80);
                        }
                    }
                }
            });
            count++;
        } catch (Throwable t) {
            XposedCompat.debug("hook getScanWindowMillisCustom failed: " + t.getMessage());
        }
        return count;
    }

    public static void updateDeviceState(BluetoothDevice device, ConfigStore.HeadsetState state) {
        if (state == null || state.mac == null || state.mac.isEmpty()) {
            return;
        }
        String mac = state.mac.toUpperCase();
        if (!state.connected) {
            STATES_BY_MAC.remove(mac);
            DEVICES_BY_MAC.remove(mac);
            FIRST_PARTIAL_BAT_MS_BY_MAC.remove(mac);
            return;
        }
        STATES_BY_MAC.put(mac, state.copy());
        BluetoothDevice resolved = resolveBluetoothDevice(device, mac);
        if (resolved != null) {
            DEVICES_BY_MAC.put(mac, resolved);
            publishToColorOs(resolved, state.copy());
        }
    }

    public static void clearDevice(BluetoothDevice device, String mac) {
        String key = mac;
        if ((key == null || key.isEmpty()) && device != null) {
            key = safeAddress(device);
        }
        if (key != null && !key.isEmpty()) {
            String upper = key.toUpperCase();
            STATES_BY_MAC.remove(upper);
            DEVICES_BY_MAC.remove(upper);
            FIRST_PARTIAL_BAT_MS_BY_MAC.remove(upper);
        }
    }

    /** Sends initial/updated AirPods transport, model, battery, noise mode, and feature status into ColorOS. */
    private static void publishToColorOs(BluetoothDevice device, ConfigStore.HeadsetState state) {
        if (device == null || state == null || !isBridgeEnabled()) {
            return;
        }
        MAIN_HANDLER.post(() -> {
            Object ext = getOplusRemoteDevicesExt();
            if (ext == null) {
                XposedCompat.debug("OplusRemoteDevicesExtImpl unavailable yet");
                return;
            }
            String model = effectiveModelName(device, state);
            int[] battery = buildBatteryArray(state, model);
            int oplusNoise = toOplusNoiseMode(state.ancMode);

            callRemoteMethod(ext, "setOplusDeviceProtocolTransport",
                    new Class<?>[] { BluetoothDevice.class, int.class }, device, TRANSPORT_AIRPOD);
            if (!model.isEmpty()) {
                callRemoteMethod(ext, "setDeviceModel",
                        new Class<?>[] { BluetoothDevice.class, String.class }, device, model);
            }
            if (hasValidBattery(battery)) {
                callRemoteMethod(ext, "notifyBatteryLevelChanged",
                        new Class<?>[] { BluetoothDevice.class, int[].class }, device, battery);
            } else if (state.leftBattery > 0 || state.rightBattery > 0) {
                final String macKey = state.mac != null ? state.mac.toUpperCase() : "";
                MAIN_HANDLER.postDelayed(() -> {
                    ConfigStore.HeadsetState latest = !macKey.isEmpty() ? STATES_BY_MAC.get(macKey) : null;
                    if (latest != null && latest.connected) {
                        Object extLater = getOplusRemoteDevicesExt();
                        if (extLater != null) {
                            int[] latestBat = buildBatteryArray(latest, effectiveModelName(device, latest));
                            if (hasValidBattery(latestBat)) {
                                callRemoteMethod(extLater, "notifyBatteryLevelChanged",
                                        new Class<?>[] { BluetoothDevice.class, int[].class }, device, latestBat);
                            }
                        }
                    }
                }, DUAL_EAR_SYNC_GRACE_MS + 60L);
            }
            callRemoteMethod(ext, "notifyNoiseModeChanged",
                    new Class<?>[] { BluetoothDevice.class, int.class }, device, oplusNoise);
            callRemoteMethod(ext, "onExtFeatureStatusChanged",
                    new Class<?>[] { BluetoothDevice.class, int.class, int.class }, device, FEAT_ANC, oplusNoise);
            if (state.supportsSpeakToChat) {
                callRemoteMethod(ext, "onExtFeatureStatusChanged",
                        new Class<?>[] { BluetoothDevice.class, int.class, int.class },
                        device, FEAT_SPEAK_TO_CHAT, state.speakToChat ? 1 : 0);
            }
        });
    }

    private static int hookAirPodsManager(Class<?> mgrCls) {
        int count = 0;

        // 1. Block Apple AAP RFCOMM socket creation/connection for Sony devices
        count += XposedCompat.hookAllMethods(mgrCls, "checkAndConnectAirPods", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                BluetoothDevice d = firstDeviceArg(param.args);
                if (isBridgeEnabled() && isManagedSony(d)) {
                    param.setResult(null);
                }
            }
        }).size();
        count += XposedCompat.hookAllMethods(mgrCls, "connectToAirPodsSocket", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                BluetoothDevice d = firstDeviceArg(param.args);
                if (isBridgeEnabled() && isManagedSony(d)) {
                    param.setResult(null);
                }
            }
        }).size();

        // 2. Identify Sony devices as native AirPods-protocol devices
        XposedCompat.XC_MethodHook identifyHook = new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                BluetoothDevice d = firstDeviceArg(param.args);
                if (isBridgeEnabled() && isManagedSony(d)) {
                    param.setResult(Boolean.TRUE);
                }
            }
        };
        count += XposedCompat.hookAllMethods(mgrCls, "checkIsAirpodsDevice", identifyHook).size();
        count += XposedCompat.hookAllMethods(mgrCls, "isAirPodsDevice", identifyHook).size();
        count += XposedCompat.hookAllMethods(mgrCls, "getAirPodsIdentification", identifyHook).size();

        // 3. Model name
        count += XposedCompat.hookAllMethods(mgrCls, "getDeviceModel", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                BluetoothDevice d = firstDeviceArg(param.args);
                if (!isBridgeEnabled() || !isManagedSony(d)) return;
                String model = effectiveModelName(d, findState(d));
                if (!model.isEmpty()) {
                    param.setResult(model);
                }
            }
        }).size();

        // 4. Battery array int[6]: [L, R, Box, L_chg, R_chg, Box_chg]
        count += XposedCompat.hookAllMethods(mgrCls, "getBatteryInfo", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                BluetoothDevice d = firstDeviceArg(param.args);
                if (!isBridgeEnabled() || !isManagedSony(d)) return;
                ConfigStore.HeadsetState st = findState(d);
                String model = effectiveModelName(d, st);
                param.setResult(buildBatteryArray(st, model));
            }
        }).size();

        // 5. Supported extend feature bitmask
        count += XposedCompat.hookAllMethods(mgrCls, "getDeviceExtendFeatureMask", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                BluetoothDevice d = firstDeviceArg(param.args);
                if (!isBridgeEnabled() || !isManagedSony(d)) return;
                param.setResult(computeFeatureMask(d, findState(d)));
            }
        }).size();

        // 6. Feature status queries (ANC, Speak-to-Chat, wear detect, ear status, one-pod NC)
        count += XposedCompat.hookAllMethods(mgrCls, "getExtendFeatureStatus", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                BluetoothDevice d = firstDeviceArg(param.args);
                if (!isBridgeEnabled() || !isManagedSony(d) || param.args == null || param.args.length < 2) return;
                Integer val = computeExtendFeatureStatus(d, toInt(param.args[1], 0));
                if (val != null) {
                    param.setResult(val);
                }
            }
        }).size();

        count += XposedCompat.hookAllMethods(mgrCls, "getNoiseMode", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                BluetoothDevice d = firstDeviceArg(param.args);
                if (!isBridgeEnabled() || !isManagedSony(d)) return;
                ConfigStore.HeadsetState st = findState(d);
                param.setResult(st != null ? toOplusNoiseMode(st.ancMode) : OPLUS_NOISE_OFF);
            }
        }).size();

        // 7. Native control setters invoked from ColorOS MyDevices / QS / Detail Panel / Volume Slider
        count += XposedCompat.hookAllMethods(mgrCls, "setExtendFeatureStatus", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                BluetoothDevice d = firstDeviceArg(param.args);
                if (!isBridgeEnabled() || !isManagedSony(d) || param.args == null || param.args.length < 3) return;
                int featureType = toInt(param.args[1], 0);
                int status = toInt(param.args[2], 0);
                if (featureType == FEAT_ANC) {
                    dispatchNoiseMode(d, status);
                    param.setResult(Boolean.TRUE);
                } else if (featureType == FEAT_SPEAK_TO_CHAT || featureType == FEAT_OPLUS_SPEAK_TO_CHAT) {
                    dispatchSpeakToChat(d, status == 1);
                    param.setResult(Boolean.TRUE);
                }
            }
        }).size();

        count += XposedCompat.hookAllMethods(mgrCls, "setNoiseMode", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                BluetoothDevice d = firstDeviceArg(param.args);
                if (!isBridgeEnabled() || !isManagedSony(d) || param.args == null || param.args.length < 2) return;
                int mode = toInt(param.args[1], OPLUS_NOISE_OFF);
                dispatchNoiseMode(d, mode);
                param.setResult(Boolean.TRUE);
            }
        }).size();

        count += XposedCompat.hookAllMethods(mgrCls, "setConversationAwareStatus", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                BluetoothDevice d = firstDeviceArg(param.args);
                if (!isBridgeEnabled() || !isManagedSony(d) || param.args == null || param.args.length < 2) return;
                boolean enabled = (param.args[1] instanceof Boolean)
                        ? (Boolean) param.args[1]
                        : (toInt(param.args[1], 0) == 1);
                dispatchSpeakToChat(d, enabled);
                param.setResult(Boolean.TRUE);
            }
        }).size();
        return count;
    }

    private static int computeFeatureMask(BluetoothDevice d, ConfigStore.HeadsetState st) {
        String model = effectiveModelName(d, st);
        int mask = FEAT_BATTERY | FEAT_WEAR_DETECT | FEAT_ONE_POD_NC;
        if (SonyDeviceDetector.isOverEarModel(model)) {
            mask |= FEAT_HEADSET;
        }
        if (st == null || st.supportsNcAsm) {
            mask |= FEAT_ANC | FEAT_MODE_OFF | FEAT_MODE_TRANSPARENT;
        }
        if (st != null && st.supportsSpeakToChat) {
            mask |= FEAT_SPEAK_TO_CHAT;
        }
        return mask;
    }

    private static Integer computeExtendFeatureStatus(BluetoothDevice d, int featureType) {
        ConfigStore.HeadsetState st = findState(d);
        if (featureType == FEAT_ANC) {
            return st != null ? toOplusNoiseMode(st.ancMode) : OPLUS_NOISE_OFF;
        }
        if (featureType == FEAT_SPEAK_TO_CHAT || featureType == FEAT_OPLUS_SPEAK_TO_CHAT) {
            return (st != null && st.speakToChat) ? 1 : 0;
        }
        if (featureType == FEAT_WEAR_DETECT || featureType == FEAT_EAR_STATUS) {
            // 0 = both earbuds worn / in-ear ready, prevents ColorOS wear-block toast
            return 0;
        }
        if (featureType == FEAT_ONE_POD_NC) {
            return 1;
        }
        return null;
    }

    /**
     * Prevents ColorOS's {@code OplusDeviceProperties#getBatteryInfo()} from wiping
     * indices 2..5 to {@code -1} when {@code isEarbudsInCase()} returns false for TRANSPORT_AIRPOD (2).
     */
    private static int hookOplusDeviceProperties(Class<?> propsCls) {
        final Field transportField = findField(propsCls, "mProtocolTransport");
        final Field batteryField = findField(propsCls, "mBatteryInfo");
        if (transportField == null || batteryField == null) {
            return 0;
        }
        return XposedCompat.hookAllMethods(propsCls, "getBatteryInfo", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                if (!isBridgeEnabled() || param.thisObject == null) return;
                try {
                    int transport = transportField.getInt(param.thisObject);
                    Object raw = batteryField.get(param.thisObject);
                    if (transport == TRANSPORT_AIRPOD && raw instanceof int[]) {
                        int[] arr = (int[]) raw;
                        param.setResult(Arrays.copyOf(arr, arr.length));
                    }
                } catch (Throwable ignored) {
                }
            }
        }).size();
    }

    private static int hookOplusRemoteDevicesExt(Class<?> extCls) {
        int count = 0;
        count += XposedCompat.hookAllConstructors(extCls, new XposedCompat.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                if (param.thisObject != null) {
                    sRemoteDevicesExt = param.thisObject;
                }
            }
        }).size();

        count += XposedCompat.hookAllMethods(extCls, "getBatteryInfo", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                BluetoothDevice d = firstDeviceArg(param.args);
                if (!isBridgeEnabled() || !isManagedSony(d)) return;
                ConfigStore.HeadsetState st = findState(d);
                param.setResult(buildBatteryArray(st, effectiveModelName(d, st)));
            }
        }).size();

        count += XposedCompat.hookAllMethods(extCls, "getDeviceExtendFeatureMask", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                BluetoothDevice d = firstDeviceArg(param.args);
                if (!isBridgeEnabled() || !isManagedSony(d)) return;
                param.setResult(computeFeatureMask(d, findState(d)));
            }
        }).size();

        count += XposedCompat.hookAllMethods(extCls, "getDeviceModel", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                BluetoothDevice d = firstDeviceArg(param.args);
                if (!isBridgeEnabled() || !isManagedSony(d)) return;
                String model = effectiveModelName(d, findState(d));
                if (!model.isEmpty()) {
                    param.setResult(model);
                }
            }
        }).size();

        count += XposedCompat.hookAllMethods(extCls, "getExtendFeatureStatus", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                BluetoothDevice d = firstDeviceArg(param.args);
                if (!isBridgeEnabled() || !isManagedSony(d) || param.args == null || param.args.length < 2) return;
                Integer val = computeExtendFeatureStatus(d, toInt(param.args[1], 0));
                if (val != null) {
                    param.setResult(val);
                }
            }
        }).size();

        count += XposedCompat.hookAllMethods(extCls, "setExtendFeatureStatus", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                BluetoothDevice d = firstDeviceArg(param.args);
                if (!isBridgeEnabled() || !isManagedSony(d) || param.args == null || param.args.length < 3) return;
                int featureType = toInt(param.args[1], 0);
                int status = toInt(param.args[2], 0);
                if (featureType == FEAT_ANC) {
                    dispatchNoiseMode(d, status);
                    param.setResult(Boolean.TRUE);
                } else if (featureType == FEAT_SPEAK_TO_CHAT || featureType == FEAT_OPLUS_SPEAK_TO_CHAT) {
                    dispatchSpeakToChat(d, status == 1);
                    param.setResult(Boolean.TRUE);
                }
            }
        }).size();

        return count;
    }

    private static Object getOplusRemoteDevicesExt() {
        if (!EXT_HOOKS_INSTALLED.get()) {
            ensureExtHooksFromCache();
        }
        if (sRemoteDevicesExt != null) {
            return sRemoteDevicesExt;
        }
        ensureExtHooksFromCache();
        return sRemoteDevicesExt;
    }

    private static boolean hasValidBattery(int[] bat) {
        return bat != null && bat.length >= 3 && (bat[0] > 0 || bat[1] > 0 || bat[2] > 0);
    }

    private static int[] buildBatteryArray(ConfigStore.HeadsetState st, String model) {
        if (st == null) {
            return new int[] { -1, -1, -1, CHG_UNKNOWN, CHG_UNKNOWN, CHG_UNKNOWN };
        }
        if (SonyDeviceDetector.isOverEarModel(model) && !st.hasMultiBattery()) {
            int single = st.singleBattery >= 0 ? st.singleBattery : -1;
            if (single < 0) {
                return new int[] { -1, -1, -1, CHG_UNKNOWN, CHG_UNKNOWN, CHG_UNKNOWN };
            }
            int chg = st.singleCharging ? CHG_CHARGING : CHG_NOT_CHARGING;
            return new int[] { single, -1, -1, chg, CHG_DISCONNECTED, CHG_DISCONNECTED };
        }
        int l = st.leftBattery > 0 ? st.leftBattery : (st.singleBattery > 0 ? st.singleBattery : -1);
        int r = st.rightBattery > 0 ? st.rightBattery : -1;
        int box = st.caseBattery > 0 ? st.caseBattery : -1;
        if (l <= 0 && r <= 0) {
            return new int[] { -1, -1, -1, CHG_UNKNOWN, CHG_UNKNOWN, CHG_UNKNOWN };
        }
        // When user takes out both TWS earbuds slightly asynchronously (one 0.5-1.3s before the other),
        // wait briefly during initial connect so AirpodsJobHelper.n() receives both earbuds together.
        if (!SonyDeviceDetector.isOverEarModel(model) && ((l > 0) != (r > 0))) {
            String macKey = (st.mac != null) ? st.mac.toUpperCase() : "";
            if (!macKey.isEmpty()) {
                long now = SystemClock.elapsedRealtime();
                Long firstMs = FIRST_PARTIAL_BAT_MS_BY_MAC.putIfAbsent(macKey, now);
                long startMs = (firstMs != null) ? firstMs : now;
                if ((now - startMs) < DUAL_EAR_SYNC_GRACE_MS) {
                    return new int[] { -1, -1, -1, CHG_UNKNOWN, CHG_UNKNOWN, CHG_UNKNOWN };
                }
            }
        }

        int lChg = (l > 0) ? ((st.leftBattery > 0 ? st.leftCharging : st.singleCharging) ? CHG_CHARGING : CHG_NOT_CHARGING) : CHG_DISCONNECTED;
        int rChg = (r > 0) ? (st.rightCharging ? CHG_CHARGING : CHG_NOT_CHARGING) : CHG_DISCONNECTED;
        int boxChg = (box > 0) ? (st.caseCharging ? CHG_CHARGING : CHG_NOT_CHARGING) : CHG_DISCONNECTED;
        return new int[] { l, r, box, lChg, rChg, boxChg };
    }

    public static int toOplusNoiseMode(int ancMode) {
        if (ancMode == ConfigStore.ANC_NC) return OPLUS_NOISE_ANC;
        if (ancMode == ConfigStore.ANC_AMBIENT) return OPLUS_NOISE_AMBIENT;
        return OPLUS_NOISE_OFF;
    }

    public static int fromOplusNoiseMode(int oplusMode) {
        if (oplusMode == OPLUS_NOISE_ANC) return ConfigStore.ANC_NC;
        if (oplusMode == OPLUS_NOISE_AMBIENT) return ConfigStore.ANC_AMBIENT;
        return ConfigStore.ANC_OFF;
    }

    private static void dispatchNoiseMode(BluetoothDevice device, int oplusMode) {
        XposedCompat.log("Native ColorOS -> Sony noiseMode=" + oplusMode + " for " + safeAddress(device));
        int ancMode = fromOplusNoiseMode(oplusMode);
        ConfigStore.HeadsetState st = findState(device);
        if (st != null) {
            st.ancMode = ancMode;
        }
        NativeControlCallback cb = sCallback;
        if (cb != null) {
            cb.onNoiseModeRequested(device, ancMode);
        }
    }

    private static void dispatchSpeakToChat(BluetoothDevice device, boolean enabled) {
        XposedCompat.log("Native ColorOS -> Sony speakToChat=" + enabled + " for " + safeAddress(device));
        ConfigStore.HeadsetState st = findState(device);
        if (st != null) {
            st.speakToChat = enabled;
        }
        NativeControlCallback cb = sCallback;
        if (cb != null) {
            cb.onSpeakToChatRequested(device, enabled);
        }
    }

    private static boolean isBridgeEnabled() {
        ConfigStore.HookConfig cfg = sCfg;
        if (cfg == null) return true;
        cfg.reload(false);
        return cfg.bridgeEnabled;
    }

    private static boolean isManagedSony(BluetoothDevice d) {
        if (d == null) return false;
        String mac = safeAddress(d);
        if (!mac.isEmpty() && STATES_BY_MAC.containsKey(mac.toUpperCase())) {
            return true;
        }
        return SonyDeviceDetector.isSonyMacOrBonded(d);
    }

    private static ConfigStore.HeadsetState findState(BluetoothDevice d) {
        if (d == null) return null;
        String mac = safeAddress(d);
        return mac.isEmpty() ? null : STATES_BY_MAC.get(mac.toUpperCase());
    }

    private static String effectiveModelName(BluetoothDevice d, ConfigStore.HeadsetState st) {
        ConfigStore.HookConfig cfg = sCfg;
        if (cfg != null && cfg.customModelOverride != null && !cfg.customModelOverride.trim().isEmpty()) {
            return cfg.customModelOverride.trim();
        }
        if (st != null) {
            String m = st.effectiveModelName();
            if (!m.isEmpty()) return m;
        }
        try {
            if (d != null && d.getName() != null) {
                return SonyDeviceDetector.normalizeModelName(d.getName());
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static BluetoothDevice resolveBluetoothDevice(BluetoothDevice candidate, String mac) {
        if (candidate != null) return candidate;
        BluetoothDevice cached = DEVICES_BY_MAC.get(mac);
        if (cached != null) return cached;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                return adapter.getRemoteDevice(mac);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void callRemoteMethod(Object target, String methodName, Class<?>[] paramTypes, Object... args) {
        try {
            Method m = target.getClass().getMethod(methodName, paramTypes);
            m.invoke(target, args);
        } catch (Throwable t) {
            XposedCompat.debug("callRemoteMethod " + methodName + " err: " + t.getMessage());
        }
    }

    private static BluetoothDevice firstDeviceArg(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof BluetoothDevice) {
                return (BluetoothDevice) a;
            }
        }
        return null;
    }

    private static int toInt(Object obj, int def) {
        return obj instanceof Number ? ((Number) obj).intValue() : def;
    }

    private static String safeAddress(BluetoothDevice d) {
        try {
            String addr = d.getAddress();
            return addr != null ? addr.trim() : "";
        } catch (Throwable ignored) {
            return "";
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
}
