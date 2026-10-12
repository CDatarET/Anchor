package reliableudp.protocol;

import java.nio.charset.StandardCharsets;

/**
 * An immutable, decoded protocol packet. Unsigned wire fields are held in wider Java types
 * ({@code long} for 32-bit fields, {@code int} for 16-bit) so that Java's signed arithmetic never
 * produces negative sequence numbers.
 *
 * <p>Field meaning per type:
 * <pre>
 *   SYN      session=client-chosen id, seq=0,           payload=TransferInfo
 *   SYN_ACK  session=echoed id,        ack=0, window=receiver capacity
 *   DATA     session, seq=chunk index (0-based), payload=file bytes
 *   ACK      session, ack=cumulative (next expected seq), seq=echo of the DATA seq that triggered it,
 *            window=free receiver slots
 *   FIN      session, seq=total number of DATA packets
 *   FIN_ACK  session, seq=ack=total number of DATA packets, payload=FinalReport
 *   ERROR    session, payload=2-byte ErrorCode + UTF-8 text
 * </pre>
 */
public record Packet(PacketType type, long sessionId, long seq, long ack, int window, byte[] payload) {

    private static final byte[] EMPTY = new byte[0];

    public Packet {
        if (type == null) {
            throw new IllegalArgumentException("type is null");
        }
        if (sessionId < 0 || sessionId > ProtocolConfig.U32_MAX
                || seq < 0 || seq > ProtocolConfig.U32_MAX
                || ack < 0 || ack > ProtocolConfig.U32_MAX) {
            throw new IllegalArgumentException("32-bit field out of range");
        }
        if (window < 0 || window > ProtocolConfig.U16_MAX) {
            throw new IllegalArgumentException("window out of range: " + window);
        }
        if (payload == null) {
            payload = EMPTY;
        }
        if (payload.length > ProtocolConfig.MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload too large: " + payload.length);
        }
    }

    // ---- factories ---------------------------------------------------------------------------

    public static Packet syn(long sessionId, TransferInfo info) {
        return new Packet(PacketType.SYN, sessionId, 0, 0, 0, PacketCodec.encodeTransferInfo(info));
    }

    public static Packet synAck(long sessionId, int receiverWindow) {
        return new Packet(PacketType.SYN_ACK, sessionId, 0, 0, receiverWindow, EMPTY);
    }

    public static Packet data(long sessionId, long seq, byte[] chunk) {
        return new Packet(PacketType.DATA, sessionId, seq, 0, 0, chunk);
    }

    public static Packet ack(long sessionId, long cumulativeAck, long echoedSeq, int window) {
        return new Packet(PacketType.ACK, sessionId, echoedSeq, cumulativeAck, window, EMPTY);
    }

    public static Packet fin(long sessionId, long totalPackets) {
        return new Packet(PacketType.FIN, sessionId, totalPackets, 0, 0, EMPTY);
    }

    public static Packet finAck(long sessionId, long totalPackets, FinalReport report) {
        return new Packet(PacketType.FIN_ACK, sessionId, totalPackets, totalPackets, 0,
                PacketCodec.encodeFinalReport(report));
    }

    public static Packet error(long sessionId, ErrorCode code, String message) {
        byte[] text = message == null ? EMPTY : message.getBytes(StandardCharsets.UTF_8);
        int len = Math.min(text.length, 200);
        byte[] payload = new byte[2 + len];
        payload[0] = (byte) (code.code() >>> 8);
        payload[1] = (byte) code.code();
        System.arraycopy(text, 0, payload, 2, len);
        return new Packet(PacketType.ERROR, sessionId, 0, 0, 0, payload);
    }

    // ---- ERROR helpers -----------------------------------------------------------------------

    /** @return the error code of an ERROR packet (UNKNOWN for other types / short payloads). */
    public ErrorCode errorCode() {
        if (type != PacketType.ERROR || payload.length < 2) {
            return ErrorCode.UNKNOWN;
        }
        return ErrorCode.fromCode(((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF));
    }

    /** @return the human-readable text of an ERROR packet (empty for other types). */
    public String errorText() {
        if (type != PacketType.ERROR || payload.length < 2) {
            return "";
        }
        return new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return type + "{session=" + Long.toHexString(sessionId) + ", seq=" + seq + ", ack=" + ack
                + ", win=" + window + ", len=" + payload.length + "}";
    }
}
