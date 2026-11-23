package io.github.nicochanyo.sonypods;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

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
    private static volatile Object sNoiseReductionTile;
    private static volatile Object sSpatialAudioTile;
    private static volatile String sInjectedEarMac = "";
    private static volatile boolean sInjectedSpeakSupport;
    private static volatile int sOptimisticAncMode = -1;
    private static volatile long sOptimisticAncUntilMs = 0L;
    private static volatile Boolean sOptimisticSpeakVal;
    private static volatile long sOptimisticSpeakUntilMs = 0L;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile ConfigStore.HookConfig sConfig;
    private static volatile boolean sReceiverHooked;

    private ColorOsSystemUiHook() {
    }

    public static void install(ClassLoader cl, ConfigStore.HookConfig cfg) {
        sConfig = cfg;
        hookBluetoothTile(cl, cfg);
        hookEarphoneController(cl, cfg);
        hookOutputDeviceIcons(cl, cfg);
        hookDeviceCardIcons(cl, cfg);
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
                if (cfg.melodyChannel && ctx != null) {
                    syncEarphoneControllerForSony(param.thisObject, cl, ctx);
                }
            }
        });

        XposedCompat.hookAllMethods(ctrlCls, "refreshSupportType", new XposedCompat.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                sEarphoneController = param.thisObject;
                Context ctx = resolveFieldContext(param.thisObject, "context");
                cfg.reload(false);
                if (cfg.melodyChannel && ctx != null) {
                    syncEarphoneControllerForSony(param.thisObject, cl, ctx);
                }
            }
        });

        Class<?> noiseTileCls = XposedCompat.findClassIfExists(
                "com.oplus.systemui.qs.slider.detail.tile.volume.NoiseReductionDetailTile", cl);
        if (noiseTileCls != null) {
            XposedCompat.hookAllMethods(noiseTileCls, "isAvailable", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    if (param.thisObject != null) {
                        sNoiseReductionTile = param.thisObject;
                    }
                    if (Boolean.TRUE.equals(param.getResult())) {
                        return;
                    }
                    cfg.reload(false);
                    if (!cfg.melodyChannel || param.thisObject == null) {
                        return;
                    }
                    try {
                        Field ecF = param.thisObject.getClass().getDeclaredField("earphoneController");
                        ecF.setAccessible(true);
                        Object ctrl = ecF.get(param.thisObject);
                        if (ctrl != null) {
                            sEarphoneController = ctrl;
                        }
                        Context ctx = resolveFieldContext(param.thisObject, "mContext");
                        if (ctx == null && ctrl != null) {
                            ctx = resolveFieldContext(ctrl, "context");
                        }
                        if (ctx == null) {
                            return;
                        }
                        ensureReceiver(ctx);
                        ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
                        if (st.connected && st.supportsNcAsm && st.mac != null && !st.mac.isEmpty()) {
                            if (ctrl != null) {
                                syncEarphoneControllerForSony(ctrl, cl, ctx);
                            }
                            param.setResult(Boolean.TRUE);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

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
                int effectiveAnc = (sOptimisticAncMode >= 0
                        && SystemClock.elapsedRealtime() < sOptimisticAncUntilMs)
                        ? sOptimisticAncMode
                        : st.ancMode;
                param.setResult(ColorOsMelodyHook.ancToMelody(effectiveAnc));
            }
        });

        XposedCompat.hookAllMethods(ctrlCls, "setNoiseReductionMode", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                sEarphoneController = param.thisObject;
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
                        int targetAnc = ColorOsMelodyHook.melodyToAnc(melodyType);
                        sOptimisticAncMode = targetAnc;
                        sOptimisticAncUntilMs = SystemClock.elapsedRealtime() + 1200L;
                        ConfigStore.sendCommandBroadcast(
                                ctx,
                                ConfigStore.CMD_SET_ANC,
                                st.mac,
                                targetAnc,
                                st.asmLevel,
                                st.voiceFocus,
                                false);
                        notifyNoiseReductionChanged(param.thisObject);
                        param.setResult(null);
                    }
                } catch (Throwable t) {
                    XposedCompat.debug("EarphoneController.setNoiseReductionMode failed: " + t.getMessage());
                }
            }
        });

        XposedCompat.hookAllMethods(ctrlCls, "getSpatialAudioType", new XposedCompat.XC_MethodHook() {
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
                if (!st.connected || !st.supportsSpeakToChat || !addr.equalsIgnoreCase(st.mac)) {
                    return;
                }
                ensureSpatialSupportInActiveDevice(param.thisObject, true);
                param.setResult(effectiveSpeakOn(st) ? 1 : 0);
            }
        });

        Class<?> spatialTileCls = XposedCompat.findClassIfExists(
                "com.oplus.systemui.qs.slider.detail.tile.volume.SpatialAudioDetailTile", cl);
        if (spatialTileCls != null) {
            XposedCompat.hookAllMethods(spatialTileCls, "isAvailable", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    if (param.thisObject != null) {
                        sSpatialAudioTile = param.thisObject;
                    }
                    cfg.reload(false);
                    if (!cfg.melodyChannel || param.thisObject == null) {
                        return;
                    }
                    Object ctrl = resolveSpatialEarphoneController(param.thisObject);
                    Context ctx = resolveTileOrCtrlContext(param.thisObject, ctrl);
                    if (ctx == null) return;
                    ensureReceiver(ctx);
                    ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
                    if (isActiveSonyDeviceForCtrl(ctrl, st)) {
                        if (st.supportsSpeakToChat) {
                            if (ctrl != null) {
                                syncEarphoneControllerForSony(ctrl, cl, ctx);
                            }
                            param.setResult(Boolean.TRUE);
                        } else {
                            param.setResult(Boolean.FALSE);
                        }
                    }
                }
            });

            XposedCompat.hookAllMethods(spatialTileCls, "getTileLabel", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    if (param.thisObject == null) return;
                    cfg.reload(false);
                    if (!cfg.melodyChannel) return;
                    Object ctrl = resolveSpatialEarphoneController(param.thisObject);
                    Context ctx = resolveTileOrCtrlContext(param.thisObject, ctrl);
                    if (ctx == null) return;
                    ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
                    if (isActiveSonySpeakDevice(ctrl, st)) {
                        param.setResult(locSys(ctx, "對話感知", "对话感知", "Speak-to-Chat"));
                    }
                }
            });

            XposedCompat.hookAllMethods(spatialTileCls, "getSecondaryLabel", new XposedCompat.XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                    if (param.thisObject == null) return;
                    cfg.reload(false);
                    if (!cfg.melodyChannel) return;
                    Object ctrl = resolveSpatialEarphoneController(param.thisObject);
                    Context ctx = resolveTileOrCtrlContext(param.thisObject, ctrl);
                    if (ctx == null) return;
                    ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
                    if (isActiveSonySpeakDevice(ctrl, st)) {
                        boolean on = effectiveSpeakOn(st);
                        param.setResult(on
                                ? locSys(ctx, "已開啟", "已开启", "On")
                                : locSys(ctx, "已關閉", "已关闭", "Off"));
                    }
                }
            });

            XposedCompat.hookAllMethods(spatialTileCls, "handleUpdateState", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    if (param.thisObject == null || param.args == null || param.args.length < 1
                            || param.args[0] == null) {
                        return;
                    }
                    if (param.method instanceof Method && ((Method) param.method).isSynthetic()) {
                        return;
                    }
                    sSpatialAudioTile = param.thisObject;
                    cfg.reload(false);
                    if (!cfg.melodyChannel) return;
                    Object ctrl = resolveSpatialEarphoneController(param.thisObject);
                    Context ctx = resolveTileOrCtrlContext(param.thisObject, ctrl);
                    if (ctx == null) return;
                    ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
                    if (!isActiveSonySpeakDevice(ctrl, st)) {
                        return;
                    }
                    boolean speakOn = effectiveSpeakOn(st);
                    Object stateObj = param.args[0];
                    String label = locSys(ctx, "對話感知", "对话感知", "Speak-to-Chat");
                    String secLabel = speakOn
                            ? locSys(ctx, "已開啟", "已开启", "On")
                            : locSys(ctx, "已關閉", "已关闭", "Off");
                    try {
                        setStateField(stateObj, "label", label);
                        setStateField(stateObj, "secondaryLabel", secLabel);
                        setStateField(stateObj, "contentDescription", label + " " + secLabel);
                        setStateField(stateObj, "value", Boolean.valueOf(speakOn));
                        setStateField(stateObj, "state", Integer.valueOf(speakOn ? 2 : 1));
                        setStateField(stateObj, "threeStageMode", Integer.valueOf(speakOn ? 1 : 0));
                        Object icon = createSpeakToChatTileIcon(cl, ctx);
                        if (icon != null) {
                            setStateField(stateObj, "icon", icon);
                        }
                    } catch (Throwable t) {
                        XposedCompat.debug("SpatialAudioDetailTile.handleUpdateState override failed: " + t.getMessage());
                    }
                }
            });

            XposedCompat.hookAllMethods(spatialTileCls, "handleClick", new XposedCompat.XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                    if (param.thisObject == null) return;
                    sSpatialAudioTile = param.thisObject;
                    cfg.reload(false);
                    if (!cfg.melodyChannel) return;
                    Object ctrl = resolveSpatialEarphoneController(param.thisObject);
                    Context ctx = resolveTileOrCtrlContext(param.thisObject, ctrl);
                    if (ctx == null) return;
                    ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
                    if (!isActiveSonySpeakDevice(ctrl, st)) {
                        return;
                    }
                    boolean next = !effectiveSpeakOn(st);
                    sOptimisticSpeakVal = Boolean.valueOf(next);
                    sOptimisticSpeakUntilMs = SystemClock.elapsedRealtime() + 1500L;
                    ConfigStore.sendCommandBroadcast(
                            ctx,
                            ConfigStore.CMD_SET_SPEAK_TO_CHAT,
                            st.mac,
                            st.ancMode,
                            st.asmLevel,
                            st.voiceFocus,
                            next);
                    notifySpatialAudioChanged(ctrl);
                    param.setResult(null);
                }
            });

            XposedCompat.hookAllMethods(spatialTileCls, "getLongClickIntent", new XposedCompat.XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                    if (param.thisObject == null) return;
                    cfg.reload(false);
                    if (!cfg.melodyChannel) return;
                    Object ctrl = resolveSpatialEarphoneController(param.thisObject);
                    Context ctx = resolveTileOrCtrlContext(param.thisObject, ctrl);
                    if (ctx == null) return;
                    ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
                    if (isActiveSonySpeakDevice(ctrl, st)) {
                        param.setResult(null);
                    }
                }
            });
        }
    }

    private static void notifyEarphoneCallbacks(Object ctrl, String callbackMethod) {
        if (ctrl == null) return;
        try {
            Field cbField = ctrl.getClass().getDeclaredField("callbacks");
            cbField.setAccessible(true);
            Object cbObj = cbField.get(ctrl);
            if (cbObj instanceof Iterable) {
                List<Object> snapshot = new ArrayList<>();
                synchronized (cbObj) {
                    for (Object item : (Iterable<?>) cbObj) {
                        if (item != null) {
                            snapshot.add(item);
                        }
                    }
                }
                for (Object cb : snapshot) {
                    try {
                        cb.getClass().getMethod(callbackMethod).invoke(cb);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void notifyNoiseReductionChanged(Object ctrl) {
        MAIN_HANDLER.post(() -> {
            notifyEarphoneCallbacks(ctrl, "onNoiseReductionTypeChanged");
            Object noiseTile = sNoiseReductionTile;
            if (noiseTile != null) {
                try {
                    Method refreshM = noiseTile.getClass().getMethod("refreshState", Object.class);
                    refreshM.invoke(noiseTile, (Object) null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static void notifySpatialAudioChanged(Object ctrl) {
        MAIN_HANDLER.post(() -> {
            notifyEarphoneCallbacks(ctrl, "onSpatialAudioTypeChanged");
            Object spatialTile = sSpatialAudioTile;
            if (spatialTile != null) {
                try {
                    Method refreshM = spatialTile.getClass().getMethod("refreshState", Object.class);
                    refreshM.invoke(spatialTile, (Object) null);
                } catch (Throwable ignored) {
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
            if (st.supportsNcAsm) {
                support.put("noise", Arrays.asList(5, 2, 1));
            }
            if (st.supportsSpeakToChat) {
                support.put("spatial", Arrays.asList(1, 0));
            }
            // Exact constructor order: (name, address, supportType)
            return ctor.newInstance(st.name, st.mac, support);
        } catch (Throwable t) {
            XposedCompat.debug("createActiveDevice failed: " + t.getMessage());
            return null;
        }
    }

    private static void syncEarphoneControllerForSony(Object ctrl, ClassLoader cl, Context ctx) {
        if (ctrl == null || ctx == null) return;
        try {
            ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
            Method getActive = ctrl.getClass().getMethod("getActiveDevice");
            Object activeDev = getActive.invoke(ctrl);

            if (st.connected && (st.supportsNcAsm || st.supportsSpeakToChat)
                    && st.mac != null && !st.mac.isEmpty()) {
                if (activeDev == null) {
                    activeDev = createActiveDevice(cl, st);
                    if (activeDev != null) {
                        Method setActive = ctrl.getClass().getDeclaredMethod("setActiveDevice", activeDev.getClass());
                        setActive.setAccessible(true);
                        setActive.invoke(ctrl, activeDev);
                    }
                }
                if (activeDev != null) {
                    String activeAddr = invokeStringGetter(activeDev, "getAddress");
                    if (activeAddr.isEmpty() || activeAddr.equalsIgnoreCase(st.mac)) {
                        sInjectedEarMac = st.mac;
                        Method getSupport = activeDev.getClass().getMethod("getSupportType");
                        Object mapObj = getSupport.invoke(activeDev);
                        if (mapObj instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, List<Integer>> supportMap = (Map<String, List<Integer>>) mapObj;
                            if (st.supportsNcAsm) {
                                if (!supportMap.containsKey("noise") || supportMap.get("noise") == null
                                        || supportMap.get("noise").size() <= 1) {
                                    supportMap.put("noise", Arrays.asList(5, 2, 1));
                                }
                            } else {
                                supportMap.remove("noise");
                            }
                            if (st.supportsSpeakToChat) {
                                if (!supportMap.containsKey("spatial") || supportMap.get("spatial") == null
                                        || supportMap.get("spatial").size() <= 1) {
                                    supportMap.put("spatial", Arrays.asList(1, 0));
                                }
                            } else {
                                supportMap.remove("spatial");
                            }
                        }
                        setEarStateFlowValue(ctrl, 1);
                        if (sInjectedSpeakSupport != st.supportsSpeakToChat) {
                            sInjectedSpeakSupport = st.supportsSpeakToChat;
                            notifyEarphoneCallbacks(ctrl, "onActiveDeviceChanged");
                        }
                    }
                }
            } else if (!sInjectedEarMac.isEmpty()) {
                String activeAddr = activeDev != null ? invokeStringGetter(activeDev, "getAddress") : "";
                if (activeAddr.isEmpty() || activeAddr.equalsIgnoreCase(sInjectedEarMac)) {
                    setEarStateFlowValue(ctrl, 0);
                    sInjectedEarMac = "";
                    if (sInjectedSpeakSupport) {
                        sInjectedSpeakSupport = false;
                        notifyEarphoneCallbacks(ctrl, "onActiveDeviceChanged");
                    }
                }
            }
        } catch (Throwable t) {
            XposedCompat.debug("syncEarphoneControllerForSony failed: " + t.getMessage());
        }
    }

    private static void setEarStateFlowValue(Object ctrl, int value) {
        try {
            Field earStateField = ctrl.getClass().getDeclaredField("_earState");
            earStateField.setAccessible(true);
            Object flow = earStateField.get(ctrl);
            if (flow != null) {
                Method setVal = flow.getClass().getMethod("setValue", Object.class);
                setVal.invoke(flow, Integer.valueOf(value));
            }
        } catch (Throwable t) {
            XposedCompat.debug("setEarStateFlowValue failed: " + t.getMessage());
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

    private static volatile boolean sCardIconsResolved;
    private static volatile int sBoxResId;
    private static volatile int sEarbudResId;
    private static volatile int sEarbudOfflineResId;
    private static volatile int sOverEarResId;
    private static volatile int sOverEarOfflineResId;
    private static final Map<View, Object> sCardPanelDevices =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static void hookDeviceCardIcons(ClassLoader cl, ConfigStore.HookConfig cfg) {
        Class<?> remoteLoaderCls = XposedCompat.findClassIfExists(
                "com.oplus.deviceplugin.sdk.utils.RemoteDeviceIconLoaderKt", cl);
        int remoteHookCount = 0;
        if (remoteLoaderCls != null) {
            XposedCompat.XC_MethodHook remoteSkipHook = new XposedCompat.XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                    if (param.args == null || param.args.length < 2
                            || !(param.args[0] instanceof View)
                            || param.args[1] == null) {
                        return;
                    }
                    View progLayout = (View) param.args[0];
                    Object panelDev = param.args[1];
                    sCardPanelDevices.put(progLayout, panelDev);

                    cfg.reload(false);
                    if (!cfg.nativeArtwork) {
                        return;
                    }
                    Context ctx = progLayout.getContext();
                    if (ctx == null) {
                        return;
                    }
                    ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
                    if (isSonyPanelDevice(ctx, progLayout, panelDev, st)) {
                        XposedCompat.debug("QS remote icon skipped for Sony: dev="
                                + readPanelDeviceName(ctx, progLayout, panelDev)
                                + ", method=" + (param.method != null ? param.method.getName() : "?"));
                        param.setResult(Boolean.FALSE);
                    }
                }
            };
            remoteHookCount += XposedCompat.hookAllMethods(
                    remoteLoaderCls, "tryBindRemoteDeviceIcon", remoteSkipHook).size();
            remoteHookCount += XposedCompat.hookAllMethods(
                    remoteLoaderCls, "tryBindRemoteDeviceIconBlurDisable", remoteSkipHook).size();
            remoteHookCount += XposedCompat.hookAllMethods(
                    remoteLoaderCls, "tryBindRemoteDeviceIconTheme", remoteSkipHook).size();
        }

        Class<?> progCls = XposedCompat.findClassIfExists(
                "com.oplus.deviceplugin.sdk.ui.view.progressbar.BatteryCircularProgressLayout", cl);
        if (progCls == null) {
            XposedCompat.log("QS card icon hook skipped (no BatteryCircularProgressLayout), remote=" + remoteHookCount);
            return;
        }
        XposedCompat.XC_MethodHook iconReplaceHook = new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                if (param.args == null || param.args.length < 1
                        || !(param.args[0] instanceof Integer)
                        || !(param.thisObject instanceof View)) {
                    return;
                }
                cfg.reload(false);
                if (!cfg.nativeArtwork) {
                    return;
                }
                View progLayout = (View) param.thisObject;
                Context ctx = progLayout.getContext();
                if (ctx == null) {
                    return;
                }
                int resId = (Integer) param.args[0];
                if (resId == 0) {
                    return;
                }
                ensureCardIconIds(ctx);
                String entryName = "";
                try {
                    entryName = ctx.getResources().getResourceEntryName(resId);
                } catch (Resources.NotFoundException ignored) {
                }
                String lowerEntry = entryName != null ? entryName.toLowerCase(Locale.ROOT) : "";
                boolean isWithoutBox = lowerEntry.contains("without_box");
                boolean isBoxIcon = !isWithoutBox && ((sBoxResId != 0 && resId == sBoxResId)
                        || lowerEntry.contains("box")
                        || lowerEntry.contains("case"));
                boolean isOfflineDrawable = lowerEntry.contains("unusable")
                        || lowerEntry.contains("not_in_use")
                        || lowerEntry.contains("disable");

                Object panelDev = sCardPanelDevices.get(progLayout);
                if (panelDev == null) {
                    panelDev = findParentPanelDevice(progLayout);
                }
                ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
                if (!isSonyPanelDevice(ctx, progLayout, panelDev, st)) {
                    return;
                }

                String effectiveModel = resolveEffectiveSonyModel(ctx, progLayout, panelDev, st);
                boolean overEar = SonyDeviceDetector.isOverEarModel(effectiveModel);
                boolean shouldReplace = isBoxIcon
                        || (overEar && (isWithoutBox
                                || (sEarbudResId != 0 && resId == sEarbudResId)
                                || (sEarbudOfflineResId != 0 && resId == sEarbudOfflineResId)));
                if (!shouldReplace) {
                    return;
                }

                int replacement;
                if (overEar) {
                    replacement = isOfflineDrawable
                            ? (sOverEarOfflineResId != 0 ? sOverEarOfflineResId : sOverEarResId)
                            : (sOverEarResId != 0 ? sOverEarResId : sEarbudResId);
                } else {
                    replacement = isOfflineDrawable
                            ? (sEarbudOfflineResId != 0 ? sEarbudOfflineResId : sEarbudResId)
                            : sEarbudResId;
                }
                if (replacement != 0 && replacement != resId) {
                    param.args[0] = replacement;
                    String repEntry;
                    try {
                        repEntry = ctx.getResources().getResourceEntryName(replacement);
                    } catch (Resources.NotFoundException ignored) {
                        repEntry = "0x" + Integer.toHexString(replacement);
                    }
                    XposedCompat.debug("QS card icon replaced: " + entryName
                            + " -> " + repEntry
                            + ", dev=" + readPanelDeviceName(ctx, progLayout, panelDev)
                            + ", overEar=" + overEar
                            + ", offline=" + isOfflineDrawable
                            + ", method=" + (param.method != null ? param.method.getName() : "?"));
                }
            }
        };

        int hookCount = 0;
        hookCount += XposedCompat.hookAllMethods(progCls, "a", iconReplaceHook).size();
        hookCount += XposedCompat.hookAllMethods(progCls, "b", iconReplaceHook).size();
        hookCount += XposedCompat.hookAllMethods(progCls, "setDeviceIcon", iconReplaceHook).size();
        hookCount += XposedCompat.hookAllMethods(progCls, "setDeviceIconWithAnimation", iconReplaceHook).size();
        XposedCompat.log("QS card icon hook installed: remote=" + remoteHookCount + ", layout=" + hookCount);
    }

    private static void ensureCardIconIds(Context ctx) {
        if (sCardIconsResolved || ctx == null) {
            return;
        }
        Resources res = ctx.getResources();
        String pkg = ctx.getPackageName();
        sBoxResId = resolveDrawableId(res, pkg, "icon_headset_box");
        int earbud = resolveDrawableId(res, pkg, "icon_headset_without_box");
        if (earbud == 0) {
            earbud = resolveDrawableId(res, pkg, "icon_separate_default_ear");
        }
        sEarbudResId = earbud;
        int earbudOffline = resolveDrawableId(res, pkg, "icon_headset_without_box_unusable_blur");
        if (earbudOffline == 0) {
            earbudOffline = resolveDrawableId(res, pkg, "icon_separate_default_ear");
        }
        if (earbudOffline == 0) {
            earbudOffline = earbud;
        }
        sEarbudOfflineResId = earbudOffline;

        int overEar = resolveDrawableId(res, pkg, "blur_icon_bt_headset_in_use");
        if (overEar == 0) {
            overEar = resolveDrawableId(res, pkg, "icon_fixed_default_headphones");
        }
        if (overEar == 0) {
            overEar = resolveDrawableId(res, pkg, "qs_media_output_headband_headset");
        }
        if (overEar == 0) {
            overEar = earbud;
        }
        sOverEarResId = overEar;

        int overEarOffline = resolveDrawableId(res, pkg, "blur_icon_bt_headset_not_in_use");
        if (overEarOffline == 0) {
            overEarOffline = resolveDrawableId(res, pkg, "icon_fixed_default_headphones");
        }
        if (overEarOffline == 0) {
            overEarOffline = overEar;
        }
        sOverEarOfflineResId = overEarOffline;
        sCardIconsResolved = true;
    }

    private static String readPanelDeviceName(Context ctx, View progLayout, Object panelDev) {
        String devName = panelDev != null ? invokeStringGetter(panelDev, "getName") : "";
        if (devName.isEmpty() && progLayout != null && ctx != null) {
            devName = findParentCardTitle(progLayout, ctx);
        }
        return devName;
    }

    private static boolean isSonyPanelDevice(Context ctx, View progLayout, Object panelDev,
            ConfigStore.HeadsetState st) {
        String devName = readPanelDeviceName(ctx, progLayout, panelDev);
        String devId = panelDev != null ? invokeStringGetter(panelDev, "getDeviceId") : "";
        boolean matchesConnectedSony = st.connected && (
                (!devName.isEmpty() && st.name != null && devName.equalsIgnoreCase(st.name))
                || (!devId.isEmpty() && st.mac != null && devId.equalsIgnoreCase(st.mac))
                || (panelDev == null && devName.isEmpty() && st.name != null && !st.name.isEmpty()));
        return SonyDeviceDetector.isSonyName(devName) || matchesConnectedSony;
    }

    private static String resolveEffectiveSonyModel(Context ctx, View progLayout, Object panelDev,
            ConfigStore.HeadsetState st) {
        String devName = readPanelDeviceName(ctx, progLayout, panelDev);
        String candidate = !devName.isEmpty() ? devName : st.effectiveModelName();
        return SonyDeviceDetector.normalizeModelName(candidate);
    }

    private static int resolveDrawableId(Resources res, String pkg, String name) {
        if (res == null || name == null) return 0;
        int id = 0;
        if (pkg != null && !pkg.isEmpty()) {
            id = res.getIdentifier(name, "drawable", pkg);
        }
        if (id == 0 && !"com.android.systemui".equals(pkg)) {
            id = res.getIdentifier(name, "drawable", "com.android.systemui");
        }
        return id;
    }

    private static Object findParentPanelDevice(View child) {
        ViewParent cur = child != null ? child.getParent() : null;
        for (int depth = 0; depth < 10 && cur != null; depth++) {
            String clsName = cur.getClass().getName();
            if (clsName.endsWith(".RectangleDeviceCardView")) {
                Object dev = readPanelDeviceField(cur, "B");
                if (dev != null) return dev;
            } else if (clsName.endsWith(".SquareDeviceCardView")) {
                Object dev = readPanelDeviceField(cur, "w");
                if (dev != null) return dev;
            }
            cur = cur.getParent();
        }
        return null;
    }

    private static Object readPanelDeviceField(Object target, String preferredName) {
        Class<?> cls = target.getClass();
        while (cls != null && cls != Object.class) {
            try {
                Field f = cls.getDeclaredField(preferredName);
                f.setAccessible(true);
                Object v = f.get(target);
                if (v != null && v.getClass().getName().endsWith(".PanelDevice")) {
                    return v;
                }
            } catch (Throwable ignored) {
            }
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType().getName().endsWith(".PanelDevice")) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(target);
                        if (v != null) {
                            return v;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static String findParentCardTitle(View child, Context ctx) {
        ViewParent cur = child != null ? child.getParent() : null;
        for (int depth = 0; depth < 10 && cur != null; depth++) {
            String clsName = cur.getClass().getName();
            if ((clsName.endsWith(".RectangleDeviceCardView") || clsName.endsWith(".SquareDeviceCardView"))
                    && cur instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) cur;
                String[] titleNames = new String[]{"rectangle_panel_title", "square_panel_title"};
                for (String tName : titleNames) {
                    int id = ctx.getResources().getIdentifier(tName, "id", ctx.getPackageName());
                    if (id == 0) {
                        id = ctx.getResources().getIdentifier(tName, "id", "com.android.systemui");
                    }
                    if (id != 0) {
                        View tv = vg.findViewById(id);
                        if (tv instanceof TextView && ((TextView) tv).getText() != null) {
                            String txt = ((TextView) tv).getText().toString().trim();
                            if (!txt.isEmpty()) return txt;
                        }
                    }
                }
            }
            cur = cur.getParent();
        }
        return "";
    }

    private static String invokeStringGetter(Object target, String methodName) {
        if (target == null) return "";
        try {
            Object v = target.getClass().getMethod(methodName).invoke(target);
            return v instanceof String ? ((String) v).trim() : "";
        } catch (Throwable ignored) {
            return "";
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
                    sOptimisticAncUntilMs = 0L;
                    sOptimisticAncMode = -1;
                    sOptimisticSpeakVal = null;
                    sOptimisticSpeakUntilMs = 0L;
                    Object ctrl = sEarphoneController;
                    if (ctrl != null) {
                        try {
                            ctrl.getClass().getMethod("refreshActiveDevice").invoke(ctrl);
                            ctrl.getClass().getMethod("refreshSupportType").invoke(ctrl);
                        } catch (Throwable ignored) {
                        }
                        if (sConfig == null || sConfig.melodyChannel) {
                            syncEarphoneControllerForSony(ctrl, ctrl.getClass().getClassLoader(), appCtx);
                        }
                        notifyNoiseReductionChanged(ctrl);
                        notifySpatialAudioChanged(ctrl);
                    } else {
                        if (sNoiseReductionTile != null) {
                            notifyNoiseReductionChanged(null);
                        }
                        if (sSpatialAudioTile != null) {
                            notifySpatialAudioChanged(null);
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

    private static Object resolveSpatialEarphoneController(Object tile) {
        if (tile == null) return sEarphoneController;
        try {
            Field f = tile.getClass().getDeclaredField("earphoneController");
            f.setAccessible(true);
            Object v = f.get(tile);
            if (v != null) {
                sEarphoneController = v;
                return v;
            }
        } catch (Throwable ignored) {
        }
        return sEarphoneController;
    }

    private static Context resolveTileOrCtrlContext(Object tile, Object ctrl) {
        Context ctx = resolveFieldContext(tile, "mContext");
        if (ctx == null && ctrl != null) {
            ctx = resolveFieldContext(ctrl, "context");
        }
        return ctx;
    }

    private static boolean isActiveSonyDeviceForCtrl(Object ctrl, ConfigStore.HeadsetState st) {
        if (st == null || !st.connected || st.mac == null || st.mac.isEmpty()) {
            return false;
        }
        if (ctrl == null) {
            return true;
        }
        try {
            Object activeDev = ctrl.getClass().getMethod("getActiveDevice").invoke(ctrl);
            if (activeDev == null) {
                return true;
            }
            String activeAddr = invokeStringGetter(activeDev, "getAddress");
            if (!activeAddr.isEmpty()) {
                return activeAddr.equalsIgnoreCase(st.mac);
            }
            String activeName = invokeStringGetter(activeDev, "getName");
            return activeName.isEmpty() || SonyDeviceDetector.isSonyName(activeName)
                    || (st.name != null && activeName.equalsIgnoreCase(st.name));
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean isActiveSonySpeakDevice(Object ctrl, ConfigStore.HeadsetState st) {
        return st != null && st.supportsSpeakToChat && isActiveSonyDeviceForCtrl(ctrl, st);
    }

    private static boolean effectiveSpeakOn(ConfigStore.HeadsetState st) {
        Boolean opt = sOptimisticSpeakVal;
        if (opt != null && SystemClock.elapsedRealtime() < sOptimisticSpeakUntilMs) {
            return opt.booleanValue();
        }
        return st != null && st.speakToChat;
    }

    private static void ensureSpatialSupportInActiveDevice(Object ctrl, boolean supported) {
        if (ctrl == null) return;
        try {
            Object activeDev = ctrl.getClass().getMethod("getActiveDevice").invoke(ctrl);
            if (activeDev == null) return;
            Object mapObj = activeDev.getClass().getMethod("getSupportType").invoke(activeDev);
            if (mapObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, List<Integer>> supportMap = (Map<String, List<Integer>>) mapObj;
                if (supported) {
                    if (!supportMap.containsKey("spatial") || supportMap.get("spatial") == null
                            || supportMap.get("spatial").size() <= 1) {
                        supportMap.put("spatial", Arrays.asList(1, 0));
                    }
                } else {
                    supportMap.remove("spatial");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void setStateField(Object target, String fieldName, Object value) {
        if (target == null) return;
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return;
            }
        }
    }

    private static String locSys(Context ctx, String zhTw, String zhCn, String en) {
        Locale locale = (ctx != null && ctx.getResources() != null)
                ? ctx.getResources().getConfiguration().getLocales().get(0)
                : Locale.getDefault();
        if (locale != null && "zh".equals(locale.getLanguage())) {
            String country = locale.getCountry();
            String script = locale.getScript();
            if ("CN".equalsIgnoreCase(country) || "SG".equalsIgnoreCase(country) || "Hans".equalsIgnoreCase(script)) {
                return zhCn;
            }
            return zhTw;
        }
        return en;
    }

    private static Object createSpeakToChatTileIcon(ClassLoader cl, Context ctx) {
        try {
            Class<?> iconCls = XposedCompat.findClassIfExists(
                    "com.android.systemui.qs.tileimpl.QSTileImpl$DrawableIcon", cl);
            if (iconCls == null) return null;
            Constructor<?> ctor = iconCls.getConstructor(Drawable.class);
            float d = (ctx != null && ctx.getResources() != null)
                    ? ctx.getResources().getDisplayMetrics().density
                    : 3f;
            int sizePx = Math.max(24, Math.round(24f * d));
            return ctor.newInstance(new SpeakToChatTileDrawable(sizePx));
        } catch (Throwable t) {
            XposedCompat.debug("createSpeakToChatTileIcon failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * 24dp monochrome vector-style drawable depicting a speaker profile + dual sound waves
     * for the ColorOS volume panel Speak-to-Chat quick tile.
     */
    private static final class SpeakToChatTileDrawable extends Drawable {
        private final int sizePx;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shoulderPath = new Path();
        private final RectF innerArcRect = new RectF();
        private final RectF outerArcRect = new RectF();
        private final ConstantState constantState;
        private ColorStateList tintList;
        private ColorFilter colorFilter;
        private int alpha = 255;

        SpeakToChatTileDrawable(int sizePx) {
            this.sizePx = sizePx;
            fillPaint.setStyle(Paint.Style.FILL);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);
            syncPaintColor(getState());
            this.constantState = new ConstantState() {
                @Override
                public Drawable newDrawable() {
                    SpeakToChatTileDrawable copy = new SpeakToChatTileDrawable(sizePx);
                    copy.tintList = tintList;
                    copy.colorFilter = colorFilter;
                    copy.alpha = alpha;
                    copy.syncPaintColor(copy.getState());
                    return copy;
                }

                @Override
                public int getChangingConfigurations() {
                    return 0;
                }
            };
        }

        private void syncPaintColor(int[] state) {
            int baseColor = Color.WHITE;
            if (tintList != null) {
                baseColor = tintList.getColorForState(state, tintList.getDefaultColor());
            }
            int finalAlpha = Math.round((Color.alpha(baseColor) * alpha) / 255f);
            int rgb = baseColor & 0x00FFFFFF;
            int combined = (finalAlpha << 24) | rgb;
            fillPaint.setColor(combined);
            strokePaint.setColor(combined);
            fillPaint.setColorFilter(colorFilter);
            strokePaint.setColorFilter(colorFilter);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float bw = b.width();
            float bh = b.height();
            if (bw <= 0f || bh <= 0f) return;

            float scale = Math.min(bw, bh) / 24f;
            float ox = b.left + (bw - 24f * scale) * 0.5f;
            float oy = b.top + (bh - 24f * scale) * 0.5f;

            int sc = canvas.save();
            canvas.translate(ox, oy);
            canvas.scale(scale, scale);

            // Person head + shoulders on left half
            canvas.drawCircle(8.5f, 7.2f, 3.2f, fillPaint);

            shoulderPath.reset();
            shoulderPath.moveTo(2.8f, 19.6f);
            shoulderPath.cubicTo(2.8f, 15.6f, 5.4f, 13.6f, 8.5f, 13.6f);
            shoulderPath.cubicTo(11.6f, 13.6f, 14.2f, 15.6f, 14.2f, 19.6f);
            shoulderPath.close();
            canvas.drawPath(shoulderPath, fillPaint);

            // Dual voice waves radiating to the right
            strokePaint.setStrokeWidth(1.9f);
            innerArcRect.set(11.4f, 6.2f, 17.6f, 13.8f);
            canvas.drawArc(innerArcRect, -44f, 88f, false, strokePaint);

            outerArcRect.set(12.2f, 3.6f, 21.0f, 16.4f);
            canvas.drawArc(outerArcRect, -48f, 96f, false, strokePaint);

            canvas.restoreToCount(sc);
        }

        @Override
        public int getIntrinsicWidth() {
            return sizePx;
        }

        @Override
        public int getIntrinsicHeight() {
            return sizePx;
        }

        @Override
        public void setAlpha(int alpha) {
            if (this.alpha != alpha) {
                this.alpha = Math.max(0, Math.min(255, alpha));
                syncPaintColor(getState());
                invalidateSelf();
            }
        }

        @Override
        public int getAlpha() {
            return alpha;
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            this.colorFilter = cf;
            syncPaintColor(getState());
            invalidateSelf();
        }

        @Override
        public void setTintList(ColorStateList tint) {
            this.tintList = tint;
            syncPaintColor(getState());
            invalidateSelf();
        }

        @Override
        public boolean isStateful() {
            return tintList != null && tintList.isStateful();
        }

        @Override
        protected boolean onStateChange(int[] state) {
            int beforeFill = fillPaint.getColor();
            syncPaintColor(state);
            return beforeFill != fillPaint.getColor() || super.onStateChange(state);
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public ConstantState getConstantState() {
            return constantState;
        }
    }
}
