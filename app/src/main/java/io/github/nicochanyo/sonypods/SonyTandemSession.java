package io.github.nicochanyo.sonypods;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.ParcelUuid;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Manages the active RFCOMM/SPP Tandem V1/V2 link to a Sony headset.
 * Runs in the com.android.bluetooth process when hooked by BluetoothHook.
 */
public final class SonyTandemSession {
    public interface Listener {
        void onStateUpdated(BluetoothDevice device, ConfigStore.HeadsetState state);
    }

    private static final class OutboundFrame {
        final boolean table2;
        final byte[] payload;

        OutboundFrame(boolean table2, byte[] payload) {
            this.table2 = table2;
            this.payload = payload;
        }
    }

    private static final class CachedRfcommEndpoint {
        final UUID uuid;
        final boolean insecure;

        CachedRfcommEndpoint(UUID uuid, boolean insecure) {
            this.uuid = uuid;
            this.insecure = insecure;
        }
    }

    private static final Map<String, CachedRfcommEndpoint> LAST_WORKING_RFCOMM_BY_MAC =
            new ConcurrentHashMap<>();
    private static final long[] RFCOMM_CONNECT_BACKOFF_MS = { 300L, 600L, 1200L, 2500L };

    private final BluetoothDevice device;
    private final Listener listener;
    private final ConfigStore.HeadsetState state;
    private final BlockingQueue<OutboundFrame> outboundQueue = new LinkedBlockingQueue<>();

    private volatile boolean running;
    private volatile boolean yielded;
    private volatile boolean ncAsmDiscovered;
    private volatile int rfcommLockedProtocolVersion = 0;
    private volatile int dseeInquiredType = SonyTandemProtocol.AUDIO_INQ_UPSCALING;
    private volatile BluetoothSocket socket;
    private volatile OutputStream outStream;

    private final Object ackLock = new Object();
    private final Object writeLock = new Object();
    private volatile boolean awaitingAck;
    private volatile byte expectedAckSeq;
    private byte txSeq = 0;

    private Thread sessionThread;
    private Thread writerThread;

    public SonyTandemSession(BluetoothDevice device, Listener listener, int defaultAsmLevel, boolean defaultVoiceFocus) {
        this.device = device;
        this.listener = listener;
        this.state = new ConfigStore.HeadsetState();
        this.state.connected = true;
        this.state.mac = safeAddress(device);
        this.state.name = safeName(device);
        this.state.modelName = SonyDeviceDetector.normalizeModelName(this.state.name);
        this.state.protocolVersion = SonyDeviceDetector.detectPreferredVersion(device);
        this.state.ncAsmInquiredType = (this.state.protocolVersion == 1)
                ? SonyTandemProtocol.V1_INQ_NC_ASM
                : SonyTandemProtocol.V2_INQ_AUTO_SEAMLESS;
        this.state.asmLevel = ConfigStore.clampAsmLevel(defaultAsmLevel);
        this.state.voiceFocus = defaultVoiceFocus;
        this.state.updatedAtMs = System.currentTimeMillis();
    }

    public BluetoothDevice getDevice() {
        return device;
    }

    public synchronized ConfigStore.HeadsetState getStateSnapshot() {
        return state.copy();
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        sessionThread = new Thread(this::runSessionLoop, "SonyPods-Session");
        sessionThread.setDaemon(true);
        sessionThread.start();
    }

    public synchronized void stop() {
        running = false;
        closeSocket();
        if (sessionThread != null) {
            sessionThread.interrupt();
        }
        if (writerThread != null) {
            writerThread.interrupt();
        }
    }

    public void setYielded(boolean yield) {
        if (this.yielded == yield) {
            return;
        }
        this.yielded = yield;
        XposedCompat.log("Tandem session yield=" + yield + " for " + state.mac);
        if (yield) {
            closeSocket();
        } else if (running && sessionThread != null) {
            sessionThread.interrupt();
        }
    }

    private int activeProtocolVersion() {
        int locked = rfcommLockedProtocolVersion;
        return locked != 0 ? locked : state.protocolVersion;
    }

    public void enqueueRefresh() {
        if (!running || yielded) {
            return;
        }
        int ver;
        boolean overEar;
        int ncAsmInq;
        boolean mp;
        int mpInq;
        synchronized (this) {
            ver = activeProtocolVersion();
            overEar = SonyDeviceDetector.isOverEarModel(state.effectiveModelName());
            ncAsmInq = state.ncAsmInquiredType;
            mp = state.supportsMultipoint;
            mpInq = state.multipointInquiredType;
        }
        if (overEar) {
            enqueueRaw(SonyTandemProtocol.buildGetBattery(ver, SonyTandemProtocol.POWER_TYPE_BATTERY));
        } else {
            enqueueRaw(SonyTandemProtocol.buildGetBattery(ver, SonyTandemProtocol.POWER_TYPE_LEFT_RIGHT_BATTERY));
            enqueueRaw(SonyTandemProtocol.buildGetBattery(ver, SonyTandemProtocol.POWER_TYPE_CRADLE_BATTERY));
            enqueueRaw(SonyTandemProtocol.buildGetBattery(ver, SonyTandemProtocol.POWER_TYPE_BATTERY));
        }
        if (ncAsmDiscovered) {
            enqueueRaw(SonyTandemProtocol.buildGetNcAsmParam(ncAsmInq));
        }
        if (ver != SonyTandemProtocol.PROTOCOL_V1) {
            if (mp) {
                enqueueRaw(SonyTandemProtocol.buildGetGsParam(SonyTandemProtocol.getGsMultipointSlot()));
                enqueueRaw(true, SonyTandemProtocol.buildT2GetPeripheralStatus(mpInq));
                enqueueRaw(true, SonyTandemProtocol.buildT2GetPeripheralParam(mpInq));
            }
            int wearSlot = SonyTandemProtocol.getGsWearPauseSlot();
            if (wearSlot != 0) {
                enqueueRaw(SonyTandemProtocol.buildGetGsParam(wearSlot));
            }
        }
    }

