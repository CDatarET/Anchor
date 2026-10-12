package reliableudp.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SlidingWindowTest {

    private static final long MS = 1_000_000L;

    private static SlidingWindow filled(long total, int window) {
        SlidingWindow w = new SlidingWindow(total);
        while (w.canSend(window)) {
            w.register(new byte[] {1}, 0, 100 * MS);
        }
        return w;
    }

    @Test
    void windowLimitsPacketsInFlight() {
        SlidingWindow w = filled(100, 5);
        assertEquals(5, w.nextSeq());
        assertEquals(5, w.outstanding());
        assertFalse(w.canSend(5));
        assertTrue(w.canSend(6), "a larger window would allow one more");
    }

    @Test
    void selectiveAckDoesNotSlideWindowPastAHole() {
        SlidingWindow w = filled(100, 5);
        SlidingWindow.AckOutcome o = w.onAck(0, 2); // packet 2 received, 0 and 1 still missing
        assertEquals(1, o.newlyAcked());
        assertNotNull(o.selectiveEntry());
        assertEquals(0, w.base());
        assertEquals(4, w.outstanding());
        assertFalse(w.canSend(5), "window cannot advance while seq 0 is missing");
        w.onAck(0, 0);
        assertEquals(1, w.base());
        w.onAck(0, 1); // now 0,1,2 are acked: base jumps over the contiguous run
        assertEquals(3, w.base());
        assertTrue(w.canSend(5));
    }

    @Test
    void cumulativeAckCoversEverythingBelowIt() {
        SlidingWindow w = filled(100, 8);
        SlidingWindow.AckOutcome o = w.onAck(5, 4);
        assertEquals(5, o.newlyAcked());
        assertEquals(5, w.base());
        assertEquals(3, w.outstanding());
    }

    @Test
    void duplicateAndOutOfRangeAcksAreHarmless() {
        SlidingWindow w = filled(100, 4);
        w.onAck(2, 1);
        assertEquals(2, w.base());
        SlidingWindow.AckOutcome again = w.onAck(2, 1); // same ACK again
        assertEquals(0, again.newlyAcked());
        assertNull(again.selectiveEntry());
        assertEquals(0, w.onAck(999, 999).newlyAcked(), "ack for something never sent");
        assertEquals(0, w.onAck(1, 0).newlyAcked(), "stale ack below base");
        assertEquals(2, w.base());
    }

    @Test
    void onlyExpiredUnackedPacketsAreRetransmitCandidates() {
        SlidingWindow w = new SlidingWindow(10);
        w.register(new byte[1], 0, 100 * MS);        // seq 0 deadline 100ms
        w.register(new byte[1], 50 * MS, 100 * MS);  // seq 1 deadline 150ms
        w.register(new byte[1], 50 * MS, 100 * MS);  // seq 2 deadline 150ms
        assertEquals(100 * MS, w.nextDeadlineNanos());
        assertTrue(w.expired(99 * MS).isEmpty());
        List<SlidingWindow.Entry> e = w.expired(120 * MS);
        assertEquals(1, e.size());
        assertEquals(0, e.get(0).seq());

        w.onAck(0, 2); // 2 acked individually
        assertEquals(2, w.expired(200 * MS).size());
        List<SlidingWindow.Entry> both = w.expired(200 * MS);
        assertEquals(0, both.get(0).seq());
        assertEquals(1, both.get(1).seq());

        w.markRetransmitted(both.get(0), 200 * MS, 100 * MS);
        assertEquals(1, both.get(0).retransmissions());
        assertEquals(1, w.expired(250 * MS).size(), "seq 0 was re-armed until 300ms, only seq 1 is overdue");
    }

    @Test
    void completesWhenEverythingIsAcked() {
        SlidingWindow w = new SlidingWindow(3);
        while (w.canSend(10)) {
            w.register(new byte[1], 0, MS);
        }
        assertFalse(w.canSend(10), "nothing beyond totalPackets");
        assertFalse(w.isComplete());
        w.onAck(3, 2);
        assertTrue(w.isComplete());
        assertEquals(Long.MAX_VALUE, w.nextDeadlineNanos());
    }

    @Test
    void emptyTransferIsImmediatelyComplete() {
        assertTrue(new SlidingWindow(0).isComplete());
    }
}
