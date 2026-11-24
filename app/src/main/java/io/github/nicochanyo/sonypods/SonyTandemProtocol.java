package io.github.nicochanyo.sonypods;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Sony Tandem V1/V2 Table 1 protocol builder and parser aligned with upstream SonyPods.
 * Directly updates {@link ConfigStore.HeadsetState}.
 */
public final class SonyTandemProtocol {
    private static final Map<ConfigStore.HeadsetState, Integer> CONFIRMED_NC_ASM_TYPES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ConfigStore.HeadsetState, int[]> V1_NC_ASM_TYPES =
            Collections.synchronizedMap(new WeakHashMap<>());
    public static final int PROTOCOL_V1 = 1;
    public static final int PROTOCOL_V2 = 2;

    public static final int ANC_OFF = ConfigStore.ANC_OFF;
    public static final int ANC_ON = ConfigStore.ANC_NC;
    public static final int ANC_AMBIENT = ConfigStore.ANC_AMBIENT;

    // Connect / Support / Device Info
    public static final int CMD_CONNECT_GET_PROTOCOL_INFO = 0x00;
    public static final int CMD_CONNECT_RET_PROTOCOL_INFO = 0x01;
    public static final int CMD_CONNECT_GET_DEVICE_INFO = 0x04;
    public static final int CMD_CONNECT_RET_DEVICE_INFO = 0x05;
    public static final int CMD_CONNECT_GET_SUPPORT_FUNCTION = 0x06;
    public static final int CMD_CONNECT_RET_SUPPORT_FUNCTION = 0x07;

    public static final int DEVICE_INFO_MODEL_NAME = 0x01;
    public static final int DEVICE_INFO_FW_VERSION = 0x02;
    public static final int DEVICE_INFO_SERIES_AND_COLOR = 0x03;
    public static final int DEVICE_INFO_INSTRUCTION_GUIDE = 0x04;

    // Battery: V1 = 0x10/0x11/0x13, V2 = 0x22/0x23/0x25
    public static final int CMD_V1_GET_BATTERY = 0x10;
    public static final int CMD_V1_RET_BATTERY = 0x11;
    public static final int CMD_V1_NTFY_BATTERY = 0x13;

    public static final int CMD_V2_POWER_GET_STATUS = 0x22;
    public static final int CMD_V2_POWER_RET_STATUS = 0x23;
    public static final int CMD_V2_POWER_NTFY_STATUS = 0x25;

    public static final int POWER_TYPE_BATTERY = 0x00;
    public static final int POWER_TYPE_LEFT_RIGHT_BATTERY = 0x01;
    public static final int POWER_TYPE_CRADLE_BATTERY = 0x02;
    public static final int POWER_TYPE_BATTERY_THRESHOLD = 0x08;
    public static final int POWER_TYPE_LEFT_RIGHT_THRESHOLD = 0x09;
    public static final int POWER_TYPE_CRADLE_THRESHOLD = 0x0A;

    // EQ / EBB
    public static final int CMD_EQEBB_GET_PARAM = 0x56;
    public static final int CMD_EQEBB_RET_PARAM = 0x57;
    public static final int CMD_EQEBB_SET_PARAM = 0x58;
    public static final int CMD_EQEBB_NTFY_PARAM = 0x59;
    public static final int V1_EQ_INQ_PRESET_EQ = 0x01;

    // Noise Cancelling / Ambient Sound Mode
    public static final int CMD_NCASM_GET_PARAM = 0x66;
    public static final int CMD_NCASM_RET_PARAM = 0x67;
    public static final int CMD_NCASM_SET_PARAM = 0x68;
    public static final int CMD_NCASM_NTFY_PARAM = 0x69;

    // V1 NcAsmInquiredType
    public static final int V1_INQ_NC = 0x01;
    public static final int V1_INQ_NC_ASM = 0x02;
    public static final int V1_INQ_ASM = 0x03;

    // V2 NcAsmInquiredType (Sony Tandem Table 1)
    public static final int V2_INQ_NC_ON_OFF = 0x01;
    public static final int V2_INQ_NC_ON_OFF_ASM_ON_OFF = 0x02;
    public static final int V2_INQ_NC_MODE_SWITCH_ASM_ON_OFF = 0x03;
    public static final int V2_INQ_NC_ON_OFF_ASM_SEAMLESS = 0x13;
    public static final int V2_INQ_NC_MODE_SWITCH_ASM_SEAMLESS = 0x14;
    public static final int V2_INQ_AUTO_SEAMLESS = 0x15; // WF-1000XM4
    public static final int V2_INQ_DUAL_SINGLE_NC_SEAMLESS = 0x16;
    public static final int V2_INQ_DUAL_NC_ASM_OFF_SEAMLESS = 0x17;
    public static final int V2_INQ_AUTO_ASM_OFF = 0x18; // WF-1000XM5
    public static final int V2_INQ_DUAL_NC_SEAMLESS_NA = 0x19;
    public static final int V2_INQ_ASM_ON_OFF = 0x21;
    public static final int V2_INQ_ASM_SEAMLESS = 0x22;
    public static final int V2_INQ_NC_AMB_TOGGLE = 0x30;

    public static final int[] V2_NC_ASM_PROBES = new int[] {
            V2_INQ_AUTO_SEAMLESS,
            V2_INQ_AUTO_ASM_OFF,
            V2_INQ_DUAL_NC_ASM_OFF_SEAMLESS,
            V2_INQ_NC_MODE_SWITCH_ASM_SEAMLESS,
            V2_INQ_DUAL_NC_SEAMLESS_NA,
            V2_INQ_DUAL_SINGLE_NC_SEAMLESS,
            V2_INQ_NC_ON_OFF_ASM_SEAMLESS,
            V2_INQ_NC_AMB_TOGGLE,
            V2_INQ_NC_MODE_SWITCH_ASM_ON_OFF,
            V2_INQ_NC_ON_OFF_ASM_ON_OFF,
            V2_INQ_NC_ON_OFF,
            V2_INQ_ASM_SEAMLESS,
            V2_INQ_ASM_ON_OFF
    };

    public static final int[] V1_NC_ASM_PROBES = new int[] {
            V1_INQ_NC_ASM,
            V1_INQ_NC,
            V1_INQ_ASM
    };

    // System Ext Param (Speak-to-Chat Detail: 0xFA..0xFD)
    public static final int CMD_SYSTEM_GET_EXT_PARAM = 0xFA;
    public static final int CMD_SYSTEM_RET_EXT_PARAM = 0xFB;
    public static final int CMD_SYSTEM_SET_EXT_PARAM = 0xFC;
    public static final int CMD_SYSTEM_NTFY_EXT_PARAM = 0xFD;

    // System InquiredTypes (0xF6..0xF9)
    public static final int SYS_INQ_PLAYBACK_CONTROL_BY_WEARING = 0x01;
    public static final int SYS_INQ_SMART_TALKING_V1 = 0x02;
    public static final int SYS_INQ_ASSIGNABLE_SETTINGS = 0x03;
    public static final int SYS_INQ_WEARING_STATUS_DETECTOR = 0x06;
    public static final int SYS_INQ_SMART_TALKING_V2 = 0x0C;
    public static final int SYS_INQ_ASSIGNABLE_SETTINGS_LIMITED = 0x0E;
    public static final int V1_SYS_INQ_CONTROL_BY_WEARING = 0x03;
    public static final int V1_SYS_INQ_AUTO_POWER_OFF = 0x04;
    public static final int V1_SYS_INQ_SMART_TALKING = 0x05;
    public static final int V1_SYS_INQ_ASSIGNABLE_SETTINGS = 0x06;

    // Power Param (Auto Power Off: 0x26..0x29)
    public static final int CMD_POWER_GET_PARAM = 0x26;
    public static final int CMD_POWER_RET_PARAM = 0x27;
    public static final int CMD_POWER_SET_PARAM = 0x28;
    public static final int CMD_POWER_NTFY_PARAM = 0x29;
    public static final int POWER_INQ_AUTO_POWER_OFF = 0x04;
    public static final int POWER_INQ_AUTO_POWER_OFF_WEARING = 0x05;

    // Audio InquiredTypes (0xE6..0xE9)
    public static final int AUDIO_INQ_CONNECTION_MODE = 0x00;
    public static final int AUDIO_INQ_UPSCALING = 0x01;
    public static final int AUDIO_INQ_CONNECTION_MODE_LDAC = 0x02;
    public static final int AUDIO_INQ_UPSCALING_WITH_REASON = 0x0B;
    public static final int V1_AUDIO_INQ_CONNECTION_MODE = 0x01;
    public static final int V1_AUDIO_INQ_UPSCALING = 0x02;

    // Touch Sensor Presets
    public static final int TOUCH_PRESET_AMBIENT = 0x00;
    public static final int TOUCH_PRESET_VOLUME = 0x10;
    public static final int TOUCH_PRESET_PLAYBACK = 0x20;
    public static final int TOUCH_PRESET_VOICE = 0x30;
    public static final int TOUCH_PRESET_QUICK_ACCESS = 0x36;
    public static final int TOUCH_PRESET_NONE = 0xFF;

