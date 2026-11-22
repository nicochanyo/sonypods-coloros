package io.github.nicochanyo.sonypods;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Manages the active RFCOMM/SPP Tandem V1/V2 link to a Sony headset.
 * Runs in the com.android.bluetooth process when hooked by BluetoothHook.
 */
public final class SonyTandemSession {
    public interface Listener {
        void onStateUpdated(BluetoothDevice device, ConfigStore.HeadsetState state);
    }

    private final BluetoothDevice device;
    private final Listener listener;
    private final ConfigStore.HeadsetState state;
    private final BlockingQueue<byte[]> outboundQueue = new LinkedBlockingQueue<>();

    private volatile boolean running;
    private volatile boolean yielded;
    private volatile boolean ncAsmDiscovered;
    private volatile int dseeInquiredType = SonyTandemProtocol.AUDIO_INQ_UPSCALING;
    private volatile BluetoothSocket socket;
    private volatile OutputStream outStream;

    private final Object ackLock = new Object();
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

    public void enqueueRefresh() {
        if (!running || yielded) {
            return;
        }
        int ver = state.protocolVersion;
        enqueueRaw(SonyTandemProtocol.buildGetBattery(ver, SonyTandemProtocol.POWER_TYPE_LEFT_RIGHT_BATTERY));
        enqueueRaw(SonyTandemProtocol.buildGetBattery(ver, SonyTandemProtocol.POWER_TYPE_CRADLE_BATTERY));
        enqueueRaw(SonyTandemProtocol.buildGetBattery(ver, SonyTandemProtocol.POWER_TYPE_BATTERY));
        if (ncAsmDiscovered) {
            enqueueRaw(SonyTandemProtocol.buildGetNcAsmParam(state.ncAsmInquiredType));
        }
    }

    public void setNcAsm(int ancMode, int asmLevel, boolean voiceFocus) {
        int lastNcVal;
        int ver;
        int inq;
        int clampedLvl = ConfigStore.clampAsmLevel(asmLevel);
        synchronized (this) {
            state.ancMode = ancMode;
            state.asmLevel = clampedLvl;
            state.voiceFocus = voiceFocus;
            state.updatedAtMs = System.currentTimeMillis();
            lastNcVal = state.lastNcValue;
            ver = state.protocolVersion;
            inq = state.ncAsmInquiredType;
        }
        notifyListener();
        enqueueRaw(SonyTandemProtocol.buildSetNcAsm(
                ver,
                inq,
                ancMode,
                clampedLvl,
                voiceFocus,
                lastNcVal));
    }

    /** Updates ambient level / voice focus immediately when active mode is Ambient, otherwise stores defaults. */
    public void updateAmbientPreferences(int asmLevel, boolean voiceFocus) {
        int activeAncMode;
        int lastNcVal;
        int ver;
        int inq;
        int clampedLvl = ConfigStore.clampAsmLevel(asmLevel);
        synchronized (this) {
            state.asmLevel = clampedLvl;
            state.voiceFocus = voiceFocus;
            state.updatedAtMs = System.currentTimeMillis();
            activeAncMode = state.ancMode;
            lastNcVal = state.lastNcValue;
            ver = state.protocolVersion;
            inq = state.ncAsmInquiredType;
        }
        notifyListener();
        if (activeAncMode == ConfigStore.ANC_AMBIENT) {
            enqueueRaw(SonyTandemProtocol.buildSetNcAsm(
                    ver,
                    inq,
                    activeAncMode,
                    clampedLvl,
                    voiceFocus,
                    lastNcVal));
        }
    }

    public void setSpeakToChat(boolean enabled) {
        int inq;
        synchronized (this) {
            state.speakToChat = enabled;
            state.updatedAtMs = System.currentTimeMillis();
            inq = state.speakToChatInquiredType;
        }
        notifyListener();
        enqueueRaw(SonyTandemProtocol.buildSetSpeakToChat(inq, enabled));
    }

    public void setDsee(boolean enabled) {
        synchronized (this) {
            state.dsee = enabled;
            state.updatedAtMs = System.currentTimeMillis();
        }
        notifyListener();
        enqueueRaw(SonyTandemProtocol.buildSetDsee(dseeInquiredType, enabled));
    }

    private void enqueueRaw(byte[] payload) {
        if (payload != null && running) {
            outboundQueue.offer(payload);
        }
    }

    private void runSessionLoop() {
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
                try {
                    Thread.sleep(3500L);
                } catch (InterruptedException ignored) {
                }
                continue;
            }