    public void setNcAsm(int ancMode, int asmLevel, boolean voiceFocus) {
        int lastNcVal;
        int ver;
        int inq;
        int v1NcType;
        int v1AsmType;
        int clampedLvl = ConfigStore.clampAsmLevel(asmLevel);
        synchronized (this) {
            state.ancMode = ancMode;
            state.asmLevel = clampedLvl;
            state.voiceFocus = voiceFocus;
            state.updatedAtMs = System.currentTimeMillis();
            lastNcVal = state.lastNcValue;
            ver = activeProtocolVersion();
            inq = state.ncAsmInquiredType;
            v1NcType = SonyTandemProtocol.getObservedV1NcSettingType(state);
            v1AsmType = SonyTandemProtocol.getObservedV1AsmSettingType(state);
        }
        notifyListener();
        enqueueRaw(SonyTandemProtocol.buildSetNcAsm(
                ver,
                inq,
                ancMode,
                clampedLvl,
                voiceFocus,
                lastNcVal,
                v1NcType,
                v1AsmType));
    }

    /** Updates ambient level and voice focus parameters immediately on the headset. */
    public void updateAmbientPreferences(int asmLevel, boolean voiceFocus) {
        int activeAncMode;
        int lastNcVal;
        int ver;
        int inq;
        int v1NcType;
        int v1AsmType;
        int clampedLvl = ConfigStore.clampAsmLevel(asmLevel);
        synchronized (this) {
            state.asmLevel = clampedLvl;
            state.voiceFocus = voiceFocus;
            state.updatedAtMs = System.currentTimeMillis();
            activeAncMode = state.ancMode;
            lastNcVal = state.lastNcValue;
            ver = activeProtocolVersion();
            inq = state.ncAsmInquiredType;
            v1NcType = SonyTandemProtocol.getObservedV1NcSettingType(state);
            v1AsmType = SonyTandemProtocol.getObservedV1AsmSettingType(state);
        }
        notifyListener();
        enqueueRaw(SonyTandemProtocol.buildSetNcAsm(
                ver,
                inq,
                activeAncMode,
                clampedLvl,
                voiceFocus,
                lastNcVal,
                v1NcType,
                v1AsmType));
    }

    public void setSpeakToChat(boolean enabled) {
        int inq;
        int ver;
        synchronized (this) {
            ver = activeProtocolVersion();
            state.speakToChat = enabled;
            state.updatedAtMs = System.currentTimeMillis();
            inq = state.speakToChatInquiredType;
        }
        notifyListener();
        byte[] pkt = SonyTandemProtocol.buildSetSpeakToChat(ver, inq, enabled);
        XposedCompat.log("TX setSpeakToChat: " + SonyTandemProtocol.toHex(pkt));
        enqueueRaw(pkt);
        enqueueRaw(SonyTandemProtocol.buildGetSystemParam(
                ver == SonyTandemProtocol.PROTOCOL_V1 ? SonyTandemProtocol.V1_SYS_INQ_SMART_TALKING : inq));
    }

    public void setDsee(boolean enabled) {
        int ver = activeProtocolVersion();
        int inq = (ver == SonyTandemProtocol.PROTOCOL_V1)
                ? SonyTandemProtocol.V1_AUDIO_INQ_UPSCALING
                : dseeInquiredType;
        synchronized (this) {
            state.dsee = enabled;
            state.updatedAtMs = System.currentTimeMillis();
        }
        notifyListener();
        enqueueRaw(SonyTandemProtocol.buildSetDsee(ver, inq, enabled));
        enqueueRaw(SonyTandemProtocol.buildGetAudioParam(inq));
    }

    public void setEq(int presetId, int[] bands) {
        int inq;
        int ver;
        synchronized (this) {
            ver = activeProtocolVersion();
            state.supportsEq = true;
            state.eqPresetId = presetId;
            if (bands != null && bands.length > 0) {
                int n = Math.min(state.eqBands.length, bands.length);
                state.eqBandCount = n;
                for (int i = 0; i < n; i++) {
                    state.eqBands[i] = ConfigStore.HeadsetState.clampEqBand(bands[i]);
                }
            }
            if (ver == SonyTandemProtocol.PROTOCOL_V1 && state.eqInquiredType < 0) {
                state.eqInquiredType = SonyTandemProtocol.V1_EQ_INQ_PRESET_EQ;
            }
            state.updatedAtMs = System.currentTimeMillis();
            inq = state.eqInquiredType;
        }
        notifyListener();
        int refreshInq = (inq >= 0) ? inq
                : (ver == SonyTandemProtocol.PROTOCOL_V1 ? SonyTandemProtocol.V1_EQ_INQ_PRESET_EQ : 0x00);
        enqueueRaw(SonyTandemProtocol.buildSetEqPreset(refreshInq, presetId, bands));
        enqueueRaw(SonyTandemProtocol.buildGetEqEbbParam(refreshInq));
    }