    // Table 2 Peripheral (Multipoint / Paired Devices: 0x32..0x39 on DATA_TYPE_DATA_MDR_NO2)
    public static final int CMD_T2_PERI_GET_STATUS = 0x32;
    public static final int CMD_T2_PERI_RET_STATUS = 0x33;
    public static final int CMD_T2_PERI_SET_STATUS = 0x34;
    public static final int CMD_T2_PERI_NTFY_STATUS = 0x35;
    public static final int CMD_T2_PERI_GET_PARAM = 0x36;
    public static final int CMD_T2_PERI_RET_PARAM = 0x37;
    public static final int CMD_T2_PERI_SET_PARAM = 0x38;
    public static final int CMD_T2_PERI_NTFY_PARAM = 0x39;
    public static final int CMD_T2_PERI_SET_EXTENDED_PARAM = 0x3C;
    public static final int CMD_T2_PERI_NTFY_EXTENDED_PARAM = 0x3D;
    public static final int PERI_INQ_CLASSIC_BT = 0x00;
    public static final int PERI_INQ_WITH_COD = 0x02;
    public static final int PERI_ACTION_DISCONNECT = 0x00;
    public static final int PERI_ACTION_CONNECT = 0x01;
    public static final int PERI_ACTION_UNPAIR = 0x02;

    // Audio / DSEE Upscaling (0xE6..0xE9)
    public static final int CMD_AUDIO_GET_PARAM = 0xE6;
    public static final int CMD_AUDIO_RET_PARAM = 0xE7;
    public static final int CMD_AUDIO_SET_PARAM = 0xE8;
    public static final int CMD_AUDIO_NTFY_PARAM = 0xE9;

    // System Capability & System Param (0xF0..0xF9; inverted value: 0x00=ON, 0x01=OFF)
    public static final int CMD_SYSTEM_GET_CAPABILITY = 0xF0;
    public static final int CMD_SYSTEM_RET_CAPABILITY = 0xF1;
    public static final int CMD_SYSTEM_GET_PARAM = 0xF6;
    public static final int CMD_SYSTEM_RET_PARAM = 0xF7;
    public static final int CMD_SYSTEM_SET_PARAM = 0xF8;
    public static final int CMD_SYSTEM_NTFY_PARAM = 0xF9;

    // Alert (0x94..0x99: required for auto-confirming Multipoint / LDAC mode-switch prompts)
    public static final int CMD_ALERT_SET_STATUS = 0x94;
    public static final int CMD_ALERT_SET_PARAM = 0x98;
    public static final int CMD_ALERT_NTFY_PARAM = 0x99;
    public static final int ALERT_INQ_FIXED_MESSAGE = 0x00;
    public static final int ALERT_INQ_APP_BECOMES_FOREGROUND = 0x02;

    // General Setting (GS 0xD0..0xD9: slot 0xD1 = MULTIPOINT_SETTING on WF-1000XM4/XM5/WH-1000XM5)
    public static final int CMD_GS_GET_CAPABILITY = 0xD0;
    public static final int CMD_GS_RET_CAPABILITY = 0xD1;
    public static final int CMD_GS_GET_PARAM = 0xD6;
    public static final int CMD_GS_RET_PARAM = 0xD7;
    public static final int CMD_GS_SET_PARAM = 0xD8;
    public static final int CMD_GS_NTFY_PARAM = 0xD9;
    public static final int GS_INQ_MULTIPOINT = 0xD1;
    public static final int[] GS_SLOTS = new int[] { 0xD1, 0xD2, 0xD3, 0xD4 };

    private static volatile boolean sGsMultipointActive;
    private static volatile boolean sGsWearPauseActive;
    private static volatile int sGsMultipointSlot;
    private static volatile int sGsWearPauseSlot;

    private SonyTandemProtocol() {
    }

    public static void resetSessionCaches() {
        sGsMultipointActive = false;
        sGsWearPauseActive = false;
        sGsMultipointSlot = 0;
        sGsWearPauseSlot = 0;
        V1_NC_ASM_TYPES.clear();
    }

    public static int getObservedV1NcSettingType(ConfigStore.HeadsetState state) {
        int[] pair = (state != null) ? V1_NC_ASM_TYPES.get(state) : null;
        return (pair != null && pair.length >= 2 && pair[0] >= 0) ? pair[0] : 0x02;
    }

    public static int getObservedV1AsmSettingType(ConfigStore.HeadsetState state) {
        int[] pair = (state != null) ? V1_NC_ASM_TYPES.get(state) : null;
        return (pair != null && pair.length >= 2 && pair[1] >= 0) ? pair[1] : 0x01;
    }

    public static int getGsMultipointSlot() {
        return sGsMultipointSlot != 0 ? sGsMultipointSlot : GS_INQ_MULTIPOINT;
    }

    public static int getGsWearPauseSlot() {
        return sGsWearPauseSlot;
    }

    public static byte[] buildSetAlertStatus(int inquiredType, boolean enable) {
        return new byte[] {
                (byte) CMD_ALERT_SET_STATUS,
                (byte) inquiredType,
                (byte) (enable ? 0x00 : 0x01)
        };
    }

    public static byte[] buildReplyAlert(int inquiredType, int messageType, boolean positive) {
        return new byte[] {
                (byte) CMD_ALERT_SET_PARAM,
                (byte) inquiredType,
                (byte) messageType,
                (byte) (positive ? 0x01 : 0x00)
        };
    }

    public static byte[] buildGetGsCapability(int gsInquiredType) {
        return new byte[] { (byte) CMD_GS_GET_CAPABILITY, (byte) gsInquiredType, 0x01 };
    }

    public static byte[] buildGetGsParam(int gsInquiredType) {
        return new byte[] { (byte) CMD_GS_GET_PARAM, (byte) gsInquiredType };
    }

    public static byte[] buildSetGsBooleanParam(int gsInquiredType, boolean enabled) {
        // GS_SET_PARAM (0xD8): [0xD8, slot(0xD1), 0x00(BOOLEAN_TYPE), 0x00=ON / 0x01=OFF]
        return new byte[] {
                (byte) CMD_GS_SET_PARAM,
                (byte) gsInquiredType,
                0x00,
                (byte) (enabled ? 0x00 : 0x01)
        };
    }

    public static byte[] buildGetSystemCapability(int inquiredType) {
        return new byte[] { (byte) CMD_SYSTEM_GET_CAPABILITY, (byte) inquiredType };
    }

    public static byte[] buildGetProtocolInfo() {
        return new byte[] { CMD_CONNECT_GET_PROTOCOL_INFO, 0x00 };
    }

    public static byte[] buildGetSupportFunction() {
        return new byte[] { CMD_CONNECT_GET_SUPPORT_FUNCTION, 0x00 };
    }

    public static byte[] buildGetDeviceInfo(int infoType) {
        return new byte[] { CMD_CONNECT_GET_DEVICE_INFO, (byte) infoType };
    }

    public static byte[] buildGetBattery(int protocolVersion, int batteryType) {
        int cmd = (protocolVersion == PROTOCOL_V1) ? CMD_V1_GET_BATTERY : CMD_V2_POWER_GET_STATUS;
        return new byte[] { (byte) cmd, (byte) batteryType };
    }

    public static byte[] buildGetNcAsmParam(int inquiredType) {
        return new byte[] { CMD_NCASM_GET_PARAM, (byte) inquiredType };
    }

    public static byte[] buildGetSystemParam(int inquiredType) {
        return new byte[] { (byte) CMD_SYSTEM_GET_PARAM, (byte) inquiredType };
    }

    public static byte[] buildGetAudioParam(int inquiredType) {
        return new byte[] { (byte) CMD_AUDIO_GET_PARAM, (byte) inquiredType };
    }

    public static byte[] buildGetEqEbbParam(int inquiredType) {
        return new byte[] { CMD_EQEBB_GET_PARAM, (byte) inquiredType };
    }

    public static byte[] buildGetSystemExtParam(int inquiredType) {
        return new byte[] { (byte) CMD_SYSTEM_GET_EXT_PARAM, (byte) inquiredType };
    }

    public static byte[] buildGetPowerParam(int inquiredType) {
        return new byte[] { (byte) CMD_POWER_GET_PARAM, (byte) inquiredType };
    }

    public static byte[] buildT2GetPeripheralStatus(int inquiredType) {
        return new byte[] { (byte) CMD_T2_PERI_GET_STATUS, (byte) inquiredType };
    }

    public static byte[] buildT2GetPeripheralParam(int inquiredType) {
        return new byte[] { (byte) CMD_T2_PERI_GET_PARAM, (byte) inquiredType };
    }

    public static byte[] buildSetEqPreset(int inquiredType, int presetId, int[] bands) {
        int inq = (inquiredType >= 0) ? inquiredType : 0x00;
        boolean sendBands = bands != null && bands.length > 0
                && (presetId == 0xA0 || presetId == 0xA1 || presetId == 0xA2);
        if (!sendBands) {
            return new byte[] { (byte) CMD_EQEBB_SET_PARAM, (byte) inq, (byte) presetId, 0x00 };
        }
        int cnt = Math.min(8, bands.length);
        byte[] pkt = new byte[4 + cnt];
        pkt[0] = (byte) CMD_EQEBB_SET_PARAM;
        pkt[1] = (byte) inq;
        pkt[2] = (byte) presetId;
        pkt[3] = (byte) cnt;
        if (cnt == 6 && bands.length >= 6) {
            // Wire order for 6-band PRESET_EQ: [0]=Clear Bass (UI bands[5]), [1..5]=400Hz..16kHz (UI bands[0..4])
            pkt[4] = (byte) (ConfigStore.HeadsetState.clampEqBand(bands[5]) + 10);
            for (int i = 0; i < 5; i++) {
                pkt[5 + i] = (byte) (ConfigStore.HeadsetState.clampEqBand(bands[i]) + 10);
            }
        } else {
            for (int i = 0; i < cnt; i++) {
                pkt[4 + i] = (byte) (ConfigStore.HeadsetState.clampEqBand(bands[i]) + 10);
            }
        }
        return pkt;
    }

    public static byte[] buildSetSpeakToChatDetail(int inquiredType, int sensitivity, int modeOutTime) {
        return buildSetSpeakToChatDetail(PROTOCOL_V2, inquiredType, sensitivity, modeOutTime, false);
    }

