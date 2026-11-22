package io.github.nicochanyo.sonypods;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hooks Sony's official Sound Connect app (com.sony.songpal.mdr) to yield
 * the Tandem RFCOMM channel while the user is actively using Sound Connect,
 * preventing socket contention and automatically resuming 2s after exit.
 */
public final class SoundConnectHandoverHook {
    private static final AtomicInteger STARTED_ACTIVITIES = new AtomicInteger(0);
    private static final AtomicInteger ACTIVE_SERVICES = new AtomicInteger(0);
    private static volatile Context sAppContext;
    private static volatile boolean sCallbacksRegistered;

    private SoundConnectHandoverHook() {
    }

    public static void install(ClassLoader cl, ConfigStore.HookConfig cfg) {
        XposedCompat.hookAllMethods(Application.class, "onCreate", new XposedCompat.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                if (param.thisObject instanceof Application) {
                    registerLifecycle((Application) param.thisObject);
                }
            }
        });

        Class<?> keepSvcCls = XposedCompat.findClassIfExists(
                "com.sony.songpal.mdr.service.KeepConnectionForegroundService", cl);
        if (keepSvcCls != null) {
            XposedCompat.hookAllMethods(keepSvcCls, "onStartCommand", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    ACTIVE_SERVICES.incrementAndGet();
                    publishHandoverState();
                }
            });
            XposedCompat.hookAllMethods(keepSvcCls, "onDestroy", new XposedCompat.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XposedCompat.MethodHookParam param) {
                    int val = ACTIVE_SERVICES.decrementAndGet();
                    if (val < 0) ACTIVE_SERVICES.set(0);
                    publishHandoverState();
                }
            });
        }

        XposedCompat.log("SoundConnectHandoverHook installed");
    }

    private static synchronized void registerLifecycle(Application app) {
        sAppContext = app;
        if (sCallbacksRegistered) {
            return;
        }
        sCallbacksRegistered = true;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity a, Bundle b) {}

            @Override
            public void onActivityStarted(Activity a) {
                STARTED_ACTIVITIES.incrementAndGet();
                publishHandoverState();
            }

            @Override
            public void onActivityResumed(Activity a) {}

            @Override
            public void onActivityPaused(Activity a) {}

            @Override
            public void onActivityStopped(Activity a) {
                int val = STARTED_ACTIVITIES.decrementAndGet();
                if (val < 0) STARTED_ACTIVITIES.set(0);
                publishHandoverState();
            }

            @Override
            public void onActivitySaveInstanceState(Activity a, Bundle outState) {}

            @Override
            public void onActivityDestroyed(Activity a) {}
        });
    }

    private static void publishHandoverState() {
        Context ctx = sAppContext;
        if (ctx == null) {
            return;
        }
        boolean active = STARTED_ACTIVITIES.get() > 0 || ACTIVE_SERVICES.get() > 0;
        try {
            Intent intent = new Intent(ConfigStore.ACTION_HANDOVER);
            intent.putExtra(ConfigStore.EXTRA_HANDOVER_ACTIVE, active);
            ctx.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }
}
