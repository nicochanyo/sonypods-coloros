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

    // Audio / DSEE Upscaling (0xE6..0xE9)
    public static final int CMD_AUDIO_GET_PARAM = 0xE6;
    public static final int CMD_AUDIO_RET_PARAM = 0xE7;
    public static final int CMD_AUDIO_SET_PARAM = 0xE8;
    public static final int CMD_AUDIO_NTFY_PARAM = 0xE9;
    public static final int AUDIO_INQ_UPSCALING = 0x01;
    public static final int AUDIO_INQ_UPSCALING_WITH_REASON = 0x0B;

    // System / Speak-to-Chat (0xF6..0xF9; inverted value: 0x00=ON, 0x01=OFF)
    public static final int CMD_SYSTEM_GET_PARAM = 0xF6;
    public static final int CMD_SYSTEM_RET_PARAM = 0xF7;
    public static final int CMD_SYSTEM_SET_PARAM = 0xF8;
    public static final int CMD_SYSTEM_NTFY_PARAM = 0xF9;
    public static final int SYS_INQ_SMART_TALKING_V1 = 0x02;
    public static final int SYS_INQ_SMART_TALKING_V2 = 0x0C;

    private SonyTandemProtocol() {
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

    public static byte[] buildSetNcAsm(
            int protocolVersion,
            int inquiredType,
            int ancMode,
            int ambientLevel,
            boolean focusOnVoice
    ) {
        return buildSetNcAsm(protocolVersion, inquiredType, ancMode, ambientLevel, focusOnVoice, -1);
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
            int observedNcValue
    ) {
        int lvl = ConfigStore.clampAsmLevel(ambientLevel);
        int effect = (ancMode == ANC_OFF) ? 0x00 : 0x01;
        int mode = (ancMode == ANC_AMBIENT) ? 0x01 : 0x00;
        int asmMode = focusOnVoice ? 0x01 : 0x00;
        int ncOn = (ancMode == ANC_ON) ? 0x01 : 0x00;
        int asmOn = (ancMode == ANC_AMBIENT) ? 0x01 : 0x00;
        int ncVal = (ancMode == ANC_ON) ? 0x02 : 0x00;

        if (protocolVersion == PROTOCOL_V1) {
            int inq = (inquiredType > 0) ? inquiredType : V1_INQ_NC_ASM;
            if (inq == V1_INQ_NC) {
                return new byte[] { CMD_NCASM_SET_PARAM, (byte) inq, (byte) ncOn, (byte) ncOn };
            }
            if (inq == V1_INQ_ASM) {
                return new byte[] { CMD_NCASM_SET_PARAM, (byte) inq, (byte) asmOn, (byte) asmMode, (byte) lvl };
            }
            return new byte[] {
                    CMD_NCASM_SET_PARAM, V1_INQ_NC_ASM,
                    (byte) effect, (byte) mode, (byte) asmMode, (byte) lvl
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

    /** Speak-to-Chat (0xF8): value is inverted (0x00=ON, 0x01=OFF), trailing 0x01 fixed. */
    public static byte[] buildSetSpeakToChat(int inquiredType, boolean enabled) {
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

    /** DSEE / Upscaling (0xE8): [0xE8, inq(0x01 or 0x0B), 0x01=AUTO / 0x00=OFF]. */
    public static byte[] buildSetDsee(int inquiredType, boolean enabled) {
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
        return buildSetDsee(AUDIO_INQ_UPSCALING, enabled);
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
            case CMD_V2_POWER_RET_STATUS:
            case CMD_V2_POWER_NTFY_STATUS:
                return parseBattery(payload, state);
            case CMD_NCASM_RET_PARAM:
            case CMD_NCASM_NTFY_PARAM:
                return parseNcAsm(payload, state);
            case CMD_SYSTEM_RET_PARAM:
            case CMD_SYSTEM_NTFY_PARAM:
                return parseSystemParam(payload, state);
            case CMD_AUDIO_RET_PARAM:
            case CMD_AUDIO_NTFY_PARAM:
                return parseAudioParam(payload, state);
            case CMD_EQEBB_RET_PARAM:
            case CMD_EQEBB_NTFY_PARAM:
                return parseEqEbb(payload, state);
            default:
                return false;
        }
    }

    private static boolean parseProtocolInfo(byte[] payload, ConfigStore.HeadsetState state) {
        if (payload.length >= 4) {
            int vHi = payload[2] & 0xFF;
            int version = (vHi >= 2) ? PROTOCOL_V2 : PROTOCOL_V1;
            if (state.protocolVersion != version) {
                state.protocolVersion = version;
                state.lastEvent = "proto=V" + version;
                state.updatedAtMs = System.currentTimeMillis();
                return true;
            }
        }
        return false;
    }

    private static boolean parseSupportFunction(byte[] payload, ConfigStore.HeadsetState state) {
        boolean changed = false;
        if (payload.length >= 3) {
            for (int i = 3; i < payload.length; i++) {
                int fn = payload[i] & 0xFF;
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

        if (state.protocolVersion == PROTOCOL_V1 && inq <= 0x03 && payload.length >= 4 && payload.length <= 6) {
            if (inq == V1_INQ_NC) {
                newAncMode = ((payload[2] & 0xFF) != 0) ? ANC_ON : ANC_OFF;
            } else if (inq == V1_INQ_ASM && payload.length >= 5) {
                boolean on = (payload[2] & 0xFF) != 0;
                newAncMode = on ? ANC_AMBIENT : ANC_OFF;
                newVoice = (payload[3] & 0xFF) != 0;
                newAsmLevel = clampLevel(payload[4] & 0xFF, newAsmLevel);
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
        if ((inq == SYS_INQ_SMART_TALKING_V1 || inq == SYS_INQ_SMART_TALKING_V2) && payload.length >= 3) {
            boolean newlyBound = !state.supportsSpeakToChat || state.speakToChatInquiredType != inq;
            state.speakToChatInquiredType = inq;
            state.supportsSpeakToChat = true;
            // Inverted Sony wire convention: 0x00=ON, 0x01=OFF
            boolean enabled = (payload[2] & 0xFF) == 0x00;
            boolean changed = newlyBound || enabled != state.speakToChat;
            state.speakToChat = enabled;
            state.lastEvent = String.format(Locale.US, "speak(0x%02X)=%s", inq, enabled);
            if (changed) {
                state.updatedAtMs = System.currentTimeMillis();
            }
            return changed;
        }
        return false;
    }

    private static boolean parseAudioParam(byte[] payload, ConfigStore.HeadsetState state) {
        int inq = payload[1] & 0xFF;
        if ((inq == AUDIO_INQ_UPSCALING || inq == AUDIO_INQ_UPSCALING_WITH_REASON) && payload.length >= 3) {
            boolean newlyBound = !state.supportsDsee;
            state.supportsDsee = true;
            boolean enabled = (payload[2] & 0xFF) != 0;
            boolean changed = newlyBound || enabled != state.dsee;
            state.dsee = enabled;
            state.lastEvent = String.format(Locale.US, "dsee(0x%02X)=%s", inq, enabled);
            if (changed) {
                state.updatedAtMs = System.currentTimeMillis();
            }
            return changed;
        }
        return false;
    }

    private static boolean parseEqEbb(byte[] payload, ConfigStore.HeadsetState state) {
        int inq = payload[1] & 0xFF;
        if ((inq == 0x00 || inq == 0x02) && payload.length >= 3) {
            state.eqInquiredType = inq;
            int presetId = payload[2] & 0xFF;
            String name = mapEqPresetName(presetId);
            if (!name.equals(state.eqPresetName)) {
                state.eqPresetName = name;
                state.updatedAtMs = System.currentTimeMillis();
                return true;
            }
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