    public void setSpeakToChatDetail(int sensitivity, int timeout) {
        int inq;
        int ver;
        int sens;
        int out;
        boolean voice;
        synchronized (this) {
            ver = activeProtocolVersion();
            state.supportsSpeakToChat = true;
            sens = Math.max(0, Math.min(2, sensitivity));
            out = Math.max(0, Math.min(3, timeout));
            state.speakSensitivity = sens;
            state.speakTimeout = out;
            state.updatedAtMs = System.currentTimeMillis();
            inq = state.speakToChatInquiredType;
            voice = state.voiceFocus;
        }
        notifyListener();
        byte[] pkt = SonyTandemProtocol.buildSetSpeakToChatDetail(ver, inq, sens, out, voice);
        XposedCompat.log("TX setSpeakToChatDetail: " + SonyTandemProtocol.toHex(pkt));
        enqueueRaw(pkt);
        enqueueRaw(SonyTandemProtocol.buildGetSystemExtParam(
                ver == SonyTandemProtocol.PROTOCOL_V1 ? SonyTandemProtocol.V1_SYS_INQ_SMART_TALKING : inq));
    }

    public void setTouchSensor(int leftPreset, int rightPreset) {
        int inq;
        int ver;
        synchronized (this) {
            ver = activeProtocolVersion();
            state.supportsTouchSensor = true;
            state.touchLeftPreset = leftPreset;
            state.touchRightPreset = rightPreset;
            state.updatedAtMs = System.currentTimeMillis();
            inq = state.touchInquiredType;
        }
        notifyListener();
        byte[] pkt = SonyTandemProtocol.buildSetTouchSensor(ver, inq, leftPreset, rightPreset);
        XposedCompat.log("TX setTouchSensor: " + SonyTandemProtocol.toHex(pkt));
        enqueueRaw(pkt);
        enqueueRaw(SonyTandemProtocol.buildGetSystemParam(
                ver == SonyTandemProtocol.PROTOCOL_V1 ? SonyTandemProtocol.V1_SYS_INQ_ASSIGNABLE_SETTINGS : inq));
    }

    public void setMultipoint(boolean enabled) {
        int inq;
        synchronized (this) {
            state.supportsMultipoint = true;
            state.multipointEnabled = enabled;
            state.updatedAtMs = System.currentTimeMillis();
            inq = state.multipointInquiredType;
        }
        notifyListener();
        int slot = SonyTandemProtocol.getGsMultipointSlot();
        byte[] pkt = SonyTandemProtocol.buildSetGsBooleanParam(slot, enabled);
        XposedCompat.log("TX setMultipoint: " + SonyTandemProtocol.toHex(pkt));
        enqueueRaw(pkt);
        enqueueRaw(SonyTandemProtocol.buildGetGsParam(slot));
        enqueueRaw(true, SonyTandemProtocol.buildT2GetPeripheralStatus(inq));
        enqueueRaw(true, SonyTandemProtocol.buildT2GetPeripheralParam(inq));
    }

    public void sendMultipointAction(String targetMac, int actionType) {
        if (targetMac == null || targetMac.trim().isEmpty()) return;
        int inq;
        synchronized (this) {
            inq = state.multipointInquiredType;
        }
        byte[] pkt = SonyTandemProtocol.buildT2PeripheralAction(inq, actionType, targetMac);
        if (pkt != null) {
            XposedCompat.log("TX sendMultipointAction: " + SonyTandemProtocol.toHex(pkt));
            enqueueRaw(true, pkt);
            enqueueRaw(true, SonyTandemProtocol.buildT2GetPeripheralParam(inq));
        }
    }

    public void setConnectionMode(int priorMode) {
        int ver;
        int inq;
        synchronized (this) {
            ver = activeProtocolVersion();
            state.supportsConnectionMode = true;
            state.connectionPriorMode = priorMode;
            state.updatedAtMs = System.currentTimeMillis();
            inq = (ver == SonyTandemProtocol.PROTOCOL_V1)
                    ? SonyTandemProtocol.V1_AUDIO_INQ_CONNECTION_MODE
                    : state.connectionModeInquiredType;
        }
        notifyListener();
        enqueueRaw(SonyTandemProtocol.buildSetConnectionMode(ver, inq, priorMode));
        enqueueRaw(SonyTandemProtocol.buildGetAudioParam(inq));
    }

    public void setWearDetect(boolean enabled) {
        int ver;
        synchronized (this) {
            ver = activeProtocolVersion();
            state.supportsWearDetect = true;
            state.wearAutoPause = enabled;
            state.updatedAtMs = System.currentTimeMillis();
        }
        notifyListener();
        if (ver == SonyTandemProtocol.PROTOCOL_V1) {
            byte[] pkt = SonyTandemProtocol.buildSetWearDetect(ver, enabled);
            XposedCompat.log("TX setWearDetect(V1): " + SonyTandemProtocol.toHex(pkt));
            enqueueRaw(pkt);
            enqueueRaw(SonyTandemProtocol.buildGetSystemParam(SonyTandemProtocol.V1_SYS_INQ_CONTROL_BY_WEARING));
        } else {
            int wearSlot = SonyTandemProtocol.getGsWearPauseSlot();
            if (wearSlot != 0) {
                byte[] pkt = SonyTandemProtocol.buildSetGsBooleanParam(wearSlot, enabled);
                XposedCompat.log("TX setWearDetect(GS): " + SonyTandemProtocol.toHex(pkt));
                enqueueRaw(pkt);
                enqueueRaw(SonyTandemProtocol.buildGetGsParam(wearSlot));
            } else {
                byte[] pkt = SonyTandemProtocol.buildSetWearDetect(ver, enabled);
                XposedCompat.log("TX setWearDetect: " + SonyTandemProtocol.toHex(pkt));
                enqueueRaw(pkt);
                enqueueRaw(SonyTandemProtocol.buildGetSystemParam(SonyTandemProtocol.SYS_INQ_PLAYBACK_CONTROL_BY_WEARING));
            }
        }
    }

