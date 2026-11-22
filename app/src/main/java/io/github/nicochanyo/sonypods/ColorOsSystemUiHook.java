package io.github.nicochanyo.sonypods;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hooks ColorOS SystemUI components:
 * <ul>
 *   <li>{@code OplusBluetoothTile}: displays 3-segment battery and active ANC mode.</li>
 *   <li>{@code EarphoneController}: connects volume-slider ANC controls with Sony headsets.</li>
 *   <li>{@code *OutputBluetoothDeviceIconProvider}: supplies transparent Sony artwork for media switcher.</li>
 * </ul>
 */
public final class ColorOsSystemUiHook {
    private static volatile Object sBluetoothTile;
    private static volatile Object sEarphoneController;
    private static volatile ConfigStore.HookConfig sConfig;
    private static volatile boolean sReceiverHooked;

    private ColorOsSystemUiHook() {
    }

    public static void install(ClassLoader cl, ConfigStore.HookConfig cfg) {
        sConfig = cfg;
        hookBluetoothTile(cl, cfg);
        hookEarphoneController(cl, cfg);
        hookOutputDeviceIcons(cl, cfg);
        XposedCompat.log("ColorOsSystemUiHook installed");
    }

    private static void hookBluetoothTile(ClassLoader cl, ConfigStore.HookConfig cfg) {
        Class<?> tileCls = XposedCompat.findClassIfExists("com.oplus.systemui.qs.tiles.OplusBluetoothTile", cl);
        if (tileCls == null) {
            XposedCompat.log("ColorOsSystemUiHook: OplusBluetoothTile not found");
            return;
        }

        XposedCompat.hookAllMethods(tileCls, "handleUpdateState", new XposedCompat.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                cfg.reload(false);
                if (!cfg.qsTile || param.args == null || param.args.length == 0 || param.args[0] == null) {
                    return;
                }
                sBluetoothTile = param.thisObject;
                Context ctx = resolveTileContext(param.thisObject);
                if (ctx == null) {
                    return;
                }
                ensureReceiver(ctx);

                Object stateObj = param.args[0];
                try {
                    Field valF = stateObj.getClass().getField("value");
                    boolean enabled = valF.getBoolean(stateObj);
                    if (!enabled) {
                        return;
                    }
                    ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
                    if (!st.connected) {
                        return;
                    }
                    Field secField = stateObj.getClass().getField("secondaryLabel");
                    Object curSec = secField.get(stateObj);
                    String curStr = curSec != null ? curSec.toString() : "";

                    // Only rewrite when tile is showing connected headset (or matching Sony name)
                    if (!curStr.isEmpty() && (SonyDeviceDetector.isSonyName(curStr) || curStr.contains(st.name) || st.name.contains(curStr))) {
                        String summary = formatCompactTileText(ctx, st);
                        if (!summary.isEmpty()) {
                            secField.set(stateObj, summary);
                            try {
                                Field descField = stateObj.getClass().getField("contentDescription");
                                descField.set(stateObj, st.name + " " + summary);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static void hookEarphoneController(ClassLoader cl, ConfigStore.HookConfig cfg) {
        Class<?> ctrlCls = XposedCompat.findClassIfExists(
                "com.oplus.systemui.qs.slider.detail.data.EarphoneController", cl);
        if (ctrlCls == null) {
            XposedCompat.log("ColorOsSystemUiHook: EarphoneController not found");
            return;
        }

        XposedCompat.hookAllConstructors(ctrlCls, new XposedCompat.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                sEarphoneController = param.thisObject;
                Context ctx = resolveFieldContext(param.thisObject, "context");
                if (ctx != null) {
                    ensureReceiver(ctx);
                }
            }
        });

        XposedCompat.hookAllMethods(ctrlCls, "refreshActiveDevice", new XposedCompat.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                sEarphoneController = param.thisObject;
                Context ctx = resolveFieldContext(param.thisObject, "context");
                if (ctx != null) {
                    ensureReceiver(ctx);
                }
                cfg.reload(false);
                if (!cfg.melodyChannel || ctx == null) {
                    return;
                }
                try {
                    Method getActive = param.thisObject.getClass().getMethod("getActiveDevice");
                    Object current = getActive.invoke(param.thisObject);
                    if (current != null) {
                        return;
                    }
                    ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
                    if (!st.connected || !st.supportsNcAsm || st.mac == null || st.mac.isEmpty()) {
                        return;
                    }
                    Object activeDev = createActiveDevice(cl, st);
                    if (activeDev != null) {
                        Method setActive = ctrlCls.getDeclaredMethod("setActiveDevice", activeDev.getClass());
                        setActive.setAccessible(true);
                        setActive.invoke(param.thisObject, activeDev);
                    }
                } catch (Throwable t) {
                    XposedCompat.debug("EarphoneController.refreshActiveDevice fallback failed: " + t.getMessage());
                }
            }
        });

        XposedCompat.hookAllMethods(ctrlCls, "getNoiseReductionMode", new XposedCompat.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                sEarphoneController = param.thisObject;
                cfg.reload(false);
                if (!cfg.melodyChannel || param.args == null || param.args.length < 1
                        || !(param.args[0] instanceof String)) {
                    return;
                }
                Context ctx = resolveFieldContext(param.thisObject, "context");
                if (ctx == null) return;
                ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
                String addr = (String) param.args[0];
                if (!st.connected || !st.supportsNcAsm || !addr.equalsIgnoreCase(st.mac)) {
                    return;
                }
                try {
                    Method getActive = param.thisObject.getClass().getMethod("getActiveDevice");
                    Object activeDev = getActive.invoke(param.thisObject);
                    if (activeDev != null) {
                        Method getSupport = activeDev.getClass().getMethod("getSupportType");
                        Object mapObj = getSupport.invoke(activeDev);
                        if (mapObj instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, List<Integer>> supportMap = (Map<String, List<Integer>>) mapObj;
                            if (!supportMap.containsKey("noise")) {
                                supportMap.put("noise", Arrays.asList(5, 2, 1));
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                param.setResult(ColorOsMelodyHook.ancToMelody(st.ancMode));
            }
        });

        XposedCompat.hookAllMethods(ctrlCls, "setNoiseReductionMode", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                cfg.reload(false);
                if (!cfg.melodyChannel || param.args == null || param.args.length < 2
                        || param.args[0] == null || !(param.args[1] instanceof Integer)) {
                    return;
                }
                Context ctx = resolveFieldContext(param.thisObject, "context");
                if (ctx == null) return;
                try {
                    Method getAddr = param.args[0].getClass().getMethod("getAddress");
                    Object addrObj = getAddr.invoke(param.args[0]);
                    String addr = addrObj instanceof String ? (String) addrObj : "";
                    ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
                    if (st.connected && !addr.isEmpty() && addr.equalsIgnoreCase(st.mac)) {
                        int melodyType = (Integer) param.args[1];
                        ConfigStore.sendCommandBroadcast(
                                ctx,
                                ConfigStore.CMD_SET_ANC,
                                st.mac,
                                ColorOsMelodyHook.melodyToAnc(melodyType),
                                st.asmLevel,
                                st.voiceFocus,
                                false);
                        param.setResult(null);
                    }
                } catch (Throwable t) {
                    XposedCompat.debug("EarphoneController.setNoiseReductionMode failed: " + t.getMessage());
                }
            }
        });
    }

    private static Object createActiveDevice(ClassLoader cl, ConfigStore.HeadsetState st) {
        try {
            Class<?> activeCls = XposedCompat.findClassIfExists(
                    "com.oplus.systemui.qs.slider.detail.data.EarphoneController$ActiveDevice", cl);
            if (activeCls == null) return null;
            Constructor<?> ctor = activeCls.getConstructor(String.class, String.class, Map.class);
            Map<String, List<Integer>> support = new LinkedHashMap<>();
            support.put("noise", Arrays.asList(5, 2, 1));
            // Exact constructor order: (name, address, supportType)
            return ctor.newInstance(st.name, st.mac, support);
        } catch (Throwable t) {
            XposedCompat.debug("createActiveDevice failed: " + t.getMessage());
            return null;
        }
    }

    private static void hookOutputDeviceIcons(ClassLoader cl, ConfigStore.HookConfig cfg) {
        XposedCompat.XC_MethodHook iconHook = new XposedCompat.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                cfg.reload(false);
                if (!cfg.nativeArtwork || param.getResult() != null
                        || param.args == null || param.args.length < 2
                        || !(param.args[0] instanceof Context) || param.args[1] == null) {
                    return;
                }
                Context ctx = (Context) param.args[0];
                try {
                    Method getDev = param.args[1].getClass().getMethod("getDevice");
                    Object devObj = getDev.invoke(param.args[1]);
                    if (!(devObj instanceof BluetoothDevice)) {
                        return;
                    }
                    BluetoothDevice dev = (BluetoothDevice) devObj;
                    ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
                    boolean matchesState = st.connected && st.mac != null
                            && st.mac.equalsIgnoreCase(dev.getAddress());
                    if (!matchesState && !SonyDeviceDetector.isSonyMacOrBonded(dev)) {
                        return;
                    }
                    String model;
                    int color = -1;
                    if (matchesState) {
                        model = st.effectiveModelName();
                        color = st.colorCode;
                    } else {
                        String rawName = null;
                        try {
                            rawName = dev.getName();
                        } catch (Throwable ignored) {
                        }
                        model = SonyDeviceDetector.normalizeModelName(rawName);
                    }
                    String customUri = cfg.customArtworkUri;
                    Bitmap bmp = SonyAssetProvider.loadBitmap(ctx, model, color, customUri, 128);
                    if (bmp != null) {
                        param.setResult(new BitmapDrawable(ctx.getResources(), bmp));
                    }
                } catch (Throwable ignored) {
                }
            }
        };

        String[] providers = {
                "com.oplus.systemui.media.output.data.OplusOutputBluetoothDeviceIconProvider",
                "com.oplus.systemui.media.output.data.AppleOutputBluetoothDeviceIconProvider"
        };
        for (String name : providers) {
            Class<?> cls = XposedCompat.findClassIfExists(name, cl);
            if (cls != null) {
                XposedCompat.hookAllMethods(cls, "getDeviceIcon", iconHook);
            }
        }
    }