    public static byte[] buildSetSpeakToChatDetail(
            int protocolVersion, int inquiredType, int sensitivity, int modeOutTime, boolean focusOnVoice) {
        int sens = Math.max(0, Math.min(2, sensitivity));
        int out = Math.max(0, Math.min(3, modeOutTime));
        if (protocolVersion == PROTOCOL_V1) {
            return new byte[] {
                    (byte) CMD_SYSTEM_SET_EXT_PARAM, (byte) V1_SYS_INQ_SMART_TALKING,
                    0x00, (byte) sens, (byte) (focusOnVoice ? 0x01 : 0x00), (byte) out
            };
        }
        if (inquiredType == SYS_INQ_SMART_TALKING_V2) {
            return new byte[] {
                    (byte) CMD_SYSTEM_SET_EXT_PARAM, (byte) SYS_INQ_SMART_TALKING_V2,
                    (byte) sens, (byte) out
            };
        }
        return new byte[] {
                (byte) CMD_SYSTEM_SET_EXT_PARAM, (byte) SYS_INQ_SMART_TALKING_V1,
                (byte) sens, 0x01, (byte) out
        };
    }

    public static byte[] buildSetWearDetect(boolean enabled) {
        return buildSetWearDetect(PROTOCOL_V2, enabled);
    }

    public static byte[] buildSetWearDetect(int protocolVersion, boolean enabled) {
        if (protocolVersion == PROTOCOL_V1) {
            return new byte[] {
                    (byte) CMD_SYSTEM_SET_PARAM, (byte) V1_SYS_INQ_CONTROL_BY_WEARING,
                    0x00, (byte) (enabled ? 0x01 : 0x00)
            };
        }
        if (sGsWearPauseSlot != 0) {
            return buildSetGsBooleanParam(sGsWearPauseSlot, enabled);
        }
        return new byte[] {
                (byte) CMD_SYSTEM_SET_PARAM, (byte) SYS_INQ_PLAYBACK_CONTROL_BY_WEARING,
                (byte) (enabled ? 0x00 : 0x01)
        };
    }

    public static byte[] buildSetConnectionMode(int inquiredType, int priorMode) {
        return buildSetConnectionMode(PROTOCOL_V2, inquiredType, priorMode);
    }

    public static byte[] buildSetConnectionMode(int protocolVersion, int inquiredType, int priorMode) {
        if (protocolVersion == PROTOCOL_V1) {
            int val = (priorMode == 1) ? 0x01 : 0x00;
            return new byte[] {
                    (byte) CMD_AUDIO_SET_PARAM, (byte) V1_AUDIO_INQ_CONNECTION_MODE,
                    0x00, (byte) val
            };
        }
        int inq = (inquiredType == AUDIO_INQ_CONNECTION_MODE_LDAC)
                ? AUDIO_INQ_CONNECTION_MODE_LDAC
                : AUDIO_INQ_CONNECTION_MODE;
        int mode = Math.max(0, Math.min(2, priorMode));
        return new byte[] { (byte) CMD_AUDIO_SET_PARAM, (byte) inq, (byte) mode };
    }

    public static byte[] buildSetAutoPowerOff(int inquiredType, int element) {
        return buildSetAutoPowerOff(PROTOCOL_V2, inquiredType, element);
    }

    public static byte[] buildSetAutoPowerOff(int protocolVersion, int inquiredType, int element) {
        if (protocolVersion == PROTOCOL_V1) {
            int selectElem = (element == 0x11 || element == 0x10) ? 0x00 : element;
            return new byte[] {
                    (byte) CMD_SYSTEM_SET_PARAM, (byte) V1_SYS_INQ_AUTO_POWER_OFF,
                    0x01, (byte) element, (byte) selectElem
            };
        }
        int inq = (inquiredType == POWER_INQ_AUTO_POWER_OFF)
                ? POWER_INQ_AUTO_POWER_OFF
                : POWER_INQ_AUTO_POWER_OFF_WEARING;
        int selectElem = (element == 0x11)
                ? (inq == POWER_INQ_AUTO_POWER_OFF_WEARING ? 0x10 : 0x00)
                : element;
        return new byte[] { (byte) CMD_POWER_SET_PARAM, (byte) inq, (byte) element, (byte) selectElem };
    }

    public static byte[] buildT2SetMultipoint(int inquiredType, boolean enabled) {
        int inq = (inquiredType == PERI_INQ_WITH_COD) ? PERI_INQ_WITH_COD : PERI_INQ_CLASSIC_BT;
        // [2]=PeripheralBluetoothMode.NORMAL_MODE (0x00), [3]=EnableDisable (0x00=ENABLE, 0x01=DISABLE)
        return new byte[] {
                (byte) CMD_T2_PERI_SET_STATUS, (byte) inq, 0x00, (byte) (enabled ? 0x00 : 0x01)
        };
    }

    public static byte[] buildT2PeripheralAction(int inquiredType, int actionType, String mac) {
        if (mac == null) return null;
        String cleanMac = mac.trim().toUpperCase(Locale.US);
        byte[] macBytes = cleanMac.getBytes(StandardCharsets.US_ASCII);
        if (macBytes.length != 17) return null;
        int inq = (inquiredType == PERI_INQ_WITH_COD) ? PERI_INQ_WITH_COD : PERI_INQ_CLASSIC_BT;
        byte[] pkt = new byte[3 + macBytes.length];
        pkt[0] = (byte) CMD_T2_PERI_SET_EXTENDED_PARAM;
        pkt[1] = (byte) inq;
        pkt[2] = (byte) actionType;
        System.arraycopy(macBytes, 0, pkt, 3, macBytes.length);
        return pkt;
    }

    public static byte[] buildSetTouchSensor(int inquiredType, int leftPreset, int rightPreset) {
        return buildSetTouchSensor(PROTOCOL_V2, inquiredType, leftPreset, rightPreset);
    }

    public static byte[] buildSetTouchSensor(int protocolVersion, int inquiredType, int leftPreset, int rightPreset) {
        if (protocolVersion == PROTOCOL_V1) {
            return new byte[] {
                    (byte) CMD_SYSTEM_SET_PARAM,
                    (byte) V1_SYS_INQ_ASSIGNABLE_SETTINGS,
                    0x02,
                    (byte) (leftPreset & 0xFF),
                    (byte) (rightPreset & 0xFF)
            };
        }
        int inq = (inquiredType == SYS_INQ_ASSIGNABLE_SETTINGS_LIMITED)
                ? SYS_INQ_ASSIGNABLE_SETTINGS_LIMITED
                : SYS_INQ_ASSIGNABLE_SETTINGS;
        return new byte[] {
                (byte) CMD_SYSTEM_SET_PARAM,
                (byte) inq,
                0x02,
                (byte) (leftPreset & 0xFF),
                (byte) (rightPreset & 0xFF)
        };
    }

    public static byte[] buildSetNcAsm(
            int protocolVersion,
            int inquiredType,
            int ancMode,
            int ambientLevel,
            boolean focusOnVoice
    ) {
        return buildSetNcAsm(protocolVersion, inquiredType, ancMode, ambientLevel, focusOnVoice, -1, 0x02, 0x01);
    }

    public static byte[] buildSetNcAsm(
            int protocolVersion,
            int inquiredType,
            int ancMode,
            int ambientLevel,
            boolean focusOnVoice,
            int observedNcValue
    ) {
        return buildSetNcAsm(protocolVersion, inquiredType, ancMode, ambientLevel, focusOnVoice, observedNcValue, 0x02, 0x01);
    }

