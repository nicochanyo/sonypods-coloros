package io.github.nicochanyo.sonypods;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Built-in Root ({@code su}) scope restarter and MyDevices artwork cache cleaner.
 * Uses {@code killall} for core system daemons ({@code com.android.bluetooth},
 * {@code com.android.systemui}) and {@code am force-stop} for ColorOS UI apps.
 */
public final class ScopeRestarter {
    public interface Callback {
        void onComplete(boolean success, String message);
    }

    public static final String PKG_BLUETOOTH = "com.android.bluetooth";
    public static final String PKG_MYDEVICES = "com.heytap.mydevices";
    public static final String PKG_WIRELESS = "com.oplus.wirelesssettings";
    public static final String PKG_ACCESSORY = "com.heytap.accessory";
    public static final String PKG_SETTINGS = "com.android.settings";
    public static final String PKG_SYSTEMUI = "com.android.systemui";
    public static final String PKG_SOUND_CONNECT = "com.sony.songpal.mdr";
    public static final String PKG_MELODY = "com.oplus.melody";

    private static final Set<String> ALLOWED_PACKAGES = new HashSet<>(Arrays.asList(
            PKG_BLUETOOTH,
            PKG_MYDEVICES,
            PKG_WIRELESS,
            PKG_ACCESSORY,
            PKG_SETTINGS,
            PKG_SYSTEMUI,
            PKG_SOUND_CONNECT,
            PKG_MELODY
    ));

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private ScopeRestarter() {
    }

    public static void checkRootAsync(Callback cb) {
        new Thread(() -> {
            ExecResult res = runRootCommand("id", 4);
            boolean ok = res.exitCode == 0 && res.output.contains("uid=0");
            MAIN_HANDLER.post(() -> {
                if (cb != null) cb.onComplete(ok, res.output);
            });
        }, "SonyPods-RootCheck").start();
    }

    /** Restarts core Bluetooth + MyDevices + WirelessSettings + Accessory without restarting SystemUI. */
    public static void restartCoreScopesAsync(Callback cb) {
        String cmd = "killall com.android.bluetooth 2>/dev/null || pkill -f com.android.bluetooth; "
                + "am force-stop com.heytap.mydevices; "
                + "am force-stop com.oplus.wirelesssettings; "
                + "am force-stop com.oplus.melody; "
                + "am force-stop com.heytap.accessory";
        runAsync(cmd, cb);
    }

    /** Restarts user-selected scope packages safely. */
    public static void restartSelectedAsync(Collection<String> packages, Callback cb) {
        if (packages == null || packages.isEmpty()) {
            if (cb != null) MAIN_HANDLER.post(() -> cb.onComplete(false, "No scope selected"));
            return;
        }
        StringBuilder cmd = new StringBuilder();
        for (String pkg : packages) {
            if (!ALLOWED_PACKAGES.contains(pkg)) {
                continue;
            }
            if (cmd.length() > 0) cmd.append("; ");
            if (PKG_BLUETOOTH.equals(pkg) || PKG_SYSTEMUI.equals(pkg)) {
                cmd.append("killall ").append(pkg)
                        .append(" 2>/dev/null || pkill -f ").append(pkg);
            } else {
                cmd.append("am force-stop ").append(pkg);
            }
        }
        if (cmd.length() == 0) {
            if (cb != null) MAIN_HANDLER.post(() -> cb.onComplete(false, "Invalid packages"));
            return;
        }
        runAsync(cmd.toString(), cb);
    }

    /** Clears MyDevices cached WebP device artwork so updated Sony PNGs render immediately. */
    public static void clearMyDevicesArtworkCacheAndRestartAsync(Callback cb) {
        String cmd = "rm -rf /data/user/0/com.heytap.mydevices/cache/device_image/sony_* "
                + "/data/user/0/com.heytap.mydevices/cache/device_image/*airpods* "
                + "/data/user/0/com.heytap.mydevices/cache/sonypods_art "
                + "2>/dev/null; am force-stop com.heytap.mydevices";
        runAsync(cmd, cb);
    }

    /** Launches ColorOS MyDevices detail panel directly with root privilege when caller lacks internal permission. */
    public static void openNativePanelAsRootAsync(String mac, String deviceName, Callback cb) {
        String cleanMac = mac != null ? mac.trim() : "";
        if (!cleanMac.matches("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")) {
            if (cb != null) MAIN_HANDLER.post(() -> cb.onComplete(false, "Invalid MAC"));
            return;
        }
        String safeName = (deviceName != null)
                ? deviceName.replaceAll("[^A-Za-z0-9 _.-]", "").trim()
                : "";
        if (safeName.isEmpty()) {
            safeName = "Sony";
        }
        String cmd = "am start -a com.oplus.mydevices.ACTION_DEVICE_DETAILED_PANEL "
                + "-p com.heytap.mydevices "
                + "--es device_mac_info '" + cleanMac + "' "
                + "--es device_id '" + cleanMac + "' "
                + "--es device_name '" + safeName + "' "
                + "--ei device_connect_state 2";
        runAsync(cmd, cb);
    }

    /** Triggers the native ColorOS open-case popup preview through MyDevices. */
    public static void openOpenCasePopupAsRootAsync(String modelName, Callback cb) {
        String safeModel = (modelName != null)
                ? modelName.replaceAll("[^A-Za-z0-9 _.-]", "").trim()
                : "";
        if (safeModel.isEmpty()) {
            safeModel = "Sony";
        }
        String cmd = "am broadcast -a " + ConfigStore.ACTION_SHOW_OPEN_POPUP
                + " -p com.heytap.mydevices"
                + " --es " + ConfigStore.EXTRA_MODEL + " '" + safeModel + "'";
        runAsync(cmd, cb);
    }

    private static void runAsync(String shellCmd, Callback cb) {
        new Thread(() -> {
            ExecResult res = runRootCommand(shellCmd, 12);
            boolean ok = res.exitCode == 0;
            MAIN_HANDLER.post(() -> {
                if (cb != null) cb.onComplete(ok, res.output);
            });
        }, "SonyPods-RestartScope").start();
    }

    private static final class ExecResult {
        final int exitCode;
        final String output;

        ExecResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static ExecResult runRootCommand(String shellCmd, int timeoutSec) {
        Process proc = null;
        try {
            proc = new ProcessBuilder("su", "-c", shellCmd)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }
            }
            if (!finished) {
                proc.destroyForcibly();
                return new ExecResult(-1, "Timed out");
            }
            return new ExecResult(proc.exitValue(), sb.toString().trim());
        } catch (Throwable t) {
            if (proc != null) proc.destroyForcibly();
            return new ExecResult(-1, t.getMessage() != null ? t.getMessage() : "su failed");
        }
    }
}