    public void setAutoPowerOff(int element) {
        int ver;
        int inq;
        synchronized (this) {
            ver = activeProtocolVersion();
            state.supportsAutoPowerOff = true;
            state.autoPowerOffElement = element;
            state.updatedAtMs = System.currentTimeMillis();
            inq = state.autoPowerOffInquiredType;
        }
        notifyListener();
        byte[] pkt = SonyTandemProtocol.buildSetAutoPowerOff(ver, inq, element);
        XposedCompat.log("TX setAutoPowerOff: " + SonyTandemProtocol.toHex(pkt));
        enqueueRaw(pkt);
        enqueueRaw(ver == SonyTandemProtocol.PROTOCOL_V1
                ? SonyTandemProtocol.buildGetSystemParam(SonyTandemProtocol.V1_SYS_INQ_AUTO_POWER_OFF)
                : SonyTandemProtocol.buildGetPowerParam(inq));
    }

    private void enqueueRaw(byte[] payload) {
        enqueueRaw(false, payload);
    }

    private void enqueueRaw(boolean table2, byte[] payload) {
        if (payload != null && running) {
            outboundQueue.offer(new OutboundFrame(table2, payload));
        }
    }

    private void runSessionLoop() {
        int connectFailCount = 0;
        while (running) {
            if (yielded) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ignored) {
                }
                continue;
            }