    private static String formatCompactTileText(Context ctx, ConfigStore.HeadsetState st) {
        StringBuilder sb = new StringBuilder();
        if (st.leftBattery >= 0 && st.rightBattery >= 0) {
            sb.append("左").append(st.leftBattery).append("% 右").append(st.rightBattery).append("%");
            if (st.caseBattery >= 0) {
                sb.append(" 盒").append(st.caseBattery).append("%");
            }
        } else if (st.singleBattery >= 0) {
            sb.append(st.singleBattery).append("%");
        }
        if (st.supportsNcAsm) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(st.formatModeLabel(ctx));
        }
        return sb.toString();
    }

    private static synchronized void ensureReceiver(Context ctx) {
        if (sReceiverHooked || ctx == null) {
            return;
        }
        sReceiverHooked = true;
        Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        try {
            IntentFilter filter = new IntentFilter(ConfigStore.ACTION_STATE_CHANGED);
            BroadcastReceiver rec = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Object tile = sBluetoothTile;
                    if (tile != null) {
                        try {
                            Method refreshM = tile.getClass().getMethod("refreshState", Object.class);
                            refreshM.invoke(tile, (Object) null);
                        } catch (Throwable ignored) {
                        }
                    }
                    Object ctrl = sEarphoneController;
                    if (ctrl != null) {
                        try {
                            ctrl.getClass().getMethod("refreshActiveDevice").invoke(ctrl);
                            ctrl.getClass().getMethod("refreshSupportType").invoke(ctrl);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                appCtx.registerReceiver(rec, filter, Context.RECEIVER_EXPORTED);
            } else {
                appCtx.registerReceiver(rec, filter);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Context resolveTileContext(Object tile) {
        return resolveFieldContext(tile, "mContext");
    }

    private static Context resolveFieldContext(Object target, String fieldName) {
        if (target == null) return null;
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object v = f.get(target);
                if (v instanceof Context) {
                    return (Context) v;
                }
            } catch (Throwable ignored) {
            }
            c = c.getSuperclass();
        }
        return null;
    }
}
