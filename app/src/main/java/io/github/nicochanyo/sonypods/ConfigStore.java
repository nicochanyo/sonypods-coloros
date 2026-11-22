package io.github.nicochanyo.sonypods;

import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.service.XposedProvider;

/**
 * Centralized settings store (LibXposed API 102 RemotePreferences) and
 * cross-process Sony headset state snapshot & command bus.
 */
public final class ConfigStore {
    public static final String PKG_NAME = "io.github.nicochanyo.sonypods";
    public static final String PREFS_NAME = "config";

    // Intent actions & extras
    public static final String ACTION_STATE_CHANGED = "sonypods.coloros.action.STATE_CHANGED";
    public static final String ACTION_COMMAND = "sonypods.coloros.action.COMMAND";
    public static final String ACTION_CYCLE_ANC = "sonypods.coloros.action.CYCLE_ANC";
    public static final String ACTION_SET_ANC = "sonypods.coloros.action.SET_ANC";
    public static final String ACTION_HANDOVER = "sonypods.coloros.action.HANDOVER";
    public static final String ACTION_SHOW_OPEN_POPUP = "sonypods.coloros.action.SHOW_OPEN_POPUP";
    public static final String ACTION_PAIR_STATUS = "sonypods.coloros.action.PAIR_STATUS";
    public static final String EXTRA_DEVICE = "device";
    public static final String EXTRA_MODEL = "model";
    public static final String EXTRA_BOND_STATE = "bond_state";
    public static final String EXTRA_IS_CONNECTED = "is_connected";
    public static final String EXTRA_PAIR_STATE = "pair_state";

    public static final String CMD_SET_ANC = "set_anc";
    public static final String CMD_CYCLE_ANC = "cycle_anc";
    public static final String CMD_SET_SPEAK_TO_CHAT = "set_speak_to_chat";
    public static final String CMD_SET_DSEE = "set_dsee";
    public static final String CMD_SET_AMBIENT_PARAMS = "set_ambient_params";
    public static final String CMD_APPLY_DEFAULTS = "apply_defaults";
    public static final String CMD_REFRESH = "refresh";
    public static final String CMD_PAIR_AND_CONNECT = "pair_and_connect";
    public static final String CMD_DISMISS_PAIRING = "dismiss_pairing";
    public static final String CMD_SET_CODEC = "set_codec";

    public static final String EXTRA_CMD = "cmd";
    public static final String EXTRA_MAC = "mac";
    public static final String EXTRA_ANC_MODE = "anc_mode";
    public static final String EXTRA_ASM_LEVEL = "asm_level";
    public static final String EXTRA_VOICE_FOCUS = "voice_focus";
    public static final String EXTRA_BOOL_VAL = "bool_val";
    public static final String EXTRA_CODEC_TYPE = "codec_type";
    public static final String EXTRA_HANDOVER_ACTIVE = "handover_active";
    public static final String EXTRA_STATE_SERIALIZED = "state_serialized";

    // Settings.Global keys for zero-permission cross-process read from SystemUI/Settings/MyDevices
    public static final String GLOBAL_KEY_STATE = "sonypods_coloros_state";
    public static final String GLOBAL_KEY_BATTERY = "sonypods_coloros_battery";
    public static final String GLOBAL_ACTIVE_PAIR = "sonypods_coloros_active_pair";
    public static final String GLOBAL_KEY_CODEC = "sonypods_coloros_codec";

    public static final int CODEC_SBC = 0;
    public static final int CODEC_AAC = 1;
    public static final int CODEC_LDAC = 4;

    // Preference keys
    public static final String KEY_BRIDGE_ENABLED = "bridge_enabled";
    public static final String KEY_VENDOR_BYPASS = "bypass_vendor_whitelist";
    public static final String KEY_MYDEVICES_PANEL = "mydevices_panel_enabled";
    public static final String KEY_NATIVE_ARTWORK = "native_artwork_enabled";
    public static final String KEY_FLUID_CLOUD_CAPSULE = "fluid_cloud_capsule_enabled";
    public static final String KEY_QS_TILE = "qs_tile_battery_enabled";
    public static final String KEY_HANDOVER = "sound_connect_handover";
    public static final String KEY_MELODY_CHANNEL = "melody_channel_enabled";
    public static final String KEY_OPEN_CASE_POPUP = "open_case_popup_enabled";
    public static final String KEY_OPEN_CASE_POPUP_MODE = "open_case_popup_mode";
    public static final int POPUP_MODE_PAIRING_ONLY = 0;
    public static final int POPUP_MODE_PAIRING_AND_CONNECT = 1;
    public static final String KEY_SUPPRESS_NATIVE_PAIRING = "suppress_native_pairing_enabled";
    public static final String KEY_CYCLE_MODE = "anc_cycle_mode";
    public static final String KEY_DEFAULT_ASM_LEVEL = "default_asm_level";
    public static final String KEY_DEFAULT_VOICE_FOCUS = "default_voice_focus";
    public static final String KEY_CUSTOM_MODEL_OVERRIDE = "custom_model_override";
    public static final String KEY_CUSTOM_ARTWORK_URI = "custom_artwork_uri";
    public static final String KEY_DEBUG_MODE = "debug_mode";

    public static final String KEY_APP_LANG = "app_lang";
    public static final String KEY_APP_MONET = "app_monet";
    public static final String KEY_APP_DARK_MODE = "app_dark_mode";

    public static final int DARK_MODE_SYSTEM = 0;
    public static final int DARK_MODE_LIGHT = 1;
    public static final int DARK_MODE_DARK = 2;

