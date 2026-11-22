package io.github.nicochanyo.sonypods;

import android.app.Activity;
import android.os.SystemClock;
import android.view.MotionEvent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Hooks ColorOS Quick Connect / Accessory framework (com.heytap.accessory)
 * so quick-connect popup cards for Sony headsets stay open while the user interacts.
 */
public final class ColorOsAccessoryHook {
    private static final long INTERACT_GRACE_MS = 3000L;
    private static final Map<Activity, Long> LAST_TOUCH_MS = new WeakHashMap<>();

    private ColorOsAccessoryHook() {
    }

    public static void install(ClassLoader cl, ConfigStore.HookConfig cfg) {
        Class<?> dialogCls = XposedCompat.findClassIfExists(
                "com.oplus.pantaconnect.appcore.devicediscovery.ui.DialogActivity", cl);
        if (dialogCls == null) {
            return;
        }

        XposedCompat.hookAllMethods(dialogCls, "dispatchTouchEvent", new XposedCompat.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                if (param.thisObject instanceof Activity && param.args != null && param.args.length > 0
                        && param.args[0] instanceof MotionEvent) {
                    synchronized (LAST_TOUCH_MS) {
                        LAST_TOUCH_MS.put((Activity) param.thisObject, SystemClock.uptimeMillis());
                    }
                }
            }
        });

        for (Method m : dialogCls.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())
                    && m.getReturnType() == void.class
                    && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == dialogCls
                    && ("g".equals(m.getName()) || "h".equals(m.getName()))) {
                XposedCompat.hookMethod(m, new XposedCompat.XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(XposedCompat.MethodHookParam param) {
                        if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof Activity)) {
                            return;
                        }
                        Activity act = (Activity) param.args[0];
                        ConfigStore.HeadsetState st = ConfigStore.readGlobalState(act);
                        if (!st.connected) {
                            return;
                        }
                        long lastTouch;
                        synchronized (LAST_TOUCH_MS) {
                            Long t = LAST_TOUCH_MS.get(act);
                            lastTouch = t != null ? t : 0L;
                        }
                        if (lastTouch > 0L && (SystemClock.uptimeMillis() - lastTouch) < INTERACT_GRACE_MS) {
                            param.setResult(null);
                            XposedCompat.debug("Suppressed Accessory DialogActivity auto-dismiss during touch");
                        }
                    }
                });
            }
        }

        XposedCompat.log("ColorOsAccessoryHook installed");
    }
}