            this.socket = sock;
            try {
                this.outStream = sock.getOutputStream();
                InputStream in = sock.getInputStream();

                outboundQueue.clear();
                txSeq = 0;
                ncAsmDiscovered = false;

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
                    Thread.sleep(2500L);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private BluetoothSocket connectRfcommSocket() {
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

        for (UUID uuid : candidates) {
            if (!running || yielded) {
                return null;
            }
            BluetoothSocket s = tryConnectUuid(uuid, false);
            if (s != null) {
                if (uuid.equals(SonyDeviceDetector.UUID_TANDEM_V1) || uuid.equals(SonyDeviceDetector.UUID_TANDEM_V1_REV)) {
                    synchronized (this) {
                        state.protocolVersion = 1;
                    }
                } else if (uuid.equals(SonyDeviceDetector.UUID_TANDEM_V2) || uuid.equals(SonyDeviceDetector.UUID_TANDEM_V2_REV)) {
                    synchronized (this) {
                        state.protocolVersion = 2;
                    }
                }
                XposedCompat.log("Connected Sony RFCOMM (" + state.mac + ") via UUID " + uuid);
                return s;
            }
            s = tryConnectUuid(uuid, true);
            if (s != null) {
                XposedCompat.log("Connected Sony insecure RFCOMM (" + state.mac + ") via UUID " + uuid);
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
                    byte[] payload = outboundQueue.poll(1500L, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (payload != null) {
                        sendReliableFrame(payload);
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

    private void sendReliableFrame(byte[] payload) {
        OutputStream os = outStream;
        if (os == null) {
            return;
        }
        byte seq = txSeq;
        byte[] packet = SonyTandemFraming.encodeDataMdr(seq, payload);

        for (int attempt = 0; attempt < 2; attempt++) {
            if (!running || yielded || outStream == null) {
                return;
            }
            synchronized (ackLock) {
                awaitingAck = true;
                expectedAckSeq = SonyTandemFraming.inverseSequence(seq);
            }
            try {
                os.write(packet);
                os.flush();
                XposedCompat.debug("TX seq=" + (seq & 0xFF) + " cmd=0x" + Integer.toHexString(payload[0] & 0xFF));
            } catch (Throwable t) {
                return;
            }

            long deadline = System.currentTimeMillis() + 850L;
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
        enqueueRaw(SonyTandemProtocol.buildGetProtocolInfo());
        enqueueRaw(SonyTandemProtocol.buildGetSupportFunction());
        enqueueRaw(SonyTandemProtocol.buildGetDeviceInfo(SonyTandemProtocol.DEVICE_INFO_MODEL_NAME));
        enqueueRaw(SonyTandemProtocol.buildGetDeviceInfo(SonyTandemProtocol.DEVICE_INFO_SERIES_AND_COLOR));

        int ver = state.protocolVersion;
        enqueueRaw(SonyTandemProtocol.buildGetBattery(ver, SonyTandemProtocol.POWER_TYPE_LEFT_RIGHT_BATTERY));
        enqueueRaw(SonyTandemProtocol.buildGetBattery(ver, SonyTandemProtocol.POWER_TYPE_CRADLE_BATTERY));
        enqueueRaw(SonyTandemProtocol.buildGetBattery(ver, SonyTandemProtocol.POWER_TYPE_BATTERY));

        // Probe NC/ASM inquired types in background thread until headset responds with its active type
        Thread probeThread = new Thread(() -> {
            try {
                Thread.sleep(180L);
            } catch (InterruptedException e) {
                return;
            }
            int[] types = (state.protocolVersion == SonyTandemProtocol.PROTOCOL_V1)
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
                enqueueRaw(SonyTandemProtocol.buildGetSystemParam(SonyTandemProtocol.SYS_INQ_SMART_TALKING_V2));
                enqueueRaw(SonyTandemProtocol.buildGetSystemParam(SonyTandemProtocol.SYS_INQ_SMART_TALKING_V1));
                enqueueRaw(SonyTandemProtocol.buildGetAudioParam(SonyTandemProtocol.AUDIO_INQ_UPSCALING));
                enqueueRaw(SonyTandemProtocol.buildGetAudioParam(SonyTandemProtocol.AUDIO_INQ_UPSCALING_WITH_REASON));
                enqueueRaw(SonyTandemProtocol.buildGetEqEbbParam(0x00));
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
                    curVer = state.protocolVersion;
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
            int cmd = frame.payload[0] & 0xFF;
            if (cmd == SonyTandemProtocol.CMD_CONNECT_RET_DEVICE_INFO
                    || cmd == SonyTandemProtocol.CMD_V1_RET_BATTERY
                    || cmd == SonyTandemProtocol.CMD_V1_NTFY_BATTERY
                    || cmd == SonyTandemProtocol.CMD_V2_POWER_RET_STATUS
                    || cmd == SonyTandemProtocol.CMD_V2_POWER_NTFY_STATUS
                    || cmd == SonyTandemProtocol.CMD_SYSTEM_RET_PARAM
                    || cmd == SonyTandemProtocol.CMD_AUDIO_RET_PARAM
                    || cmd == SonyTandemProtocol.CMD_NCASM_RET_PARAM
                    || cmd == SonyTandemProtocol.CMD_NCASM_NTFY_PARAM) {
                XposedCompat.log("RX cmd=0x" + Integer.toHexString(cmd) + " hex=" + SonyTandemProtocol.toHex(frame.payload));
            } else {
                XposedCompat.debug("RX cmd=0x" + Integer.toHexString(cmd) + " hex=" + SonyTandemProtocol.toHex(frame.payload));
            }
            if ((cmd == SonyTandemProtocol.CMD_AUDIO_RET_PARAM || cmd == SonyTandemProtocol.CMD_AUDIO_NTFY_PARAM)
                    && frame.payload.length >= 2) {
                int inq = frame.payload[1] & 0xFF;
                if (inq == SonyTandemProtocol.AUDIO_INQ_UPSCALING
                        || inq == SonyTandemProtocol.AUDIO_INQ_UPSCALING_WITH_REASON) {
                    dseeInquiredType = inq;
                }
            }
            boolean changed;
            boolean validNcAsm = false;
            synchronized (this) {
                changed = SonyTandemProtocol.parsePayload(frame.payload, state);
                if (cmd == SonyTandemProtocol.CMD_NCASM_RET_PARAM
                        || cmd == SonyTandemProtocol.CMD_NCASM_NTFY_PARAM) {
                    int inq = frame.payload[1] & 0xFF;
                    if (state.supportsNcAsm
                            && frame.payload.length >= SonyTandemProtocol.minNcAsmPayloadLength(state.protocolVersion, inq)
                            && inq == state.ncAsmInquiredType) {
                        validNcAsm = true;
                    }
                }
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
            os.write(SonyTandemFraming.encodeAck(inboundSeq));
            os.flush();
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