    public static final String LANG_SYSTEM = "system";
    public static final String LANG_ZH_TW = "zh-TW";
    public static final String LANG_ZH_CN = "zh-CN";
    public static final String LANG_EN = "en";

    public static final int CYCLE_3WAY = 0; // ANC -> Ambient -> Off
    public static final int CYCLE_2WAY = 1; // ANC -> Ambient

    public static final int ANC_OFF = 0;
    public static final int ANC_NC = 1;
    public static final int ANC_AMBIENT = 2;

    public static final int DEF_ASM_LEVEL = 15;
    private static final long REMOTE_RETRY_INTERVAL_MS = 2000L;
    private static final long RELOAD_THROTTLE_MS = 500L;

    private ConfigStore() {
    }

    public static final class SettingsData {
        public boolean bridgeEnabled = true;
        public boolean vendorBypass = true;
        public boolean myDevicesPanel = true;
        public boolean nativeArtwork = true;
        public boolean fluidCloudCapsule = true;
        public boolean qsTile = true;
        public boolean handover = true;
        public boolean melodyChannel = true;
        public boolean openCasePopup = true;
        public int openCasePopupMode = POPUP_MODE_PAIRING_ONLY;
        public boolean suppressNativePairing = true;
        public int cycleMode = CYCLE_3WAY;
        public int defaultAsmLevel = DEF_ASM_LEVEL;
        public boolean defaultVoiceFocus = false;
        public String customModelOverride = "";
        public String customArtworkUri = "";
        public boolean debugMode = false;

        public SettingsData copy() {
            SettingsData s = new SettingsData();
            s.bridgeEnabled = bridgeEnabled;
            s.vendorBypass = vendorBypass;
            s.myDevicesPanel = myDevicesPanel;
            s.nativeArtwork = nativeArtwork;
            s.fluidCloudCapsule = fluidCloudCapsule;
            s.qsTile = qsTile;
            s.handover = handover;
            s.melodyChannel = melodyChannel;
            s.openCasePopup = openCasePopup;
            s.openCasePopupMode = openCasePopupMode;
            s.suppressNativePairing = suppressNativePairing;
            s.cycleMode = cycleMode;
            s.defaultAsmLevel = defaultAsmLevel;
            s.defaultVoiceFocus = defaultVoiceFocus;
            s.customModelOverride = customModelOverride;
            s.customArtworkUri = customArtworkUri;
            s.debugMode = debugMode;
            return s;
        }
    }

    public static SettingsData createDefaults() {
        return new SettingsData();
    }

    public static int clampAsmLevel(int level) {
        if (level < 1) {
            return 1;
        }
        if (level > 20) {
            return 20;
        }
        return level;
    }

    public static int normalizePopupMode(int raw) {
        return raw == POPUP_MODE_PAIRING_AND_CONNECT
                ? POPUP_MODE_PAIRING_AND_CONNECT
                : POPUP_MODE_PAIRING_ONLY;
    }

    public static String normalizeLanguage(String raw) {
        if (LANG_ZH_TW.equals(raw)) {
            return LANG_ZH_TW;
        }
        if (LANG_ZH_CN.equals(raw)) {
            return LANG_ZH_CN;
        }
        if (LANG_EN.equals(raw)) {
            return LANG_EN;
        }
        return LANG_SYSTEM;
    }

    public static String getAppLanguage(Context ctx) {
        if (ctx == null) {
            return LANG_SYSTEM;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return normalizeLanguage(sp.getString(KEY_APP_LANG, LANG_SYSTEM));
        } catch (Throwable ignored) {
            return LANG_SYSTEM;
        }
    }

    public static String getEffectiveAppLanguage(Context ctx) {
        String pref = getAppLanguage(ctx);
        return LANG_SYSTEM.equals(pref) ? detectSystemLanguage() : pref;
    }

