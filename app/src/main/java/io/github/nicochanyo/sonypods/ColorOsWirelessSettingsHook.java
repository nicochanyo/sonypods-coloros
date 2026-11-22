package io.github.nicochanyo.sonypods;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal ColorOS WirelessSettings hook ({@code com.oplus.wirelesssettings}).
 * Only bypasses the 3rd-party earbuds vendor ID whitelist so ColorOS routes
 * Sony detail panel launches directly into the built-in {@code AirpodsPlugin}.
 */
public final class ColorOsWirelessSettingsHook {
    private static final int[] DEFAULT_3RD_VENDORS = {2688, 1356, 301, 2016, 3686};

    private ColorOsWirelessSettingsHook() {
    }

    public static void install(ClassLoader cl, ConfigStore.HookConfig cfg, boolean isWirelessSettings) {
        if (!isWirelessSettings) {
            return;
        }
        Class<?> btNativeCls = XposedCompat.findClassIfExists(
                "com.oplus.wirelesssettings.api.BluetoothNative", cl);
        if (btNativeCls != null) {
            XposedCompat.hookAllMethods(btNativeCls, "getDeviceVendorId", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    cfg.reload(false);
                    if (!cfg.vendorBypass || param.args == null || param.args.length == 0) {
                        return;
                    }
                    if (param.args[0] instanceof BluetoothDevice) {
                        BluetoothDevice dev = (BluetoothDevice) param.args[0];
                        if (SonyDeviceDetector.isSonyDevice(dev)) {
                            List<Integer> allowed = readAllowedVendorIds();
                            int cur = (param.getResult() instanceof Integer) ? (Integer) param.getResult() : -1;
                            if (!allowed.contains(cur) && !allowed.isEmpty()) {
                                param.setResult(allowed.get(0));
                                XposedCompat.debug("Whitelisted Sony device vendor ID -> " + allowed.get(0));
                            }
                        }
                    }
                }
            });
        }
        Class<?> quickConnectCls = XposedCompat.findClassIfExists(
                "com.oplus.wirelesssettings.bluetooth.QuickConnectDialogActivity", cl);
        if (quickConnectCls != null) {
            XposedCompat.hookAllMethods(quickConnectCls, "onCreate", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    cfg.reload(false);
                    if (!cfg.suppressNativePairing || !(param.thisObject instanceof Activity)) {
                        return;
                    }
                    Activity act = (Activity) param.thisObject;
                    if (isSonyQuickConnectIntent(act, act.getIntent())) {
                        XposedCompat.log("Suppressed ColorOS QuickConnectDialogActivity for Sony device");
                        act.finish();
                    }
                }
            });
        }
        Class<?> pairingReqCls = XposedCompat.findClassIfExists(
                "com.android.settings.bluetooth.BluetoothPairingRequest", cl);
        if (pairingReqCls != null) {
            XposedCompat.hookAllMethods(pairingReqCls, "onReceive", new XposedCompat.XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                    cfg.reload(false);
                    if (!cfg.suppressNativePairing || param.args == null || param.args.length < 2
                            || !(param.args[0] instanceof Context)
                            || !(param.args[1] instanceof Intent)) {
                        return;
                    }
                    Context ctx = (Context) param.args[0];
                    Intent intent = (Intent) param.args[1];
                    if (!BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())) {
                        return;
                    }
                    BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (dev == null) {
                        return;
                    }
                    int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
                    if (variant != 3 && variant != 2 && variant != -1) {
                        return;
                    }
                    if (ConfigStore.isRecentActivePair(ctx, dev, 30_000L)) {
                        try {
                            dev.setPairingConfirmation(true);
                        } catch (Throwable ignored) {
                        }
                        XposedCompat.log("Suppressed ColorOS BluetoothPairingRequest (active popup pair)");
                        param.setResult(null);
                    } else if (cfg.openCasePopup && SonyDeviceDetector.isSonyDevice(dev)) {
                        XposedCompat.log("Suppressed ColorOS BluetoothPairingRequest (routed to SonyPods open-case popup)");
                        param.setResult(null);
                    }
                }
            });
        }
        Class<?> pairingDialogCls = XposedCompat.findClassIfExists(
                "com.android.settings.bluetooth.BluetoothPairingDialog", cl);
        if (pairingDialogCls != null) {
            XposedCompat.hookAllMethods(pairingDialogCls, "onCreate", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    cfg.reload(false);
                    if (!cfg.suppressNativePairing || !(param.thisObject instanceof Activity)) {
                        return;
                    }
                    Activity act = (Activity) param.thisObject;
                    Intent intent = act.getIntent();
                    if (intent == null) {
                        return;
                    }
                    BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (dev == null) {
                        return;
                    }
                    int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
                    if (variant != 3 && variant != 2 && variant != -1) {
                        return;
                    }
                    if (ConfigStore.isRecentActivePair(act, dev, 30_000L)) {
                        try {
                            dev.setPairingConfirmation(true);
                        } catch (Throwable ignored) {
                        }
                        XposedCompat.log("Suppressed ColorOS BluetoothPairingDialog for popup-initiated Sony pairing");
                        act.finish();
                    } else if (cfg.openCasePopup && SonyDeviceDetector.isSonyDevice(dev)) {
                        XposedCompat.log("Suppressed ColorOS BluetoothPairingDialog (routed to SonyPods open-case popup)");
                        act.finish();
                    }
                }
            });
        }
        XposedCompat.log("ColorOsWirelessSettingsHook installed");
    }

    private static boolean isSonyQuickConnectIntent(Activity act, Intent intent) {
        if (intent == null) return false;
        try {
            BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (dev == null) {
                dev = intent.getParcelableExtra("device");
            }
            if (dev != null && SonyDeviceDetector.isSonyMacOrBonded(dev)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        String[] keys = {"extra_device_address", "address", "device_address", "mac", "bluetooth_address"};
        for (String k : keys) {
            try {
                String addr = intent.getStringExtra(k);
                if (addr != null && !addr.trim().isEmpty()) {
                    if (SonyDeviceDetector.findBondedSonyDeviceByMac(addr) != null) {
                        return true;
                    }
                    ConfigStore.HeadsetState st = ConfigStore.readGlobalState(act);
                    if (st.connected && st.mac != null && addr.trim().equalsIgnoreCase(st.mac)) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static List<Integer> readAllowedVendorIds() {
        List<Integer> out = new ArrayList<>();
        try {
            Class<?> spCls = Class.forName("android.os.SystemProperties");
            Method getM = spCls.getMethod("get", String.class, String.class);
            String csv = (String) getM.invoke(null, "persist.bluetooth.3rd_earbuds_hfp_at_vendor", "");
            if (csv != null && !csv.trim().isEmpty()) {
                for (String part : csv.split(",")) {
                    String p = part.trim();
                    if (p.isEmpty()) continue;
                    try {
                        int val = p.startsWith("0x") || p.startsWith("0X")
                                ? Integer.parseInt(p.substring(2), 16)
                                : Integer.parseInt(p);
                        out.add(val);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (out.isEmpty()) {
            for (int v : DEFAULT_3RD_VENDORS) {
                out.add(v);
            }
        }
        return out;
    }
}
