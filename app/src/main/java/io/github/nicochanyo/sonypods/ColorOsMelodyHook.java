package io.github.nicochanyo.sonypods;

import android.app.Activity;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

/**
 * Hooks OPPO Melody ({@code com.oplus.melody}) providers and activities so connected Sony
 * headphones integrate natively with ColorOS volume-slider ANC controls, media output artwork,
 * and headset battery/wear queries without touching real OPPO/OnePlus headsets.
 */
public final class ColorOsMelodyHook {
    private static final Uri URI_ACTIVE_DEVICE =
            Uri.parse("content://com.oplus.melody.provider.EarphoneControlProvider/melody_method_active_device");
    private static final Uri URI_NOISE_REDUCTION =
            Uri.parse("content://com.oplus.melody.provider.EarphoneControlProvider/melody_method_noise_reduction");
    private static final Uri URI_CONTROL_WEAR =
            Uri.parse("content://com.oplus.melody.provider.EarphoneControlProvider/melody_method_control_wear");

    private static volatile Context sAppContext;
    private static volatile ConfigStore.HookConfig sCfg;
    private static volatile boolean sReceiverRegistered;

    private ColorOsMelodyHook() {
    }

    public static void install(ClassLoader cl, ConfigStore.HookConfig cfg) {
        sCfg = cfg;

        hookApplicationContext();
        hookEarphoneControlProvider(cl);
        hookMelodyAliveProvider(cl);
        hookBatteryProvider(cl);
        hookDetailMainActivity(cl);

        XposedCompat.log("ColorOsMelodyHook installed");
    }

