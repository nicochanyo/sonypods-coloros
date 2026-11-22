package io.github.nicochanyo.sonypods;

import android.app.Activity;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Native ColorOS 16/17 integration hook inside {@code com.heytap.mydevices}.
 *
 * <p>Enables ColorOS's dormant {@code AirpodsPlugin} for Sony headphones, routes
 * Sony MACs in {@code PluginPanelHostActivity} directly to {@code BT_AIRPODS},
 * supplies official transparent Sony catalog artwork (with vector fallback),
 * and gates Fluid Cloud capsule creation via {@link ConfigStore.HookConfig}.</p>
 */
public final class ColorOsMyDevicesHook {
    private static final class SonyCtx {
        final String mac;
        final String model;
        final int colorCode;

        SonyCtx(String mac, String model, int colorCode) {
            this.mac = mac;
            this.model = model;
            this.colorCode = colorCode;
        }
    }

    private static final ThreadLocal<SonyCtx> TLS_SONY = new ThreadLocal<>();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final long POPUP_LAUNCH_CACHE_TTL_MS = 30_000L;

    private static volatile Context sAppContext;
    private static volatile ConfigStore.HookConfig sCfg;
    private static volatile Object sSuccessResultLf;
    private static volatile String sBtAirpodsTypeName;
    private static volatile boolean sOpenPopupReceiverRegistered;
    private static volatile String sLastLaunchedPopupMac = "";
    private static volatile String sLastLaunchedPopupModel = "";
    private static volatile long sLastLaunchedPopupMs;
    private static volatile Object sLastCapsuleHelper;
    private static volatile String sLastCapsuleMac = "";
    private static volatile String sLastCapsuleModel = "";
    private static volatile String sLastCapsuleName = "";
    private static volatile long sLastCapsuleMs;
    private static volatile boolean sLastCapsuleBothEars;
    private static final Map<Object, BroadcastReceiver> POPUP_RECEIVERS =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private ColorOsMyDevicesHook() {
    }

    public static void install(ClassLoader cl, ConfigStore.HookConfig cfg) {
        sCfg = cfg;

        hookApplicationContext();
        hookAirpodsFeatureFlag(cl);
        hookAirpodsBluetoothGateway(cl);
        hookPluginPanelHostActivity(cl);
        hookDeviceInfoConverter(cl);
        hookCapsuleHelper(cl);
        hookIconHelper(cl);
        hookBlueToothCardView(cl);
        hookDetailViewModel(cl);
        hookDetailPreferencesFragment(cl);
        hookSettingActivity(cl);
        hookOpenCasePopup(cl);
        ColorOsNativeExtraPrefsHelper.install(cl, cfg);

        XposedCompat.log("ColorOsMyDevicesHook (native AirpodsPlugin) installed");
    }