            BluetoothSocket sock = connectRfcommSocket();
            if (sock == null) {
                long sleepMs = RFCOMM_CONNECT_BACKOFF_MS[Math.min(connectFailCount, RFCOMM_CONNECT_BACKOFF_MS.length - 1)];
                connectFailCount++;
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ignored) {
                }
                continue;
            }
            connectFailCount = 0;

            this.socket = sock;
            try {
                this.outStream = sock.getOutputStream();
                InputStream in = sock.getInputStream();

                outboundQueue.clear();
                txSeq = 0;
                ncAsmDiscovered = false;
                dseeInquiredType = (activeProtocolVersion() == SonyTandemProtocol.PROTOCOL_V1)
                        ? SonyTandemProtocol.V1_AUDIO_INQ_UPSCALING
                        : SonyTandemProtocol.AUDIO_INQ_UPSCALING;
                SonyTandemProtocol.resetSessionCaches();

                startWriterThread();
                scheduleInitialProbes();

                SonyTandemFraming.StreamParser parser = new SonyTandemFraming.StreamParser();
                byte[] buf = new byte[1024];
                while (running && !yielded) {
                    int n = in.read(buf);
                    if (n < 0) {
                        break;
                    }
                    for (SonyTandemFraming.DecodedFrame frame : parser.feed(buf, n)) {
                        handleInboundFrame(frame);
                    }
                }
            } catch (Throwable t) {
                if (running && !yielded) {
                    XposedCompat.debug("Tandem stream ended (" + state.mac + "): " + t.getMessage());
                }
            } finally {
                stopWriterThread();
                closeSocket();
            }

            if (running && !yielded) {
                try {
                    Thread.sleep(800L);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private static void addRfcommEndpointUnique(
            List<CachedRfcommEndpoint> out,
            UUID uuid,
            boolean insecure
    ) {
        if (uuid == null) return;
        for (CachedRfcommEndpoint existing : out) {
            if (existing.insecure == insecure && uuid.equals(existing.uuid)) {
                return;
            }
        }
        out.add(new CachedRfcommEndpoint(uuid, insecure));
    }

    private BluetoothSocket connectRfcommSocket() {
        String macKey = (state.mac != null) ? state.mac.toUpperCase(Locale.US) : "";
        UUID[] candidates = state.protocolVersion == 1
                ? new UUID[]{
                SonyDeviceDetector.UUID_TANDEM_V1,
                SonyDeviceDetector.UUID_TANDEM_V1_REV,
                SonyDeviceDetector.UUID_TANDEM_V2,
                SonyDeviceDetector.UUID_TANDEM_V2_REV,
                SonyDeviceDetector.UUID_TANDEM_ALT
        }
                : new UUID[]{
                SonyDeviceDetector.UUID_TANDEM_V2,
                SonyDeviceDetector.UUID_TANDEM_V2_REV,
                SonyDeviceDetector.UUID_TANDEM_V1,
                SonyDeviceDetector.UUID_TANDEM_V1_REV,
                SonyDeviceDetector.UUID_TANDEM_ALT
        };

        List<CachedRfcommEndpoint> ordered = new ArrayList<>(candidates.length * 2 + 1);
        CachedRfcommEndpoint cached = !macKey.isEmpty() ? LAST_WORKING_RFCOMM_BY_MAC.get(macKey) : null;
        if (cached != null) {
            addRfcommEndpointUnique(ordered, cached.uuid, cached.insecure);
        }

        // Prioritize Tandem UUIDs that the headset explicitly advertises in SDP cache
        try {
            ParcelUuid[] devUuids = device.getUuids();
            if (devUuids != null) {
                for (UUID cand : candidates) {
                    for (ParcelUuid pu : devUuids) {
                        if (pu != null && cand.equals(pu.getUuid())) {
                            addRfcommEndpointUnique(ordered, cand, false);
                            addRfcommEndpointUnique(ordered, cand, true);
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        for (UUID cand : candidates) {
            addRfcommEndpointUnique(ordered, cand, false);
            addRfcommEndpointUnique(ordered, cand, true);
        }

        for (CachedRfcommEndpoint ep : ordered) {
            if (!running || yielded) {
                return null;
            }
            BluetoothSocket s = tryConnectUuid(ep.uuid, ep.insecure);
            if (s != null) {
                if (!macKey.isEmpty()) {
                    LAST_WORKING_RFCOMM_BY_MAC.put(macKey, ep);
                }
                if (ep.uuid.equals(SonyDeviceDetector.UUID_TANDEM_V1)
                        || ep.uuid.equals(SonyDeviceDetector.UUID_TANDEM_V1_REV)) {
                    rfcommLockedProtocolVersion = SonyTandemProtocol.PROTOCOL_V1;
                    dseeInquiredType = SonyTandemProtocol.V1_AUDIO_INQ_UPSCALING;
                    synchronized (this) {
                        state.protocolVersion = SonyTandemProtocol.PROTOCOL_V1;
                        state.ncAsmInquiredType = SonyTandemProtocol.V1_INQ_NC_ASM;
                    }
                } else if (ep.uuid.equals(SonyDeviceDetector.UUID_TANDEM_V2)
                        || ep.uuid.equals(SonyDeviceDetector.UUID_TANDEM_V2_REV)) {
                    rfcommLockedProtocolVersion = SonyTandemProtocol.PROTOCOL_V2;
                    dseeInquiredType = SonyTandemProtocol.AUDIO_INQ_UPSCALING;
                    synchronized (this) {
                        state.protocolVersion = SonyTandemProtocol.PROTOCOL_V2;
                        state.ncAsmInquiredType = SonyTandemProtocol.V2_INQ_AUTO_SEAMLESS;
                    }
                } else {
                    rfcommLockedProtocolVersion = 0;
                }
                XposedCompat.log("Connected Sony " + (ep.insecure ? "insecure " : "")
                        + "RFCOMM (" + state.mac + ") via UUID " + ep.uuid);
                return s;
            }
        }
        return null;
    }

    private BluetoothSocket tryConnectUuid(UUID uuid, boolean insecure) {
        BluetoothSocket sock = null;
        try {
            if (insecure) {
                Method m = device.getClass().getMethod("createInsecureRfcommSocketToServiceRecord", UUID.class);
                sock = (BluetoothSocket) m.invoke(device, uuid);
            } else {
                sock = device.createRfcommSocketToServiceRecord(uuid);
            }
            if (sock == null) {
                return null;
            }
            sock.connect();
            return sock;
        } catch (Throwable ignored) {
            if (sock != null) {
                try {
                    sock.close();
                } catch (Throwable ignored2) {
                }
            }
            return null;
        }
    }

    private void startWriterThread() {
        stopWriterThread();
        writerThread = new Thread(() -> {
            long lastPollMs = System.currentTimeMillis();
            while (running && !yielded && socket != null) {
                try {
                    OutboundFrame frame = outboundQueue.poll(1500L, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (frame != null) {
                        sendReliableFrame(frame.table2, frame.payload);
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastPollMs >= 20_000L && outboundQueue.isEmpty()) {
                        lastPollMs = now;
                        enqueueRefresh();
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable t) {
                    XposedCompat.debug("Tandem writer error: " + t.getMessage());
                    break;
                }
            }
        }, "SonyPods-Writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    private void stopWriterThread() {
        Thread wt = writerThread;
        writerThread = null;
        if (wt != null) {
            wt.interrupt();
        }
        synchronized (ackLock) {
            awaitingAck = false;
            ackLock.notifyAll();
        }
    }

    private void sendReliableFrame(boolean table2, byte[] payload) {
        if (!table2 && payload != null && payload.length > 0
                && (payload[0] & 0xFF) == SonyTandemProtocol.CMD_V2_POWER_GET_STATUS
                && (state.protocolVersion == SonyTandemProtocol.PROTOCOL_V1
                || rfcommLockedProtocolVersion == SonyTandemProtocol.PROTOCOL_V1)) {
            XposedCompat.log("Blocked unsafe V1 POWER_OFF (0x22) frame for " + state.mac);
            return;
        }
        OutputStream os = outStream;
        if (os == null) {
            return;
        }
        byte seq = txSeq;
        byte[] packet = table2
                ? SonyTandemFraming.encodeDataMdrNo2(seq, payload)
                : SonyTandemFraming.encodeDataMdr(seq, payload);

        for (int attempt = 0; attempt < 2; attempt++) {
            if (!running || yielded || outStream == null) {
                return;
            }
            synchronized (ackLock) {
                awaitingAck = true;
                expectedAckSeq = SonyTandemFraming.inverseSequence(seq);
            }
            try {
                synchronized (writeLock) {
                    os.write(packet);
                    os.flush();
                }
                XposedCompat.debug("TX seq=" + (seq & 0xFF) + " cmd=0x" + Integer.toHexString(payload[0] & 0xFF));
            } catch (Throwable t) {
                return;
            }

            long deadline = System.currentTimeMillis() + 500L;
            synchronized (ackLock) {
                while (awaitingAck && running && !yielded) {
                    long rem = deadline - System.currentTimeMillis();
                    if (rem <= 0) {
                        break;
                    }
                    try {
                        ackLock.wait(rem);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (!awaitingAck) {
                    txSeq = SonyTandemFraming.inverseSequence(seq);
                    return;
                }
            }
        }
        // Advance sequence on timeout so subsequent messages don't deadlock
        synchronized (ackLock) {
            awaitingAck = false;
        }
        txSeq = SonyTandemFraming.inverseSequence(seq);
    }

    private void scheduleInitialProbes() {
        // Enqueue battery probes FIRST so the popup & ColorOS bridge get battery in the earliest round-trips
        int ver = activeProtocolVersion();
        boolean overEar = SonyDeviceDetector.isOverEarModel(state.effectiveModelName());
        if (overEar) {
            enqueueRaw(SonyTandemProtocol.buildGetBattery(ver, SonyTandemProtocol.POWER_TYPE_BATTERY));
        } else {
            enqueueRaw(SonyTandemProtocol.buildGetBattery(ver, SonyTandemProtocol.POWER_TYPE_LEFT_RIGHT_BATTERY));
            enqueueRaw(SonyTandemProtocol.buildGetBattery(ver, SonyTandemProtocol.POWER_TYPE_CRADLE_BATTERY));
            enqueueRaw(SonyTandemProtocol.buildGetBattery(ver, SonyTandemProtocol.POWER_TYPE_BATTERY));
        }
        enqueueRaw(SonyTandemProtocol.buildGetDeviceInfo(SonyTandemProtocol.DEVICE_INFO_SERIES_AND_COLOR));
        enqueueRaw(SonyTandemProtocol.buildGetDeviceInfo(SonyTandemProtocol.DEVICE_INFO_MODEL_NAME));
        enqueueRaw(SonyTandemProtocol.buildGetProtocolInfo());
        enqueueRaw(SonyTandemProtocol.buildGetSupportFunction());
        if (ver == SonyTandemProtocol.PROTOCOL_V2) {
            enqueueRaw(SonyTandemProtocol.buildSetAlertStatus(SonyTandemProtocol.ALERT_INQ_FIXED_MESSAGE, true));
            enqueueRaw(SonyTandemProtocol.buildSetAlertStatus(SonyTandemProtocol.ALERT_INQ_APP_BECOMES_FOREGROUND, true));
        }

        // Probe NC/ASM inquired types in background thread until headset responds with its active type
        Thread probeThread = new Thread(() -> {
            try {
                Thread.sleep(260L);
            } catch (InterruptedException e) {
                return;
            }
            int probeVer = activeProtocolVersion();
            int[] types = (probeVer == SonyTandemProtocol.PROTOCOL_V1)
                    ? SonyTandemProtocol.V1_NC_ASM_PROBES
                    : SonyTandemProtocol.V2_NC_ASM_PROBES;
            for (int t : types) {
                if (!running || yielded || ncAsmDiscovered) {
                    break;
                }
                enqueueRaw(SonyTandemProtocol.buildGetNcAsmParam(t));
                try {
                    Thread.sleep(380L);
                } catch (InterruptedException e) {
                    break;
                }
            }
            if (running && !yielded) {
                probeVer = activeProtocolVersion();
                if (probeVer == SonyTandemProtocol.PROTOCOL_V1) {
                    enqueueRaw(SonyTandemProtocol.buildGetEqEbbParam(SonyTandemProtocol.V1_EQ_INQ_PRESET_EQ));
                    enqueueRaw(SonyTandemProtocol.buildGetEqEbbParam(0x02));
                    enqueueRaw(SonyTandemProtocol.buildGetAudioParam(SonyTandemProtocol.V1_AUDIO_INQ_UPSCALING));
                    enqueueRaw(SonyTandemProtocol.buildGetAudioParam(SonyTandemProtocol.V1_AUDIO_INQ_CONNECTION_MODE));
                    enqueueRaw(SonyTandemProtocol.buildGetSystemParam(SonyTandemProtocol.V1_SYS_INQ_CONTROL_BY_WEARING));
                    enqueueRaw(SonyTandemProtocol.buildGetSystemParam(SonyTandemProtocol.V1_SYS_INQ_AUTO_POWER_OFF));
                    enqueueRaw(SonyTandemProtocol.buildGetSystemParam(SonyTandemProtocol.V1_SYS_INQ_ASSIGNABLE_SETTINGS));
                    enqueueRaw(SonyTandemProtocol.buildGetSystemParam(SonyTandemProtocol.V1_SYS_INQ_SMART_TALKING));
                    enqueueRaw(SonyTandemProtocol.buildGetSystemExtParam(SonyTandemProtocol.V1_SYS_INQ_SMART_TALKING));
                } else {
                    enqueueRaw(SonyTandemProtocol.buildGetSystemParam(SonyTandemProtocol.SYS_INQ_SMART_TALKING_V2));
                    enqueueRaw(SonyTandemProtocol.buildGetSystemParam(SonyTandemProtocol.SYS_INQ_SMART_TALKING_V1));
                    enqueueRaw(SonyTandemProtocol.buildGetSystemExtParam(SonyTandemProtocol.SYS_INQ_SMART_TALKING_V2));
                    enqueueRaw(SonyTandemProtocol.buildGetSystemExtParam(SonyTandemProtocol.SYS_INQ_SMART_TALKING_V1));
                    enqueueRaw(SonyTandemProtocol.buildGetSystemParam(SonyTandemProtocol.SYS_INQ_ASSIGNABLE_SETTINGS));
                    enqueueRaw(SonyTandemProtocol.buildGetSystemParam(SonyTandemProtocol.SYS_INQ_ASSIGNABLE_SETTINGS_LIMITED));
                    enqueueRaw(SonyTandemProtocol.buildGetSystemParam(SonyTandemProtocol.SYS_INQ_PLAYBACK_CONTROL_BY_WEARING));
                    enqueueRaw(SonyTandemProtocol.buildGetAudioParam(SonyTandemProtocol.AUDIO_INQ_UPSCALING_WITH_REASON));
                    enqueueRaw(SonyTandemProtocol.buildGetAudioParam(SonyTandemProtocol.AUDIO_INQ_UPSCALING));
                    enqueueRaw(SonyTandemProtocol.buildGetAudioParam(SonyTandemProtocol.AUDIO_INQ_CONNECTION_MODE_LDAC));
                    enqueueRaw(SonyTandemProtocol.buildGetAudioParam(SonyTandemProtocol.AUDIO_INQ_CONNECTION_MODE));
                    enqueueRaw(SonyTandemProtocol.buildGetEqEbbParam(0x00));
                    enqueueRaw(SonyTandemProtocol.buildGetEqEbbParam(0x02));
                    enqueueRaw(SonyTandemProtocol.buildGetPowerParam(SonyTandemProtocol.POWER_INQ_AUTO_POWER_OFF_WEARING));
                    enqueueRaw(SonyTandemProtocol.buildGetPowerParam(SonyTandemProtocol.POWER_INQ_AUTO_POWER_OFF));
                    for (int gsSlot : SonyTandemProtocol.GS_SLOTS) {
                        if (!running || yielded) break;
                        enqueueRaw(SonyTandemProtocol.buildGetGsCapability(gsSlot));
                        try {
                            Thread.sleep(120L);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    enqueueRaw(SonyTandemProtocol.buildGetGsParam(SonyTandemProtocol.GS_INQ_MULTIPOINT));
                    enqueueRaw(true, SonyTandemProtocol.buildT2GetPeripheralStatus(SonyTandemProtocol.PERI_INQ_CLASSIC_BT));
                    enqueueRaw(true, SonyTandemProtocol.buildT2GetPeripheralStatus(SonyTandemProtocol.PERI_INQ_WITH_COD));
                    enqueueRaw(true, SonyTandemProtocol.buildT2GetPeripheralParam(SonyTandemProtocol.PERI_INQ_CLASSIC_BT));
                    enqueueRaw(true, SonyTandemProtocol.buildT2GetPeripheralParam(SonyTandemProtocol.PERI_INQ_WITH_COD));
                }
            }
            // Fast follow-up battery probes during the initial connection window for TWS earbuds
            // in case the second earbud finishes linking 0.5-4s after the primary earbud.
            long[] followUpDelays = new long[] { 400L, 500L, 650L, 850L, 1100L, 1500L };
            for (long delayMs : followUpDelays) {
                if (!running || yielded) {
                    break;
                }
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    break;
                }
                if (!running || yielded) {
                    break;
                }
                boolean needLr;
                boolean needBox;
                int curVer;
                synchronized (SonyTandemSession.this) {
                    String model = state.effectiveModelName();
                    if (SonyDeviceDetector.isOverEarModel(model)) {
                        break;
                    }
                    needLr = (state.leftBattery <= 0 || state.rightBattery <= 0);
                    needBox = (state.caseBattery <= 0);
                    curVer = activeProtocolVersion();
                }
                if (!needLr && !needBox) {
                    break;
                }
                if (needLr) {
                    enqueueRaw(SonyTandemProtocol.buildGetBattery(curVer, SonyTandemProtocol.POWER_TYPE_LEFT_RIGHT_BATTERY));
                }
                if (needBox) {
                    enqueueRaw(SonyTandemProtocol.buildGetBattery(curVer, SonyTandemProtocol.POWER_TYPE_CRADLE_BATTERY));
                }
            }
        }, "SonyPods-NcAsmProbe");
        probeThread.setDaemon(true);
        probeThread.start();
    }

    private void handleInboundFrame(SonyTandemFraming.DecodedFrame frame) {
        if (frame.isAck()) {
            synchronized (ackLock) {
                if (awaitingAck && frame.sequence == expectedAckSeq) {
                    awaitingAck = false;
                    ackLock.notifyAll();
                }
            }
            return;
        }

        if (frame.requiresAck()) {
            sendAck(frame.sequence);
        }

        if ((frame.dataType == SonyTandemFraming.DATA_TYPE_DATA_MDR
                || frame.dataType == SonyTandemFraming.DATA_TYPE_DATA_MDR_NO2)
                && frame.payload != null && frame.payload.length >= 2) {
            boolean isTable2 = (frame.dataType == SonyTandemFraming.DATA_TYPE_DATA_MDR_NO2);
            int cmd = frame.payload[0] & 0xFF;
            if (isTable2
                    || cmd == SonyTandemProtocol.CMD_CONNECT_RET_DEVICE_INFO
                    || cmd == SonyTandemProtocol.CMD_V1_NTFY_BATTERY
                    || cmd == SonyTandemProtocol.CMD_V2_POWER_NTFY_STATUS
                    || cmd == SonyTandemProtocol.CMD_POWER_RET_PARAM
                    || cmd == SonyTandemProtocol.CMD_EQEBB_RET_PARAM
                    || cmd == SonyTandemProtocol.CMD_SYSTEM_RET_PARAM
                    || cmd == SonyTandemProtocol.CMD_SYSTEM_NTFY_PARAM
                    || cmd == SonyTandemProtocol.CMD_SYSTEM_RET_EXT_PARAM
                    || cmd == SonyTandemProtocol.CMD_SYSTEM_NTFY_EXT_PARAM
                    || cmd == SonyTandemProtocol.CMD_AUDIO_RET_PARAM
                    || cmd == SonyTandemProtocol.CMD_GS_RET_CAPABILITY
                    || cmd == SonyTandemProtocol.CMD_GS_RET_PARAM
                    || cmd == SonyTandemProtocol.CMD_GS_NTFY_PARAM
                    || cmd == SonyTandemProtocol.CMD_ALERT_NTFY_PARAM
                    || cmd == SonyTandemProtocol.CMD_NCASM_RET_PARAM
                    || cmd == SonyTandemProtocol.CMD_NCASM_NTFY_PARAM) {
                XposedCompat.log((isTable2 ? "RX2" : "RX") + " cmd=0x" + Integer.toHexString(cmd)
                        + " hex=" + SonyTandemProtocol.toHex(frame.payload));
            } else {
                XposedCompat.debug("RX cmd=0x" + Integer.toHexString(cmd) + " hex=" + SonyTandemProtocol.toHex(frame.payload));
            }
            if (!isTable2 && cmd == SonyTandemProtocol.CMD_ALERT_NTFY_PARAM && frame.payload.length >= 3
                    && activeProtocolVersion() == SonyTandemProtocol.PROTOCOL_V2) {
                int alertInq = frame.payload[1] & 0xFF;
                int alertMsg = frame.payload[2] & 0xFF;
                enqueueRaw(SonyTandemProtocol.buildReplyAlert(alertInq, alertMsg, true));
            }
            if (!isTable2 && (cmd == SonyTandemProtocol.CMD_AUDIO_RET_PARAM || cmd == SonyTandemProtocol.CMD_AUDIO_NTFY_PARAM)
                    && frame.payload.length >= 3
                    && activeProtocolVersion() == SonyTandemProtocol.PROTOCOL_V2) {
                int inq = frame.payload[1] & 0xFF;
                int raw = frame.payload[2] & 0xFF;
                if (raw <= 0x01 && (inq == SonyTandemProtocol.AUDIO_INQ_UPSCALING
                        || inq == SonyTandemProtocol.AUDIO_INQ_UPSCALING_WITH_REASON)) {
                    if (!(dseeInquiredType == SonyTandemProtocol.AUDIO_INQ_UPSCALING_WITH_REASON
                            && inq == SonyTandemProtocol.AUDIO_INQ_UPSCALING)) {
                        dseeInquiredType = inq;
                    }
                }
            }
            boolean changed;
            boolean validNcAsm = false;
            int periRefreshInq = -1;
            synchronized (this) {
                changed = isTable2
                        ? SonyTandemProtocol.parseTable2Payload(frame.payload, state)
                        : SonyTandemProtocol.parsePayload(frame.payload, state);
                if (rfcommLockedProtocolVersion == SonyTandemProtocol.PROTOCOL_V1
                        && state.protocolVersion != SonyTandemProtocol.PROTOCOL_V1) {
                    state.protocolVersion = SonyTandemProtocol.PROTOCOL_V1;
                }
                if (!isTable2 && (cmd == SonyTandemProtocol.CMD_NCASM_RET_PARAM
                        || cmd == SonyTandemProtocol.CMD_NCASM_NTFY_PARAM)) {
                    int inq = frame.payload[1] & 0xFF;
                    if (state.supportsNcAsm
                            && frame.payload.length >= SonyTandemProtocol.minNcAsmPayloadLength(state.protocolVersion, inq)
                            && inq == state.ncAsmInquiredType) {
                        validNcAsm = true;
                    }
                }
                if (isTable2 && cmd == SonyTandemProtocol.CMD_T2_PERI_NTFY_EXTENDED_PARAM && frame.payload.length >= 2) {
                    periRefreshInq = state.multipointInquiredType;
                }
            }
            if (!isTable2 && cmd == SonyTandemProtocol.CMD_GS_RET_CAPABILITY && frame.payload.length >= 2
                    && activeProtocolVersion() == SonyTandemProtocol.PROTOCOL_V2) {
                enqueueRaw(SonyTandemProtocol.buildGetGsParam(frame.payload[1] & 0xFF));
            }
            if (periRefreshInq >= 0) {
                enqueueRaw(true, SonyTandemProtocol.buildT2GetPeripheralParam(periRefreshInq));
            }
            if (validNcAsm) {
                ncAsmDiscovered = true;
            }
            if (changed) {
                notifyListener();
            }
        }
    }

    private void sendAck(byte inboundSeq) {
        OutputStream os = outStream;
        if (os == null) {
            return;
        }
        try {
            synchronized (writeLock) {
                os.write(SonyTandemFraming.encodeAck(inboundSeq));
                os.flush();
            }
        } catch (Throwable ignored) {
        }
    }

    private void notifyListener() {
        if (listener != null) {
            listener.onStateUpdated(device, getStateSnapshot());
        }
    }

    private void closeSocket() {
        BluetoothSocket s = this.socket;
        this.socket = null;
        this.outStream = null;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String safeAddress(BluetoothDevice d) {
        try {
            String a = d.getAddress();
            return a != null ? a : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            if (n != null && !n.trim().isEmpty()) {
                return n.trim();
            }
        } catch (Throwable ignored) {
        }
        return "Sony Headphones";
    }
}
