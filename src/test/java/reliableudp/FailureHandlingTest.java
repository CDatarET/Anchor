package reliableudp;

import static org.junit.jupiter.api.Assertions.*;
import static reliableudp.TestSupport.*;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import reliableudp.protocol.PacketType;
import reliableudp.protocol.ProtocolConfig;
import reliableudp.simulation.NetworkConditions;
import reliableudp.transport.TransferResult;

/** Peer failure and lost control packets. Covers test cases 12, 13 and 15. */
@Timeout(60)
class FailureHandlingTest {

    @TempDir
    Path tmp;
    Server server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    // 12
    @Test
    void unreachableServerEndsWithClearError() throws Exception {
        int deadPort;
        try (DatagramSocket s = new DatagramSocket(0, loopback())) {
            deadPort = s.getLocalPort();
        } // closed again: nobody listens there
        Path file = randomFile(tmp, "f.bin", 2000, 1);
        ProtocolConfig cfg = fastConfig().retransmitTimeoutMs(30).maxRetransmissions(3);
        long t0 = System.nanoTime();
        TransferResult r = reliableudp.Client.transfer(new InetSocketAddress(loopback(), deadPort), file, cfg,
                NetworkConditions.none(), "dead", null);
        assertFalse(r.success());
        assertTrue(r.error().contains("SYN_ACK") && r.error().contains("not responding"), r.error());
        assertEquals(4, r.metrics().controlPacketsSent(), "1 SYN + 3 retransmissions");
        assertTrue((System.nanoTime() - t0) / 1e9 < 5, "must give up promptly");
        assertNull(r.serverReport());
    }

    @Test
    void missingLocalFileIsReportedWithoutNetworkTraffic() throws Exception {
        TransferResult r = reliableudp.Client.transfer(new InetSocketAddress(loopback(), 9), tmp.resolve("nope.bin"),
                fastConfig(), NetworkConditions.none(), "x", null);
        assertFalse(r.success());
        assertTrue(r.error().contains("not a readable file"));
    }

    // 13 (+ sender window enforcement: with no ACKs ever arriving, exactly `window` packets may be sent)
    @Test
    void retransmissionLimitIsEnforcedAndSenderRespectsItsWindow() throws Exception {
        ProtocolConfig cfg = fastConfig().windowSize(4).maxRetransmissions(3).retransmitTimeoutMs(30);
        server = startServer(tmp.resolve("dl"), cfg, NetworkConditions.none().withDropRule(p -> p.type() == PacketType.ACK));
        Path file = randomFile(tmp, "f.bin", 20_000, 2);
        TransferResult r = send(server, file, cfg, NetworkConditions.none());
        assertFalse(r.success());
        assertTrue(r.error().contains("retransmissions") && r.error().contains("unacknowledged"), r.error());
        assertEquals(4, r.metrics().uniqueDataPackets(), "window=4: only 4 packets may be in flight without ACKs");
        assertTrue(r.metrics().retransmissions() >= 3);
        assertTrue(r.metrics().timeouts() >= 4);
        assertTrue(server.completedTransfers().isEmpty());
    }

    @Test
    void senderObeysReceiversAdvertisedWindow() throws Exception {
        ProtocolConfig cfg = fastConfig().windowSize(32).maxRetransmissions(2).retransmitTimeoutMs(30);
        ProtocolConfig serverCfg = cfg.copy().receiveWindow(3);
        server = startServer(tmp.resolve("dl"), serverCfg,
                NetworkConditions.none().withDropRule(p -> p.type() == PacketType.ACK));
        Path file = randomFile(tmp, "f.bin", 20_000, 2);
        TransferResult r = send(server, file, cfg, NetworkConditions.none());
        assertFalse(r.success());
        assertEquals(3, r.metrics().uniqueDataPackets(), "receiver advertised 3 slots; sender has window 32");
    }

    @Test
    void transferSucceedsWithTinyReceiverWindowAndLoss() throws Exception {
        ProtocolConfig cfg = fastConfig().windowSize(32);
        server = startServer(tmp.resolve("dl"), cfg.copy().receiveWindow(2), new NetworkConditions(0.1, 0, 0, 0, 0, 3));
        Path file = randomFile(tmp, "f.bin", 40_000, 3);
        TransferResult r = send(server, file, cfg, new NetworkConditions(0.1, 0, 0, 0, 0, 4));
        assertDelivered(r, server, file);
    }

    // 15
    @Test
    void lostFinalAcknowledgementsDoNotCauseFalseFailure() throws Exception {
        ProtocolConfig cfg = fastConfig();
        AtomicInteger finAcksDropped = new AtomicInteger();
        server = startServer(tmp.resolve("dl"), cfg, NetworkConditions.none()
                .withDropRule(p -> p.type() == PacketType.FIN_ACK && finAcksDropped.getAndIncrement() < 2));
        Path file = randomFile(tmp, "f.bin", 10_000, 4);
        TransferResult r = send(server, file, cfg, NetworkConditions.none());
        assertDelivered(r, server, file);
        assertEquals(3, finAcksDropped.get(), "two FIN_ACKs lost, the third got through");
        assertTrue(r.metrics().controlRetransmissions() >= 2, "client had to repeat its FIN");
        assertEquals(1, server.completedTransfers().size(), "server must not finalise the file twice");
    }

    @Test
    void lostAckForTheLastDataPacketIsRecovered() throws Exception {
        ProtocolConfig cfg = fastConfig().chunkSize(500);
        AtomicInteger dropped = new AtomicInteger();
        server = startServer(tmp.resolve("dl"), cfg, NetworkConditions.none()
                .withDropRule(p -> p.type() == PacketType.ACK && p.seq() == 4 && dropped.getAndIncrement() < 2));
        Path file = randomFile(tmp, "f.bin", 2500, 5); // exactly 5 packets, last has seq 4
        TransferResult r = send(server, file, cfg, NetworkConditions.none());
        assertDelivered(r, server, file);
        assertTrue(r.metrics().retransmissions() >= 2);
        assertTrue(r.serverReport().duplicates() >= 2);
    }

    @Test
    void lostSynAckIsRecoveredByRepeatingTheSyn() throws Exception {
        ProtocolConfig cfg = fastConfig();
        AtomicInteger dropped = new AtomicInteger();
        server = startServer(tmp.resolve("dl"), cfg, NetworkConditions.none()
                .withDropRule(p -> p.type() == PacketType.SYN_ACK && dropped.getAndIncrement() < 1));
        Path file = randomFile(tmp, "f.bin", 3000, 6);
        TransferResult r = send(server, file, cfg, NetworkConditions.none());
        assertDelivered(r, server, file);
        assertTrue(r.metrics().controlRetransmissions() >= 1);
        assertEquals(1, server.completedTransfers().size(), "repeated SYN must not create a second session");
    }

    @Test
    void serverErrorsAreSurfacedToTheClient() throws Exception {
        ProtocolConfig cfg = fastConfig();
        server = startServer(tmp.resolve("dl"), cfg, NetworkConditions.none());
        Path file = randomFile(tmp, "we;ird.txt", 100, 7); // ';' is not an accepted file-name character
        TransferResult r = send(server, file, cfg, NetworkConditions.none());
        assertFalse(r.success());
        assertTrue(r.error().contains("BAD_FILENAME"), r.error());
    }
}