    /**
     * Reads the true OS system locale list from Resources.getSystem() (unaffected by
     * process-level Locale.setDefault() overrides) and picks the first supported language.
     * Falls back to English for non-Chinese locales.
     */
    public static String detectSystemLanguage() {
        try {
            Resources sysRes = Resources.getSystem();
            if (sysRes != null && sysRes.getConfiguration() != null) {
                if (Build.VERSION.SDK_INT >= 24) {
                    LocaleList locales = sysRes.getConfiguration().getLocales();
                    if (locales != null && !locales.isEmpty()) {
                        for (int i = 0; i < locales.size(); i++) {
                            String matched = matchSupportedLocale(locales.get(i));
                            if (matched != null) {
                                return matched;
                            }
                        }
                        return LANG_EN;
                    }
                } else {
                    @SuppressWarnings("deprecation")
                    Locale legacy = sysRes.getConfiguration().locale;
                    if (legacy != null) {
                        return detectLanguageFromLocale(legacy);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return detectLanguageFromLocale(Locale.getDefault());
    }

    public static String matchSupportedLocale(Locale loc) {
        if (loc == null) {
            return null;
        }
        String language = loc.getLanguage();
        if (language == null || language.isEmpty()) {
            return null;
        }
        String langLower = language.toLowerCase(Locale.US);
        if ("zh".equals(langLower)) {
            String script = loc.getScript();
            if ("Hant".equalsIgnoreCase(script)) {
                return LANG_ZH_TW;
            }
            if ("Hans".equalsIgnoreCase(script)) {
                return LANG_ZH_CN;
            }
            String country = loc.getCountry();
            if ("TW".equalsIgnoreCase(country)
                    || "HK".equalsIgnoreCase(country)
                    || "MO".equalsIgnoreCase(country)) {
                return LANG_ZH_TW;
            }
            return LANG_ZH_CN;
        }
        if ("en".equals(langLower)) {
            return LANG_EN;
        }
        return null;
    }

    public static String detectLanguageFromLocale(Locale loc) {
        String matched = matchSupportedLocale(loc);
        return matched != null ? matched : LANG_EN;
    }

    public static void setAppLanguage(Context ctx, String lang) {
        if (ctx == null) {
            return;
        }
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_APP_LANG, normalizeLanguage(lang)).commit();
    }

    public static boolean isMonetEnabled(Context ctx) {
        if (ctx == null) {
            return false;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return sp.getBoolean(KEY_APP_MONET, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void setMonetEnabled(Context ctx, boolean enabled) {
        if (ctx == null) {
            return;
        }
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_APP_MONET, enabled).commit();
    }

    public static int normalizeDarkMode(int raw) {
        if (raw == DARK_MODE_LIGHT) {
            return DARK_MODE_LIGHT;
        }
        if (raw == DARK_MODE_DARK) {
            return DARK_MODE_DARK;
        }
        return DARK_MODE_SYSTEM;
    }

    public static int getAppDarkMode(Context ctx) {
        if (ctx == null) {
            return DARK_MODE_SYSTEM;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return normalizeDarkMode(sp.getInt(KEY_APP_DARK_MODE, DARK_MODE_SYSTEM));
        } catch (Throwable ignored) {
            return DARK_MODE_SYSTEM;
        }
    }

    public static void setAppDarkMode(Context ctx, int mode) {
        if (ctx == null) {
            return;
        }
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_APP_DARK_MODE, normalizeDarkMode(mode)).commit();
    }

    public static boolean isDarkThemeActive(Context ctx) {
        int mode = getAppDarkMode(ctx);
        if (mode == DARK_MODE_DARK) {
            return true;
        }
        if (mode == DARK_MODE_LIGHT) {
            return false;
        }
        if (ctx == null) {
            return false;
        }
        int nightMask = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightMask == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int resolveThemeColor(Context ctx, int attrId, int fallbackColorRes) {
        if (ctx != null) {
            TypedValue tv = new TypedValue();
            if (ctx.getTheme().resolveAttribute(attrId, tv, true) && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
            return ctx.getColor(fallbackColorRes);
        }
        return 0;
    }

    public static final class AppPrefs {
        public final SharedPreferences prefs;
        public final boolean xspActive;
        public final boolean hadLocalKeys;

        public AppPrefs(SharedPreferences prefs, boolean xspActive, boolean hadLocalKeys) {
            this.prefs = prefs;
            this.xspActive = xspActive;
            this.hadLocalKeys = hadLocalKeys;
        }
    }

    public static AppPrefs openAppPrefs(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean hadKeys = hasAnySetting(prefs);
        return new AppPrefs(prefs, XposedProvider.isConnected(), hadKeys);
    }

    public static boolean hasAnySetting(SharedPreferences sp) {
        if (sp == null) {
            return false;
        }
        return sp.contains(KEY_BRIDGE_ENABLED)
                || sp.contains(KEY_MYDEVICES_PANEL)
                || sp.contains(KEY_NATIVE_ARTWORK)
                || sp.contains(KEY_FLUID_CLOUD_CAPSULE)
                || sp.contains(KEY_QS_TILE);
    }

    public static boolean hasAnySetting(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return false;
        }
        return map.containsKey(KEY_BRIDGE_ENABLED)
                || map.containsKey(KEY_MYDEVICES_PANEL)
                || map.containsKey(KEY_NATIVE_ARTWORK)
                || map.containsKey(KEY_FLUID_CLOUD_CAPSULE)
                || map.containsKey(KEY_QS_TILE);
    }

    public static SettingsData loadFromPrefs(SharedPreferences sp) {
        SettingsData s = createDefaults();
        if (sp == null) {
            return s;
        }
        s.bridgeEnabled = sp.getBoolean(KEY_BRIDGE_ENABLED, true);
        s.vendorBypass = sp.getBoolean(KEY_VENDOR_BYPASS, true);
        s.myDevicesPanel = sp.getBoolean(KEY_MYDEVICES_PANEL, true);
        s.nativeArtwork = sp.getBoolean(KEY_NATIVE_ARTWORK, true);
        s.fluidCloudCapsule = sp.getBoolean(KEY_FLUID_CLOUD_CAPSULE, true);
        s.qsTile = sp.getBoolean(KEY_QS_TILE, true);
        s.handover = sp.getBoolean(KEY_HANDOVER, true);
        s.melodyChannel = sp.getBoolean(KEY_MELODY_CHANNEL, true);
        s.openCasePopup = sp.getBoolean(KEY_OPEN_CASE_POPUP, true);
        s.openCasePopupMode = normalizePopupMode(sp.getInt(KEY_OPEN_CASE_POPUP_MODE, POPUP_MODE_PAIRING_ONLY));
        s.suppressNativePairing = sp.getBoolean(KEY_SUPPRESS_NATIVE_PAIRING, true);
        s.cycleMode = sp.getInt(KEY_CYCLE_MODE, CYCLE_3WAY);
        s.defaultAsmLevel = clampAsmLevel(sp.getInt(KEY_DEFAULT_ASM_LEVEL, DEF_ASM_LEVEL));
        s.defaultVoiceFocus = sp.getBoolean(KEY_DEFAULT_VOICE_FOCUS, false);
        s.customModelOverride = safeTrim(sp.getString(KEY_CUSTOM_MODEL_OVERRIDE, ""));
        s.customArtworkUri = safeTrim(sp.getString(KEY_CUSTOM_ARTWORK_URI, ""));
        s.debugMode = sp.getBoolean(KEY_DEBUG_MODE, false);
        return s;
    }

    public static SettingsData loadFromMap(Map<String, Object> map) {
        SettingsData s = createDefaults();
        if (map == null) {
            return s;
        }
        s.bridgeEnabled = getBool(map, KEY_BRIDGE_ENABLED, true);
        s.vendorBypass = getBool(map, KEY_VENDOR_BYPASS, true);
        s.myDevicesPanel = getBool(map, KEY_MYDEVICES_PANEL, true);
        s.nativeArtwork = getBool(map, KEY_NATIVE_ARTWORK, true);
        s.fluidCloudCapsule = getBool(map, KEY_FLUID_CLOUD_CAPSULE, true);
        s.qsTile = getBool(map, KEY_QS_TILE, true);
        s.handover = getBool(map, KEY_HANDOVER, true);
        s.melodyChannel = getBool(map, KEY_MELODY_CHANNEL, true);
        s.openCasePopup = getBool(map, KEY_OPEN_CASE_POPUP, true);
        s.openCasePopupMode = normalizePopupMode(getInt(map, KEY_OPEN_CASE_POPUP_MODE, POPUP_MODE_PAIRING_ONLY));
        s.suppressNativePairing = getBool(map, KEY_SUPPRESS_NATIVE_PAIRING, true);
        s.cycleMode = getInt(map, KEY_CYCLE_MODE, CYCLE_3WAY);
        s.defaultAsmLevel = clampAsmLevel(getInt(map, KEY_DEFAULT_ASM_LEVEL, DEF_ASM_LEVEL));
        s.defaultVoiceFocus = getBool(map, KEY_DEFAULT_VOICE_FOCUS, false);
        s.customModelOverride = getString(map, KEY_CUSTOM_MODEL_OVERRIDE, "");
        s.customArtworkUri = getString(map, KEY_CUSTOM_ARTWORK_URI, "");
        s.debugMode = getBool(map, KEY_DEBUG_MODE, false);
        return s;
    }

    private static boolean getBool(Map<String, Object> map, String key, boolean def) {
        Object v = map.get(key);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    private static int getInt(Map<String, Object> map, String key, int def) {
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    private static String getString(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        return v instanceof String ? safeTrim((String) v) : def;
    }

    private static String safeTrim(String raw) {
        return raw == null ? "" : raw.trim();
    }

    public static Map<String, Object> toMap(SettingsData s) {
        Map<String, Object> map = new HashMap<>();
        map.put(KEY_BRIDGE_ENABLED, s.bridgeEnabled);
        map.put(KEY_VENDOR_BYPASS, s.vendorBypass);
        map.put(KEY_MYDEVICES_PANEL, s.myDevicesPanel);
        map.put(KEY_NATIVE_ARTWORK, s.nativeArtwork);
        map.put(KEY_FLUID_CLOUD_CAPSULE, s.fluidCloudCapsule);
        map.put(KEY_QS_TILE, s.qsTile);
        map.put(KEY_HANDOVER, s.handover);
        map.put(KEY_MELODY_CHANNEL, s.melodyChannel);
        map.put(KEY_OPEN_CASE_POPUP, s.openCasePopup);
        map.put(KEY_OPEN_CASE_POPUP_MODE, normalizePopupMode(s.openCasePopupMode));
        map.put(KEY_SUPPRESS_NATIVE_PAIRING, s.suppressNativePairing);
        map.put(KEY_CYCLE_MODE, s.cycleMode);
        map.put(KEY_DEFAULT_ASM_LEVEL, s.defaultAsmLevel);
        map.put(KEY_DEFAULT_VOICE_FOCUS, s.defaultVoiceFocus);
        map.put(KEY_CUSTOM_MODEL_OVERRIDE, safeTrim(s.customModelOverride));
        map.put(KEY_CUSTOM_ARTWORK_URI, safeTrim(s.customArtworkUri));
        map.put(KEY_DEBUG_MODE, s.debugMode);
        return map;
    }

    public static void saveLocal(SharedPreferences prefs, SettingsData s) {
        if (prefs == null || s == null) {
            return;
        }
        prefs.edit()
                .putBoolean(KEY_BRIDGE_ENABLED, s.bridgeEnabled)
                .putBoolean(KEY_VENDOR_BYPASS, s.vendorBypass)
                .putBoolean(KEY_MYDEVICES_PANEL, s.myDevicesPanel)
                .putBoolean(KEY_NATIVE_ARTWORK, s.nativeArtwork)
                .putBoolean(KEY_FLUID_CLOUD_CAPSULE, s.fluidCloudCapsule)
                .putBoolean(KEY_QS_TILE, s.qsTile)
                .putBoolean(KEY_HANDOVER, s.handover)
                .putBoolean(KEY_MELODY_CHANNEL, s.melodyChannel)
                .putBoolean(KEY_OPEN_CASE_POPUP, s.openCasePopup)
                .putInt(KEY_OPEN_CASE_POPUP_MODE, normalizePopupMode(s.openCasePopupMode))
                .putBoolean(KEY_SUPPRESS_NATIVE_PAIRING, s.suppressNativePairing)
                .putInt(KEY_CYCLE_MODE, s.cycleMode)
                .putInt(KEY_DEFAULT_ASM_LEVEL, s.defaultAsmLevel)
                .putBoolean(KEY_DEFAULT_VOICE_FOCUS, s.defaultVoiceFocus)
                .putString(KEY_CUSTOM_MODEL_OVERRIDE, safeTrim(s.customModelOverride))
                .putString(KEY_CUSTOM_ARTWORK_URI, safeTrim(s.customArtworkUri))
                .putBoolean(KEY_DEBUG_MODE, s.debugMode)
                .apply();
    }

    public static boolean pushRemote(SettingsData s) {
        return XposedProvider.pushRemotePrefs(PREFS_NAME, toMap(s));
    }

    // ==================== Cross-Process Headset State ====================

    public static final class HeadsetState {
        public boolean connected;
        public String mac = "";
        public String name = "";
        public String modelName = "";
        public int seriesCode = -1;
        public int colorCode = -1;
        public int protocolVersion = 2; // 1 or 2
        public int ncAsmInquiredType = SonyTandemProtocol.V2_INQ_AUTO_SEAMLESS;
        public int lastNcValue = -1;
        public int speakToChatInquiredType = -1;
        public int eqInquiredType = -1;
        public boolean supportsNcAsm = true;
        public boolean supportsSpeakToChat = false;
        public boolean supportsDsee = false;
        public int ancMode = ANC_OFF; // 0=OFF, 1=NC, 2=AMBIENT
        public int asmLevel = DEF_ASM_LEVEL;
        public boolean voiceFocus = false;
        public boolean speakToChat = false;
        public boolean dsee = false;
        public String eqPresetName = "";
        public int leftBattery = -1;
        public boolean leftCharging = false;
        public int rightBattery = -1;
        public boolean rightCharging = false;
        public int caseBattery = -1;
        public boolean caseCharging = false;
        public int singleBattery = -1;
        public boolean singleCharging = false;
        public boolean wearing = true;
        public long updatedAtMs = 0L;
        public String lastEvent = "";

        public HeadsetState copy() {
            HeadsetState c = new HeadsetState();
            c.connected = connected;
            c.mac = mac;
            c.name = name;
            c.modelName = modelName;
            c.seriesCode = seriesCode;
            c.colorCode = colorCode;
            c.protocolVersion = protocolVersion;
            c.ncAsmInquiredType = ncAsmInquiredType;
            c.lastNcValue = lastNcValue;
            c.speakToChatInquiredType = speakToChatInquiredType;
            c.eqInquiredType = eqInquiredType;
            c.supportsNcAsm = supportsNcAsm;
            c.supportsSpeakToChat = supportsSpeakToChat;
            c.supportsDsee = supportsDsee;
            c.ancMode = ancMode;
            c.asmLevel = asmLevel;
            c.voiceFocus = voiceFocus;
            c.speakToChat = speakToChat;
            c.dsee = dsee;
            c.eqPresetName = eqPresetName;
            c.leftBattery = leftBattery;
            c.leftCharging = leftCharging;
            c.rightBattery = rightBattery;
            c.rightCharging = rightCharging;
            c.caseBattery = caseBattery;
            c.caseCharging = caseCharging;
            c.singleBattery = singleBattery;
            c.singleCharging = singleCharging;
            c.wearing = wearing;
            c.updatedAtMs = updatedAtMs;
            c.lastEvent = lastEvent;
            return c;
        }

        public String effectiveModelName() {
            if (modelName != null && !modelName.trim().isEmpty()) {
                return SonyDeviceDetector.normalizeModelName(modelName);
            }
            return SonyDeviceDetector.normalizeModelName(name);
        }

        public int effectiveLeft() {
            return leftBattery >= 0 ? leftBattery : singleBattery;
        }

        public int effectiveRight() {
            return rightBattery >= 0 ? rightBattery : singleBattery;
        }

        public boolean hasMultiBattery() {
            return leftBattery >= 0 || rightBattery >= 0 || caseBattery >= 0;
        }

        public int systemMinBattery() {
            int min = 101;
            if (leftBattery >= 0) min = Math.min(min, leftBattery);
            if (rightBattery >= 0) min = Math.min(min, rightBattery);
            if (min <= 100) return min;
            if (singleBattery >= 0) return singleBattery;
            if (caseBattery >= 0) return caseBattery;
            return -1;
        }

        public String formatBatterySummary(Context ctx, boolean includeMode) {
            StringBuilder sb = new StringBuilder();
            if (leftBattery >= 0 && rightBattery >= 0) {
                sb.append("L ").append(leftBattery).append("% · R ").append(rightBattery).append("%");
                if (caseBattery >= 0) {
                    sb.append(" · Box ").append(caseBattery).append("%");
                }
            } else if (leftBattery >= 0) {
                sb.append("L ").append(leftBattery).append("%");
                if (caseBattery >= 0) sb.append(" · Box ").append(caseBattery).append("%");
            } else if (rightBattery >= 0) {
                sb.append("R ").append(rightBattery).append("%");
                if (caseBattery >= 0) sb.append(" · Box ").append(caseBattery).append("%");
            } else if (singleBattery >= 0) {
                sb.append(singleBattery).append("%");
            }
            if (includeMode && supportsNcAsm) {
                String modeStr = formatModeLabel(ctx);
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append(modeStr);
            }
            return sb.toString();
        }

        public String formatModeLabel(Context ctx) {
            if (ctx != null) {
                if (ancMode == ANC_NC) return ctx.getString(R.string.mode_anc);
                if (ancMode == ANC_AMBIENT) return ctx.getString(R.string.mode_ambient);
                return ctx.getString(R.string.mode_off);
            }
            if (ancMode == ANC_NC) return "ANC";
            if (ancMode == ANC_AMBIENT) return "Ambient";
            return "Off";
        }

        private static String cleanToken(String raw) {
            return raw == null ? "" : raw.replace('|', ' ').replace('\n', ' ').trim();
        }

        public String serialize() {
            String safeName = cleanToken(name);
            String safeModel = cleanToken(modelName);
            String safeEq = cleanToken(eqPresetName);
            String safeMac = (mac == null ? "" : mac.trim().toUpperCase(Locale.US));
            return "v2|"
                    + (connected ? 1 : 0) + "|"
                    + safeMac + "|"
                    + safeName + "|"
                    + safeModel + "|"
                    + seriesCode + "|"
                    + colorCode + "|"
                    + protocolVersion + "|"
                    + ncAsmInquiredType + "|"
                    + lastNcValue + "|"
                    + speakToChatInquiredType + "|"
                    + eqInquiredType + "|"
                    + (supportsNcAsm ? 1 : 0) + "|"
                    + (supportsSpeakToChat ? 1 : 0) + "|"
                    + (supportsDsee ? 1 : 0) + "|"
                    + ancMode + "|"
                    + asmLevel + "|"
                    + (voiceFocus ? 1 : 0) + "|"
                    + (speakToChat ? 1 : 0) + "|"
                    + (dsee ? 1 : 0) + "|"
                    + safeEq + "|"
                    + leftBattery + "|"
                    + (leftCharging ? 1 : 0) + "|"
                    + rightBattery + "|"
                    + (rightCharging ? 1 : 0) + "|"
                    + caseBattery + "|"
                    + (caseCharging ? 1 : 0) + "|"
                    + singleBattery + "|"
                    + (singleCharging ? 1 : 0) + "|"
                    + (wearing ? 1 : 0) + "|"
                    + updatedAtMs;
        }

        public static HeadsetState deserialize(String raw) {
            HeadsetState s = new HeadsetState();
            if (raw == null) {
                return s;
            }
            String[] p = raw.split("\\|", -1);
            try {
                if (raw.startsWith("v2|") && p.length >= 31) {
                    s.connected = "1".equals(p[1]);
                    s.mac = p[2];
                    s.name = p[3];
                    s.modelName = p[4];
                    s.seriesCode = Integer.parseInt(p[5]);
                    s.colorCode = Integer.parseInt(p[6]);
                    s.protocolVersion = Integer.parseInt(p[7]);
                    s.ncAsmInquiredType = Integer.parseInt(p[8]);
                    s.lastNcValue = Integer.parseInt(p[9]);
                    s.speakToChatInquiredType = Integer.parseInt(p[10]);
                    s.eqInquiredType = Integer.parseInt(p[11]);
                    s.supportsNcAsm = "1".equals(p[12]);
                    s.supportsSpeakToChat = "1".equals(p[13]);
                    s.supportsDsee = "1".equals(p[14]);
                    s.ancMode = Integer.parseInt(p[15]);
                    s.asmLevel = clampAsmLevel(Integer.parseInt(p[16]));
                    s.voiceFocus = "1".equals(p[17]);
                    s.speakToChat = "1".equals(p[18]);
                    s.dsee = "1".equals(p[19]);
                    s.eqPresetName = p[20];
                    s.leftBattery = Integer.parseInt(p[21]);
                    s.leftCharging = "1".equals(p[22]);
                    s.rightBattery = Integer.parseInt(p[23]);
                    s.rightCharging = "1".equals(p[24]);
                    s.caseBattery = Integer.parseInt(p[25]);
                    s.caseCharging = "1".equals(p[26]);
                    s.singleBattery = Integer.parseInt(p[27]);
                    s.singleCharging = "1".equals(p[28]);
                    s.wearing = "1".equals(p[29]);
                    s.updatedAtMs = Long.parseLong(p[30]);
                } else if (raw.startsWith("v1|") && p.length >= 25) {
                    s.connected = "1".equals(p[1]);
                    s.mac = p[3];
                    s.name = p[4];
                    s.protocolVersion = Integer.parseInt(p[5]);
                    s.ncAsmInquiredType = Integer.parseInt(p[6]);
                    s.supportsNcAsm = "1".equals(p[7]);
                    s.supportsSpeakToChat = "1".equals(p[8]);
                    s.supportsDsee = "1".equals(p[9]);
                    s.ancMode = Integer.parseInt(p[10]);
                    s.asmLevel = clampAsmLevel(Integer.parseInt(p[11]));
                    s.voiceFocus = "1".equals(p[12]);
                    s.speakToChat = "1".equals(p[13]);
                    s.dsee = "1".equals(p[14]);
                    s.leftBattery = Integer.parseInt(p[15]);
                    s.leftCharging = "1".equals(p[16]);
                    s.rightBattery = Integer.parseInt(p[17]);
                    s.rightCharging = "1".equals(p[18]);
                    s.caseBattery = Integer.parseInt(p[19]);
                    s.caseCharging = "1".equals(p[20]);
                    s.singleBattery = Integer.parseInt(p[21]);
                    s.singleCharging = "1".equals(p[22]);
                    s.wearing = "1".equals(p[23]);
                    s.updatedAtMs = Long.parseLong(p[24]);
                }
            } catch (Throwable ignored) {
            }
            return s;
        }
    }

    public static void writeGlobalState(Context ctx, HeadsetState state) {
        if (ctx == null || state == null) {
            return;
        }
        String serialized = state.serialize();
        try {
            ContentResolver cr = ctx.getContentResolver();
            Settings.Global.putString(cr, GLOBAL_KEY_STATE, serialized);
            String bat = state.connected
                    ? (state.mac + "|" + state.leftBattery + "|" + state.rightBattery + "|" + state.caseBattery + "|" + state.singleBattery + "|" + state.name)
                    : "";
            Settings.Global.putString(cr, GLOBAL_KEY_BATTERY, bat);
        } catch (Throwable t) {
            XposedCompat.debug("writeGlobalState failed (expected in unprivileged app): " + t.getMessage());
        }
        try {
            Intent intent = new Intent(ACTION_STATE_CHANGED);
            intent.putExtra(EXTRA_STATE_SERIALIZED, serialized);
            ctx.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    public static HeadsetState readGlobalState(Context ctx) {
        if (ctx == null) {
            return new HeadsetState();
        }
        try {
            String raw = Settings.Global.getString(ctx.getContentResolver(), GLOBAL_KEY_STATE);
            if (raw != null && !raw.isEmpty()) {
                HeadsetState s = HeadsetState.deserialize(raw);
                if (s.connected) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
        }
        return new HeadsetState();
    }

    public static void writeActivePairMarker(Context ctx, String mac, String model) {
        if (ctx == null) {
            return;
        }
        try {
            String cleanMac = mac != null ? mac.trim().toUpperCase(Locale.US) : "";
            String cleanModel = model != null ? model.trim().replace("|", "") : "";
            String val = cleanMac + "|" + cleanModel + "|" + System.currentTimeMillis();
            Settings.Global.putString(ctx.getContentResolver(), GLOBAL_ACTIVE_PAIR, val);
        } catch (Throwable ignored) {
        }
    }

    public static void clearActivePairMarker(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            Settings.Global.putString(ctx.getContentResolver(), GLOBAL_ACTIVE_PAIR, "");
        } catch (Throwable ignored) {
        }
    }

    public static boolean isRecentActivePair(Context ctx, BluetoothDevice dev, long windowMs) {
        if (ctx == null || dev == null) {
            return false;
        }
        try {
            String raw = Settings.Global.getString(ctx.getContentResolver(), GLOBAL_ACTIVE_PAIR);
            if (raw == null || raw.isEmpty()) {
                return false;
            }
            String[] parts = raw.split("\\|", -1);
            if (parts.length < 3) {
                return false;
            }
            long ts = Long.parseLong(parts[2]);
            long age = System.currentTimeMillis() - ts;
            if (age < 0L || age > windowMs) {
                return false;
            }
            String markerMac = parts[0].trim();
            String markerModel = parts[1].trim();
            String devMac = dev.getAddress();
            if (!markerMac.isEmpty() && devMac != null && markerMac.equalsIgnoreCase(devMac.trim())) {
                return true;
            }
            if (SonyDeviceDetector.isSonyMacOrBonded(dev)) {
                if (markerModel.isEmpty() || "Sony".equalsIgnoreCase(markerModel)) {
                    return true;
                }
                String devName = dev.getName();
                if (devName == null || devName.trim().isEmpty()) {
                    return true;
                }
                String normMarker = SonyDeviceDetector.normalizeModelName(markerModel);
                String normDev = SonyDeviceDetector.normalizeModelName(devName);
                return normMarker.equalsIgnoreCase(normDev);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static void sendCommandBroadcast(Context ctx, String cmd, String mac, int ancMode, int asmLevel, boolean voiceFocus, boolean boolVal) {
        if (ctx == null) {
            return;
        }
        Intent intent = new Intent(ACTION_COMMAND);
        intent.putExtra(EXTRA_CMD, cmd);
        if (mac != null) intent.putExtra(EXTRA_MAC, mac);
        intent.putExtra(EXTRA_ANC_MODE, ancMode);
        intent.putExtra(EXTRA_ASM_LEVEL, asmLevel);
        intent.putExtra(EXTRA_VOICE_FOCUS, voiceFocus);
        intent.putExtra(EXTRA_BOOL_VAL, boolVal);
        ctx.sendBroadcast(intent);
    }

    public static final class CodecState {
        public String mac = "";
        public int activeCodec = -1;
        public boolean supportsLdac = false;
        public boolean supportsAac = false;
    }

    public static void writeGlobalCodecState(
            Context ctx,
            String mac,
            int activeCodec,
            boolean supportsLdac,
            boolean supportsAac
    ) {
        if (ctx == null) {
            return;
        }
        try {
            String cleanMac = mac != null ? mac.trim().toUpperCase(Locale.US) : "";
            String serialized = cleanMac + "|" + activeCodec + "|" + (supportsLdac ? "1" : "0") + "|" + (supportsAac ? "1" : "0");
            Settings.Global.putString(ctx.getContentResolver(), GLOBAL_KEY_CODEC, serialized);
        } catch (Throwable ignored) {
        }
        try {
            Intent intent = new Intent(ACTION_STATE_CHANGED);
            ctx.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    public static CodecState readGlobalCodecState(Context ctx) {
        CodecState out = new CodecState();
        if (ctx == null) {
            return out;
        }
        try {
            String raw = Settings.Global.getString(ctx.getContentResolver(), GLOBAL_KEY_CODEC);
            if (raw == null || raw.isEmpty()) {
                return out;
            }
            String[] parts = raw.split("\\|", -1);
            if (parts.length >= 4) {
                out.mac = parts[0].trim();
                out.activeCodec = Integer.parseInt(parts[1].trim());
                out.supportsLdac = "1".equals(parts[2].trim());
                out.supportsAac = "1".equals(parts[3].trim());
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static String codecDisplayName(int codecType) {
        switch (codecType) {
            case CODEC_LDAC:
                return "LDAC";
            case CODEC_AAC:
                return "AAC";
            case CODEC_SBC:
                return "SBC";
            case 2:
                return "aptX";
            case 3:
                return "aptX HD";
            case 18:
            case 19:
                return "LHDC";
            default:
                return "";
        }
    }

    public static void sendCodecCommandBroadcast(Context ctx, String mac, int codecType) {
        if (ctx == null) {
            return;
        }
        Intent intent = new Intent(ACTION_COMMAND);
        intent.setPackage("com.android.bluetooth");
        intent.putExtra(EXTRA_CMD, CMD_SET_CODEC);
        if (mac != null) {
            intent.putExtra(EXTRA_MAC, mac);
        }
        intent.putExtra(EXTRA_CODEC_TYPE, codecType);
        ctx.sendBroadcast(intent);
    }

    // ==================== Hook-side RemotePrefs Reader ====================

    public static final class HookConfig {
        private final XposedModule module;
        private SharedPreferences remotePrefs;
        private long lastRemoteInitAttemptMs;
        private long lastCheckUptimeMs;

        public volatile boolean bridgeEnabled = true;
        public volatile boolean vendorBypass = true;
        public volatile boolean myDevicesPanel = true;
        public volatile boolean nativeArtwork = true;
        public volatile boolean fluidCloudCapsule = true;
        public volatile boolean qsTile = true;
        public volatile boolean handover = true;
        public volatile boolean melodyChannel = true;
        public volatile boolean openCasePopup = true;
        public volatile int openCasePopupMode = POPUP_MODE_PAIRING_ONLY;
        public volatile boolean suppressNativePairing = true;
        public volatile int cycleMode = CYCLE_3WAY;
        public volatile int defaultAsmLevel = DEF_ASM_LEVEL;
        public volatile boolean defaultVoiceFocus = false;
        public volatile String customModelOverride = "";
        public volatile String customArtworkUri = "";
        public volatile boolean debugMode = false;

        public HookConfig(XposedModule module) {
            this.module = module;
            reload(true);
        }

        public synchronized void reload(boolean force) {
            long now = SystemClock.uptimeMillis();
            if (!force && lastCheckUptimeMs != 0L && (now - lastCheckUptimeMs) < RELOAD_THROTTLE_MS) {
                return;
            }
            lastCheckUptimeMs = now;
            try {
                if (remotePrefs == null && module != null
                        && (force || lastRemoteInitAttemptMs == 0L || (now - lastRemoteInitAttemptMs) >= REMOTE_RETRY_INTERVAL_MS)) {
                    lastRemoteInitAttemptMs = now;
                    SharedPreferences sp = module.getRemotePreferences(PREFS_NAME);
                    if (sp != null) {
                        remotePrefs = sp;
                        sp.registerOnSharedPreferenceChangeListener(new SharedPreferences.OnSharedPreferenceChangeListener() {
                            @Override
                            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                                reload(true);
                            }
                        });
                    }
                }
                if (remotePrefs == null || !hasAnySetting(remotePrefs)) {
                    return;
                }
                bridgeEnabled = remotePrefs.getBoolean(KEY_BRIDGE_ENABLED, true);
                vendorBypass = remotePrefs.getBoolean(KEY_VENDOR_BYPASS, true);
                myDevicesPanel = remotePrefs.getBoolean(KEY_MYDEVICES_PANEL, true);
                nativeArtwork = remotePrefs.getBoolean(KEY_NATIVE_ARTWORK, true);
                fluidCloudCapsule = remotePrefs.getBoolean(KEY_FLUID_CLOUD_CAPSULE, true);
                qsTile = remotePrefs.getBoolean(KEY_QS_TILE, true);
                handover = remotePrefs.getBoolean(KEY_HANDOVER, true);
                melodyChannel = remotePrefs.getBoolean(KEY_MELODY_CHANNEL, true);
                openCasePopup = remotePrefs.getBoolean(KEY_OPEN_CASE_POPUP, true);
                openCasePopupMode = normalizePopupMode(
                        remotePrefs.getInt(KEY_OPEN_CASE_POPUP_MODE, POPUP_MODE_PAIRING_ONLY));
                suppressNativePairing = remotePrefs.getBoolean(KEY_SUPPRESS_NATIVE_PAIRING, true);
                cycleMode = remotePrefs.getInt(KEY_CYCLE_MODE, CYCLE_3WAY);
                defaultAsmLevel = clampAsmLevel(remotePrefs.getInt(KEY_DEFAULT_ASM_LEVEL, DEF_ASM_LEVEL));
                defaultVoiceFocus = remotePrefs.getBoolean(KEY_DEFAULT_VOICE_FOCUS, false);
                customModelOverride = safeTrim(remotePrefs.getString(KEY_CUSTOM_MODEL_OVERRIDE, ""));
                customArtworkUri = safeTrim(remotePrefs.getString(KEY_CUSTOM_ARTWORK_URI, ""));
                debugMode = remotePrefs.getBoolean(KEY_DEBUG_MODE, false);
                XposedCompat.setVerboseDebug(debugMode);
            } catch (Throwable t) {
                if (force) {
                    XposedCompat.log("RemotePrefs reload failed: " + t.getMessage());
                }
            }
        }
    }
}
