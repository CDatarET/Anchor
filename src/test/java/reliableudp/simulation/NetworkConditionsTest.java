package reliableudp.simulation;

import static org.junit.jupiter.api.Assertions.*;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import reliableudp.protocol.Packet;
import reliableudp.protocol.PacketCodec;
import reliableudp.protocol.PacketFormatException;

/** Shows that the simulator acts on real datagrams, not just on counters. */
class NetworkConditionsTest {

    private static int drain(DatagramSocket rx, int waitMs) throws Exception {
        int n = 0;
        byte[] buf = new byte[2000];
        rx.setSoTimeout(waitMs);
        while (true) {
            try {
                rx.receive(new DatagramPacket(buf, buf.length));
                n++;
            } catch (SocketTimeoutException e) {
                return n;
            }
        }
    }

    @Test
    void lossReallyRemovesDatagramsFromTheWire() throws Exception {
        try (DatagramSocket rx = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
             DatagramSocket tx = new DatagramSocket()) {
            rx.setReceiveBufferSize(1 << 20);
            NetworkConditions n = NetworkConditions.of(0.3, 0, 42);
            InetSocketAddress dest = new InetSocketAddress("127.0.0.1", rx.getLocalPort());
            int sent = 400;
            for (int i = 0; i < sent; i++) {
                n.send(tx, Packet.fin(1, i), dest);
            }
            int received = drain(rx, 300);
            assertEquals(sent, n.submittedCount());
            assertEquals(sent - n.droppedCount(), received, "every non-dropped packet arrives, every dropped one never does");
            assertTrue(n.droppedCount() > 60 && n.droppedCount() < 180, "about 30% of 400: " + n.droppedCount());
        }
    }

    @Test
    void disabledSimulatorPassesEverythingUntouched() throws Exception {
        try (DatagramSocket rx = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
             DatagramSocket tx = new DatagramSocket()) {
            NetworkConditions n = NetworkConditions.none();
            assertFalse(n.isEnabled());
            InetSocketAddress dest = new InetSocketAddress("127.0.0.1", rx.getLocalPort());
            for (int i = 0; i < 50; i++) {
                assertTrue(n.send(tx, Packet.fin(1, i), dest));
            }
            assertEquals(50, drain(rx, 300));
            assertEquals(0, n.droppedCount());
        }
    }

    @Test
    void delayHoldsPacketsBack() throws Exception {
        try (DatagramSocket rx = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
             DatagramSocket tx = new DatagramSocket();
             NetworkConditions n = NetworkConditions.of(0, 120, 1)) {
            InetSocketAddress dest = new InetSocketAddress("127.0.0.1", rx.getLocalPort());
            long t0 = System.nanoTime();
            n.send(tx, Packet.fin(1, 1), dest);
            assertEquals(0, drain(rx, 60), "nothing may arrive before the delay has elapsed");
            rx.setSoTimeout(1000);
            rx.receive(new DatagramPacket(new byte[100], 100));
            double ms = (System.nanoTime() - t0) / 1e6;
            assertTrue(ms >= 115, "arrived after " + ms + " ms");
        }
    }

    @Test
    void corruptedDatagramsFailValidationAtTheReceiver() throws Exception {
        try (DatagramSocket rx = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
             DatagramSocket tx = new DatagramSocket()) {
            NetworkConditions n = new NetworkConditions(0, 0, 0, 1.0, 0, 9); // corrupt every packet
            InetSocketAddress dest = new InetSocketAddress("127.0.0.1", rx.getLocalPort());
            byte[] buf = new byte[2000];
            rx.setSoTimeout(1000);
            for (int i = 0; i < 100; i++) {
                n.send(tx, Packet.data(7, i, new byte[64]), dest);
                DatagramPacket dp = new DatagramPacket(buf, buf.length);
                rx.receive(dp);
                int idx = i;
                assertThrows(PacketFormatException.class, () -> PacketCodec.decode(buf, 0, dp.getLength()), "packet " + idx);
            }
            assertEquals(100, n.corruptedCount());
        }
    }

    @Test
    void invalidParametersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> NetworkConditions.of(1.5, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> NetworkConditions.of(-0.1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> NetworkConditions.of(0.1, -5, 1));
    }
}
