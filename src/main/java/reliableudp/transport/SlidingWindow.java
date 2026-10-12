package reliableudp.transport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sender-side bookkeeping for <b>Selective Repeat</b>.
 *
 * <p>Sequence numbers are packet indices 0 .. total-1. The window is the half-open range
 * {@code [base, base + effectiveWindow)}: {@code base} is the lowest sequence number that has not
 * been acknowledged yet, {@code nextSeq} the next one to send for the first time. Every packet in
 * {@code [base, nextSeq)} is "in flight" and has its own retransmission deadline; acknowledgements
 * mark packets individually, and the window slides past a contiguous run of acknowledged packets.
 *
 * <p>Not thread-safe (used from the single sender thread).
 */
public final class SlidingWindow {

    /** One packet that has been sent at least once. */
    public static final class Entry {
        private final long seq;
        private byte[] payload;
        private final long firstSentNanos;
        private long deadlineNanos;
        private int retransmissions;
        private boolean acked;

        Entry(long seq, byte[] payload, long now, long deadline) {
            this.seq = seq;
            this.payload = payload;
            this.firstSentNanos = now;
            this.deadlineNanos = deadline;
        }

        public long seq() { return seq; }
        public byte[] payload() { return payload; }
        public long firstSentNanos() { return firstSentNanos; }
        public int retransmissions() { return retransmissions; }
        public boolean isAcked() { return acked; }
    }

    /** Result of processing one ACK. */
    public record AckOutcome(int newlyAcked, Entry selectiveEntry) { }

    private final long totalPackets;
    private long base;
    private long nextSeq;
    private long ackedPackets;
    private final Map<Long, Entry> inFlight = new HashMap<>();

    public SlidingWindow(long totalPackets) {
        this.totalPackets = totalPackets;
    }

    public long base() { return base; }
    public long nextSeq() { return nextSeq; }
    public long totalPackets() { return totalPackets; }
    public long ackedPackets() { return ackedPackets; }
    public boolean isComplete() { return base >= totalPackets; }

    /** @return number of packets sent but not yet acknowledged. */
    public int outstanding() {
        int n = 0;
        for (Entry e : inFlight.values()) {
            if (!e.acked) {
                n++;
            }
        }
        return n;
    }

    /** @return true if a new packet may be sent now: data remains and nextSeq is inside the window. */
    public boolean canSend(int effectiveWindow) {
        return nextSeq < totalPackets && nextSeq < base + effectiveWindow;
    }

    /** Registers the next new packet as sent at {@code nowNanos}; returns its entry. */
    public Entry register(byte[] payload, long nowNanos, long timeoutNanos) {
        Entry e = new Entry(nextSeq, payload, nowNanos, nowNanos + timeoutNanos);
        inFlight.put(nextSeq, e);
        nextSeq++;
        return e;
    }

    /**
     * Applies an ACK carrying a cumulative number and the echoed (selective) sequence number.
     * Both forms are honoured: everything below {@code cumulativeAck} is acknowledged, plus the
     * single packet {@code selectiveSeq}. Out-of-range values are ignored.
     */
    public AckOutcome onAck(long cumulativeAck, long selectiveSeq) {
        int newly = 0;
        Entry selective = null;
        if (selectiveSeq >= base && selectiveSeq < nextSeq) {
            Entry e = inFlight.get(selectiveSeq);
            if (e != null && !e.acked) {
                markAcked(e);
                newly++;
                selective = e;
            }
        }
        if (cumulativeAck > base && cumulativeAck <= nextSeq) {
            for (long s = base; s < cumulativeAck; s++) {
                Entry e = inFlight.get(s);
                if (e != null && !e.acked) {
                    markAcked(e);
                    newly++;
                }
            }
        }
        while (base < nextSeq) { // slide the window over the acknowledged prefix
            Entry e = inFlight.get(base);
            if (e == null || !e.acked) {
                break;
            }
            inFlight.remove(base);
            base++;
        }
        return new AckOutcome(newly, selective);
    }

    private void markAcked(Entry e) {
        e.acked = true;
        e.payload = null; // free memory; it will never be retransmitted
        ackedPackets++;
    }

    /** @return the unacknowledged packets whose deadline has passed, lowest sequence number first. */
    public List<Entry> expired(long nowNanos) {
        List<Entry> out = new ArrayList<>();
        for (long s = base; s < nextSeq; s++) {
            Entry e = inFlight.get(s);
            if (e != null && !e.acked && e.deadlineNanos - nowNanos <= 0) {
                out.add(e);
            }
        }
        return out;
    }

    /** Records that {@code e} was retransmitted at {@code nowNanos} and re-arms its timer. */
    public void markRetransmitted(Entry e, long nowNanos, long timeoutNanos) {
        e.retransmissions++;
        e.deadlineNanos = nowNanos + timeoutNanos;
    }

    /** @return the earliest retransmission deadline among unacknowledged packets, or {@code Long.MAX_VALUE}. */
    public long nextDeadlineNanos() {
        long best = Long.MAX_VALUE;
        boolean any = false;
        for (Entry e : inFlight.values()) {
            if (!e.acked && (!any || e.deadlineNanos - best < 0)) {
                best = e.deadlineNanos;
                any = true;
            }
        }
        return any ? best : Long.MAX_VALUE;
    }
}
