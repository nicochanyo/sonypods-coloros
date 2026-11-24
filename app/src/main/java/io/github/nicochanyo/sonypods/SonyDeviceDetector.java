package io.github.nicochanyo.sonypods;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects Sony headphones supporting Tandem V1/V2 protocol via device name,
 * SPP/RFCOMM UUIDs (including Android SDP byte-swapped variants), and GATT UUIDs.
 */
public final class SonyDeviceDetector {
    // Standard and byte-reversed Sony Tandem SPP UUIDs
    public static final UUID UUID_TANDEM_V2 = UUID.fromString("956c7b26-d49a-4ba8-b03f-b17d393cb6e2");
    public static final UUID UUID_TANDEM_V2_REV = UUID.fromString("e2b63c39-7db1-3fb0-a84b-9ad4267b6c95");

    public static final UUID UUID_TANDEM_V1 = UUID.fromString("96cc203e-5068-46ad-b32d-e316f5e069ba");
    public static final UUID UUID_TANDEM_V1_REV = UUID.fromString("ba69e0f5-16e3-2db3-ad46-68503e20cc96");

    public static final UUID UUID_TANDEM_ALT = UUID.fromString("443cce33-e85d-4b85-8d53-6e319ede53ae");
    public static final UUID UUID_TANDEM_ALT_REV = UUID.fromString("ae53de9e-316e-538d-854b-5de833ce3c44");

    private static final String SONY_GATT_PREFIX = "5b833e";
    private static final String SONY_GATT_SUFFIX = "-6bc7-4802-8e9a-723ceca4bd8f";

