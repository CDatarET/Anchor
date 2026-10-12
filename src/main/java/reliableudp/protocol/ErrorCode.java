package reliableudp.protocol;

/** Reason codes carried in the payload of an {@link PacketType#ERROR} packet (2 bytes on the wire). */
public enum ErrorCode {
    BAD_REQUEST(1),
    BAD_FILENAME(2),
    FILE_TOO_LARGE(3),
    SERVER_BUSY(4),
    UNKNOWN_SESSION(5),
    SESSION_EXISTS(6),
    INCOMPLETE_TRANSFER(7),
    INTERNAL_ERROR(8),
    PROTOCOL_VIOLATION(9),
    UNKNOWN(0);

    private final int code;

    ErrorCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ErrorCode fromCode(int code) {
        for (ErrorCode e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return UNKNOWN;
    }
}
