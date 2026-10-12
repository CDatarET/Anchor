package reliableudp.protocol;

/**
 * The seven packet types of the protocol. The numeric code is what travels on the wire
 * (1 byte, offset 1 of the header).
 */
public enum PacketType {
    /** Client -> server: open a transfer session; payload = {@link TransferInfo}. */
    SYN(1),
    /** Server -> client: session accepted; carries the receiver's advertised window. */
    SYN_ACK(2),
    /** Client -> server: one chunk of file data; {@code seq} is the chunk index. */
    DATA(3),
    /** Server -> client: acknowledgement (cumulative {@code ack} + echoed {@code seq}). */
    ACK(4),
    /** Client -> server: all data was acknowledged, request orderly close. */
    FIN(5),
    /** Server -> client: transfer finished; payload = {@link FinalReport}. */
    FIN_ACK(6),
    /** Either direction: protocol/transfer error; payload = error code + text. */
    ERROR(7);

    private final int code;

    PacketType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** @return the type for a wire code, or {@code null} if the code is unknown. */
    public static PacketType fromCode(int code) {
        for (PacketType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return null;
    }
}
