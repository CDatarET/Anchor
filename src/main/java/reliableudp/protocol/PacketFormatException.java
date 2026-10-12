package reliableudp.protocol;

/** Thrown by {@link PacketCodec#decode} when a datagram is not a valid protocol packet. */
public class PacketFormatException extends Exception {
    private static final long serialVersionUID = 1L;
    private final boolean checksumFailure;

    public PacketFormatException(String message) {
        this(message, false);
    }

    public PacketFormatException(String message, boolean checksumFailure) {
        super(message);
        this.checksumFailure = checksumFailure;
    }

    /** @return true if the packet was structurally fine but its CRC-32 did not match (corruption). */
    public boolean isChecksumFailure() {
        return checksumFailure;
    }
}
