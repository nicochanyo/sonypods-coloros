package io.github.nicochanyo.sonypods;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * LibXposed API 102 module entry point for SonyPods ColorOS.
 */
public final class SonyPodsModule extends XposedModule {
    private static final Set<String> HOOKED_PACKAGES = ConcurrentHashMap.newKeySet();

    public SonyPodsModule() {
        super();
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        XposedCompat.init(this);
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (param == null) {
            return;
        }
        String pkg = param.getPackageName();
        if (pkg == null || !HOOKED_PACKAGES.add(pkg)) {
            return;
        }
        XposedCompat.init(this);
        ConfigStore.HookConfig cfg = new ConfigStore.HookConfig(this);
        ClassLoader cl = param.getClassLoader();

        switch (pkg) {
            case "com.android.bluetooth":
                BluetoothHook.install(cl, cfg);
                break;
            case "com.heytap.mydevices":
                ColorOsMyDevicesHook.install(cl, cfg);
                break;
            case "com.oplus.wirelesssettings":
                ColorOsWirelessSettingsHook.install(cl, cfg, true);
                break;
            case "com.android.settings":
                ColorOsWirelessSettingsHook.install(cl, cfg, false);
                break;
            case "com.android.systemui":
                ColorOsSystemUiHook.install(cl, cfg);
                break;
            case "com.heytap.accessory":
                ColorOsAccessoryHook.install(cl, cfg);
                break;
            case "com.sony.songpal.mdr":
                SoundConnectHandoverHook.install(cl, cfg);
                break;
            case "com.oplus.melody":
                ColorOsMelodyHook.install(cl, cfg);
                break;
            default:
                break;
        }
        XposedCompat.log("SonyPods ColorOS ready in " + pkg);
    }
}