    /**
     * Builds an exact V1 or V2 NCASM_SET_PARAM (0x68) payload matching Sony Sound Connect wire framing.
     */
    public static byte[] buildSetNcAsm(
            int protocolVersion,
            int inquiredType,
            int ancMode,
            int ambientLevel,
            boolean focusOnVoice,
            int observedNcValue,
            int v1NcSettingType,
            int v1AsmSettingType
    ) {
        int lvl = ConfigStore.clampAsmLevel(ambientLevel);
        int effect = (ancMode == ANC_OFF) ? 0x00 : 0x01;
        int mode = (ancMode == ANC_AMBIENT) ? 0x01 : 0x00;
        int asmMode = focusOnVoice ? 0x01 : 0x00;
        int ncOn = (ancMode == ANC_ON) ? 0x01 : 0x00;
        int asmOn = (ancMode == ANC_AMBIENT) ? 0x01 : 0x00;
        int ncVal = (ancMode == ANC_ON) ? 0x02 : 0x00;

        if (protocolVersion == PROTOCOL_V1) {
            int inq = (inquiredType == V1_INQ_NC || inquiredType == V1_INQ_NC_ASM || inquiredType == V1_INQ_ASM)
                    ? inquiredType : V1_INQ_NC_ASM;
            if (inq == V1_INQ_NC) {
                return new byte[] { CMD_NCASM_SET_PARAM, (byte) inq, (byte) ncOn, (byte) ncOn };
            }
            if (inq == V1_INQ_ASM) {
                return new byte[] { CMD_NCASM_SET_PARAM, (byte) inq, (byte) asmOn, (byte) asmMode, (byte) lvl };
            }
            byte eff = (ancMode == ANC_OFF) ? (byte) 0x00 : (byte) 0x11;
            int ncType = (v1NcSettingType >= 0) ? v1NcSettingType : 0x02;
            int v1NcVal = (ancMode == ANC_ON) ? (ncType == 0x02 ? 0x02 : 0x01) : 0x00;
            int asmType = (v1AsmSettingType >= 0) ? v1AsmSettingType : 0x01;
            int asmId = focusOnVoice ? 0x01 : 0x00;
            int asmAmt = (ancMode == ANC_AMBIENT) ? (asmType == 0x00 ? 0x01 : lvl) : 0x00;
            return new byte[] {
                    CMD_NCASM_SET_PARAM, V1_INQ_NC_ASM,
                    eff, (byte) ncType, (byte) v1NcVal, (byte) asmType, (byte) asmId, (byte) asmAmt
            };
        }

        int inq = (inquiredType > 0) ? inquiredType : V2_INQ_AUTO_SEAMLESS;
        switch (inq) {
            case V2_INQ_NC_ON_OFF:
                return new byte[] { CMD_NCASM_SET_PARAM, (byte) inq, 0x01, (byte) effect, (byte) ncOn };

            case V2_INQ_NC_ON_OFF_ASM_ON_OFF:
                return new byte[] {
                        CMD_NCASM_SET_PARAM, (byte) inq, 0x01, (byte) effect,
                        (byte) ncOn, (byte) asmMode, (byte) asmOn
                };

            case V2_INQ_NC_MODE_SWITCH_ASM_ON_OFF:
                return new byte[] {
                        CMD_NCASM_SET_PARAM, (byte) inq, 0x01, (byte) effect,
                        (byte) ncVal, (byte) asmMode, (byte) asmOn
                };

            case V2_INQ_NC_ON_OFF_ASM_SEAMLESS:
                return new byte[] {
                        CMD_NCASM_SET_PARAM, (byte) inq, 0x01, (byte) effect,
                        (byte) ncOn, (byte) asmMode, (byte) lvl
                };

            case V2_INQ_NC_MODE_SWITCH_ASM_SEAMLESS:
                return new byte[] {
                        CMD_NCASM_SET_PARAM, (byte) inq, 0x01, (byte) effect,
                        (byte) ncVal, (byte) asmMode, (byte) lvl
                };

            case V2_INQ_AUTO_SEAMLESS:
                // WF-1000XM4 (0x15): ncValue is ALWAYS 0x02 regardless of OFF/NC/ASM
                return new byte[] {
                        CMD_NCASM_SET_PARAM, (byte) inq, 0x01, (byte) effect,
                        (byte) mode, 0x02, (byte) asmMode, (byte) lvl
                };

            case V2_INQ_DUAL_SINGLE_NC_SEAMLESS:
                return new byte[] {
                        CMD_NCASM_SET_PARAM, (byte) inq, 0x01, (byte) effect,
                        (byte) mode, (byte) ncVal, (byte) asmMode, (byte) lvl
                };

            case V2_INQ_DUAL_NC_ASM_OFF_SEAMLESS:
            case V2_INQ_AUTO_ASM_OFF:
                // 0x17 and 0x18 (WF-1000XM5): 7 bytes, no ncVal byte
                return new byte[] {
                        CMD_NCASM_SET_PARAM, (byte) inq, 0x01, (byte) effect,
                        (byte) mode, (byte) asmMode, (byte) lvl
                };

            case V2_INQ_DUAL_NC_SEAMLESS_NA:
                return new byte[] {
                        CMD_NCASM_SET_PARAM, (byte) inq, 0x01, (byte) effect,
                        (byte) mode, (byte) asmMode, (byte) lvl, 0x00, 0x00
                };

            case V2_INQ_ASM_ON_OFF:
                return new byte[] {
                        CMD_NCASM_SET_PARAM, (byte) inq, 0x01, (byte) effect,
                        (byte) asmMode, (byte) asmOn
                };

            case V2_INQ_ASM_SEAMLESS:
                return new byte[] {
                        CMD_NCASM_SET_PARAM, (byte) inq, 0x01, (byte) effect,
                        (byte) asmMode, (byte) lvl
                };

            case V2_INQ_NC_AMB_TOGGLE: {
                int fn = (ancMode == ANC_ON) ? 0x04 : ((ancMode == ANC_AMBIENT) ? 0x03 : 0x01);
                return new byte[] { CMD_NCASM_SET_PARAM, (byte) inq, (byte) fn };
            }

            default:
                return new byte[] {
                        CMD_NCASM_SET_PARAM, (byte) inq, 0x01, (byte) effect,
                        (byte) mode, 0x02, (byte) asmMode, (byte) lvl
                };
        }
    }

    /** Speak-to-Chat (0xF8): V2 uses inverted 0x00=ON, V1 uses 0xF8 0x05 0x01 <00/01>. */
    public static byte[] buildSetSpeakToChat(int inquiredType, boolean enabled) {
        return buildSetSpeakToChat(PROTOCOL_V2, inquiredType, enabled);
    }

    public static byte[] buildSetSpeakToChat(int protocolVersion, int inquiredType, boolean enabled) {
        if (protocolVersion == PROTOCOL_V1) {
            return new byte[] {
                    (byte) CMD_SYSTEM_SET_PARAM,
                    (byte) V1_SYS_INQ_SMART_TALKING,
                    0x01,
                    (byte) (enabled ? 0x01 : 0x00)
            };
        }
        int inq = (inquiredType == SYS_INQ_SMART_TALKING_V1 || inquiredType == SYS_INQ_SMART_TALKING_V2)
                ? inquiredType
                : SYS_INQ_SMART_TALKING_V1;
        return new byte[] {
                (byte) CMD_SYSTEM_SET_PARAM,
                (byte) inq,
                (byte) (enabled ? 0x00 : 0x01),
                0x01
        };
    }

    /** DSEE / Upscaling (0xE8): V2 [0xE8, 0x01/0x0B, 0x01/0x00], V1 [0xE8, 0x02, 0x00, 0x01/0x00]. */
    public static byte[] buildSetDsee(int inquiredType, boolean enabled) {
        return buildSetDsee(PROTOCOL_V2, inquiredType, enabled);
    }

    public static byte[] buildSetDsee(int protocolVersion, int inquiredType, boolean enabled) {
        if (protocolVersion == PROTOCOL_V1) {
            return new byte[] {
                    (byte) CMD_AUDIO_SET_PARAM,
                    (byte) V1_AUDIO_INQ_UPSCALING,
                    0x00,
                    (byte) (enabled ? 0x01 : 0x00)
            };
        }
        int inq = (inquiredType == AUDIO_INQ_UPSCALING_WITH_REASON)
                ? AUDIO_INQ_UPSCALING_WITH_REASON
                : AUDIO_INQ_UPSCALING;
        return new byte[] {
                (byte) CMD_AUDIO_SET_PARAM,
                (byte) inq,
                (byte) (enabled ? 0x01 : 0x00)
        };
    }

    public static byte[] buildSetDsee(boolean enabled) {
        return buildSetDsee(PROTOCOL_V2, AUDIO_INQ_UPSCALING, enabled);
    }

    /** Decodes a Tandem DATA_MDR / DATA_MDR_NO2 payload into {@link ConfigStore.HeadsetState}. */
    public static boolean parsePayload(byte[] payload, ConfigStore.HeadsetState state) {
        if (payload == null || payload.length < 2 || state == null) {
            return false;
        }
        int cmd = payload[0] & 0xFF;
        switch (cmd) {
            case CMD_CONNECT_RET_PROTOCOL_INFO:
                return parseProtocolInfo(payload, state);
            case CMD_CONNECT_RET_SUPPORT_FUNCTION:
                return parseSupportFunction(payload, state);
            case CMD_CONNECT_RET_DEVICE_INFO:
                return parseDeviceInfo(payload, state);
            case CMD_V1_RET_BATTERY:
            case CMD_V1_NTFY_BATTERY:
                return (state.protocolVersion == PROTOCOL_V1) && parseBattery(payload, state);
            case CMD_V2_POWER_RET_STATUS:
            case CMD_V2_POWER_NTFY_STATUS:
                return (state.protocolVersion == PROTOCOL_V2) && parseBattery(payload, state);
            case CMD_NCASM_RET_PARAM:
            case CMD_NCASM_NTFY_PARAM:
                return parseNcAsm(payload, state);
            case CMD_SYSTEM_RET_PARAM:
            case CMD_SYSTEM_NTFY_PARAM:
                return parseSystemParam(payload, state);
            case CMD_SYSTEM_RET_EXT_PARAM:
            case CMD_SYSTEM_NTFY_EXT_PARAM:
                return parseSystemExtParam(payload, state);
            case CMD_AUDIO_RET_PARAM:
            case CMD_AUDIO_NTFY_PARAM:
                return parseAudioParam(payload, state);
            case CMD_EQEBB_RET_PARAM:
            case CMD_EQEBB_NTFY_PARAM:
                return parseEqEbb(payload, state);
            case CMD_POWER_RET_PARAM:
            case CMD_POWER_NTFY_PARAM:
                return (state.protocolVersion != PROTOCOL_V1) && parsePowerParam(payload, state);
            case CMD_GS_RET_CAPABILITY:
                return parseGsCapability(payload, state);
            case CMD_GS_RET_PARAM:
            case CMD_GS_NTFY_PARAM:
                return parseGsParam(payload, state);
            default:
                return false;
        }
    }

