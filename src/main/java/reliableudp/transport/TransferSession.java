package reliableudp.transport;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import reliableudp.protocol.Packet;
import reliableudp.protocol.TransferInfo;
import reliableudp.util.Logger;

/**
 * State of one transfer session, shared by client and server. A session is identified by its
 * 32-bit {@code sessionId} (chosen by the client, confirmed by the server in SYN_ACK) together
 * with the peer's address.
 *
 * <pre>
 * Client:  INIT --SYN--> SYN_SENT --SYN_ACK--> ESTABLISHED --all DATA acked, FIN--> FIN_WAIT --FIN_ACK--> COMPLETE
 * Server:  (SYN received, SYN_ACK sent) ESTABLISHED --FIN with all data--> COMPLETE (lingers) --> removed
 * Either:  any state --error / retry limit / idle timeout--> FAILED
 * </pre>
 */
public final class TransferSession {

    public enum State { INIT, SYN_SENT, ESTABLISHED, FIN_WAIT, COMPLETE, FAILED }

    private final long sessionId;
    private final InetSocketAddress peer;
    private volatile State state = State.INIT;
    private volatile long lastActivityNanos = System.nanoTime();

    private TransferInfo info;

    // server-side extras
    private ReliableReceiver receiver;
    private Path targetFile;
    private Packet finAck;
    private long lingerUntilNanos;

    public TransferSession(long sessionId, InetSocketAddress peer) {
        this.sessionId = sessionId;
        this.peer = peer;
    }

    public long sessionId() { return sessionId; }
    public InetSocketAddress peer() { return peer; }
    public State state() { return state; }

    public void transition(State next) {
        Logger.debug("session %08x: %s -> %s", sessionId, state, next);
        state = next;
    }

    public void touch() { lastActivityNanos = System.nanoTime(); }
    public long idleNanos() { return System.nanoTime() - lastActivityNanos; }

    public TransferInfo info() { return info; }
    public void setInfo(TransferInfo info) { this.info = info; }
    public long totalPackets() { return info.totalPackets(); }

    public ReliableReceiver receiver() { return receiver; }
    public void setReceiver(ReliableReceiver r) { this.receiver = r; }
    public Path targetFile() { return targetFile; }
    public void setTargetFile(Path p) { this.targetFile = p; }
    public Packet finAck() { return finAck; }
    public void setFinAck(Packet p) { this.finAck = p; }
    public long lingerUntilNanos() { return lingerUntilNanos; }
    public void setLingerUntilNanos(long n) { this.lingerUntilNanos = n; }

    /** Key used by the server to look sessions up: peer address + session id. */
    public static String key(InetSocketAddress peer, long sessionId) {
        return peer.getAddress().getHostAddress() + ":" + peer.getPort() + "/" + Long.toHexString(sessionId);
    }
}