    private static void hookApplicationContext() {
        XposedCompat.hookAllMethods(Application.class, "onCreate", new XposedCompat.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                if (param.thisObject instanceof Application) {
                    sAppContext = ((Application) param.thisObject).getApplicationContext();
                    if ("com.oplus.melody".equals(Application.getProcessName())) {
                        ensureStateReceiver(sAppContext);
                    }
                }
            }
        });
    }

    private static synchronized void ensureStateReceiver(Context ctx) {
        if (sReceiverRegistered || ctx == null) {
            return;
        }
        sReceiverRegistered = true;
        IntentFilter filter = new IntentFilter(ConfigStore.ACTION_STATE_CHANGED);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!isMelodyEnabled()) {
                    return;
                }
                notifyControlObservers(context);
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(receiver, filter);
            }
        } catch (Throwable t) {
            sReceiverRegistered = false;
            XposedCompat.log("ColorOsMelodyHook receiver register failed: " + t.getMessage());
        }
    }

    private static void notifyControlObservers(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.getContentResolver().notifyChange(URI_ACTIVE_DEVICE, null);
            ctx.getContentResolver().notifyChange(URI_NOISE_REDUCTION, null);
            ctx.getContentResolver().notifyChange(URI_CONTROL_WEAR, null);
        } catch (Throwable ignored) {
        }
    }

    private static void hookEarphoneControlProvider(ClassLoader cl) {
        Class<?> providerCls = XposedCompat.findClassIfExists(
                "com.oplus.melody.provider.EarphoneControlProvider", cl);
        if (providerCls == null) {
            XposedCompat.log("EarphoneControlProvider not found");
            return;
        }

        XposedCompat.hookAllMethods(providerCls, "query", new XposedCompat.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                if (!isMelodyEnabled() || param.args == null || param.args.length < 1
                        || !(param.args[0] instanceof Uri)) {
                    return;
                }
                Object orig = param.getResult();
                if (orig instanceof Cursor && ((Cursor) orig).getCount() > 0) {
                    return;
                }
                Context ctx = resolveProviderContext(param.thisObject);
                ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
                if (!st.connected || st.mac == null || st.mac.isEmpty()) {
                    return;
                }

                Uri uri = (Uri) param.args[0];
                String path = uri.getPath();
                if (path == null) {
                    return;
                }
                String reqMac = extractFirstSelectionArg(param.args);
                MatrixCursor cursor = null;

                if (path.endsWith("melody_method_active_device")) {
                    cursor = new MatrixCursor(new String[]{"name", "address"});
                    cursor.addRow(new Object[]{st.name, st.mac});
                } else if (path.endsWith("melody_method_noise_reduction")) {
                    if ((reqMac == null || reqMac.equalsIgnoreCase(st.mac)) && st.supportsNcAsm) {
                        cursor = new MatrixCursor(new String[]{"name", "address", "type", "supports"});
                        cursor.addRow(new Object[]{
                                st.name,
                                st.mac,
                                ancToMelody(st.ancMode),
                                "[5,2,1]"
                        });
                    }
                } else if (path.endsWith("melody_method_control_wear")) {
                    if (reqMac == null || reqMac.equalsIgnoreCase(st.mac)) {
                        cursor = new MatrixCursor(new String[]{"name", "address", "ear_left", "ear_right"});
                        cursor.addRow(new Object[]{st.name, st.mac, 1, 1});
                    }
                }

                if (cursor != null) {
                    if (orig instanceof Cursor) {
                        try {
                            ((Cursor) orig).close();
                        } catch (Throwable ignored) {
                        }
                    }
                    param.setResult(cursor);
                }
            }
        });

        XposedCompat.hookAllMethods(providerCls, "call", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                if (!isMelodyEnabled() || param.args == null) {
                    return;
                }
                CallArgs callArgs = extractCallArgs(param.args);
                if (callArgs == null || callArgs.method == null || callArgs.extras == null) {
                    return;
                }
                Context ctx = resolveProviderContext(param.thisObject);
                ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
                if (!st.connected || st.mac == null || st.mac.isEmpty()) {
                    return;
                }
                String addr = callArgs.extras.getString("address");
                if (addr != null && !addr.isEmpty() && !addr.equalsIgnoreCase(st.mac)) {
                    return;
                }

                if ("melody_method_noise_reduction".equals(callArgs.method)) {
                    int melodyType = callArgs.extras.getInt("type", 1);
                    ConfigStore.sendCommandBroadcast(
                            ctx,
                            ConfigStore.CMD_SET_ANC,
                            st.mac,
                            melodyToAnc(melodyType),
                            st.asmLevel,
                            st.voiceFocus,
                            false);
                    param.setResult(new Bundle());
                } else if ("melody_method_enable_speech_perception".equals(callArgs.method)) {
                    boolean sw = callArgs.extras.getBoolean("switch", false);
                    ConfigStore.sendCommandBroadcast(
                            ctx,
                            ConfigStore.CMD_SET_SPEAK_TO_CHAT,
                            st.mac,
                            st.ancMode,
                            st.asmLevel,
                            st.voiceFocus,
                            sw);
                    param.setResult(new Bundle());
                }
            }
        });
    }

    private static void hookMelodyAliveProvider(ClassLoader cl) {
        Class<?> aliveCls = XposedCompat.findClassIfExists(
                "com.oplus.melody.alive.provider.MelodyAliveProvider", cl);
        if (aliveCls == null) {
            return;
        }
        XposedCompat.hookAllMethods(aliveCls, "call", new XposedCompat.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                if (!isMelodyEnabled() || !isArtworkEnabled() || param.args == null) {
                    return;
                }
                CallArgs callArgs = extractCallArgs(param.args);
                if (callArgs == null || !"pick_equipment_image".equals(callArgs.method)
                        || callArgs.extras == null) {
                    return;
                }
                Object orig = param.getResult();
                if (orig instanceof Bundle) {
                    String existing = ((Bundle) orig).getString("uri");
                    if (existing != null && !existing.isEmpty()) {
                        return;
                    }
                }
                String addr = callArgs.extras.getString("address");
                Context ctx = resolveProviderContext(param.thisObject);
                ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);

                String model = "";
                int color = -1;
                if (st.connected && addr != null && addr.equalsIgnoreCase(st.mac)) {
                    model = st.effectiveModelName();
                    color = st.colorCode;
                } else {
                    BluetoothDevice bonded = SonyDeviceDetector.findBondedSonyDeviceByMac(addr);
                    if (bonded == null) {
                        return;
                    }
                    try {
                        model = SonyDeviceDetector.normalizeModelName(bonded.getName());
                    } catch (Throwable ignored) {
                        model = "Sony";
                    }
                }

                String customUri = (sCfg != null && sCfg.customArtworkUri != null)
                        ? sCfg.customArtworkUri.trim() : "";
                Uri artUri = !customUri.isEmpty()
                        ? Uri.parse(customUri)
                        : SonyAssetProvider.buildArtworkUri(model, color);
                Bundle out = orig instanceof Bundle ? new Bundle((Bundle) orig) : new Bundle();
                out.putString("uri", artUri.toString());
                param.setResult(out);
            }
        });
    }

    private static void hookBatteryProvider(ClassLoader cl) {
        Class<?> batCls = XposedCompat.findClassIfExists(
                "com.oplus.melody.provider.BatteryProvider", cl);
        if (batCls == null) {
            return;
        }
        XposedCompat.hookAllMethods(batCls, "query", new XposedCompat.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                if (!isMelodyEnabled()) {
                    return;
                }
                Object orig = param.getResult();
                if (orig instanceof Cursor && ((Cursor) orig).getCount() > 0) {
                    return;
                }
                Context ctx = resolveProviderContext(param.thisObject);
                ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
                if (!st.connected || st.mac == null || st.mac.isEmpty()) {
                    return;
                }
                String reqMac = extractFirstSelectionArg(param.args);
                if (reqMac != null && !reqMac.equalsIgnoreCase(st.mac)) {
                    return;
                }
                int left = st.leftBattery >= 0 ? st.leftBattery : st.singleBattery;
                int right = st.rightBattery >= 0 ? st.rightBattery : st.singleBattery;
                if (left < 0 && right < 0) {
                    return;
                }
                MatrixCursor mc = new MatrixCursor(new String[]{
                        "macAddress", "headsetLeftBattery", "headsetRightBattery"
                });
                mc.addRow(new Object[]{st.mac, left, right});
                if (orig instanceof Cursor) {
                    try {
                        ((Cursor) orig).close();
                    } catch (Throwable ignored) {
                    }
                }
                param.setResult(mc);
            }
        });
    }

    private static void hookDetailMainActivity(ClassLoader cl) {
        Class<?> detailCls = XposedCompat.findClassIfExists(
                "com.oplus.melody.ui.component.detail.DetailMainActivity", cl);
        if (detailCls == null) {
            return;
        }
        XposedCompat.hookAllMethods(detailCls, "onCreate", new XposedCompat.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                if (!isMelodyEnabled() || !(param.thisObject instanceof Activity)) {
                    return;
                }
                Activity act = (Activity) param.thisObject;
                Intent in = act.getIntent();
                if (in == null) {
                    return;
                }
                String mac = firstNonEmpty(
                        in.getStringExtra("device_id"),
                        in.getStringExtra("address"),
                        in.getStringExtra("mac"));
                String devName = in.getStringExtra("device_name");
                ConfigStore.HeadsetState st = ConfigStore.readGlobalState(act);

                boolean matchesState = st.connected && st.mac != null && mac.equalsIgnoreCase(st.mac);
                BluetoothDevice bonded = !mac.isEmpty() ? SonyDeviceDetector.findBondedSonyDeviceByMac(mac) : null;
                boolean matchesName = SonyDeviceDetector.isSonyName(devName);
                if (!matchesState && bonded == null && !matchesName) {
                    return;
                }

                String targetMac = !mac.isEmpty() ? mac : (st.mac != null ? st.mac : "");
                if (bonded == null && !targetMac.isEmpty()) {
                    try {
                        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                        if (adapter != null && BluetoothAdapter.checkBluetoothAddress(targetMac)) {
                            bonded = adapter.getRemoteDevice(targetMac);
                        }
                    } catch (Throwable ignored) {
                    }
                }

                Intent panel = new Intent("com.oplus.mydevices.ACTION_DEVICE_DETAILED_PANEL");
                panel.setPackage("com.heytap.mydevices");
                panel.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!targetMac.isEmpty()) {
                    panel.putExtra("device_mac_info", targetMac);
                    panel.putExtra("device_id", targetMac);
                }
                if (bonded != null) {
                    panel.putExtra(BluetoothDevice.EXTRA_DEVICE, bonded);
                }
                try {
                    act.startActivity(panel);
                    act.finish();
                } catch (Throwable t) {
                    XposedCompat.log("DetailMainActivity redirect failed: " + t.getMessage());
                }
            }
        });
    }

    private static final class CallArgs {
        final String method;
        final Bundle extras;

        CallArgs(String method, Bundle extras) {
            this.method = method;
            this.extras = extras;
        }
    }

    private static CallArgs extractCallArgs(Object[] args) {
        if (args == null) return null;
        if (args.length == 3 && args[0] instanceof String) {
            return new CallArgs((String) args[0], args[2] instanceof Bundle ? (Bundle) args[2] : null);
        }
        if (args.length >= 4 && args[1] instanceof String) {
            return new CallArgs((String) args[1], args[3] instanceof Bundle ? (Bundle) args[3] : null);
        }
        return null;
    }

    private static String extractFirstSelectionArg(Object[] args) {
        if (args != null && args.length >= 4 && args[3] instanceof String[]) {
            String[] sel = (String[]) args[3];
            if (sel.length > 0 && sel[0] != null && !sel[0].trim().isEmpty()) {
                return sel[0].trim();
            }
        }
        return null;
    }

    private static Context resolveProviderContext(Object providerObj) {
        if (providerObj instanceof ContentProvider) {
            Context c = ((ContentProvider) providerObj).getContext();
            if (c != null) {
                return c;
            }
        }
        return sAppContext;
    }

    private static boolean isMelodyEnabled() {
        ConfigStore.HookConfig cfg = sCfg;
        if (cfg == null) return true;
        cfg.reload(false);
        return cfg.melodyChannel;
    }

    private static boolean isArtworkEnabled() {
        ConfigStore.HookConfig cfg = sCfg;
        if (cfg == null) return true;
        cfg.reload(false);
        return cfg.nativeArtwork;
    }

    public static int ancToMelody(int ancMode) {
        if (ancMode == ConfigStore.ANC_NC) return 5;
        if (ancMode == ConfigStore.ANC_AMBIENT) return 2;
        return 1;
    }

    public static int melodyToAnc(int melodyType) {
        if (melodyType == 5 || melodyType == 10) return ConfigStore.ANC_NC;
        if (melodyType == 2) return ConfigStore.ANC_AMBIENT;
        return ConfigStore.ANC_OFF;
    }

    private static String firstNonEmpty(String... vals) {
        if (vals == null) return "";
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return "";
    }
}