    private static void hookApplicationContext() {
        XposedCompat.hookAllMethods(Application.class, "onCreate", new XposedCompat.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                if (param.thisObject instanceof Application) {
                    sAppContext = ((Application) param.thisObject).getApplicationContext();
                    if ("com.heytap.mydevices".equals(Application.getProcessName())) {
                        ensureOpenPopupReceiver(sAppContext);
                    }
                }
            }
        });
    }

    private static void hookAirpodsFeatureFlag(ClassLoader cl) {
        Class<?> helperCls = XposedCompat.findClassIfExists("aa.b2$a", cl);
        if (helperCls == null) {
            XposedCompat.log("MyDevices aa.b2$a not found");
            return;
        }
        // Enable dormant persist.bluetooth.airpods_support feature gate
        XposedCompat.hookAllMethods(helperCls, "r", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                if (isPanelEnabled()) {
                    param.setResult(Boolean.TRUE);
                }
            }
        });
        // Treat over-ear Sony headphones (e.g. WH-1000XM5) as headset form factor
        XposedCompat.hookAllMethods(helperCls, "s", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                if (!isPanelEnabled() || param.args == null || param.args.length < 1) return;
                if (param.args[0] instanceof String) {
                    String model = (String) param.args[0];
                    if (SonyDeviceDetector.isOverEarModel(model)) {
                        param.setResult(Boolean.TRUE);
                    }
                }
            }
        });
    }

    private static void hookAirpodsBluetoothGateway(ClassLoader cl) {
        Class<?> gwCls = XposedCompat.findClassIfExists(
                "com.heytap.mydevices.plugin.airpods.core.AirpodsBluetoothGateway", cl);
        if (gwCls == null) {
            XposedCompat.log("AirpodsBluetoothGateway not found");
            return;
        }
        XposedCompat.hookAllMethods(gwCls, "f", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                if (!isPanelEnabled() || param.args == null || param.args.length < 1) return;
                if (param.args[0] instanceof BluetoothDevice) {
                    BluetoothDevice dev = (BluetoothDevice) param.args[0];
                    if (isSonyDeviceOrState(sAppContext, dev)) {
                        Object ok = getSuccessResultLf(cl);
                        if (ok != null) {
                            param.setResult(ok);
                        }
                    }
                }
            }
        });
        XposedCompat.hookAllMethods(gwCls, "g", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                if (!isPanelEnabled() || param.args == null || param.args.length < 1) return;
                if (param.args[0] instanceof String) {
                    String mac = (String) param.args[0];
                    if (isSonyMac(sAppContext, mac)) {
                        Object ok = getSuccessResultLf(cl);
                        if (ok != null) {
                            param.setResult(ok);
                        }
                    }
                }
            }
        });
        XposedCompat.hookAllMethods(gwCls, "e", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                if (!isPanelEnabled() || param.args == null || param.args.length < 1) return;
                if (param.args[0] instanceof BluetoothDevice) {
                    BluetoothDevice dev = (BluetoothDevice) param.args[0];
                    if (isSonyDeviceOrState(sAppContext, dev)) {
                        String model = resolveSonyModel(sAppContext, dev, safeAddress(dev));
                        if (!model.isEmpty()) {
                            param.setResult(model);
                        }
                    }
                }
            }
        });
    }

    private static void hookPluginPanelHostActivity(ClassLoader cl) {
        Class<?> hostCls = XposedCompat.findClassIfExists(
                "com.oplus.mydevices.bluetooth.PluginPanelHostActivity", cl);
        if (hostCls == null) {
            return;
        }
        XposedCompat.hookAllMethods(hostCls, "a0", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                if (!isPanelEnabled() || param.args == null || param.args.length < 1) return;
                Context ctx = (param.thisObject instanceof Context) ? (Context) param.thisObject : sAppContext;
                if (param.args[0] instanceof String && isSonyMac(ctx, (String) param.args[0])) {
                    String typeName = getBtAirpodsTypeName(cl);
                    if (typeName != null) {
                        param.setResult(typeName);
                    }
                }
            }
        });
        XposedCompat.hookAllMethods(hostCls, "b0", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                if (!isPanelEnabled() || param.args == null || param.args.length < 1) return;
                if (!(param.args[0] instanceof Intent)) return;
                Intent intent = (Intent) param.args[0];
                Context ctx = (param.thisObject instanceof Context) ? (Context) param.thisObject : sAppContext;
                if (isSonyIntent(ctx, intent)) {
                    String typeName = getBtAirpodsTypeName(cl);
                    if (typeName != null) {
                        param.setResult(typeName);
                    }
                }
            }
        });
        XposedCompat.hookAllMethods(hostCls, "c0", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                if (!isPanelEnabled() || param.args == null || param.args.length < 2) return;
                Context ctx = (param.thisObject instanceof Context) ? (Context) param.thisObject : sAppContext;
                String devId = (param.args[0] instanceof String) ? (String) param.args[0] : "";
                String mac = (param.args[1] instanceof String) ? (String) param.args[1] : "";
                if (isSonyMac(ctx, mac) || isSonyMac(ctx, devId)) {
                    String typeName = getBtAirpodsTypeName(cl);
                    if (typeName != null) {
                        param.setResult(typeName);
                    }
                }
            }
        });
    }

    private static void hookDeviceInfoConverter(ClassLoader cl) {
        Class<?> convCls = XposedCompat.findClassIfExists(
                "com.heytap.mydevices.plugin.airpods.core.a", cl);
        if (convCls == null) {
            return;
        }
        XposedCompat.hookAllMethods(convCls, "p", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                if (!isPanelEnabled() || param.args == null || param.args.length < 1) return;
                if (param.args[0] instanceof BluetoothDevice) {
                    BluetoothDevice dev = (BluetoothDevice) param.args[0];
                    if (isSonyDeviceOrState(sAppContext, dev)) {
                        TLS_SONY.set(buildSonyCtx(sAppContext, dev, safeAddress(dev), null));
                    }
                }
            }

            @Override
            protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                TLS_SONY.remove();
            }
        });
    }

    private static void hookCapsuleHelper(ClassLoader cl) {
        Class<?> capCls = XposedCompat.findClassIfExists(
                "com.heytap.mydevices.plugin.airpods.core.AirpodsCapsuleHelper", cl);
        if (capCls != null) {
            XposedCompat.hookAllMethods(capCls, "b", new XposedCompat.XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                    if (!isPanelEnabled() || param.args == null || param.args.length < 2) return;
                    String mac = (param.args[0] instanceof String) ? (String) param.args[0] : "";
                    String model = (param.args[1] instanceof String) ? (String) param.args[1] : "";
                    if (isSonyMac(sAppContext, mac) || isLikelySonyModelName(model)) {
                        TLS_SONY.set(buildSonyCtx(sAppContext, null, mac, model));
                    }
                }

                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    SonyCtx sc = TLS_SONY.get();
                    if (sc != null && param.getResult() instanceof String) {
                        XposedCompat.log("Fluid Cloud capsule JSON for " + sc.model + ": " + param.getResult());
                    }
                    TLS_SONY.remove();
                }
            });

            XposedCompat.hookAllMethods(capCls, "f", new XposedCompat.XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                    if (param.args == null || param.args.length < 1) return;
                    String mac = (param.args[0] instanceof String) ? (String) param.args[0] : "";
                    String model = (param.args.length > 1 && param.args[1] instanceof String)
                            ? (String) param.args[1] : "";
                    String name = (param.args.length > 2 && param.args[2] instanceof String)
                            ? (String) param.args[2] : model;
                    if (!isSonyMac(sAppContext, mac) && !isLikelySonyModelName(model)) {
                        return;
                    }
                    if (!isFluidCloudEnabled()) {
                        param.setResult(null);
                        return;
                    }
                    int[] bat = (param.args.length > 3 && param.args[3] instanceof int[])
                            ? (int[]) param.args[3] : null;
                    boolean valid = isValidBatteryArray(bat);
                    if (sAppContext != null) {
                        ConfigStore.HeadsetState st = ConfigStore.readGlobalState(sAppContext);
                        if (st.connected && (st.leftBattery > 0 || st.rightBattery > 0 || st.singleBattery > 0)) {
                            String effectiveModel = !model.isEmpty() ? model : st.effectiveModelName();
                            boolean missingSecondEar = !SonyDeviceDetector.isOverEarModel(effectiveModel)
                                    && st.leftBattery > 0 && st.rightBattery > 0
                                    && (bat == null || bat.length < 2 || bat[0] <= 0 || bat[1] <= 0);
                            if (!valid || missingSecondEar) {
                                bat = buildBatteryArrayFromState(st, effectiveModel);
                                param.args[3] = bat;
                                valid = isValidBatteryArray(bat);
                            }
                        }
                    }
                    if (!valid) {
                        XposedCompat.debug("Suppressed empty-battery Fluid Cloud capsule for " + model);
                        param.setResult(null);
                        return;
                    }
                    sLastCapsuleHelper = param.thisObject;
                    sLastCapsuleMac = mac;
                    sLastCapsuleModel = model;
                    sLastCapsuleName = name;
                    sLastCapsuleMs = SystemClock.elapsedRealtime();
                    sLastCapsuleBothEars = SonyDeviceDetector.isOverEarModel(model)
                            || (bat != null && bat.length >= 2 && bat[0] > 0 && bat[1] > 0);
                }
            });
        }

        Class<?> capabilityCls = XposedCompat.findClassIfExists("aa.M1", cl);
        if (capabilityCls != null) {
            XposedCompat.hookAllMethods(capabilityCls, "supports", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    if (Boolean.TRUE.equals(param.getResult()) || !isPanelEnabled()
                            || param.args == null || param.args.length < 1 || param.args[0] == null) {
                        return;
                    }
                    try {
                        Method getDeviceModel = findMethod(param.args[0].getClass(), "getDeviceModel");
                        Object res = getDeviceModel != null ? getDeviceModel.invoke(param.args[0]) : null;
                        if (res instanceof String && SonyDeviceDetector.isOverEarModel((String) res)) {
                            param.setResult(Boolean.TRUE);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
    }

    private static void hookIconHelper(ClassLoader cl) {
        Class<?> iconCls = XposedCompat.findClassIfExists(
                "com.heytap.mydevices.plugin.airpods.core.AirpodsIconHelper", cl);
        if (iconCls == null) {
            return;
        }
        final Field ctxField = findField(iconCls, "a");
        final Method saveUriMethod = findMethod(iconCls, "c", Bitmap.class, String.class);

        XposedCompat.XC_MethodHook uriHook = new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                SonyCtx sc = TLS_SONY.get();
                if (sc == null || !isArtworkEnabled() || param.thisObject == null || saveUriMethod == null) {
                    return;
                }
                Context ctx = safeContext(ctxField, param.thisObject);
                if (ctx == null) return;
                boolean isCapsule = param.method != null && param.method.getName().contains("Capsule");
                int sizePx = isCapsule ? 256 : 512;
                String customUri = sCfg != null ? sCfg.customArtworkUri : "";
                Bitmap bmp = SonyAssetProvider.loadBitmap(ctx, sc.model, sc.colorCode, customUri, sizePx);
                if (bmp == null) return;
                try {
                    String safeName = buildWebpFileName(sc.model, sc.colorCode, isCapsule);
                    Uri out = (Uri) saveUriMethod.invoke(param.thisObject, bmp, safeName);
                    if (out != null) {
                        param.setResult(out.toString());
                    }
                } catch (Throwable t) {
                    XposedCompat.debug("AirpodsIconHelper URI injection failed: " + t.getMessage());
                }
            }
        };
        XposedCompat.hookAllMethods(iconCls, "getHomeIconUri", uriHook);
        XposedCompat.hookAllMethods(iconCls, "getCapsuleIconUri", uriHook);
    }

    private static void hookBlueToothCardView(ClassLoader cl) {
        Class<?> cardCls = XposedCompat.findClassIfExists(
                "com.oplus.mydevices.bluetooth.BlueToothCardView", cl);
        if (cardCls == null) {
            return;
        }
        final Field addrField = findField(cardCls, "c");
        XposedCompat.hookAllMethods(cardCls, "g", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                if (!isPanelEnabled() || !isArtworkEnabled() || !(param.thisObject instanceof View)) {
                    return;
                }
                View cardView = (View) param.thisObject;
                String mac = safeStringField(addrField, cardView);
                Context ctx = cardView.getContext() != null ? cardView.getContext() : sAppContext;
                if (!isSonyMac(ctx, mac)) {
                    return;
                }
                SonyCtx sc = buildSonyCtx(ctx, null, mac, null);
                String customUri = sCfg != null ? sCfg.customArtworkUri : "";
                Bitmap bmp = SonyAssetProvider.loadBitmap(ctx, sc.model, sc.colorCode, customUri, 512);
                if (bmp != null && param.args != null && param.args.length >= 1) {
                    param.args[0] = new BitmapDrawable(cardView.getResources(), bmp);
                }
            }
        });
    }

    private static void hookDetailViewModel(ClassLoader cl) {
        Class<?> vmCls = XposedCompat.findClassIfExists(
                "com.heytap.mydevices.plugin.airpods.viewmodel.AirpodsDetailViewModel", cl);
        if (vmCls == null) {
            return;
        }
        final Field macField = findField(vmCls, "e");
        XposedCompat.hookAllMethods(vmCls, "o", new XposedCompat.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                if (!isPanelEnabled() || param.thisObject == null) return;
                Object res = param.getResult();
                if (res instanceof String && !((String) res).trim().isEmpty()) {
                    return;
                }
                String mac = safeStringField(macField, param.thisObject);
                if (isSonyMac(sAppContext, mac)) {
                    String model = buildSonyCtx(sAppContext, null, mac, null).model;
                    if (!model.isEmpty()) {
                        param.setResult(model);
                    }
                }
            }
        });
        XposedCompat.hookAllMethods(vmCls, "s", new XposedCompat.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                if (!isPanelEnabled() || param.thisObject == null) return;
                String mac = safeStringField(macField, param.thisObject);
                if (isSonyMac(sAppContext, mac)) {
                    String model = buildSonyCtx(sAppContext, null, mac, null).model;
                    param.setResult(SonyDeviceDetector.isOverEarModel(model) ? 2000 : 2001);
                }
            }
        });
        XposedCompat.hookAllMethods(vmCls, "q", new XposedCompat.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                if (!isPanelEnabled() || param.thisObject == null) return;
                Object res = param.getResult();
                if (res instanceof Integer && ((Integer) res) > 0) {
                    return;
                }
                String mac = safeStringField(macField, param.thisObject);
                if (isSonyMac(sAppContext, mac)) {
                    String model = buildSonyCtx(sAppContext, null, mac, null).model;
                    param.setResult(SonyDeviceDetector.isOverEarModel(model) ? 0x2E17 : 0x2E15);
                }
            }
        });
    }

    private static void hookDetailPreferencesFragment(ClassLoader cl) {
        Class<?> fragCls = XposedCompat.findClassIfExists(
                "com.heytap.mydevices.plugin.airpods.fragment.AirpodsDetailPreferencesFragment", cl);
        if (fragCls == null) {
            return;
        }
        final Field macField = findField(fragCls, "mMac");
        final Field bindingField = findField(fragCls, "mHostBinding");
        final Field vmField = findField(fragCls, "viewModel");
        final Method getContextMethod = findMethod(fragCls, "getContext");

        XposedCompat.hookAllMethods(fragCls, "initAirpodsCardAppearance", new XposedCompat.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                if (!isPanelEnabled() || param.thisObject == null) {
                    return;
                }
                String mac = safeStringField(macField, param.thisObject);
                Context ctx = sAppContext;
                if (getContextMethod != null) {
                    try {
                        Object c = getContextMethod.invoke(param.thisObject);
                        if (c instanceof Context) ctx = (Context) c;
                    } catch (Throwable ignored) {
                    }
                }
                if (!isSonyMac(ctx, mac)) {
                    return;
                }
                try {
                    Object binding = bindingField != null ? bindingField.get(param.thisObject) : null;
                    if (binding == null) return;
                    Field cardField = findField(binding.getClass(), "c");
                    Object cardObj = cardField != null ? cardField.get(binding) : null;
                    if (!(cardObj instanceof View)) return;
                    View cardView = (View) cardObj;

                    SonyCtx sc = buildSonyCtx(ctx, null, mac, null);
                    String vmName = "";
                    try {
                        Object vmObj = vmField != null ? vmField.get(param.thisObject) : null;
                        if (vmObj != null) {
                            Method getNameM = findMethod(vmObj.getClass(), "o");
                            Object res = getNameM != null ? getNameM.invoke(vmObj) : null;
                            if (res instanceof String) vmName = ((String) res).trim();
                        }
                    } catch (Throwable ignored) {
                    }
                    if (vmName.isEmpty() && !sc.model.isEmpty()) {
                        Method setName = findMethod(cardView.getClass(), "setDeviceName", String.class);
                        if (setName != null) {
                            setName.invoke(cardView, sc.model);
                        }
                    }

                    if (!isArtworkEnabled()) return;
                    final Method setHero = findMethod(cardView.getClass(), "g", Drawable.class);
                    if (setHero == null) return;
                    final String customUri = sCfg != null ? sCfg.customArtworkUri : "";
                    final View targetCard = cardView;
                    final SonyCtx targetCtx = sc;
                    final Context appCtx = ctx;
                    Runnable refreshHero = () -> targetCard.post(() -> {
                        if (!targetCard.isAttachedToWindow()) return;
                        Bitmap fresh = SonyAssetProvider.loadBitmap(
                                appCtx, targetCtx.model, targetCtx.colorCode, customUri, 512);
                        if (fresh != null) {
                            try {
                                setHero.invoke(targetCard, new BitmapDrawable(targetCard.getResources(), fresh));
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    Bitmap bmp = SonyAssetProvider.loadBitmap(
                            ctx, sc.model, sc.colorCode, customUri, 512, refreshHero);
                    if (bmp != null) {
                        setHero.invoke(cardView, new BitmapDrawable(cardView.getResources(), bmp));
                    }
                } catch (Throwable t) {
                    XposedCompat.debug("initAirpodsCardAppearance hero inject failed: " + t.getMessage());
                }
            }
        });
    }

    private static void hookSettingActivity(ClassLoader cl) {
        Class<?> actCls = XposedCompat.findClassIfExists(
                "com.heytap.mydevices.plugin.airpods.ui.AirpodsSettingActivity", cl);
        if (actCls == null) {
            return;
        }
        final Field devField = findField(actCls, "mBluetoothDevice");
        final Method getImgPoster = findMethod(actCls, "getImgPoster");

        XposedCompat.hookAllMethods(actCls, "showAirpodsPosterFromDeviceModel", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                if (!isPanelEnabled() || !isArtworkEnabled() || !(param.thisObject instanceof Context)) {
                    return;
                }
                Context ctx = (Context) param.thisObject;
                BluetoothDevice dev = null;
                try {
                    if (devField != null) {
                        Object o = devField.get(param.thisObject);
                        if (o instanceof BluetoothDevice) dev = (BluetoothDevice) o;
                    }
                } catch (Throwable ignored) {
                }
                String modelArg = (param.args != null && param.args.length >= 1 && param.args[0] instanceof String)
                        ? (String) param.args[0]
                        : "";
                if (!isSonyDeviceOrState(ctx, dev) && !isLikelySonyModelName(modelArg)) {
                    return;
                }
                final SonyCtx sc = buildSonyCtx(ctx, dev, safeAddress(dev), modelArg);
                final String customUri = sCfg != null ? sCfg.customArtworkUri : "";
                if (getImgPoster == null) {
                    return;
                }
                final Object targetAct = param.thisObject;
                final Context appCtx = ctx;
                Runnable refreshPoster = () -> {
                    try {
                        Object ivAsync = getImgPoster.invoke(targetAct);
                        if (ivAsync instanceof ImageView && ((ImageView) ivAsync).isAttachedToWindow()) {
                            Bitmap fresh = SonyAssetProvider.loadBitmap(
                                    appCtx, sc.model, sc.colorCode, customUri, 640);
                            if (fresh != null) {
                                ((ImageView) ivAsync).setImageBitmap(fresh);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                };
                Bitmap bmp = SonyAssetProvider.loadBitmap(ctx, sc.model, sc.colorCode, customUri, 640, refreshPoster);
                if (bmp == null) {
                    return;
                }
                try {
                    Object ivObj = getImgPoster.invoke(param.thisObject);
                    if (ivObj instanceof ImageView) {
                        ((ImageView) ivObj).setImageBitmap(bmp);
                        param.setResult(null);
                    }
                } catch (Throwable t) {
                    XposedCompat.debug("AirpodsSettingActivity poster inject failed: " + t.getMessage());
                }
            }
        });
    }

    private static Object getSuccessResultLf(ClassLoader cl) {
        if (sSuccessResultLf != null) {
            return sSuccessResultLf;
        }
        try {
            Class<?> lfCls = Class.forName("aa.lF", false, cl);
            Field compField = lfCls.getField("c");
            Object comp = compField.get(null);
            if (comp != null) {
                Method cMethod = comp.getClass().getMethod("c");
                sSuccessResultLf = cMethod.invoke(comp);
                return sSuccessResultLf;
            }
        } catch (Throwable t) {
            XposedCompat.debug("aa.lF.c.c() resolve failed: " + t.getMessage());
        }
        return null;
    }

    private static String getBtAirpodsTypeName(ClassLoader cl) {
        if (sBtAirpodsTypeName != null) {
            return sBtAirpodsTypeName;
        }
        try {
            Class<?> enumCls = Class.forName("com.oplus.mydevices.domain.entities.device.DeviceType", false, cl);
            Object btAirpods = enumCls.getField("BT_AIRPODS").get(null);
            if (btAirpods != null) {
                Method getTypeName = enumCls.getMethod("getTypeName");
                Object res = getTypeName.invoke(btAirpods);
                if (res instanceof String) {
                    sBtAirpodsTypeName = (String) res;
                    return sBtAirpodsTypeName;
                }
            }
        } catch (Throwable t) {
            XposedCompat.debug("DeviceType.BT_AIRPODS resolve failed: " + t.getMessage());
        }
        return null;
    }

    private static synchronized void ensureOpenPopupReceiver(Context ctx) {
        if (sOpenPopupReceiverRegistered || ctx == null) {
            return;
        }
        sOpenPopupReceiverRegistered = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConfigStore.ACTION_SHOW_OPEN_POPUP);
        filter.addAction(ConfigStore.ACTION_STATE_CHANGED);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) {
                    return;
                }
                if (ConfigStore.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                    maybeRefreshCapsuleWhenSecondEarJoins(context);
                    return;
                }
                if (!ConfigStore.ACTION_SHOW_OPEN_POPUP.equals(intent.getAction())) {
                    return;
                }
                ConfigStore.HookConfig cfg = sCfg;
                if (cfg != null) {
                    cfg.reload(false);
                    if (!cfg.openCasePopup) {
                        return;
                    }
                }
                BluetoothDevice dev = intent.getParcelableExtra(ConfigStore.EXTRA_DEVICE);
                String model = intent.getStringExtra(ConfigStore.EXTRA_MODEL);
                String rawMac = intent.getStringExtra(ConfigStore.EXTRA_MAC);
                boolean hasExplicitConn = intent.hasExtra(ConfigStore.EXTRA_IS_CONNECTED);
                boolean explicitConn = intent.getBooleanExtra(ConfigStore.EXTRA_IS_CONNECTED, false);
                if (dev == null) {
                    if (rawMac != null && !rawMac.trim().isEmpty()) {
                        String normMac = rawMac.trim().toUpperCase(Locale.US);
                        try {
                            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                            if (adapter != null && BluetoothAdapter.checkBluetoothAddress(normMac)) {
                                dev = adapter.getRemoteDevice(normMac);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    if (dev == null && hasExplicitConn && !explicitConn) {
                        try {
                            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                            if (adapter != null) {
                                dev = adapter.getRemoteDevice("02:00:00:00:00:01");
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    if (dev == null) {
                        dev = resolveFallbackSonyDevice(context);
                    }
                }
                if (dev == null) {
                    XposedCompat.log("Open-case popup ignored: no Sony BluetoothDevice resolved");
                    return;
                }
                SonyCtx sc = buildSonyCtx(context, dev, safeAddress(dev), model);
                String finalModel = sanitizeModel(
                        (model != null && !model.trim().isEmpty()) ? model : sc.model,
                        sc.model);
                String devMac = safeAddress(dev);
                int defaultBond = BluetoothDevice.BOND_NONE;
                try {
                    defaultBond = dev.getBondState();
                } catch (Throwable ignored) {
                }
                int bondState = intent.getIntExtra(ConfigStore.EXTRA_BOND_STATE, defaultBond);
                ConfigStore.HeadsetState st = ConfigStore.readGlobalState(context);
                boolean defaultConn = st.connected && !devMac.isEmpty()
                        && devMac.equalsIgnoreCase(st.mac);
                boolean isConnected = hasExplicitConn ? explicitConn : defaultConn;

                sLastLaunchedPopupMac = devMac;
                sLastLaunchedPopupModel = finalModel;
                sLastLaunchedPopupMs = SystemClock.elapsedRealtime();

                Intent launch = new Intent();
                launch.setClassName("com.heytap.mydevices",
                        "com.heytap.mydevices.plugin.airpods.ui.AirpodsDiscoveryActivity");
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                launch.putExtra("bluetooth_device", dev);
                launch.putExtra("device_model", finalModel);
                launch.putExtra("sonypods_model", finalModel);
                launch.putExtra("sonypods_bond_state", bondState);
                launch.putExtra("sonypods_is_connected", isConnected);
                try {
                    context.startActivity(launch);
                    XposedCompat.log("Launched ColorOS open-case popup for " + finalModel
                            + " [" + devMac + "] bonded=" + (bondState == BluetoothDevice.BOND_BONDED)
                            + " connected=" + isConnected);
                } catch (Throwable t) {
                    XposedCompat.log("Failed to launch AirpodsDiscoveryActivity: " + t.getMessage());
                }
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(receiver, filter);
            }
        } catch (Throwable t) {
            sOpenPopupReceiverRegistered = false;
            XposedCompat.log("ensureOpenPopupReceiver failed: " + t.getMessage());
        }
    }

    private static BluetoothDevice resolveFallbackSonyDevice(Context ctx) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                return null;
            }
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    if (d != null && SonyDeviceDetector.isSonyDevice(d)) {
                        return d;
                    }
                }
            }
            if (ctx != null) {
                ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
                if (st.mac != null && BluetoothAdapter.checkBluetoothAddress(st.mac)) {
                    return adapter.getRemoteDevice(st.mac);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void hookOpenCasePopup(ClassLoader cl) {
        Class<?> vmCls = XposedCompat.findClassIfExists(
                "com.heytap.mydevices.plugin.airpods.ui.viewmodel.AirpodsDiscoveryViewModel", cl);
        if (vmCls != null) {
            XposedCompat.hookAllMethods(vmCls, "getDeviceName", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    if (!isPanelEnabled()) return;
                    BluetoothDevice dev = getPopupBluetoothDevice(param.thisObject);
                    String devMac = safeAddress(dev);
                    String recentModel = lookupRecentLaunchedModel(devMac);
                    if (!recentModel.isEmpty()) {
                        param.setResult(recentModel);
                        return;
                    }
                    Context ctx = (param.args != null && param.args.length > 0 && param.args[0] instanceof Context)
                            ? (Context) param.args[0] : sAppContext;
                    if (dev != null && isSonyDeviceOrState(ctx, dev)) {
                        SonyCtx sc = buildSonyCtx(ctx, dev, devMac, null);
                        String modelName = sanitizeModel(sc.model, "");
                        if (!modelName.isEmpty()) {
                            param.setResult(modelName);
                        }
                    }
                }
            });
        }

        Class<?> contentCls = XposedCompat.findClassIfExists(
                "com.heytap.mydevices.plugin.airpods.ui.AirPodsDialogFragment$AirPodsDialogContentFragment", cl);
        if (contentCls != null) {
            XposedCompat.XC_MethodHook artworkHook = new XposedCompat.XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                    if (!isPanelEnabled() || !isArtworkEnabled()) {
                        return;
                    }
                    SonyCtx sony = resolvePopupSonyCtx(param.thisObject);
                    if (sony == null) {
                        return;
                    }
                    if (applySonyPopupArtwork(param.thisObject, sony)) {
                        param.setResult(null);
                    }
                }
            };
            XposedCompat.hookAllMethods(contentCls, "m0", artworkHook);
            XposedCompat.hookAllMethods(contentCls, "o0", artworkHook);
            XposedCompat.hookAllMethods(contentCls, "p0", artworkHook);
            XposedCompat.hookAllMethods(contentCls, "a", artworkHook);

            XposedCompat.hookAllMethods(contentCls, "t0", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    if (!isPanelEnabled()) return;
                    SonyCtx sony = resolvePopupSonyCtx(param.thisObject);
                    if (sony == null) return;
                    Intent actIntent = resolvePopupActivityIntent(param.thisObject);
                    if (actIntent != null && actIntent.hasExtra("sonypods_is_connected")
                            && !actIntent.getBooleanExtra("sonypods_is_connected", true)) {
                        Class<?> c = param.thisObject.getClass();
                        setFieldViewVisibility(c, param.thisObject, "j", View.GONE);
                        setFieldViewVisibility(c, param.thisObject, "k", View.GONE);
                        setFieldViewVisibility(c, param.thisObject, "l", View.GONE);
                        setFieldViewVisibility(c, param.thisObject, "i", View.GONE);
                        return;
                    }
                    if (SonyDeviceDetector.isOverEarModel(sony.model)) {
                        return;
                    }
                    int[] bat = (param.args != null && param.args.length > 0 && param.args[0] instanceof int[])
                            ? (int[]) param.args[0] : null;
                    applyTwsBatteryLabelsAndSpacing(param.thisObject, bat);
                }
            });

            XposedCompat.hookAllMethods(contentCls, "Q", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    if (!isPanelEnabled()) return;
                    SonyCtx sony = resolvePopupSonyCtx(param.thisObject);
                    if (sony == null) return;
                    configurePopupState(param.thisObject, sony);
                }
            });

            XposedCompat.hookAllMethods(contentCls, "onDestroyView", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    unregisterPopupReceiver(param.thisObject);
                }
            });
        }
    }

    private static void configurePopupState(Object fragment, SonyCtx sony) {
        Context ctx = resolveFragmentContext(fragment);
        if (ctx == null) return;

        BluetoothDevice dev = resolvePopupBluetoothDevice(fragment);
        String mac = safeAddress(dev);
        String recentModel = lookupRecentLaunchedModel(mac);
        String safeModel = sanitizeModel(sony.model, !recentModel.isEmpty() ? recentModel : "Sony");
        final SonyCtx finalSony = safeModel.equals(sony.model)
                ? sony : new SonyCtx(sony.mac, safeModel, sony.colorCode);

        ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
        boolean stateConnected = st.connected && !mac.isEmpty() && mac.equalsIgnoreCase(st.mac);
        boolean connected = stateConnected;
        boolean bonded = false;
        if (dev != null) {
            try {
                bonded = dev.getBondState() == BluetoothDevice.BOND_BONDED;
            } catch (Throwable ignored) {
            }
        }
        Intent actIntent = resolvePopupActivityIntent(fragment);
        if (actIntent != null) {
            if (actIntent.hasExtra("sonypods_is_connected")) {
                connected = actIntent.getBooleanExtra("sonypods_is_connected", stateConnected);
            }
            if (actIntent.hasExtra("sonypods_bond_state")) {
                bonded = actIntent.getIntExtra("sonypods_bond_state", BluetoothDevice.BOND_NONE)
                        == BluetoothDevice.BOND_BONDED;
            }
        }
        if (connected) {
            final String connTitle = ColorOsNativeExtraPrefsHelper.locText(
                    ctx,
                    "已連線 " + safeModel,
                    "已连接 " + safeModel,
                    "Connected to " + safeModel);
            applySonyPopupArtwork(fragment, finalSony);
            setPopupTitleText(fragment, connTitle);
            applyInstantPopupBattery(fragment, finalSony);
            registerPopupBatteryReceiver(fragment, ctx, finalSony);
            MAIN_HANDLER.post(() -> {
                if (!isFragmentAlive(fragment)) return;
                applySonyPopupArtwork(fragment, finalSony);
                setPopupTitleText(fragment, connTitle);
                applyInstantPopupBattery(fragment, finalSony);
            });
            return;
        }

        // Unpaired or disconnected: NEVER show "已連線 ..." or fake battery bars; show actionable "連線" button
        try {
            Class<?> cls = fragment.getClass();
            setFieldViewVisibility(cls, fragment, "j", View.GONE);
            setFieldViewVisibility(cls, fragment, "k", View.GONE);
            setFieldViewVisibility(cls, fragment, "l", View.GONE);
            setFieldViewVisibility(cls, fragment, "i", View.GONE);

            schedulePopupDismiss(fragment, 20_000L);
            applySonyPopupArtwork(fragment, finalSony);
            setPopupTitleText(fragment, safeModel);
            MAIN_HANDLER.post(() -> {
                if (!isFragmentAlive(fragment) || POPUP_RECEIVERS.containsKey(fragment)) {
                    return;
                }
                applySonyPopupArtwork(fragment, finalSony);
                setPopupTitleText(fragment, safeModel);
            });

            View leftBtn = getFieldView(cls, fragment, "z");
            if (leftBtn instanceof TextView) {
                ((TextView) leftBtn).setText(ColorOsNativeExtraPrefsHelper.locText(
                        ctx, "關閉", "关闭", "Close"));
                leftBtn.setVisibility(View.VISIBLE);
                leftBtn.setEnabled(true);
                final BluetoothDevice targetDevForDismiss = dev;
                leftBtn.setOnClickListener(v -> {
                    sendDismissPairingCommand(ctx, targetDevForDismiss, finalSony);
                    dismissPopupFragment(fragment);
                });
            }

            View rightBtn = getFieldView(cls, fragment, "n");
            if (rightBtn instanceof TextView) {
                TextView connectBtn = (TextView) rightBtn;
                connectBtn.setText(ColorOsNativeExtraPrefsHelper.locText(
                        ctx, "連線", "连接", "Connect"));
                connectBtn.setVisibility(View.VISIBLE);
                connectBtn.setEnabled(true);
                final boolean isBonded = bonded;
                final BluetoothDevice targetDev = dev;
                connectBtn.setOnClickListener(v ->
                        startPopupPairOrConnect(fragment, targetDev, finalSony, isBonded));
            }
        } catch (Throwable t) {
            XposedCompat.debug("configurePopupState(disconnected) failed: " + t.getMessage());
        }
    }

    private static void startPopupPairOrConnect(
            Object fragment,
            BluetoothDevice dev,
            SonyCtx sony,
            boolean bonded
    ) {
        Context ctx = resolveFragmentContext(fragment);
        if (ctx == null) return;
        Class<?> cls = fragment.getClass();

        schedulePopupDismiss(fragment, 20_000L);
        setPopupTitleText(fragment, ColorOsNativeExtraPrefsHelper.locText(
                ctx,
                (bonded ? "正在連線 " : "正在配對 ") + sony.model,
                (bonded ? "正在连接 " : "正在配对 ") + sony.model,
                (bonded ? "Connecting " : "Pairing ") + sony.model));

        View rightBtn = getFieldView(cls, fragment, "n");
        if (rightBtn instanceof TextView) {
            ((TextView) rightBtn).setText(ColorOsNativeExtraPrefsHelper.locText(
                    ctx,
                    bonded ? "連線中…" : "配對中…",
                    bonded ? "连接中…" : "配对中…",
                    bonded ? "Connecting…" : "Pairing…"));
            rightBtn.setEnabled(false);
        }

        final String targetMac = safeAddress(dev);
        registerPopupConnectReceiver(fragment, ctx, targetMac, sony);

        Intent cmd = new Intent(ConfigStore.ACTION_COMMAND);
        cmd.setPackage("com.android.bluetooth");
        cmd.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        cmd.putExtra(ConfigStore.EXTRA_CMD, ConfigStore.CMD_PAIR_AND_CONNECT);
        if (!targetMac.isEmpty()) {
            cmd.putExtra(ConfigStore.EXTRA_MAC, targetMac);
        }
        cmd.putExtra(ConfigStore.EXTRA_MODEL, sony.model);
        try {
            ctx.sendBroadcast(cmd);
        } catch (Throwable t) {
            XposedCompat.log("Send CMD_PAIR_AND_CONNECT failed: " + t.getMessage());
        }
    }

    private static void registerPopupConnectReceiver(
            Object fragment,
            Context ctx,
            String targetMac,
            SonyCtx sony
    ) {
        unregisterPopupReceiver(fragment);
        Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConfigStore.ACTION_PAIR_STATUS);
        filter.addAction(ConfigStore.ACTION_STATE_CHANGED);
        BroadcastReceiver rec = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !isFragmentAlive(fragment)) {
                    unregisterPopupReceiver(fragment);
                    return;
                }
                String action = intent.getAction();
                if (ConfigStore.ACTION_STATE_CHANGED.equals(action)) {
                    String raw = intent.getStringExtra(ConfigStore.EXTRA_STATE_SERIALIZED);
                    ConfigStore.HeadsetState st = (raw != null && !raw.isEmpty())
                            ? ConfigStore.HeadsetState.deserialize(raw)
                            : ConfigStore.readGlobalState(context);
                    if (st.connected && matchesTargetHeadset(st.mac, st.effectiveModelName(), targetMac, sony.model)) {
                        transitionPopupToConnected(fragment, sony, null);
                    }
                    return;
                }
                if (ConfigStore.ACTION_PAIR_STATUS.equals(action)) {
                    String state = intent.getStringExtra(ConfigStore.EXTRA_PAIR_STATE);
                    String evMac = intent.getStringExtra(ConfigStore.EXTRA_MAC);
                    String evModel = intent.getStringExtra(ConfigStore.EXTRA_MODEL);
                    if (!matchesTargetHeadset(evMac, evModel, targetMac, sony.model)) {
                        return;
                    }
                    if ("connecting".equals(state)) {
                        schedulePopupDismiss(fragment, 15_000L);
                        setPopupTitleText(fragment, ColorOsNativeExtraPrefsHelper.locText(
                                context,
                                "正在連線 " + sony.model,
                                "正在连接 " + sony.model,
                                "Connecting " + sony.model));
                        View rb = getFieldView(fragment.getClass(), fragment, "n");
                        if (rb instanceof TextView) {
                            ((TextView) rb).setText(ColorOsNativeExtraPrefsHelper.locText(
                                    context, "連線中…", "连接中…", "Connecting…"));
                        }
                    } else if ("connected".equals(state)) {
                        BluetoothDevice evDev = intent.getParcelableExtra(ConfigStore.EXTRA_DEVICE);
                        transitionPopupToConnected(fragment, sony, evDev);
                    } else if ("failed".equals(state)) {
                        unregisterPopupReceiver(fragment);
                        schedulePopupDismiss(fragment, 15_000L);
                        setPopupTitleText(fragment, ColorOsNativeExtraPrefsHelper.locText(
                                context,
                                "連線失敗，請長按耳機進入配對模式",
                                "连接失败，请长按耳机进入配对模式",
                                "Connection failed. Hold buds to pair."));
                        View rb = getFieldView(fragment.getClass(), fragment, "n");
                        if (rb instanceof TextView) {
                            ((TextView) rb).setText(ColorOsNativeExtraPrefsHelper.locText(
                                    context, "重試", "重试", "Retry"));
                            rb.setEnabled(true);
                        }
                    }
                }
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                appCtx.registerReceiver(rec, filter, Context.RECEIVER_EXPORTED);
            } else {
                appCtx.registerReceiver(rec, filter);
            }
            POPUP_RECEIVERS.put(fragment, rec);
        } catch (Throwable t) {
            XposedCompat.debug("registerPopupConnectReceiver failed: " + t.getMessage());
        }
    }

    private static void registerPopupBatteryReceiver(
            Object fragment,
            Context ctx,
            SonyCtx sony
    ) {
        if (fragment == null || ctx == null) return;
        unregisterPopupReceiver(fragment);
        Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        IntentFilter filter = new IntentFilter(ConfigStore.ACTION_STATE_CHANGED);
        BroadcastReceiver rec = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!isFragmentAlive(fragment)) {
                    unregisterPopupReceiver(fragment);
                    return;
                }
                applyInstantPopupBattery(fragment, sony);
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                appCtx.registerReceiver(rec, filter, Context.RECEIVER_EXPORTED);
            } else {
                appCtx.registerReceiver(rec, filter);
            }
            POPUP_RECEIVERS.put(fragment, rec);
        } catch (Throwable t) {
            XposedCompat.debug("registerPopupBatteryReceiver failed: " + t.getMessage());
        }
    }

    private static void unregisterPopupReceiver(Object fragment) {
        if (fragment == null) return;
        BroadcastReceiver rec = POPUP_RECEIVERS.remove(fragment);
        if (rec == null) return;
        Context ctx = resolveFragmentContext(fragment);
        Context appCtx = (ctx != null && ctx.getApplicationContext() != null)
                ? ctx.getApplicationContext() : sAppContext;
        if (appCtx != null) {
            try {
                appCtx.unregisterReceiver(rec);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void transitionPopupToConnected(
            Object fragment,
            SonyCtx sony,
            BluetoothDevice newDev
    ) {
        unregisterPopupReceiver(fragment);
        Context ctx = resolveFragmentContext(fragment);
        if (ctx == null || !isFragmentAlive(fragment)) return;

        try {
            if (newDev != null) {
                Method getVm = findMethod(fragment.getClass(), "getViewModel");
                Object vm = getVm != null ? getVm.invoke(fragment) : null;
                if (vm != null) {
                    Method setBackLink = findMethod(vm.getClass(), "setBluetoothDeviceForBackLink",
                            BluetoothDevice.class, String.class);
                    if (setBackLink != null) {
                        setBackLink.invoke(vm, newDev, sony.model);
                    }
                }
            }

            setPopupTitleText(fragment, ColorOsNativeExtraPrefsHelper.locText(
                    ctx,
                    "已連線 " + sony.model,
                    "已连接 " + sony.model,
                    "Connected to " + sony.model));

            View leftBtn = getFieldView(fragment.getClass(), fragment, "z");
            if (leftBtn instanceof TextView) {
                ((TextView) leftBtn).setText(ColorOsNativeExtraPrefsHelper.locText(
                        ctx, "設定", "设置", "Settings"));
                leftBtn.setEnabled(true);
                leftBtn.setOnClickListener(v -> invokePopupSettingsAction(fragment, ctx));
            }

            View rightBtn = getFieldView(fragment.getClass(), fragment, "n");
            if (rightBtn instanceof TextView) {
                ((TextView) rightBtn).setText(ColorOsNativeExtraPrefsHelper.locText(
                        ctx, "完成", "完成", "Done"));
                rightBtn.setEnabled(true);
                rightBtn.setOnClickListener(v -> dismissPopupFragment(fragment));
            }

            applyInstantPopupBattery(fragment, sony);
            schedulePopupDismiss(fragment, 5_000L);
        } catch (Throwable t) {
            XposedCompat.debug("transitionPopupToConnected failed: " + t.getMessage());
        }
    }

    private static void applyTwsBatteryLabelsAndSpacing(Object fragment, int[] bat) {
        if (fragment == null) return;
        Context ctx = resolveFragmentContext(fragment);
        if (ctx == null) return;
        Class<?> cls = fragment.getClass();

        // Spread the 3 vertical Guidelines evenly across 20% / 50% / 80%
        View leftContainer = getFieldView(cls, fragment, "j");
        if (leftContainer != null && leftContainer.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) leftContainer.getParent();
            float[] percents = new float[]{0.20f, 0.50f, 0.80f};
            int gIdx = 0;
            for (int i = 0; i < parent.getChildCount() && gIdx < percents.length; i++) {
                View child = parent.getChildAt(i);
                if (child != null && child.getClass().getName().endsWith("Guideline")) {
                    try {
                        Method setPct = child.getClass().getMethod("setGuidelinePercent", float.class);
                        setPct.invoke(child, percents[gIdx]);
                    } catch (Throwable ignored) {
                    }
                    gIdx++;
                }
            }
        }

        if (bat == null || bat.length < 3) return;
        String[] fieldNames = new String[]{"p", "t", "w"};
        String[] labels = new String[]{
                ColorOsNativeExtraPrefsHelper.locText(ctx, "左耳 ", "左耳 ", "L "),
                ColorOsNativeExtraPrefsHelper.locText(ctx, "右耳 ", "右耳 ", "R "),
                ColorOsNativeExtraPrefsHelper.locText(ctx, "充電盒 ", "充电盒 ", "Case ")
        };
        for (int i = 0; i < 3; i++) {
            int val = bat[i];
            if (val < 0 || val > 100) continue;
            View tv = getFieldView(cls, fragment, fieldNames[i]);
            if (tv instanceof TextView) {
                TextView textView = (TextView) tv;
                textView.setSingleLine(true);
                textView.setText(labels[i] + val + "%");
            }
        }
    }

    private static boolean matchesTargetHeadset(
            String candidateMac,
            String candidateModel,
            String targetMac,
            String targetModel
    ) {
        if (targetMac != null && !targetMac.isEmpty() && candidateMac != null
                && !candidateMac.isEmpty() && targetMac.equalsIgnoreCase(candidateMac)) {
            return true;
        }
        if (targetModel != null && !targetModel.isEmpty() && !"Sony".equalsIgnoreCase(targetModel)
                && candidateModel != null && !candidateModel.isEmpty()) {
            String tNorm = SonyDeviceDetector.normalizeModelName(targetModel);
            String cNorm = SonyDeviceDetector.normalizeModelName(candidateModel);
            return tNorm.equalsIgnoreCase(cNorm);
        }
        return false;
    }

    private static BluetoothDevice resolvePopupBluetoothDevice(Object fragment) {
        try {
            Method getVm = findMethod(fragment.getClass(), "getViewModel");
            Object vm = getVm != null ? getVm.invoke(fragment) : null;
            return getPopupBluetoothDevice(vm);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isBluetoothDeviceConnected(BluetoothDevice dev) {
        if (dev == null) return false;
        try {
            Method m = dev.getClass().getMethod("isConnected");
            return Boolean.TRUE.equals(m.invoke(dev));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isFragmentAlive(Object fragment) {
        if (fragment == null) return false;
        try {
            Method isAdded = findMethod(fragment.getClass(), "isAdded");
            Method getAct = findMethod(fragment.getClass(), "getActivity");
            return (isAdded == null || Boolean.TRUE.equals(isAdded.invoke(fragment)))
                    && (getAct == null || getAct.invoke(fragment) != null);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void setPopupTitleText(Object fragment, CharSequence title) {
        View tv = getFieldView(fragment.getClass(), fragment, "m");
        if (tv instanceof TextView) {
            ((TextView) tv).setText(title);
        }
    }

    private static void schedulePopupDismiss(Object fragment, long delayMs) {
        try {
            Method j0 = findMethod(fragment.getClass(), "j0", long.class);
            if (j0 != null) j0.invoke(fragment, delayMs);
        } catch (Throwable ignored) {
        }
    }

    private static void invokePopupSettingsAction(Object fragment, Context ctx) {
        try {
            Method r0 = findMethod(fragment.getClass(), "r0");
            if (r0 != null) {
                r0.invoke(fragment);
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            Intent i = new Intent("com.oplus.mydevices.AIRPODS_SETTING");
            i.putExtra("device", resolvePopupBluetoothDevice(fragment));
            ctx.startActivity(i);
        } catch (Throwable ignored) {
        }
        dismissPopupFragment(fragment);
    }

    private static void sendDismissPairingCommand(Context ctx, BluetoothDevice dev, SonyCtx sony) {
        if (ctx == null) return;
        try {
            Intent cmd = new Intent(ConfigStore.ACTION_COMMAND);
            cmd.setPackage("com.android.bluetooth");
            cmd.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            cmd.putExtra(ConfigStore.EXTRA_CMD, ConfigStore.CMD_DISMISS_PAIRING);
            String mac = safeAddress(dev);
            if (!mac.isEmpty()) {
                cmd.putExtra(ConfigStore.EXTRA_MAC, mac);
            }
            if (sony != null && sony.model != null && !sony.model.isEmpty()) {
                cmd.putExtra(ConfigStore.EXTRA_MODEL, sony.model);
            }
            ctx.sendBroadcast(cmd);
        } catch (Throwable t) {
            XposedCompat.debug("Send CMD_DISMISS_PAIRING failed: " + t.getMessage());
        }
    }

    private static void dismissPopupFragment(Object fragment) {
        try {
            Method getAct = findMethod(fragment.getClass(), "getActivity");
            Object act = getAct != null ? getAct.invoke(fragment) : null;
            if (act != null) {
                try {
                    act.getClass().getMethod("dismissDialog").invoke(act);
                    return;
                } catch (Throwable ignored) {
                }
                if (act instanceof android.app.Activity) {
                    ((android.app.Activity) act).finish();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static View getFieldView(Class<?> cls, Object target, String fieldName) {
        try {
            Field f = findField(cls, fieldName);
            Object v = f != null ? f.get(target) : null;
            return v instanceof View ? (View) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isValidBatteryArray(int[] bat) {
        return bat != null && bat.length >= 6 && (bat[0] > 0 || bat[1] > 0 || bat[2] > 0);
    }

    private static void maybeRefreshCapsuleWhenSecondEarJoins(Context ctx) {
        if (ctx == null || sLastCapsuleBothEars || sLastCapsuleHelper == null) {
            return;
        }
        if ((SystemClock.elapsedRealtime() - sLastCapsuleMs) > 12_000L || !isFluidCloudEnabled()) {
            return;
        }
        ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
        if (!st.connected || st.leftBattery <= 0 || st.rightBattery <= 0) {
            return;
        }
        String model = !sLastCapsuleModel.isEmpty() ? sLastCapsuleModel : st.effectiveModelName();
        if (SonyDeviceDetector.isOverEarModel(model)) {
            return;
        }
        sLastCapsuleBothEars = true;
        String mac = !sLastCapsuleMac.isEmpty() ? sLastCapsuleMac : st.mac;
        String name = !sLastCapsuleName.isEmpty() ? sLastCapsuleName : model;
        int[] fullBat = buildBatteryArrayFromState(st, model);
        Object helper = sLastCapsuleHelper;
        try {
            Field debounceField = findField(helper.getClass(), "b");
            if (debounceField != null && debounceField.getType() == long.class) {
                debounceField.setLong(helper, 0L);
            }
            Method buildJson = findMethod(helper.getClass(), "b",
                    String.class, String.class, String.class, int[].class, boolean.class);
            if (buildJson != null) {
                Object jsonObj = buildJson.invoke(helper, mac, model, name, fullBat, false);
                if (jsonObj instanceof String && !((String) jsonObj).isEmpty()) {
                    try {
                        Class<?> actionCls = Class.forName(
                                "com.oplus.mydevices.seedling.CoreSeedlingAction",
                                false, helper.getClass().getClassLoader());
                        Object inst = actionCls.getField("INSTANCE").get(null);
                        Method updateM = findMethod(actionCls, "updateCapsule", String.class);
                        if (inst != null && updateM != null) {
                            updateM.invoke(inst, (String) jsonObj);
                            XposedCompat.log("Updated live Fluid Cloud capsule to dual-earbud battery for " + model);
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            Method showM = findMethod(helper.getClass(), "f",
                    String.class, String.class, String.class, int[].class, boolean.class, boolean.class);
            if (showM != null) {
                showM.invoke(helper, mac, model, name, fullBat, false, false);
                XposedCompat.log("Re-dispatched Fluid Cloud capsule with dual-earbud battery for " + model);
            }
        } catch (Throwable t) {
            XposedCompat.debug("maybeRefreshCapsuleWhenSecondEarJoins failed: " + t.getMessage());
        }
    }

    private static int[] buildBatteryArrayFromState(ConfigStore.HeadsetState st, String model) {
        if (SonyDeviceDetector.isOverEarModel(model) && !st.hasMultiBattery()) {
            int single = st.singleBattery > 0 ? st.singleBattery : Math.max(st.leftBattery, st.rightBattery);
            boolean chg = st.singleCharging || st.leftCharging || st.rightCharging;
            return new int[]{
                    single,
                    -1,
                    -1,
                    single > 0 ? (chg ? 1 : 2) : 4,
                    4,
                    4
            };
        }
        int l = st.leftBattery > 0 ? st.leftBattery : (st.singleBattery > 0 ? st.singleBattery : -1);
        int r = st.rightBattery > 0 ? st.rightBattery : -1;
        int box = st.caseBattery > 0 ? st.caseBattery : -1;
        boolean lChg = st.leftBattery > 0 ? st.leftCharging : st.singleCharging;
        return new int[]{
                l,
                r,
                box,
                l > 0 ? (lChg ? 1 : 2) : 4,
                r > 0 ? (st.rightCharging ? 1 : 2) : 4,
                box > 0 ? (st.caseCharging ? 1 : 2) : 4
        };
    }

    private static void applyInstantPopupBattery(Object fragment, SonyCtx sony) {
        if (fragment == null) return;
        Context ctx = resolveFragmentContext(fragment);
        if (ctx == null) return;
        ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
        if (!st.connected) return;
        if (st.leftBattery < 0 && st.rightBattery < 0 && st.singleBattery < 0) return;

        int[] bat = buildBatteryArrayFromState(st, sony.model);
        try {
            Method t0 = findMethod(fragment.getClass(), "t0", int[].class);
            if (t0 != null) {
                t0.invoke(fragment, (Object) bat);
            }
        } catch (Throwable t) {
            XposedCompat.debug("applyInstantPopupBattery failed: " + t.getMessage());
        }
    }

    private static Context resolveFragmentContext(Object fragment) {
        try {
            Method getCtx = findMethod(fragment.getClass(), "getContext");
            Object c = getCtx != null ? getCtx.invoke(fragment) : null;
            if (c instanceof Context) return (Context) c;
        } catch (Throwable ignored) {
        }
        return sAppContext;
    }

    private static BluetoothDevice getPopupBluetoothDevice(Object vm) {
        if (vm == null) return null;
        try {
            Method m = findMethod(vm.getClass(), "getBluetoothDevice");
            if (m != null) {
                Object res = m.invoke(vm);
                return res instanceof BluetoothDevice ? (BluetoothDevice) res : null;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Intent resolvePopupActivityIntent(Object fragment) {
        if (fragment == null) return null;
        try {
            Method getAct = findMethod(fragment.getClass(), "getActivity");
            Object act = getAct != null ? getAct.invoke(fragment) : null;
            if (act instanceof Activity) {
                return ((Activity) act).getIntent();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static SonyCtx resolvePopupSonyCtx(Object fragment) {
        if (fragment == null) return null;
        try {
            Method getVm = findMethod(fragment.getClass(), "getViewModel");
            Object vm = getVm != null ? getVm.invoke(fragment) : null;
            if (vm == null) return null;

            Context ctx = sAppContext;
            try {
                Method getCtx = findMethod(fragment.getClass(), "getContext");
                Object c = getCtx != null ? getCtx.invoke(fragment) : null;
                if (c instanceof Context) ctx = (Context) c;
            } catch (Throwable ignored) {
            }

            Intent actIntent = resolvePopupActivityIntent(fragment);
            String intentModel = actIntent != null ? actIntent.getStringExtra("sonypods_model") : null;
            if ((intentModel == null || intentModel.trim().isEmpty()) && actIntent != null) {
                intentModel = actIntent.getStringExtra("device_model");
            }

            BluetoothDevice dev = getPopupBluetoothDevice(vm);
            String mac = safeAddress(dev);
            String cleanIntent = sanitizeModel(intentModel, "");
            if (cleanIntent.isEmpty()) {
                cleanIntent = lookupRecentLaunchedModel(mac);
            }
            if (!cleanIntent.isEmpty() && isLikelySonyModelName(cleanIntent)) {
                String cleanModel = sanitizeModel(SonyDeviceDetector.normalizeModelName(cleanIntent), "Sony");
                boolean allowColor = true;
                if (actIntent != null && actIntent.hasExtra("sonypods_is_connected")
                        && !actIntent.getBooleanExtra("sonypods_is_connected", false)) {
                    allowColor = false;
                }
                int color = -1;
                if (allowColor && ctx != null) {
                    ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
                    if (st.connected && !mac.isEmpty() && mac.equalsIgnoreCase(st.mac)) {
                        color = st.colorCode;
                    }
                }
                return new SonyCtx(mac, cleanModel, color);
            }

            String devType = "";
            try {
                Method getType = findMethod(vm.getClass(), "getDeviceType");
                Object t = getType != null ? getType.invoke(vm) : null;
                if (t instanceof String) devType = (String) t;
            } catch (Throwable ignored) {
            }

            if ((dev != null && isSonyDeviceOrState(ctx, dev)) || isLikelySonyModelName(devType)) {
                return buildSonyCtx(ctx, dev, mac, devType);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String lookupRecentLaunchedModel(String mac) {
        if (mac == null || mac.isEmpty() || sLastLaunchedPopupMs == 0L) {
            return "";
        }
        if ((SystemClock.elapsedRealtime() - sLastLaunchedPopupMs) > POPUP_LAUNCH_CACHE_TTL_MS) {
            return "";
        }
        if (mac.equalsIgnoreCase(sLastLaunchedPopupMac)) {
            return sanitizeModel(sLastLaunchedPopupModel, "");
        }
        return "";
    }

    private static String sanitizeModel(String raw, String fallback) {
        if (raw != null) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty() && !"null".equalsIgnoreCase(trimmed)) {
                return trimmed;
            }
        }
        return fallback != null ? fallback : "";
    }

    private static boolean applySonyPopupArtwork(Object fragment, SonyCtx sony) {
        Context ctx = resolveFragmentContext(fragment);
        if (ctx == null) return false;

        final String customUri = sCfg != null ? sCfg.customArtworkUri : "";
        Runnable onCached = () -> {
            try {
                Method isAdded = findMethod(fragment.getClass(), "isAdded");
                if (isAdded != null && !Boolean.TRUE.equals(isAdded.invoke(fragment))) {
                    return;
                }
                applySonyPopupArtwork(fragment, sony);
            } catch (Throwable ignored) {
            }
        };
        Bitmap bmp = SonyAssetProvider.loadBitmap(ctx, sony.model, sony.colorCode, customUri, 600, onCached);
        if (bmp == null) return false;

        try {
            Class<?> cls = fragment.getClass();
            setFieldViewVisibility(cls, fragment, "A", View.INVISIBLE);
            setFieldViewVisibility(cls, fragment, "e", View.GONE);
            setFieldViewVisibility(cls, fragment, "f", View.GONE);
            setFieldViewVisibility(cls, fragment, "g", View.GONE);

            boolean overEar = SonyDeviceDetector.isOverEarModel(sony.model);
            String targetField = overEar ? "B" : "h";
            String otherField = overEar ? "h" : "B";
            setFieldViewVisibility(cls, fragment, otherField, View.GONE);

            Field ivField = findField(cls, targetField);
            Object ivObj = ivField != null ? ivField.get(fragment) : null;
            if (ivObj instanceof ImageView) {
                ImageView iv = (ImageView) ivObj;
                float density = ctx.getResources().getDisplayMetrics().density;
                ViewGroup.LayoutParams rawLp = iv.getLayoutParams();
                if (rawLp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) rawLp;
                    if (!overEar) {
                        lp.width = Math.round(210f * density);
                        lp.height = Math.round(155f * density);
                        lp.topMargin = Math.round(20f * density);
                    } else {
                        lp.width = Math.round(220f * density);
                        lp.height = Math.round(175f * density);
                    }
                    iv.setLayoutParams(lp);
                }
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setVisibility(View.VISIBLE);
                iv.setImageBitmap(bmp);
                iv.requestLayout();
                return true;
            }
        } catch (Throwable t) {
            XposedCompat.debug("applySonyPopupArtwork failed: " + t.getMessage());
        }
        return false;
    }

    private static void setFieldViewVisibility(Class<?> cls, Object target, String fieldName, int vis) {
        try {
            Field f = findField(cls, fieldName);
            Object v = f != null ? f.get(target) : null;
            if (v instanceof View) {
                ((View) v).setVisibility(vis);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isPanelEnabled() {
        ConfigStore.HookConfig cfg = sCfg;
        if (cfg == null) return true;
        cfg.reload(false);
        return cfg.myDevicesPanel;
    }

    private static boolean isArtworkEnabled() {
        ConfigStore.HookConfig cfg = sCfg;
        if (cfg == null) return true;
        cfg.reload(false);
        return cfg.nativeArtwork;
    }

    private static boolean isFluidCloudEnabled() {
        ConfigStore.HookConfig cfg = sCfg;
        if (cfg == null) return true;
        cfg.reload(false);
        return cfg.fluidCloudCapsule;
    }

    private static boolean isSonyIntent(Context ctx, Intent intent) {
        if (intent == null) return false;
        try {
            BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (isSonyDeviceOrState(ctx, dev)) return true;
        } catch (Throwable ignored) {
        }
        String[] keys = {
                "device_mac_info", "device_id",
                "device_address", "mac", "address", "bluetooth_address", "deviceAddress"
        };
        for (String k : keys) {
            try {
                String v = intent.getStringExtra(k);
                if (v != null && isSonyMac(ctx, v)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean isSonyDeviceOrState(Context ctx, BluetoothDevice dev) {
        if (dev == null) return false;
        if (SonyDeviceDetector.isSonyMacOrBonded(dev)) {
            return true;
        }
        String mac = safeAddress(dev);
        return !mac.isEmpty() && isSonyMac(ctx, mac);
    }

    private static boolean isSonyMac(Context ctx, String mac) {
        if (mac == null || mac.trim().isEmpty()) {
            return false;
        }
        String upper = mac.trim().toUpperCase(Locale.US);
        if (ctx != null) {
            ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
            if (st.connected && st.mac != null && upper.equals(st.mac.toUpperCase(Locale.US))) {
                return true;
            }
        }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && BluetoothAdapter.checkBluetoothAddress(upper)) {
                BluetoothDevice dev = adapter.getRemoteDevice(upper);
                if (SonyDeviceDetector.isSonyMacOrBonded(dev)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isLikelySonyModelName(String raw) {
        return SonyDeviceDetector.isSonyName(raw);
    }

    private static SonyCtx buildSonyCtx(Context ctx, BluetoothDevice dev, String mac, String fallbackModel) {
        int color = -1;
        String model = "";
        if (sCfg != null && sCfg.customModelOverride != null && !sCfg.customModelOverride.trim().isEmpty()) {
            model = sCfg.customModelOverride.trim();
        }
        if (ctx != null) {
            ConfigStore.HeadsetState st = ConfigStore.readGlobalState(ctx);
            if (st.connected && (mac == null || mac.isEmpty() || mac.equalsIgnoreCase(st.mac))) {
                color = st.colorCode;
                if (model.isEmpty()) {
                    model = st.effectiveModelName();
                }
            }
        }
        if (model.isEmpty() && dev != null) {
            try {
                String n = dev.getName();
                if (n != null && !"null".equalsIgnoreCase(n.trim()) && !n.trim().isEmpty()) {
                    model = SonyDeviceDetector.normalizeModelName(n);
                }
            } catch (Throwable ignored) {
            }
        }
        model = sanitizeModel(model, "");
        if (model.isEmpty() && fallbackModel != null && !fallbackModel.trim().isEmpty()) {
            model = sanitizeModel(SonyDeviceDetector.normalizeModelName(fallbackModel), "");
        }
        if (model.isEmpty()) {
            BluetoothDevice fallbackDev = resolveFallbackSonyDevice(ctx);
            if (fallbackDev != null) {
                try {
                    String n = fallbackDev.getName();
                    if (n != null && !n.trim().isEmpty() && !"null".equalsIgnoreCase(n.trim())) {
                        model = SonyDeviceDetector.normalizeModelName(n);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        model = sanitizeModel(model, "Sony");
        return new SonyCtx(mac != null ? mac : "", model, color);
    }

    private static String resolveSonyModel(Context ctx, BluetoothDevice dev, String mac) {
        return buildSonyCtx(ctx, dev, mac, null).model;
    }

    private static String buildWebpFileName(String model, int colorCode, boolean isCapsule) {
        String safe = (model == null ? "sony" : model.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "_"));
        if (safe.isEmpty()) safe = "sony";
        return "sony_" + safe + "_" + (colorCode >= 0 ? colorCode : "def") + (isCapsule ? "_cap" : "") + ".webp";
    }

    private static Context safeContext(Field ctxField, Object obj) {
        if (ctxField != null) {
            try {
                Object v = ctxField.get(obj);
                if (v instanceof Context) return (Context) v;
            } catch (Throwable ignored) {
            }
        }
        return sAppContext;
    }

    private static String safeStringField(Field f, Object obj) {
        if (f == null || obj == null) return "";
        try {
            Object v = f.get(obj);
            return v instanceof String ? (String) v : "";
        } catch (Throwable ignored) {
            return "";
        }
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
