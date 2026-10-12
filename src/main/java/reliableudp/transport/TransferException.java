package reliableudp.transport;

/** A transfer failed for a protocol-level reason (peer unreachable, remote ERROR, retry limit, ...). */
public class TransferException extends Exception {
    private static final long serialVersionUID = 1L;

    public TransferException(String message) {
        super(message);
    }

    public TransferException(String message, Throwable cause) {
        super(message, cause);
    }
}
