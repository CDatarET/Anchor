package reliableudp.transport;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.TreeMap;
import reliableudp.protocol.Packet;
import reliableudp.protocol.ProtocolConfig;
import reliableudp.util.Logger;

/**
 * Receiver side of <b>Selective Repeat</b> for one session. Socket-free on purpose: it takes a
 * DATA packet in and returns the ACK to send, so it can be unit-tested and reasoned about on its own.
 *
 * <ul>
 *   <li>{@code rcvBase} = next sequence number needed for in-order delivery.</li>
 *   <li>Accepted range: {@code [rcvBase, rcvBase + capacity)}.</li>
 *   <li>In order -> written to disk immediately, then any buffered successors are flushed too.</li>
 *   <li>Ahead of {@code rcvBase} -> buffered (out-of-order) and individually acknowledged.</li>
 *   <li>Below {@code rcvBase} or already buffered -> duplicate: counted, re-acknowledged, not written twice.</li>
 *   <li>Beyond the range -> dropped silently (the sender should never do this; it respects our advertised window).</li>
 * </ul>
 *
 * <p>Every ACK carries {@code ack = rcvBase} (cumulative: "I have everything below this"), the echoed
 * {@code seq} of the packet that triggered it (selective), and {@code window = capacity - buffered}.
 * Because the buffer can never hold {@code rcvBase} itself, window is always &gt;= 1.
 */
public final class ReliableReceiver implements Closeable {

    private final TransferSession session;
    private final Path partFile;
    private final int capacity;
    private final OutputStream out;
    private final TreeMap<Long, byte[]> buffer = new TreeMap<>();
    private final long total;
    private final int chunkSize;
    private final long fileSize;

    private long rcvBase;
    private long bytesWritten;
    private long dataReceived;
    private long duplicates;
    private long outOfOrder;
    private long outOfWindow;
    private long invalid;
    private boolean closed;

    public ReliableReceiver(TransferSession session, Path partFile, int capacity) throws IOException {
        this.session = session;
        this.partFile = partFile;
        this.capacity = Math.max(1, Math.min(capacity, ProtocolConfig.U16_MAX));
        this.total = session.totalPackets();
        this.chunkSize = session.info().chunkSize();
        this.fileSize = session.info().fileSize();
        this.out = new BufferedOutputStream(Files.newOutputStream(partFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE), 1 << 16);
    }

    /** Length a DATA payload must have for sequence number {@code seq}. */
    private int expectedLength(long seq) {
        if (seq < total - 1) {
            return chunkSize;
        }
        return (int) (fileSize - (total - 1) * (long) chunkSize); // last packet: the remainder
    }

    /**
     * Processes one DATA packet.
     *
     * @return the ACK to send, or {@code null} if the packet must not be acknowledged
     *         (invalid length / sequence number, or outside the receive window)
     */
    public Packet onData(Packet p) throws IOException {
        long seq = p.seq();
        if (seq >= total) {
            invalid++;
            Logger.debug("[rx %08x] DATA seq=%d beyond last packet %d: ignored", session.sessionId(), seq, total - 1);
            return null;
        }
        if (p.payload().length != expectedLength(seq)) {
            invalid++;
            Logger.warn("[rx %08x] DATA seq=%d has length %d, expected %d: ignored",
                    session.sessionId(), seq, p.payload().length, expectedLength(seq));
            return null;
        }
        dataReceived++;
        if (seq < rcvBase) {
            duplicates++;
            Logger.debug("[rx %08x] DATA seq=%d duplicate (already delivered)", session.sessionId(), seq);
            return ack(seq);
        }
        if (seq >= rcvBase + capacity) {
            outOfWindow++;
            Logger.debug("[rx %08x] DATA seq=%d outside window [%d,%d): dropped", session.sessionId(), seq,
                    rcvBase, rcvBase + capacity);
            return null;
        }
        if (seq == rcvBase) {
            write(p.payload());
            rcvBase++;
            byte[] next;
            while ((next = buffer.remove(rcvBase)) != null) {
                write(next);
                rcvBase++;
            }
            Logger.debug("[rx %08x] DATA seq=%d in order; delivered up to %d", session.sessionId(), seq, rcvBase - 1);
        } else if (buffer.containsKey(seq)) {
            duplicates++;
            Logger.debug("[rx %08x] DATA seq=%d duplicate (already buffered)", session.sessionId(), seq);
        } else {
            buffer.put(seq, p.payload());
            outOfOrder++;
            Logger.debug("[rx %08x] DATA seq=%d out of order (expecting %d); buffered=%d",
                    session.sessionId(), seq, rcvBase, buffer.size());
        }
        return ack(seq);
    }

    private Packet ack(long echoedSeq) {
        return Packet.ack(session.sessionId(), rcvBase, echoedSeq, capacity - buffer.size());
    }

    private void write(byte[] payload) throws IOException {
        out.write(payload);
        bytesWritten += payload.length;
    }

    /** @return true only when every DATA packet has been delivered in order. */
    public boolean isComplete() {
        return rcvBase == total && buffer.isEmpty();
    }

    /** Flushes and closes the part file. Safe to call more than once. */
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            out.close();
        }
    }

    public Path partFile() { return partFile; }
    public long nextExpected() { return rcvBase; }
    public int bufferedCount() { return buffer.size(); }
    public int capacity() { return capacity; }
    public long bytesWritten() { return bytesWritten; }
    public long dataReceived() { return dataReceived; }
    public long duplicates() { return duplicates; }
    public long outOfOrder() { return outOfOrder; }
    public long outOfWindow() { return outOfWindow; }
    public long invalid() { return invalid; }
}