    public static final int SONY_MANUFACTURER_ID = 0x012D;
    public static final UUID UUID_FAST_PAIR_SERVICE =
            UUID.fromString("0000fe2c-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_FAST_PAIR_RFCOMM =
            UUID.fromString("df21fe2c-2515-4fdb-8886-f12c4d67927c");
    public static final ParcelUuid FAST_PAIR_SERVICE_UUID =
            ParcelUuid.fromString("0000fe2c-0000-1000-8000-00805f9b34fb");

    // 24-bit Google Fast Pair model IDs used by Sony audio products (big-endian) -> official model name
    private static final Map<Integer, String> SONY_FAST_PAIR_MODEL_IDS = new HashMap<>();

    static {
        SONY_FAST_PAIR_MODEL_IDS.put(0x2D7A23, "WF-1000XM4");
        SONY_FAST_PAIR_MODEL_IDS.put(0x02D815, "WF-1000XM4");
        SONY_FAST_PAIR_MODEL_IDS.put(0x4B2B8E, "WF-1000XM4");
        SONY_FAST_PAIR_MODEL_IDS.put(0x058D08, "WH-1000XM4");
        SONY_FAST_PAIR_MODEL_IDS.put(0x01EEB4, "WH-1000XM4");
        SONY_FAST_PAIR_MODEL_IDS.put(0xD446A7, "WH-1000XM5");
        SONY_FAST_PAIR_MODEL_IDS.put(0x5A2B4A, "WF-1000XM4");
        SONY_FAST_PAIR_MODEL_IDS.put(0x0E055A, "WH-1000XM4");
        SONY_FAST_PAIR_MODEL_IDS.put(0xF32234, "WF-1000XM5");
        SONY_FAST_PAIR_MODEL_IDS.put(0x821F66, "WF-1000XM5");
        SONY_FAST_PAIR_MODEL_IDS.put(0x5C7582, "WH-1000XM5");
        SONY_FAST_PAIR_MODEL_IDS.put(0x72EF8D, "LinkBuds S");
        SONY_FAST_PAIR_MODEL_IDS.put(0x20330C, "LinkBuds S");
        SONY_FAST_PAIR_MODEL_IDS.put(0xCD8256, "WH-1000XM3");
        SONY_FAST_PAIR_MODEL_IDS.put(0x6EDAF7, "WF-C700N");
        SONY_FAST_PAIR_MODEL_IDS.put(0x0582FD, "WF-C500");
        SONY_FAST_PAIR_MODEL_IDS.put(0x8E77D1, "WH-CH720N");
        SONY_FAST_PAIR_MODEL_IDS.put(0x10B2F5, "WH-CH520");
        SONY_FAST_PAIR_MODEL_IDS.put(0xA921CA, "ULT WEAR");
        SONY_FAST_PAIR_MODEL_IDS.put(0x859A7E, "LinkBuds Fit");
        SONY_FAST_PAIR_MODEL_IDS.put(0xC71D34, "LinkBuds Open");
        SONY_FAST_PAIR_MODEL_IDS.put(0x0E30C3, "WH-1000XM5");
        SONY_FAST_PAIR_MODEL_IDS.put(0x00C95C, "WF-1000X");
        SONY_FAST_PAIR_MODEL_IDS.put(0x01C95C, "WF-1000X");
        SONY_FAST_PAIR_MODEL_IDS.put(0x02C95C, "WF-1000X");
    }

    private static final String[] NAME_KEYWORDS = {
            "sony", "linkbuds", "inzone", "bravia theatre", "1000x"
    };

    private static final String[] NAME_PREFIXES = {
            "wf-", "wh-", "wi-", "xba-", "mdr-", "srs-", "ult ", "ht-", "sa-", "lspx-"
    };

    private static final Set<String> KNOWN_SONY_MACS = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> RECENT_SONY_BLE_MACS = new ConcurrentHashMap<>();
    private static final long RECENT_SONY_BLE_TTL_MS = 60_000L;

    private SonyDeviceDetector() {
    }

    public static void markKnownSony(String mac) {
        if (mac != null && !mac.isEmpty()) {
            KNOWN_SONY_MACS.add(mac.toUpperCase(Locale.US));
        }
    }

    public static boolean isKnownSonyMac(String mac) {
        return mac != null && KNOWN_SONY_MACS.contains(mac.toUpperCase(Locale.US));
    }

    public static void markRecentSonyBleMac(String mac) {
        if (mac == null || mac.isEmpty()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        RECENT_SONY_BLE_MACS.put(mac.toUpperCase(Locale.US), now);
        if (RECENT_SONY_BLE_MACS.size() > 64) {
            RECENT_SONY_BLE_MACS.entrySet().removeIf(e -> (now - e.getValue()) > RECENT_SONY_BLE_TTL_MS);
        }
    }

    public static boolean isRecentSonyBleMac(String mac) {
        if (mac == null || mac.isEmpty()) {
            return false;
        }
        Long ts = RECENT_SONY_BLE_MACS.get(mac.toUpperCase(Locale.US));
        if (ts == null) {
            return false;
        }
        if ((SystemClock.elapsedRealtime() - ts) > RECENT_SONY_BLE_TTL_MS) {
            RECENT_SONY_BLE_MACS.remove(mac.toUpperCase(Locale.US));
            return false;
        }
        return true;
    }

    public static boolean isSonyName(String rawName) {
        if (rawName == null) {
            return false;
        }
        String name = rawName.trim().toLowerCase(Locale.US);
        if (name.isEmpty()) {
            return false;
        }
        if (name.startsWith("le_") || name.startsWith("le-")) {
            name = name.substring(3).trim();
        }
        for (String kw : NAME_KEYWORDS) {
            if (name.contains(kw)) {
                return true;
            }
        }
        for (String prefix : NAME_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isSonyUuid(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        if (UUID_TANDEM_V2.equals(uuid) || UUID_TANDEM_V2_REV.equals(uuid)
                || UUID_TANDEM_V1.equals(uuid) || UUID_TANDEM_V1_REV.equals(uuid)
                || UUID_TANDEM_ALT.equals(uuid) || UUID_TANDEM_ALT_REV.equals(uuid)) {
            return true;
        }
        String s = uuid.toString().toLowerCase(Locale.US);
        return s.startsWith(SONY_GATT_PREFIX) && s.endsWith(SONY_GATT_SUFFIX);
    }

    public static boolean isSonyDevice(BluetoothDevice device) {
        if (device == null) {
            return false;
        }
        String mac = null;
        try {
            mac = device.getAddress();
            if (isKnownSonyMac(mac)) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        try {
            String name = device.getName();
            if (isSonyName(name)) {
                markKnownSony(mac);
                return true;
            }
        } catch (Throwable ignored) {
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                String alias = device.getAlias();
                if (isSonyName(alias)) {
                    markKnownSony(mac);
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            ParcelUuid[] uuids = device.getUuids();
            if (uuids != null) {
                for (ParcelUuid pu : uuids) {
                    if (pu != null && isSonyUuid(pu.getUuid())) {
                        markKnownSony(mac);
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static final Set<String> KNOWN_V1_MODELS = new java.util.HashSet<>(java.util.Arrays.asList(
            "WF-1000XM3", "WF-1000X", "WF-SP800N", "WF-SP700N", "WF-H800",
            "WH-1000XM3", "WH-1000XM2", "WH-XB900N", "WH-XB700", "WH-CH700N",
            "WH-H910N", "WH-H900N", "WH-H810", "WH-H800",
            "WI-1000XM2", "WI-1000X", "WI-C600N", "WI-H700", "WI-SP600N"
    ));

    /** Returns true for confirmed Sony Tandem V1 headset models. */
    public static boolean isKnownV1ModelName(String modelOrName) {
        if (modelOrName == null) {
            return false;
        }
        String normalized = normalizeModelName(modelOrName);
        if (normalized.isEmpty()) {
            return false;
        }
        return KNOWN_V1_MODELS.contains(normalized.toUpperCase(Locale.US));
    }

    public static int detectPreferredVersion(BluetoothDevice device) {
        if (device == null) {
            return 2;
        }
        try {
            ParcelUuid[] uuids = device.getUuids();
            if (uuids != null) {
                boolean hasV1 = false;
                for (ParcelUuid pu : uuids) {
                    if (pu == null) continue;
                    UUID u = pu.getUuid();
                    if (UUID_TANDEM_V2.equals(u) || UUID_TANDEM_V2_REV.equals(u)) {
                        return 2;
                    }
                    if (UUID_TANDEM_V1.equals(u) || UUID_TANDEM_V1_REV.equals(u)) {
                        hasV1 = true;
                    }
                }
                if (hasV1) {
                    return 1;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (isKnownV1ModelName(device.getName())) {
                return 1;
            }
        } catch (Throwable ignored) {
        }
        return 2;
    }

    /** Normalized Sony marketing model name used for official catalog artwork lookup and ColorOS labels. */
    public static String normalizeModelName(String rawName) {
        if (rawName == null) return "";
        String s = rawName.trim();
        String uPrefix = s.toUpperCase(Locale.US);
        if ((uPrefix.startsWith("LE_") || uPrefix.startsWith("LE-")) && s.length() > 3) {
            s = s.substring(3).trim();
        }
        String upper = s.toUpperCase(Locale.US);
        if (upper.contains("WF-1000XM6")) return "WF-1000XM6";
        if (upper.contains("WF-1000XM5") || upper.contains("YY2963")) return "WF-1000XM5";
        if (upper.contains("WF-1000XM4") || upper.contains("YY2952")) return "WF-1000XM4";
        if (upper.contains("WF-1000XM3") || upper.contains("YY2948")) return "WF-1000XM3";
        if (upper.contains("WF-1000X")) return "WF-1000X";
        if (upper.contains("WH-1000XM6") || upper.contains("YY2984")) return "WH-1000XM6";
        if (upper.contains("WH-1000XM5") || upper.contains("YY2954")) return "WH-1000XM5";
        if (upper.contains("WH-1000XM4C")) return "WH-1000XM4C";
        if (upper.contains("WH-1000XM4") || upper.contains("YY2947")) return "WH-1000XM4";
        if (upper.contains("WH-1000XM3") || upper.contains("YY2934")) return "WH-1000XM3";
        if (upper.contains("WH-1000XM2")) return "WH-1000XM2";
        if (upper.contains("LINKBUDS S") || upper.contains("WF-LS900N") || upper.contains("YY2950")) return "LinkBuds S";
        if (upper.contains("LINKBUDS FIT") || upper.contains("WF-LS910N")) return "LinkBuds Fit";
        if (upper.contains("LINKBUDS OPEN") || upper.contains("WF-L910")) return "LinkBuds Open";
        if (upper.contains("LINKBUDS CLIP")) return "LinkBuds Clip";
        if (upper.contains("LINKBUDS SPEAKER")) return "LinkBuds Speaker";
        if (upper.contains("LINKBUDS UC")) return "LinkBuds UC";
        if (upper.contains("LINKBUDS") || upper.contains("WF-L900")) return "LinkBuds";
        if (upper.contains("WF-C710N")) return "WF-C710N";
        if (upper.contains("WF-C700N")) return "WF-C700N";
        if (upper.contains("WF-C510")) return "WF-C510";
        if (upper.contains("WF-C500")) return "WF-C500";
        if (upper.contains("WF-H800")) return "WF-H800";
        if (upper.contains("WF-SP900")) return "WF-SP900";
        if (upper.contains("WF-SP800N")) return "WF-SP800N";
        if (upper.contains("WF-SP700N")) return "WF-SP700N";
        if (upper.contains("ULT WEAR") || upper.contains("WH-ULT900N")) return "ULT WEAR";
        if (upper.contains("ULT FIELD 7")) return "ULT FIELD 7";
        if (upper.contains("ULT FIELD 5")) return "ULT FIELD 5";
        if (upper.contains("ULT FIELD 3")) return "ULT FIELD 3";
        if (upper.contains("ULT FIELD 1")) return "ULT FIELD 1";
        if (upper.contains("WH-CH730N")) return "WH-CH730N";
        if (upper.contains("WH-CH720N")) return "WH-CH720N";
        if (upper.contains("WH-CH700N")) return "WH-CH700N";
        if (upper.contains("WH-CH530")) return "WH-CH530";
        if (upper.contains("WH-CH520")) return "WH-CH520";
        if (upper.contains("WH-H910N")) return "WH-H910N";
        if (upper.contains("WH-H900N")) return "WH-H900N";
        if (upper.contains("WH-H810")) return "WH-H810";
        if (upper.contains("WH-H800")) return "WH-H800";
        if (upper.contains("WH-XB910N")) return "WH-XB910N";
        if (upper.contains("WH-XB900N")) return "WH-XB900N";
        if (upper.contains("WH-XB700")) return "WH-XB700";
        if (upper.contains("WI-1000XM2")) return "WI-1000XM2";
        if (upper.contains("WI-1000X")) return "WI-1000X";
        if (upper.contains("WI-C600N")) return "WI-C600N";
        if (upper.contains("WI-C100")) return "WI-C100";
        if (upper.contains("WI-H700")) return "WI-H700";
        if (upper.contains("WI-SP600N")) return "WI-SP600N";
        if (upper.contains("INZONE BUDS") || upper.contains("WF-G700N")) return "INZONE Buds";
        if (upper.contains("INZONE H9 II")) return "INZONE H9 II";
        if (upper.contains("BRAVIA THEATRE U")) return "BRAVIA Theatre U";
        if (upper.contains("SRS-NS7R")) return "SRS-NS7R";
        if (upper.contains("SRS-NS7")) return "SRS-NS7";
        return s;
    }

    /** True if the model or name identifies a single-unit Sony audio device (over-ear/neckband/speaker) rather than TWS earbuds + case. */
    public static boolean isOverEarModel(String modelOrName) {
        if (modelOrName == null) return false;
        String u = modelOrName.toUpperCase(Locale.US);
        return u.startsWith("WH-") || u.contains("WH-1000") || u.contains("WH-CH")
                || u.contains("WH-H") || u.contains("WH-XB") || u.contains("WH-ULT")
                || u.contains("ULT WEAR") || u.contains("ULT FIELD") || u.contains("ULT TOWER")
                || u.contains("MDR-") || u.contains("INZONE H")
                || u.contains("BRAVIA THEATRE") || u.contains("SRS-NS")
                || u.startsWith("WI-") || u.contains("WI-1000") || u.contains("WI-C")
                || u.contains("WI-H") || u.contains("WI-SP")
                || u.contains("LINKBUDS SPEAKER");
    }

    /** Lightweight Sony check using MAC prefix table and existing device detection logic. */
    public static boolean isSonyMacOrBonded(BluetoothDevice device) {
        if (device == null) return false;
        if (isSonyDevice(device)) return true;
        try {
            String addr = device.getAddress();
            if (isKnownSonyMac(addr)) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Checks whether a BLE manufacturer/Fast-Pair record belongs to a Sony headset. */
    public static boolean isSonyBleScanRecord(ScanRecord record) {
        if (record == null) {
            return false;
        }
        try {
            if (record.getManufacturerSpecificData(SONY_MANUFACTURER_ID) != null) {
                return true;
            }
            if (isSonyName(record.getDeviceName())) {
                return true;
            }
            byte[] fpData = record.getServiceData(FAST_PAIR_SERVICE_UUID);
            if (isKnownSonyFastPairModelId(fpData)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean isSonyScanResult(ScanResult result) {
        if (result == null) {
            return false;
        }
        try {
            BluetoothDevice dev = result.getDevice();
            if (isSonyBleScanRecord(result.getScanRecord())) {
                if (dev != null) {
                    markRecentSonyBleMac(dev.getAddress());
                }
                return true;
            }
            if (dev != null) {
                String addr = dev.getAddress();
                if (isKnownSonyMac(addr) || isRecentSonyBleMac(addr)) {
                    return true;
                }
                if (isSonyMacOrBonded(dev)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean isFastPairDiscoverablePayload(byte[] fpData) {
        return fpData != null && fpData.length == 3;
    }

    public static boolean isKnownSonyFastPairModelId(byte[] fpData) {
        return !getSonyFastPairModelName(fpData).isEmpty();
    }

    public static String getSonyFastPairModelName(byte[] fpData) {
        if (fpData == null || fpData.length < 3) {
            return "";
        }
        int id = ((fpData[0] & 0xFF) << 16)
                | ((fpData[1] & 0xFF) << 8)
                | (fpData[2] & 0xFF);
        String model = SONY_FAST_PAIR_MODEL_IDS.get(id);
        return model != null ? model : "";
    }

    public static boolean hasFastPairServiceData(ScanRecord record) {
        if (record == null) {
            return false;
        }
        try {
            byte[] data = record.getServiceData(FAST_PAIR_SERVICE_UUID);
            return data != null && data.length >= 3;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static BluetoothDevice findBondedSonyDeviceByMac(String mac) {
        if (mac == null || mac.isEmpty()) {
            return null;
        }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                return null;
            }
            String upper = mac.toUpperCase(Locale.US);
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    if (d != null && upper.equalsIgnoreCase(d.getAddress()) && isSonyDevice(d)) {
                        return d;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
