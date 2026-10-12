package reliableudp;

import static org.junit.jupiter.api.Assertions.*;
import static reliableudp.TestSupport.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import reliableudp.metrics.MetricsLogger;
import reliableudp.protocol.ProtocolConfig;
import reliableudp.simulation.NetworkConditions;
import reliableudp.transport.TransferResult;
import reliableudp.util.FileChecksum;

/**
 * Full client-to-server transfers over real UDP sockets (loopback, separate ports) under
 * different simulated network conditions. Covers test cases 1-5, 9, 10, 11.
 */
@Timeout(90)
class TransferIntegrationTest {

    @TempDir
    Path tmp;
    Path src;
    Path dl;
    Server server;

    @BeforeEach
    void setUp() throws Exception {
        src = Files.createDirectories(tmp.resolve("src"));
        dl = tmp.resolve("downloads");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    /** Runs one transfer with the same impairments on both directions and verifies the file. */
    private TransferResult run(int size, double loss, int delayMs, int jitterMs, long seed, ProtocolConfig cfg)
            throws Exception {
        Path file = randomFile(src, "payload-" + size + ".bin", size, seed);
        NetworkConditions serverNet = new NetworkConditions(loss, delayMs, jitterMs, 0, 0, seed + 1000);
        server = startServer(dl, cfg, serverNet);
        TransferResult r = send(server, file, cfg, new NetworkConditions(loss, delayMs, jitterMs, 0, 0, seed));
        assertDelivered(r, server, file);
        return r;
    }

    // 1
    @Test
    void transferWithNoLoss() throws Exception {
        TransferResult r = run(100_000, 0, 0, 0, 1, fastConfig());
        assertEquals(0, r.metrics().retransmissions());
        assertEquals(0, r.metrics().simulatedDropped());
        assertEquals(r.metrics().uniqueDataPackets(), r.metrics().dataPacketsSent());
        assertEquals(196, r.metrics().uniqueDataPackets()); // ceil(100000/512)
        assertTrue(r.metrics().srttMs() > 0, "RTT should have been measured");
    }

    // 2
    @Test
    void transferWith5PercentLoss() throws Exception {
        TransferResult r = run(100_000, 0.05, 0, 0, 11, fastConfig());
        assertTrue(r.metrics().simulatedDropped() > 0, "the simulator must really have dropped packets");
        assertTrue(r.metrics().retransmissions() > 0);
    }

    // 3
    @Test
    void transferWith10PercentLoss() throws Exception {
        TransferResult r = run(100_000, 0.10, 0, 0, 22, fastConfig());
        assertTrue(r.metrics().simulatedDropped() > 0);
        assertTrue(r.metrics().retransmissions() > 0);
    }

    // 4
    @Test
    void transferWithArtificialDelay() throws Exception {
        long t0 = System.nanoTime();
        TransferResult r = run(20_000, 0, 15, 0, 33, fastConfig().retransmitTimeoutMs(120));
        double seconds = (System.nanoTime() - t0) / 1e9;
        // at least handshake + data + FIN rounds, each costing >= 2 x 15 ms
        assertTrue(seconds > 0.09, "delay must actually slow the transfer, took " + seconds);
        assertTrue(r.metrics().srttMs() >= 28, "RTT estimate should reflect 2 x 15 ms, was " + r.metrics().srttMs());
    }

    // 5
    @Test
    void transferWithLossAndDelayCombined() throws Exception {
        TransferResult r = run(60_000, 0.10, 10, 5, 44, fastConfig().retransmitTimeoutMs(100));
        assertTrue(r.metrics().simulatedDropped() > 0);
    }

    // 11
    @Test
    void largeFileSpanningManyWindows() throws Exception {
        ProtocolConfig cfg = fastConfig().windowSize(8).chunkSize(512);
        TransferResult r = run(400_000, 0.02, 0, 0, 55, cfg);
        long packets = (400_000 + 511) / 512; // 782 packets = ~98 windows of 8
        assertEquals(packets, r.metrics().uniqueDataPackets());
        assertTrue(packets / 8 > 90);
    }

    // 10
    @Test
    void emptyAndSmallFiles() throws Exception {
        ProtocolConfig cfg = fastConfig().chunkSize(100);
        server = startServer(dl, cfg, NetworkConditions.none());
        for (int size : new int[] {0, 1, 99, 100, 101, 199, 200, 201, 1000}) {
            Path f = randomFile(src, "s" + size + ".bin", size, size);
            TransferResult r = send(server, f, cfg, NetworkConditions.none());
            Path out = assertDelivered(r, server, f);
            assertEquals(size, Files.size(out));
            assertEquals((size + 99) / 100, r.metrics().uniqueDataPackets(), "packet count for size " + size);
        }
        assertEquals(9, server.completedTransfers().size());
    }

    // 9
    @Test
    void integrityDigestsAreReportedAndCompared() throws Exception {
        Path f = randomFile(src, "i.bin", 5000, 7);
        server = startServer(dl, fastConfig(), NetworkConditions.none());
        TransferResult r = send(server, f, fastConfig(), NetworkConditions.none());
        Path out = assertDelivered(r, server, f);
        assertEquals(FileChecksum.sha256Hex(f), r.sourceSha256());
        assertEquals(r.sourceSha256(), r.serverReport().sha256Hex());
        assertEquals(FileChecksum.sha256Hex(out), r.serverReport().sha256Hex());
        assertEquals(5000, r.serverReport().bytesWritten());
    }

    @Test
    void corruptedPacketsAreDetectedAndRecovered() throws Exception {
        Path file = randomFile(src, "c.bin", 80_000, 66);
        ProtocolConfig cfg = fastConfig();
        server = startServer(dl, cfg, new NetworkConditions(0, 0, 0, 0.08, 0, 5));
        TransferResult r = send(server, file, cfg, new NetworkConditions(0, 0, 0, 0.08, 0, 6));
        assertDelivered(r, server, file);
        assertTrue(r.metrics().simulatedCorrupted() > 0, "simulator must have corrupted packets");
        assertTrue(r.serverReport().invalidPackets() > 0, "server must have rejected corrupted DATA");
        assertTrue(r.metrics().retransmissions() > 0);
    }

    @Test
    void duplicatedPacketsDoNotCorruptTheFile() throws Exception {
        Path file = randomFile(src, "d.bin", 60_000, 77);
        ProtocolConfig cfg = fastConfig();
        server = startServer(dl, cfg, new NetworkConditions(0, 0, 0, 0, 0.3, 8));
        TransferResult r = send(server, file, cfg, new NetworkConditions(0, 0, 0, 0, 0.3, 9));
        assertDelivered(r, server, file);
        assertTrue(r.serverReport().duplicates() > 0);
    }

    @Test
    void jitterReordersPacketsAndReceiverReassemblesThem() throws Exception {
        Path file = randomFile(src, "j.bin", 60_000, 88);
        ProtocolConfig cfg = fastConfig().retransmitTimeoutMs(150);
        server = startServer(dl, cfg, NetworkConditions.none());
        TransferResult r = send(server, file, cfg, new NetworkConditions(0, 5, 40, 0, 0, 10));
        assertDelivered(r, server, file);
        assertTrue(r.serverReport().outOfOrder() > 0, "jitter should have reordered some packets");
    }

    @Test
    void identicalSeedGivesIdenticalDropDecisions() throws Exception {
        // same seed + same packet sequence => same drop count (reproducibility of experiments)
        int[] drops = new int[2];
        for (int run = 0; run < 2; run++) {
            NetworkConditions n = NetworkConditions.of(0.2, 0, 1234);
            java.net.DatagramSocket sink = new java.net.DatagramSocket();
            for (int i = 0; i < 500; i++) {
                n.send(sink, reliableudp.protocol.Packet.fin(1, i), new java.net.InetSocketAddress(loopback(), 9));
            }
            drops[run] = (int) n.droppedCount();
            sink.close();
        }
        assertEquals(drops[0], drops[1]);
        assertTrue(drops[0] > 50 && drops[0] < 150, "about 20% of 500, was " + drops[0]);
    }

    @Test
    void csvExportContainsMeasuredValues() throws Exception {
        TransferResult r = run(30_000, 0.05, 0, 0, 99, fastConfig());
        Path csv = tmp.resolve("logs/out.csv");
        MetricsLogger.append(csv, r.metrics());
        MetricsLogger.append(csv, r.metrics());
        List<String> lines = Files.readAllLines(csv);
        assertEquals(3, lines.size(), "header + 2 rows");
        String[] header = lines.get(0).split(",", -1);
        String[] row = lines.get(1).split(",", -1);
        assertEquals(MetricsLogger.COLUMNS.length, header.length);
        assertEquals(header.length, row.length);
        assertEquals("true", row[java.util.Arrays.asList(header).indexOf("integrity_match")]);
        assertEquals(Long.toString(r.metrics().retransmissions()),
                row[java.util.Arrays.asList(header).indexOf("retransmissions")]);
        assertEquals(Long.toString(r.metrics().simulatedDropped()),
                row[java.util.Arrays.asList(header).indexOf("simulated_dropped")]);
    }
}
