package reliableudp.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reliableudp.protocol.Packet;
import reliableudp.protocol.TransferInfo;

/** Receiver logic in isolation (no sockets). */
class ReliableReceiverTest {

    @TempDir
    Path tmp;

    private ReliableReceiver receiver(int fileSize, int chunk, int capacity) throws Exception {
        TransferSession s = new TransferSession(1, new InetSocketAddress("127.0.0.1", 1));
        s.setInfo(new TransferInfo(fileSize, chunk, "x"));
        return new ReliableReceiver(s, tmp.resolve("x.part"), capacity);
    }

    private static Packet data(long seq, int len, int fill) {
        byte[] b = new byte[len];
        Arrays.fill(b, (byte) fill);
        return Packet.data(1, seq, b);
    }

    @Test
    void reordersAndDeliversInSequence() throws Exception {
        try (ReliableReceiver r = receiver(25, 10, 8)) {
            assertEquals(0, r.onData(data(2, 5, 3)).ack());
            assertEquals(0, r.onData(data(1, 10, 2)).ack());
            assertFalse(r.isComplete());
            Packet a = r.onData(data(0, 10, 1));
            assertEquals(3, a.ack());
            assertTrue(r.isComplete());
            assertEquals(2, r.outOfOrder());
            r.close(); // idempotent: try-with-resources closes again
            byte[] out = Files.readAllBytes(tmp.resolve("x.part"));
            assertEquals(25, out.length);
            assertEquals(1, out[0]);
            assertEquals(2, out[10]);
            assertEquals(3, out[24]);
        }
    }

    @Test
    void duplicatesAreCountedAndAcknowledgedNotRewritten() throws Exception {
        try (ReliableReceiver r = receiver(20, 10, 8)) {
            r.onData(data(0, 10, 1));
            Packet a = r.onData(data(0, 10, 9)); // different content, same seq: must be ignored
            assertEquals(1, a.ack());
            r.onData(data(1, 10, 2));
            r.onData(data(1, 10, 2));
            assertEquals(2, r.duplicates());
            assertEquals(20, r.bytesWritten());
            r.close();
            assertEquals(1, Files.readAllBytes(tmp.resolve("x.part"))[0], "first copy wins");
        }
    }

    @Test
    void packetsBeyondTheReceiveWindowAreDropped() throws Exception {
        try (ReliableReceiver r = receiver(100, 10, 4)) {
            assertNull(r.onData(data(4, 10, 1)), "seq 4 is outside [0,4)");
            assertEquals(1, r.outOfWindow());
            assertNotNull(r.onData(data(3, 10, 1)), "seq 3 is inside [0,4)");
            r.onData(data(0, 10, 1));
            assertNotNull(r.onData(data(4, 10, 1)), "after the window slid, seq 4 is acceptable");
        }
    }

    @Test
    void advertisedWindowShrinksWithBufferedPackets() throws Exception {
        try (ReliableReceiver r = receiver(100, 10, 4)) {
            assertEquals(3, r.onData(data(1, 10, 1)).window());
            assertEquals(2, r.onData(data(2, 10, 1)).window());
            assertEquals(1, r.onData(data(3, 10, 1)).window(), "never reaches 0 because rcvBase itself is never buffered");
            assertEquals(4, r.onData(data(0, 10, 1)).window());
        }
    }

    @Test
    void badLengthsAndSequencesAreRejected() throws Exception {
        try (ReliableReceiver r = receiver(25, 10, 8)) {
            assertNull(r.onData(data(0, 9, 1)));
            assertNull(r.onData(data(2, 10, 1)));
            assertNull(r.onData(data(3, 5, 1)));
            assertEquals(3, r.invalid());
            assertEquals(0, r.dataReceived());
        }
    }

    @Test
    void emptyFileIsCompleteImmediately() throws Exception {
        try (ReliableReceiver r = receiver(0, 10, 8)) {
            assertTrue(r.isComplete());
            assertNull(r.onData(data(0, 1, 1)));
        }
    }
}
