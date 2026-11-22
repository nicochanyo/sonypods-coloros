package io.github.nicochanyo.sonypods;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Binary framing codec for Sony Tandem V1/V2 over RFCOMM/SPP and GATT.
 * Frame format: 0x3E + escaped([dataType(1B), seq(1B), len(4B BE), payload..., checksum(1B)]) + 0x3C.
 */
public final class SonyTandemFraming {
    public static final byte FRAME_START = 0x3E;
    public static final byte FRAME_END = 0x3C;
    public static final byte ESCAPE = 0x3D;

    public static final byte DATA_TYPE_ACK = 0x01;
    public static final byte DATA_TYPE_DATA_MDR = 0x0C;
    public static final byte DATA_TYPE_DATA_MDR_NO2 = 0x0E;
    public static final byte DATA_TYPE_SHOT_MDR = 0x1C;
    public static final byte DATA_TYPE_SHOT_MDR_NO2 = 0x1E;
    public static final byte DATA_TYPE_LARGE_DATA_MDR = 0x2C;

    private SonyTandemFraming() {
    }

    public static final class DecodedFrame {
        public final byte dataType;
        public final byte sequence;
        public final byte[] payload;

        public DecodedFrame(byte dataType, byte sequence, byte[] payload) {
            this.dataType = dataType;
            this.sequence = sequence;
            this.payload = payload;
        }

        public boolean isAck() {
            return dataType == DATA_TYPE_ACK;
        }

        public boolean requiresAck() {
            return dataType == DATA_TYPE_DATA_MDR
                    || dataType == DATA_TYPE_DATA_MDR_NO2
                    || dataType == DATA_TYPE_LARGE_DATA_MDR;
        }
    }

    public static byte inverseSequence(byte seq) {
        return (byte) (1 - (seq & 0xFF));
    }

    public static byte[] encodeAck(byte inboundSeq) {
        return encodeFrame(DATA_TYPE_ACK, inverseSequence(inboundSeq), new byte[0]);
    }

    public static byte[] encodeDataMdr(byte seq, byte[] payload) {
        return encodeFrame(DATA_TYPE_DATA_MDR, seq, payload);
    }

    public static byte[] encodeFrame(byte dataType, byte sequence, byte[] payload) {
        int len = payload != null ? payload.length : 0;
        byte[] raw = new byte[6 + len + 1];
        raw[0] = dataType;
        raw[1] = sequence;
        raw[2] = (byte) ((len >>> 24) & 0xFF);
        raw[3] = (byte) ((len >>> 16) & 0xFF);
        raw[4] = (byte) ((len >>> 8) & 0xFF);
        raw[5] = (byte) (len & 0xFF);
        if (len > 0) {
            System.arraycopy(payload, 0, raw, 6, len);
        }
        int sum = 0;
        for (int i = 0; i < 6 + len; i++) {
            sum += (raw[i] & 0xFF);
        }
        raw[6 + len] = (byte) (sum & 0xFF);

        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length + 12);
        out.write(FRAME_START);
        for (byte b : raw) {
            if (b == FRAME_END || b == ESCAPE || b == FRAME_START) {
                out.write(ESCAPE);
                out.write(b & 0xEF); // 0x3C->0x2C, 0x3D->0x2D, 0x3E->0x2E
            } else {
                out.write(b & 0xFF);
            }
        }
        out.write(FRAME_END);
        return out.toByteArray();
    }

    public static DecodedFrame decodeFrame(byte[] escapedPacket) {
        if (escapedPacket == null || escapedPacket.length < 9) {
            return null;
        }
        int start = 0;
        int end = escapedPacket.length;
        if (escapedPacket[0] == FRAME_START) {
            start++;
        }
        if (end > start && escapedPacket[end - 1] == FRAME_END) {
            end--;
        }

        ByteArrayOutputStream unescaped = new ByteArrayOutputStream(end - start);
        boolean escaping = false;
        for (int i = start; i < end; i++) {
            byte b = escapedPacket[i];
            if (escaping) {
                unescaped.write((b | 0x10) & 0xFF);
                escaping = false;
            } else if (b == ESCAPE) {
                escaping = true;
            } else {
                unescaped.write(b & 0xFF);
            }
        }
        byte[] raw = unescaped.toByteArray();
        if (raw.length < 7) {
            return null;
        }
        int declaredLen = ((raw[2] & 0xFF) << 24)
                | ((raw[3] & 0xFF) << 16)
                | ((raw[4] & 0xFF) << 8)
                | (raw[5] & 0xFF);
        if (declaredLen < 0 || 6 + declaredLen + 1 != raw.length) {
            return null;
        }
        int sum = 0;
        for (int i = 0; i < raw.length - 1; i++) {
            sum += (raw[i] & 0xFF);
        }
        if ((byte) (sum & 0xFF) != raw[raw.length - 1]) {
            return null;
        }
        byte[] payload = new byte[declaredLen];
        if (declaredLen > 0) {
            System.arraycopy(raw, 6, payload, 0, declaredLen);
        }
        return new DecodedFrame(raw[0], raw[1], payload);
    }

    /**
     * Stateful stream accumulator extracting complete 0x3E..0x3C frames from RFCOMM reads.
     */
    public static final class StreamParser {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        private boolean inFrame = false;

        public synchronized List<DecodedFrame> feed(byte[] chunk, int length) {
            List<DecodedFrame> out = new ArrayList<>();
            if (chunk == null || length <= 0) {
                return out;
            }
            for (int i = 0; i < length; i++) {
                byte b = chunk[i];
                if (b == FRAME_START) {
                    buffer.reset();
                    buffer.write(b & 0xFF);
                    inFrame = true;
                } else if (inFrame) {
                    buffer.write(b & 0xFF);
                    if (b == FRAME_END) {
                        DecodedFrame decoded = decodeFrame(buffer.toByteArray());
                        if (decoded != null) {
                            out.add(decoded);
                        }
                        buffer.reset();
                        inFrame = false;
                    } else if (buffer.size() > 16384) {
                        buffer.reset();
                        inFrame = false;
                    }
                }
            }
            return out;
        }

        public synchronized void reset() {
            buffer.reset();
            inFrame = false;
        }
    }
}