    /** Decodes Table 2 (DATA_MDR_NO2 = 0x0E) peripheral / multipoint responses. */
    public static boolean parseTable2Payload(byte[] payload, ConfigStore.HeadsetState state) {
        if (payload == null || payload.length < 3 || state == null) {
            return false;
        }
        int cmd = payload[0] & 0xFF;
        int inq = payload[1] & 0xFF;
        if ((cmd == CMD_T2_PERI_RET_STATUS || cmd == CMD_T2_PERI_NTFY_STATUS)
                && (inq == PERI_INQ_CLASSIC_BT || inq == PERI_INQ_WITH_COD)
                && payload.length >= 4) {
            // [cmd, inq, PeripheralBluetoothMode, EnableDisable(0x00=ENABLE, 0x01=DISABLE)]
            // On WF-1000XM4, byte[3] is peripheral list unlock status; GS 0xD1 owns multipointEnabled once active.
            boolean enabled = (payload[3] & 0xFF) == 0x00;
            boolean changed = !state.supportsMultipoint
                    || state.multipointInquiredType != inq
                    || (!sGsMultipointActive && state.multipointEnabled != enabled);
            state.supportsMultipoint = true;
            state.multipointInquiredType = inq;
            if (!sGsMultipointActive) {
                state.multipointEnabled = enabled;
            }
            if (changed) {
                state.lastEvent = String.format(Locale.US, "multipoint(0x%02X)=%s", inq, state.multipointEnabled);
                state.updatedAtMs = System.currentTimeMillis();
            }
            return changed;
        }
        if ((cmd == CMD_T2_PERI_RET_PARAM || cmd == CMD_T2_PERI_NTFY_PARAM)
                && (inq == PERI_INQ_CLASSIC_BT || inq == PERI_INQ_WITH_COD)) {
            int count = payload[2] & 0xFF;
            int idx = 3;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) {
                if (idx + 19 > payload.length) break;
                String devMac = new String(payload, idx, 17, StandardCharsets.US_ASCII).trim().toUpperCase(Locale.US);
                int connStatus = payload[idx + 17] & 0xFF;
                int nameLenIdx = (inq == PERI_INQ_WITH_COD) ? (idx + 21) : (idx + 18);
                int nameStartIdx = (inq == PERI_INQ_WITH_COD) ? (idx + 22) : (idx + 19);
                if (nameLenIdx >= payload.length) break;
                int nameLen = payload[nameLenIdx] & 0xFF;
                if (nameStartIdx + nameLen > payload.length) break;
                String devName = new String(payload, nameStartIdx, nameLen, StandardCharsets.UTF_8)
                        .replace('~', ' ').replace(';', ' ').replace('|', ' ').trim();
                idx = nameStartIdx + nameLen;
                if (sb.length() > 0) sb.append(';');
                sb.append(devMac).append('~').append(devName).append('~').append(connStatus != 0 ? '1' : '0');
            }
            String listStr = sb.toString();
            boolean changed = !state.supportsMultipoint
                    || state.multipointInquiredType != inq
                    || !listStr.equals(state.multipointDevices);
            state.supportsMultipoint = true;
            state.multipointInquiredType = inq;
            state.multipointDevices = listStr;
            if (changed) {
                state.lastEvent = "multiDevs=" + count;
                state.updatedAtMs = System.currentTimeMillis();
            }
            return changed;
        }
        if (cmd == CMD_T2_PERI_NTFY_EXTENDED_PARAM && payload.length >= 4) {
            int action = payload[2] & 0xFF;
            int result = payload[3] & 0xFF;
            String targetMac = (payload.length >= 21)
                    ? new String(payload, 4, 17, StandardCharsets.US_ASCII).trim().toUpperCase(Locale.US)
                    : "";
            XposedCompat.log(String.format(Locale.US,
                    "T2 peri ext result: inq=0x%02X action=0x%02X result=0x%02X mac=%s",
                    inq, action, result, targetMac));
            return false;
        }
        return false;
    }

    private static boolean parseProtocolInfo(byte[] payload, ConfigStore.HeadsetState state) {
        final int version;
        if (payload.length == 4) {
            // Sony Tandem V1 returns 4 bytes: [0x01, 0x00, vHi, vLo], e.g. 01 00 60 00 on WF-1000XM3
            version = PROTOCOL_V1;
        } else if (payload.length >= 6) {
            // Sony Tandem V2 returns 8 bytes: [0x01, 0x00, vHi, vLo, table1Mask(2B), table2Mask(2B)]
            version = PROTOCOL_V2;
        } else {
            return false;
        }
        if (state.protocolVersion != version) {
            state.protocolVersion = version;
            state.lastEvent = "proto=V" + version;
            state.updatedAtMs = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    private static boolean parseSupportFunction(byte[] payload, ConfigStore.HeadsetState state) {
        boolean changed = false;
        boolean isV1 = (state.protocolVersion == PROTOCOL_V1);
        if (payload.length >= 3) {
            for (int i = 3; i < payload.length; i++) {
                int fn = payload[i] & 0xFF;
                if (isV1) {
                    if (fn == 0x61 || fn == 0x62 || fn == 0x63) {
                        if (!state.supportsNcAsm) {
                            state.supportsNcAsm = true;
                            changed = true;
                        }
                    } else if (fn == 0xE2) {
                        if (!state.supportsDsee) {
                            state.supportsDsee = true;
                            changed = true;
                        }
                    } else if (fn == 0xF5) {
                        if (!state.supportsSpeakToChat) {
                            state.supportsSpeakToChat = true;
                            changed = true;
                        }
                    }
                } else {
                    if (fn == 0x61 || fn == 0x62 || fn == 0x63 || fn == 0x68) {
                        if (!state.supportsNcAsm) {
                            state.supportsNcAsm = true;
                            changed = true;
                        }
                    } else if (fn == 0x98 || fn == 0x9B || fn == 0xF8) {
                        if (!state.supportsSpeakToChat) {
                            state.supportsSpeakToChat = true;
                            changed = true;
                        }
                    } else if (fn == 0x78 || fn == 0xE8) {
                        if (!state.supportsDsee) {
                            state.supportsDsee = true;
                            changed = true;
                        }
                    }
                }
            }
        }
        if (changed) {
            state.updatedAtMs = System.currentTimeMillis();
        }
        return changed;
    }

    private static boolean parseDeviceInfo(byte[] payload, ConfigStore.HeadsetState state) {
        int infoType = payload[1] & 0xFF;
        if (infoType == DEVICE_INFO_MODEL_NAME && payload.length >= 3) {
            int len = payload[2] & 0xFF;
            int avail = Math.min(len, payload.length - 3);
            if (avail > 0) {
                String raw = new String(payload, 3, avail, StandardCharsets.UTF_8).trim();
                String normalized = SonyDeviceDetector.normalizeModelName(raw);
                if (!normalized.isEmpty() && !normalized.equals(state.modelName)) {
                    state.modelName = normalized;
                    if (state.name == null || state.name.isEmpty() || "Sony Headphones".equals(state.name)) {
                        state.name = normalized;
                    }
                    state.lastEvent = "model=" + normalized;
                    state.updatedAtMs = System.currentTimeMillis();
                    return true;
                }
            }
        } else if (infoType == DEVICE_INFO_SERIES_AND_COLOR && payload.length >= 4) {
            int series = payload[2] & 0xFF;
            int color = payload[3] & 0xFF;
            if (state.seriesCode != series || state.colorCode != color) {
                state.seriesCode = series;
                state.colorCode = color;
                state.lastEvent = String.format(Locale.US, "series=0x%02X color=0x%02X", series, color);
                state.updatedAtMs = System.currentTimeMillis();
                return true;
            }
        }
        return false;
    }

    private static boolean parseBattery(byte[] payload, ConfigStore.HeadsetState state) {
        int sub = payload[1] & 0xFF;
        boolean changed = false;
        if ((sub == POWER_TYPE_BATTERY || sub == POWER_TYPE_BATTERY_THRESHOLD) && payload.length >= 4) {
            int lvl = normalizeBattery(payload[2] & 0xFF);
            boolean chg = isChargingByte(payload[3] & 0xFF);
            if (lvl != state.singleBattery || chg != state.singleCharging) {
                state.singleBattery = lvl;
                state.singleCharging = chg;
                state.lastEvent = "batSingle=" + lvl + (chg ? "+" : "");
                changed = true;
            }
        } else if ((sub == POWER_TYPE_LEFT_RIGHT_BATTERY || sub == POWER_TYPE_LEFT_RIGHT_THRESHOLD) && payload.length >= 6) {
            int l = normalizeBattery(payload[2] & 0xFF);
            boolean lChg = isChargingByte(payload[3] & 0xFF);
            int r = normalizeBattery(payload[4] & 0xFF);
            boolean rChg = isChargingByte(payload[5] & 0xFF);
            if (l != state.leftBattery || lChg != state.leftCharging
                    || r != state.rightBattery || rChg != state.rightCharging) {
                state.leftBattery = l;
                state.leftCharging = lChg;
                state.rightBattery = r;
                state.rightCharging = rChg;
                state.lastEvent = "batDual L=" + l + (lChg ? "+" : "") + " R=" + r + (rChg ? "+" : "");
                changed = true;
            }
        } else if ((sub == POWER_TYPE_CRADLE_BATTERY || sub == POWER_TYPE_CRADLE_THRESHOLD) && payload.length >= 4) {
            int c = normalizeBattery(payload[2] & 0xFF);
            boolean cChg = isChargingByte(payload[3] & 0xFF);
            if (c < 0 && state.caseBattery > 0) {
                c = state.caseBattery;
                cChg = state.caseCharging;
            }
            if (c != state.caseBattery || cChg != state.caseCharging) {
                state.caseBattery = c;
                state.caseCharging = cChg;
                state.lastEvent = "batBox=" + c + (cChg ? "+" : "");
                changed = true;
            }
        }
        if (changed) {
            state.updatedAtMs = System.currentTimeMillis();
        }
        return changed;
    }

    /** True for V2 seamless Ambient Sound NC/ASM inquired types (0x13..0x19). */
    public static boolean isSeamlessNcAsmType(int inq) {
        return inq >= V2_INQ_NC_ON_OFF_ASM_SEAMLESS && inq <= V2_INQ_DUAL_NC_SEAMLESS_NA;
    }

    /** Minimum valid payload length for an NCASM_RET_PARAM / NCASM_NTFY_PARAM frame. */
    public static int minNcAsmPayloadLength(int protocolVersion, int inq) {
        if (protocolVersion == PROTOCOL_V1 && inq <= 0x03) {
            if (inq == V1_INQ_NC) return 4;
            if (inq == V1_INQ_ASM) return 5;
            return 6;
        }
        switch (inq) {
            case V2_INQ_AUTO_SEAMLESS:
            case V2_INQ_DUAL_SINGLE_NC_SEAMLESS:
                return 8;
            case V2_INQ_NC_ON_OFF_ASM_SEAMLESS:
            case V2_INQ_NC_MODE_SWITCH_ASM_SEAMLESS:
            case V2_INQ_DUAL_NC_ASM_OFF_SEAMLESS:
            case V2_INQ_AUTO_ASM_OFF:
            case V2_INQ_DUAL_NC_SEAMLESS_NA:
            case V2_INQ_NC_ON_OFF_ASM_ON_OFF:
            case V2_INQ_NC_MODE_SWITCH_ASM_ON_OFF:
                return 7;
            case V2_INQ_ASM_ON_OFF:
            case V2_INQ_ASM_SEAMLESS:
                return 6;
            case V2_INQ_NC_ON_OFF:
                return 5;
            case V2_INQ_NC_AMB_TOGGLE:
                return 3;
            default:
                return 8;
        }
    }

    private static boolean parseNcAsm(byte[] payload, ConfigStore.HeadsetState state) {
        int inq = payload[1] & 0xFF;
        if (state.protocolVersion == PROTOCOL_V1 && inq > V1_INQ_ASM) {
            return false;
        }
        if (payload.length < minNcAsmPayloadLength(state.protocolVersion, inq)) {
            return false;
        }
        boolean isNotify = ((payload[0] & 0xFF) == CMD_NCASM_NTFY_PARAM);
        Integer confirmedType = CONFIRMED_NC_ASM_TYPES.get(state);
        if (confirmedType != null && isSeamlessNcAsmType(confirmedType)) {
            // Never downgrade a discovered seamless NC/ASM type (e.g. 0x15) to 0x30 or late probes (e.g. 0x16)
            if (!isSeamlessNcAsmType(inq)) {
                return false;
            }
            if (!isNotify && inq != confirmedType) {
                return false;
            }
        }
        CONFIRMED_NC_ASM_TYPES.put(state, inq);
        boolean typeNewlyBound = (state.ncAsmInquiredType != inq) || !state.supportsNcAsm;
        state.ncAsmInquiredType = inq;
        state.supportsNcAsm = true;

        int newAncMode = state.ancMode;
        int newAsmLevel = state.asmLevel;
        boolean newVoice = state.voiceFocus;

        if (state.protocolVersion == PROTOCOL_V1 && inq <= 0x03 && payload.length >= 4) {
            if (inq == V1_INQ_NC) {
                newAncMode = ((payload[2] & 0xFF) != 0) ? ANC_ON : ANC_OFF;
            } else if (inq == V1_INQ_ASM && payload.length >= 5) {
                boolean on = (payload[2] & 0xFF) != 0;
                newAncMode = on ? ANC_AMBIENT : ANC_OFF;
                newVoice = (payload[3] & 0xFF) != 0;
                newAsmLevel = clampLevel(payload[4] & 0xFF, newAsmLevel);
            } else if (inq == V1_INQ_NC_ASM && payload.length >= 8) {
                int effect = payload[2] & 0xFF;
                int ncType = payload[3] & 0xFF;
                int ncVal = payload[4] & 0xFF;
                int asmType = payload[5] & 0xFF;
                newVoice = (payload[6] & 0xFF) == 0x01;
                int rawAsm = payload[7] & 0xFF;
                V1_NC_ASM_TYPES.put(state, new int[] { ncType, asmType });
                state.lastNcValue = ncVal;
                newAncMode = (effect == 0x00) ? ANC_OFF : (ncVal != 0 ? ANC_ON : ANC_AMBIENT);
                if (newAncMode == ANC_AMBIENT && asmType == 0x01) {
                    newAsmLevel = clampLevel(rawAsm, newAsmLevel);
                }
            } else if (inq == V1_INQ_NC_ASM && payload.length >= 6) {
                boolean on = (payload[2] & 0xFF) != 0;
                int mode = payload[3] & 0xFF;
                newVoice = (payload[4] & 0xFF) != 0;
                newAsmLevel = clampLevel(payload[5] & 0xFF, newAsmLevel);
                newAncMode = !on ? ANC_OFF : (mode == 1 ? ANC_AMBIENT : ANC_ON);
            }
        } else {
            switch (inq) {
                case V2_INQ_AUTO_SEAMLESS:
                case V2_INQ_DUAL_SINGLE_NC_SEAMLESS:
                    if (payload.length >= 8) {
                        boolean totalEffect = (payload[3] & 0xFF) != 0;
                        int mode = payload[4] & 0xFF;
                        state.lastNcValue = payload[5] & 0xFF;
                        newVoice = (payload[6] & 0xFF) != 0;
                        newAsmLevel = clampLevel(payload[7] & 0xFF, newAsmLevel);
                        newAncMode = !totalEffect ? ANC_OFF : (mode == 1 ? ANC_AMBIENT : ANC_ON);
                    }
                    break;

                case V2_INQ_DUAL_NC_ASM_OFF_SEAMLESS:
                case V2_INQ_AUTO_ASM_OFF:
                case V2_INQ_DUAL_NC_SEAMLESS_NA:
                    if (payload.length >= 7) {
                        boolean totalEffect = (payload[3] & 0xFF) != 0;
                        int mode = payload[4] & 0xFF;
                        newVoice = (payload[5] & 0xFF) != 0;
                        newAsmLevel = clampLevel(payload[6] & 0xFF, newAsmLevel);
                        newAncMode = !totalEffect ? ANC_OFF : (mode == 1 ? ANC_AMBIENT : ANC_ON);
                    }
                    break;

                case V2_INQ_NC_ON_OFF_ASM_SEAMLESS:
                case V2_INQ_NC_MODE_SWITCH_ASM_SEAMLESS:
                    if (payload.length >= 7) {
                        boolean totalEffect = (payload[3] & 0xFF) != 0;
                        int ncVal = payload[4] & 0xFF;
                        newVoice = (payload[5] & 0xFF) != 0;
                        newAsmLevel = clampLevel(payload[6] & 0xFF, newAsmLevel);
                        newAncMode = !totalEffect ? ANC_OFF : (ncVal != 0 ? ANC_ON : ANC_AMBIENT);
                    }
                    break;

                case V2_INQ_NC_ON_OFF_ASM_ON_OFF:
                case V2_INQ_NC_MODE_SWITCH_ASM_ON_OFF:
                    if (payload.length >= 7) {
                        int ncVal = payload[4] & 0xFF;
                        newVoice = (payload[5] & 0xFF) != 0;
                        int asmOn = payload[6] & 0xFF;
                        newAncMode = (ncVal != 0) ? ANC_ON : (asmOn != 0 ? ANC_AMBIENT : ANC_OFF);
                    }
                    break;

                case V2_INQ_ASM_ON_OFF:
                    if (payload.length >= 6) {
                        newVoice = (payload[4] & 0xFF) != 0;
                        int asmOn = payload[5] & 0xFF;
                        newAncMode = (asmOn != 0) ? ANC_AMBIENT : ANC_OFF;
                    }
                    break;

                case V2_INQ_ASM_SEAMLESS:
                    if (payload.length >= 6) {
                        boolean totalEffect = (payload[3] & 0xFF) != 0;
                        newVoice = (payload[4] & 0xFF) != 0;
                        newAsmLevel = clampLevel(payload[5] & 0xFF, newAsmLevel);
                        newAncMode = totalEffect ? ANC_AMBIENT : ANC_OFF;
                    }
                    break;

                case V2_INQ_NC_ON_OFF:
                    if (payload.length >= 5) {
                        newAncMode = ((payload[4] & 0xFF) != 0) ? ANC_ON : ANC_OFF;
                    }
                    break;

                case V2_INQ_NC_AMB_TOGGLE:
                    if (payload.length >= 3) {
                        int fn = payload[2] & 0xFF;
                        newAncMode = (fn == 0x04) ? ANC_ON : (fn == 0x03 ? ANC_AMBIENT : ANC_OFF);
                    }
                    break;

                default:
                    if (payload.length >= 8) {
                        boolean totalEffect = (payload[3] & 0xFF) != 0;
                        int mode = payload[4] & 0xFF;
                        newVoice = (payload[6] & 0xFF) != 0;
                        newAsmLevel = clampLevel(payload[7] & 0xFF, newAsmLevel);
                        newAncMode = !totalEffect ? ANC_OFF : (mode == 1 ? ANC_AMBIENT : ANC_ON);
                    }
                    break;
            }
        }

        boolean changed = typeNewlyBound
                || newAncMode != state.ancMode
                || newAsmLevel != state.asmLevel
                || newVoice != state.voiceFocus;
        state.ancMode = newAncMode;
        state.asmLevel = newAsmLevel;
        state.voiceFocus = newVoice;
        state.lastEvent = String.format(Locale.US, "ncAsm(0x%02X)=%d asm=%d", inq, newAncMode, newAsmLevel);
        if (changed) {
            state.updatedAtMs = System.currentTimeMillis();
        }
        return changed;
    }

    private static boolean parseSystemParam(byte[] payload, ConfigStore.HeadsetState state) {
        int inq = payload[1] & 0xFF;
        if (state.protocolVersion == PROTOCOL_V1) {
            if (inq == V1_SYS_INQ_CONTROL_BY_WEARING && payload.length == 4) {
                int raw = payload[3] & 0xFF;
                if (raw > 0x01) {
                    return false;
                }
                boolean enabled = (raw == 0x01);
                boolean changed = !state.supportsWearDetect || enabled != state.wearAutoPause;
                state.supportsWearDetect = true;
                state.wearAutoPause = enabled;
                if (changed) {
                    state.lastEvent = "wearDetectV1=" + enabled;
                    state.updatedAtMs = System.currentTimeMillis();
                }
                return changed;
            }
            if (inq == V1_SYS_INQ_AUTO_POWER_OFF && payload.length == 5) {
                int elem = payload[3] & 0xFF;
                int resolvedInq = (elem == 0x10 || !SonyDeviceDetector.isOverEarModel(state.effectiveModelName()))
                        ? POWER_INQ_AUTO_POWER_OFF_WEARING
                        : POWER_INQ_AUTO_POWER_OFF;
                boolean changed = !state.supportsAutoPowerOff
                        || state.autoPowerOffInquiredType != resolvedInq
                        || elem != state.autoPowerOffElement;
                state.supportsAutoPowerOff = true;
                state.autoPowerOffInquiredType = resolvedInq;
                state.autoPowerOffElement = elem;
                if (changed) {
                    state.lastEvent = String.format(Locale.US, "autoOffV1=0x%02X", elem);
                    state.updatedAtMs = System.currentTimeMillis();
                }
                return changed;
            }
            if (inq == V1_SYS_INQ_SMART_TALKING && payload.length == 4 && (payload[2] & 0xFF) == 0x01) {
                int raw = payload[3] & 0xFF;
                if (raw > 0x01) {
                    return false;
                }
                boolean enabled = (raw == 0x01);
                boolean newlyBound = !state.supportsSpeakToChat
                        || state.speakToChatInquiredType != V1_SYS_INQ_SMART_TALKING;
                state.supportsSpeakToChat = true;
                state.speakToChatInquiredType = V1_SYS_INQ_SMART_TALKING;
                boolean changed = newlyBound || enabled != state.speakToChat;
                state.speakToChat = enabled;
                state.lastEvent = "speakV1=" + enabled;
                if (changed) {
                    state.updatedAtMs = System.currentTimeMillis();
                }
                return changed;
            }
            if (inq == V1_SYS_INQ_ASSIGNABLE_SETTINGS && payload.length >= 5) {
                int count = payload[2] & 0xFF;
                if (count != 2 || payload.length != 3 + count) {
                    return false;
                }
                int leftP = payload[3] & 0xFF;
                int rightP = payload[4] & 0xFF;
                if (leftP == 0xFE || rightP == 0xFE) {
                    return false;
                }
                boolean changed = !state.supportsTouchSensor
                        || state.touchInquiredType != V1_SYS_INQ_ASSIGNABLE_SETTINGS
                        || leftP != state.touchLeftPreset
                        || rightP != state.touchRightPreset;
                state.supportsTouchSensor = true;
                state.touchInquiredType = V1_SYS_INQ_ASSIGNABLE_SETTINGS;
                state.touchLeftPreset = leftP;
                state.touchRightPreset = rightP;
                if (changed) {
                    state.lastEvent = String.format(Locale.US, "touchV1=0x%02X/0x%02X", leftP, rightP);
                    state.updatedAtMs = System.currentTimeMillis();
                }
                return changed;
            }
            return false;
        }
        if ((inq == SYS_INQ_SMART_TALKING_V1 || inq == SYS_INQ_SMART_TALKING_V2) && payload.length >= 3) {
            if (state.supportsSpeakToChat
                    && state.speakToChatInquiredType == SYS_INQ_SMART_TALKING_V2
                    && inq == SYS_INQ_SMART_TALKING_V1) {
                return false;
            }
            int raw = payload[2] & 0xFF;
            if (raw > 0x01) {
                return false;
            }
            boolean newlyBound = !state.supportsSpeakToChat || state.speakToChatInquiredType != inq;
            state.speakToChatInquiredType = inq;
            state.supportsSpeakToChat = true;
            // Inverted Sony wire convention: 0x00=ON, 0x01=OFF
            boolean enabled = raw == 0x00;
            boolean changed = newlyBound || enabled != state.speakToChat;
            state.speakToChat = enabled;
            state.lastEvent = String.format(Locale.US, "speak(0x%02X)=%s", inq, enabled);
            if (changed) {
                state.updatedAtMs = System.currentTimeMillis();
            }
            return changed;
        }
        if ((inq == SYS_INQ_ASSIGNABLE_SETTINGS || inq == SYS_INQ_ASSIGNABLE_SETTINGS_LIMITED)
                && payload.length >= 4) {
            if (state.supportsTouchSensor
                    && state.touchInquiredType == SYS_INQ_ASSIGNABLE_SETTINGS
                    && inq == SYS_INQ_ASSIGNABLE_SETTINGS_LIMITED) {
                return false;
            }
            int count = payload[2] & 0xFF;
            if (count < 1 || payload.length != 3 + count) {
                return false;
            }
            int leftP = payload[3] & 0xFF;
            int rightP = (count >= 2) ? (payload[4] & 0xFF) : state.touchRightPreset;
            if (leftP == 0xFE || rightP == 0xFE) {
                return false;
            }
            boolean changed = !state.supportsTouchSensor
                    || state.touchInquiredType != inq
                    || leftP != state.touchLeftPreset
                    || rightP != state.touchRightPreset;
            state.supportsTouchSensor = true;
            state.touchInquiredType = inq;
            state.touchLeftPreset = leftP;
            state.touchRightPreset = rightP;
            if (changed) {
                state.lastEvent = String.format(Locale.US, "touch=0x%02X/0x%02X", leftP, rightP);
                state.updatedAtMs = System.currentTimeMillis();
            }
            return changed;
        }
        if (inq == SYS_INQ_PLAYBACK_CONTROL_BY_WEARING && payload.length == 3 && !sGsWearPauseActive) {
            int raw = payload[2] & 0xFF;
            if (raw > 0x01) {
                return false;
            }
            boolean enabled = raw == 0x00;
            boolean changed = !state.supportsWearDetect || enabled != state.wearAutoPause;
            state.supportsWearDetect = true;
            state.wearAutoPause = enabled;
            if (changed) {
                state.lastEvent = "wearDetect=" + enabled;
                state.updatedAtMs = System.currentTimeMillis();
            }
            return changed;
        }
        return false;
    }

    private static boolean parseSystemExtParam(byte[] payload, ConfigStore.HeadsetState state) {
        int inq = payload[1] & 0xFF;
        if (state.protocolVersion == PROTOCOL_V1) {
            if (inq == V1_SYS_INQ_SMART_TALKING && payload.length >= 6) {
                int sens = payload[3] & 0xFF;
                int out = payload[5] & 0xFF;
                if (sens > 2 || out > 3) {
                    return false;
                }
                boolean changed = !state.supportsSpeakToChat
                        || state.speakToChatInquiredType != inq
                        || sens != state.speakSensitivity
                        || out != state.speakTimeout;
                state.speakToChatInquiredType = inq;
                state.supportsSpeakToChat = true;
                state.speakSensitivity = sens;
                state.speakTimeout = out;
                if (changed) {
                    state.lastEvent = "speakExtV1=" + sens + "/" + out;
                    state.updatedAtMs = System.currentTimeMillis();
                }
                return changed;
            }
            return false;
        }
        if (inq == SYS_INQ_SMART_TALKING_V2 && payload.length >= 4) {
            int sens = payload[2] & 0xFF;
            int out = payload[3] & 0xFF;
            if (sens > 2 || out > 3) {
                return false;
            }
            boolean changed = sens != state.speakSensitivity || out != state.speakTimeout;
            state.speakToChatInquiredType = inq;
            state.supportsSpeakToChat = true;
            state.speakSensitivity = sens;
            state.speakTimeout = out;
            if (changed) {
                state.lastEvent = "speakExt=" + sens + "/" + out;
                state.updatedAtMs = System.currentTimeMillis();
            }
            return changed;
        }
        if (inq == SYS_INQ_SMART_TALKING_V1 && payload.length >= 5) {
            if (state.supportsSpeakToChat
                    && state.speakToChatInquiredType == SYS_INQ_SMART_TALKING_V2) {
                return false;
            }
            int sens = payload[2] & 0xFF;
            int out = payload[4] & 0xFF;
            if (sens > 2 || out > 3) {
                return false;
            }
            boolean changed = sens != state.speakSensitivity || out != state.speakTimeout;
            state.speakToChatInquiredType = inq;
            state.supportsSpeakToChat = true;
            state.speakSensitivity = sens;
            state.speakTimeout = out;
            if (changed) {
                state.lastEvent = "speakExt1=" + sens + "/" + out;
                state.updatedAtMs = System.currentTimeMillis();
            }
            return changed;
        }
        return false;
    }

    private static boolean parseAudioParam(byte[] payload, ConfigStore.HeadsetState state) {
        int inq = payload[1] & 0xFF;
        if (state.protocolVersion == PROTOCOL_V1) {
            if (inq == V1_AUDIO_INQ_UPSCALING && payload.length == 4) {
                int raw = payload[3] & 0xFF;
                if (raw > 0x01) {
                    return false;
                }
                boolean newlyBound = !state.supportsDsee;
                state.supportsDsee = true;
                boolean enabled = (raw == 0x01);
                boolean changed = newlyBound || enabled != state.dsee;
                state.dsee = enabled;
                state.lastEvent = String.format(Locale.US, "dseeV1(0x%02X)=%s", inq, enabled);
                if (changed) {
                    state.updatedAtMs = System.currentTimeMillis();
                }
                return changed;
            }
            if (inq == V1_AUDIO_INQ_CONNECTION_MODE && payload.length == 4) {
                int prior = payload[3] & 0xFF;
                if (prior > 0x01) {
                    return false;
                }
                boolean changed = !state.supportsConnectionMode
                        || state.connectionModeInquiredType != V1_AUDIO_INQ_CONNECTION_MODE
                        || prior != state.connectionPriorMode;
                state.supportsConnectionMode = true;
                state.connectionModeInquiredType = V1_AUDIO_INQ_CONNECTION_MODE;
                state.connectionPriorMode = prior;
                if (changed) {
                    state.lastEvent = "priorModeV1=" + prior;
                    state.updatedAtMs = System.currentTimeMillis();
                }
                return changed;
            }
            return false;
        }
        if ((inq == AUDIO_INQ_UPSCALING || inq == AUDIO_INQ_UPSCALING_WITH_REASON) && payload.length >= 3) {
            int raw = payload[2] & 0xFF;
            if (raw > 0x01) {
                return false;
            }
            boolean newlyBound = !state.supportsDsee;
            state.supportsDsee = true;
            boolean enabled = raw != 0;
            boolean changed = newlyBound || enabled != state.dsee;
            state.dsee = enabled;
            state.lastEvent = String.format(Locale.US, "dsee(0x%02X)=%s", inq, enabled);
            if (changed) {
                state.updatedAtMs = System.currentTimeMillis();
            }
            return changed;
        }
        if ((inq == AUDIO_INQ_CONNECTION_MODE || inq == AUDIO_INQ_CONNECTION_MODE_LDAC) && payload.length >= 3) {
            if (state.supportsConnectionMode
                    && state.connectionModeInquiredType == AUDIO_INQ_CONNECTION_MODE_LDAC
                    && inq == AUDIO_INQ_CONNECTION_MODE) {
                return false;
            }
            int prior = payload[2] & 0xFF;
            if (prior > 2) {
                return false;
            }
            boolean changed = !state.supportsConnectionMode
                    || state.connectionModeInquiredType != inq
                    || prior != state.connectionPriorMode;
            state.supportsConnectionMode = true;
            state.connectionModeInquiredType = inq;
            state.connectionPriorMode = prior;
            if (changed) {
                state.lastEvent = "priorMode=" + prior;
                state.updatedAtMs = System.currentTimeMillis();
            }
            return changed;
        }
        return false;
    }

    private static boolean parseGsCapability(byte[] payload, ConfigStore.HeadsetState state) {
        if (payload.length < 5) {
            return false;
        }
        int slot = payload[1] & 0xFF;
        int settingType = payload[2] & 0xFF;
        int stringFormat = payload[3] & 0xFF;
        int titleLen = payload[4] & 0xFF;
        if (payload.length < 5 + titleLen) {
            return false;
        }
        String title = new String(payload, 5, titleLen, StandardCharsets.UTF_8).trim();
        XposedCompat.log(String.format(Locale.US, "GS cap slot=0x%02X type=%d fmt=%d title=%s",
                slot, settingType, stringFormat, title));
        if (settingType == 0x00) {
            String upper = title.toUpperCase(Locale.US);
            if (upper.contains("MULTIPOINT") || upper.contains("2 DEVICES")) {
                sGsMultipointSlot = slot;
                boolean changed = !state.supportsMultipoint;
                state.supportsMultipoint = true;
                if (changed) {
                    state.updatedAtMs = System.currentTimeMillis();
                }
                return changed;
            }
            if (upper.contains("WEARING") || upper.contains("TAKEN_OFF")
                    || upper.contains("TAKEN OFF") || upper.contains("PAUSE")) {
                sGsWearPauseSlot = slot;
                boolean changed = !state.supportsWearDetect;
                state.supportsWearDetect = true;
                if (changed) {
                    state.updatedAtMs = System.currentTimeMillis();
                }
                return changed;
            }
        }
        return false;
    }

    private static boolean parseGsParam(byte[] payload, ConfigStore.HeadsetState state) {
        if (payload.length < 4 || (payload[2] & 0xFF) != 0x00) {
            return false;
        }
        int raw = payload[3] & 0xFF;
        if (raw > 0x01) {
            return false;
        }
        int slot = payload[1] & 0xFF;
        boolean enabled = (raw == 0x00);
        if ((sGsMultipointSlot != 0 && slot == sGsMultipointSlot)
                || (sGsMultipointSlot == 0 && slot == GS_INQ_MULTIPOINT)) {
            sGsMultipointActive = true;
            boolean changed = !state.supportsMultipoint || state.multipointEnabled != enabled;
            state.supportsMultipoint = true;
            state.multipointEnabled = enabled;
            if (changed) {
                state.lastEvent = "multipointGs=" + enabled;
                state.updatedAtMs = System.currentTimeMillis();
            }
            return changed;
        }
        if (sGsWearPauseSlot != 0 && slot == sGsWearPauseSlot) {
            sGsWearPauseActive = true;
            boolean changed = !state.supportsWearDetect || state.wearAutoPause != enabled;
            state.supportsWearDetect = true;
            state.wearAutoPause = enabled;
            if (changed) {
                state.lastEvent = "wearPauseGs=" + enabled;
                state.updatedAtMs = System.currentTimeMillis();
            }
            return changed;
        }
        return false;
    }

    private static boolean parsePowerParam(byte[] payload, ConfigStore.HeadsetState state) {
        int inq = payload[1] & 0xFF;
        if ((inq == POWER_INQ_AUTO_POWER_OFF || inq == POWER_INQ_AUTO_POWER_OFF_WEARING)
                && payload.length >= 3) {
            if (state.supportsAutoPowerOff
                    && state.autoPowerOffInquiredType == POWER_INQ_AUTO_POWER_OFF_WEARING
                    && inq == POWER_INQ_AUTO_POWER_OFF) {
                return false;
            }
            int elem = payload[2] & 0xFF;
            boolean changed = !state.supportsAutoPowerOff
                    || state.autoPowerOffInquiredType != inq
                    || elem != state.autoPowerOffElement;
            state.supportsAutoPowerOff = true;
            state.autoPowerOffInquiredType = inq;
            state.autoPowerOffElement = elem;
            if (changed) {
                state.lastEvent = String.format(Locale.US, "autoOff(0x%02X)=0x%02X", inq, elem);
                state.updatedAtMs = System.currentTimeMillis();
            }
            return changed;
        }
        return false;
    }

    private static boolean parseEqEbb(byte[] payload, ConfigStore.HeadsetState state) {
        int inq = payload[1] & 0xFF;
        if ((inq == 0x00 || inq == V1_EQ_INQ_PRESET_EQ || inq == 0x02 || inq == 0x04) && payload.length >= 3) {
            int presetId = payload[2] & 0xFF;
            String name = mapEqPresetName(presetId);
            boolean changed = !state.supportsEq
                    || state.eqInquiredType != inq
                    || state.eqPresetId != presetId
                    || !name.equals(state.eqPresetName);
            state.supportsEq = true;
            state.eqInquiredType = inq;
            state.eqPresetId = presetId;
            state.eqPresetName = name;
            if (payload.length >= 4) {
                int cnt = payload[3] & 0xFF;
                if (cnt > 0 && payload.length >= 4 + cnt) {
                    int n = Math.min(state.eqBands.length, cnt);
                    state.eqBandCount = n;
                    if (cnt == 6 && state.eqBands.length >= 6) {
                        // Wire order for 6-band PRESET_EQ: [0]=Clear Bass (UI index 5), [1..5]=400Hz..16kHz (UI indices 0..4)
                        int clearBass = ConfigStore.HeadsetState.clampEqBand((payload[4] & 0xFF) - 10);
                        for (int i = 0; i < 5; i++) {
                            int val = ConfigStore.HeadsetState.clampEqBand((payload[5 + i] & 0xFF) - 10);
                            if (state.eqBands[i] != val) {
                                state.eqBands[i] = val;
                                changed = true;
                            }
                        }
                        if (state.eqBands[5] != clearBass) {
                            state.eqBands[5] = clearBass;
                            changed = true;
                        }
                    } else {
                        for (int i = 0; i < n; i++) {
                            int val = ConfigStore.HeadsetState.clampEqBand((payload[4 + i] & 0xFF) - 10);
                            if (state.eqBands[i] != val) {
                                state.eqBands[i] = val;
                                changed = true;
                            }
                        }
                    }
                }
            }
            if (changed) {
                state.lastEvent = "eq=" + name;
                state.updatedAtMs = System.currentTimeMillis();
            }
            return changed;
        }
        return false;
    }

    private static String mapEqPresetName(int id) {
        switch (id) {
            case 0x00: return "Off";
            case 0x01: return "Rock";
            case 0x02: return "Pop";
            case 0x03: return "Jazz";
            case 0x04: return "Dance";
            case 0x05: return "EDM";
            case 0x10: return "Bright";
            case 0x11: return "Excited";
            case 0x12: return "Mellow";
            case 0x13: return "Relaxed";
            case 0x14: return "Vocal";
            case 0x15: return "Treble Boost";
            case 0x16: return "Bass Boost";
            case 0x17: return "Speech";
            case 0x30: return "Heavy";
            case 0x31: return "Clear";
            case 0x32: return "Hard";
            case 0x33: return "Soft";
            case 0xA0: return "Custom";
            case 0xA1: return "User 1";
            case 0xA2: return "User 2";
            default: return "Preset #" + id;
        }
    }

    private static int normalizeBattery(int raw) {
        return (raw >= 1 && raw <= 100) ? raw : -1;
    }

    private static boolean isChargingByte(int raw) {
        return raw == 0x01;
    }

    private static int clampLevel(int raw, int fallback) {
        return (raw >= 1 && raw <= 20) ? raw : fallback;
    }

    public static String toHex(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }
}
