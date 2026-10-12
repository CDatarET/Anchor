package reliableudp;

import static org.junit.jupiter.api.Assertions.*;
import static reliableudp.TestSupport.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import reliableudp.protocol.ErrorCode;
import reliableudp.protocol.FinalReport;
import reliableudp.protocol.Packet;
import reliableudp.protocol.PacketCodec;
import reliableudp.protocol.PacketType;
import reliableudp.protocol.ProtocolConfig;
import reliableudp.protocol.TransferInfo;
import reliableudp.simulation.NetworkConditions;
import reliableudp.transport.TransferResult;
import reliableudp.util.FileChecksum;

/**
 * Drives the real server with hand-crafted packets from a raw UDP socket, so that duplicates,
 * reordering, malformed input and invalid sessions can be produced exactly.
 * Covers test cases 6, 7, 8, 14 plus path-traversal protection.
 */
@Timeout(60)
class ServerBehaviourTest {

    @TempDir
    Path tmp;
    Path dl;
    Server server;

    @BeforeEach
    void setUp() throws Exception {
        dl = tmp.resolve("downloads");
        server = startServer(dl, fastConfig(), NetworkConditions.none());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static byte[][] chunks(byte[] data, int chunk) {
        int n = (data.length + chunk - 1) / chunk;
        byte[][] out = new byte[n][];
        for (int i = 0; i < n; i++) {
            out[i] = Arrays.copyOfRange(data, i * chunk, Math.min(data.length, (i + 1) * chunk));
        }
        return out;
    }

    private static byte[] sample(int n) {
        byte[] b = new byte[n];
        new Random(5).nextBytes(b);
        return b;
    }

    // 6
    @Test
    void duplicateDataPacketsAreAckedButWrittenOnlyOnce() throws Exception {
        byte[] data = sample(25);
        byte[][] c = chunks(data, 10);
        long id = 0x1234;
        try (RawPeer peer = new RawPeer(server)) {
            Packet sa = peer.handshake(id, "dup.bin", 25, 10);
            assertEquals(PacketType.SYN_ACK, sa.type());
            assertEquals(64, sa.window());

            peer.send(Packet.data(id, 0, c[0]));
            Packet a = peer.recv(1000);
            assertEquals(1, a.ack());
            assertEquals(0, a.seq());
            peer.send(Packet.data(id, 0, c[0])); // duplicate
            a = peer.recv(1000);
            assertEquals(1, a.ack(), "duplicate must be re-acknowledged with the same cumulative ack");
            assertEquals(0, a.seq());

            peer.send(Packet.data(id, 1, c[1]));
            assertEquals(2, peer.recv(1000).ack());
            peer.send(Packet.data(id, 1, c[1]));
            assertEquals(2, peer.recv(1000).ack());
            peer.send(Packet.data(id, 1, c[1]));
            assertEquals(2, peer.recv(1000).ack());

            peer.send(Packet.data(id, 2, c[2]));
            assertEquals(3, peer.recv(1000).ack());

            peer.send(Packet.fin(id, 3));
            Packet fa = peer.recv(2000);
            assertEquals(PacketType.FIN_ACK, fa.type());
            FinalReport rep = PacketCodec.decodeFinalReport(fa.payload());
            assertTrue(rep.success());
            assertEquals(3, rep.duplicates());
            assertEquals(6, rep.dataPacketsReceived());
            assertEquals(FileChecksum.toHex(FileChecksum.sha256(data)), rep.sha256Hex());
        }
        Path out = server.completedTransfers().get(0).file();
        assertArrayEquals(data, Files.readAllBytes(out), "duplicates must not be written twice");
    }

    // 7
    @Test
    void outOfOrderPacketsAreBufferedAndWrittenInOrder() throws Exception {
        byte[] data = sample(35);
        byte[][] c = chunks(data, 10); // 4 packets: 10,10,10,5
        long id = 0x77;
        try (RawPeer peer = new RawPeer(server)) {
            peer.handshake(id, "ooo.bin", 35, 10);

            peer.send(Packet.data(id, 3, c[3]));
            Packet a = peer.recv(1000);
            assertEquals(0, a.ack(), "nothing in order yet");
            assertEquals(3, a.seq(), "selective part of the ACK echoes the packet received");
            assertEquals(63, a.window(), "one slot of the 64-packet buffer is now occupied");

            peer.send(Packet.data(id, 1, c[1]));
            a = peer.recv(1000);
            assertEquals(0, a.ack());
            assertEquals(1, a.seq());
            assertEquals(62, a.window());

            peer.send(Packet.data(id, 0, c[0])); // fills the hole at the front: 0,1 delivered
            a = peer.recv(1000);
            assertEquals(2, a.ack());
            assertEquals(63, a.window()); // seq 3 still buffered

            peer.send(Packet.data(id, 2, c[2])); // now 2,3 delivered
            a = peer.recv(1000);
            assertEquals(4, a.ack());
            assertEquals(64, a.window());

            peer.send(Packet.fin(id, 4));
            FinalReport rep = PacketCodec.decodeFinalReport(peer.recv(2000).payload());
            assertTrue(rep.success());
            assertEquals(2, rep.outOfOrder());
        }
        assertArrayEquals(data, Files.readAllBytes(server.completedTransfers().get(0).file()));
    }

    @Test
    void finBeforeAllDataIsRefusedAndSessionSurvives() throws Exception {
        byte[] data = sample(30);
        byte[][] c = chunks(data, 10);
        long id = 0x99;
        try (RawPeer peer = new RawPeer(server)) {
            peer.handshake(id, "early.bin", 30, 10);
            peer.send(Packet.data(id, 0, c[0]));
            peer.recv(1000);
            peer.send(Packet.fin(id, 3));
            Packet e = peer.recv(1000);
            assertEquals(PacketType.ERROR, e.type());
            assertEquals(ErrorCode.INCOMPLETE_TRANSFER, e.errorCode());
            assertTrue(server.completedTransfers().isEmpty(), "must not be marked successful with data missing");

            peer.send(Packet.data(id, 1, c[1]));
            peer.recv(1000);
            peer.send(Packet.data(id, 2, c[2]));
            peer.recv(1000);
            peer.send(Packet.fin(id, 3));
            assertEquals(PacketType.FIN_ACK, peer.recv(2000).type());
        }
        assertArrayEquals(data, Files.readAllBytes(server.completedTransfers().get(0).file()));
    }

    // 14
    @Test
    void invalidSessionIdentifiersAreRejected() throws Exception {
        long id = 0x4242;
        try (RawPeer peer = new RawPeer(server); RawPeer stranger = new RawPeer(server)) {
            peer.handshake(id, "s.bin", 10, 10);

            // wrong session id from the right peer
            peer.send(Packet.data(0xBAD, 0, new byte[10]));
            Packet e = peer.recv(1000);
            assertEquals(PacketType.ERROR, e.type());
            assertEquals(ErrorCode.UNKNOWN_SESSION, e.errorCode());
            peer.send(Packet.fin(0xBAD, 1));
            assertEquals(ErrorCode.UNKNOWN_SESSION, peer.recv(1000).errorCode());

            // right session id, but from a different address/port (session hijack attempt)
            stranger.send(Packet.data(id, 0, new byte[10]));
            assertEquals(ErrorCode.UNKNOWN_SESSION, stranger.recv(1000).errorCode());

            // the legitimate session was not disturbed
            peer.send(Packet.data(id, 0, new byte[10]));
            Packet a = peer.recv(1000);
            assertEquals(PacketType.ACK, a.type());
            assertEquals(1, a.ack());
        }
        assertEquals(0, server.completedTransfers().size());
    }

    @Test
    void dataWithWrongLengthOrSequenceIsIgnored() throws Exception {
        long id = 0x31;
        try (RawPeer peer = new RawPeer(server)) {
            peer.handshake(id, "len.bin", 25, 10);
            peer.send(Packet.data(id, 0, new byte[9]));   // chunk must be exactly 10
            assertNull(peer.recv(300));
            peer.send(Packet.data(id, 2, new byte[10]));  // last packet must be exactly 5 bytes
            assertNull(peer.recv(300));
            peer.send(Packet.data(id, 7, new byte[10]));  // beyond the end
            assertNull(peer.recv(300));
            peer.send(Packet.data(id, 0, new byte[10]));  // a good one still works
            assertEquals(1, peer.recv(1000).ack());
        }
    }

    @Test
    void duplicateSynIsAnsweredIdempotentlyEvenMidTransfer() throws Exception {
        long id = 0x55;
        TransferInfo info = new TransferInfo(20, 10, "syn.bin");
        try (RawPeer peer = new RawPeer(server)) {
            peer.send(Packet.syn(id, info));
            assertEquals(PacketType.SYN_ACK, peer.recv(1000).type());
            peer.send(Packet.syn(id, info));
            assertEquals(PacketType.SYN_ACK, peer.recv(1000).type());
            peer.send(Packet.data(id, 0, new byte[10]));
            assertEquals(1, peer.recv(1000).ack());
            peer.send(Packet.syn(id, info)); // delayed duplicate arriving mid-transfer
            assertEquals(PacketType.SYN_ACK, peer.recv(1000).type());
            peer.send(Packet.data(id, 1, new byte[10]));
            assertEquals(2, peer.recv(1000).ack(), "session must continue normally");
            // same id, different parameters => conflict
            peer.send(Packet.syn(id, new TransferInfo(99, 10, "other.bin")));
            assertEquals(ErrorCode.SESSION_EXISTS, peer.recv(1000).errorCode());
        }
    }

    // 8 (server side)
    @Test
    void garbageAndTruncatedDatagramsDoNotCrashTheServer() throws Exception {
        Random rnd = new Random(3);
        try (RawPeer peer = new RawPeer(server)) {
            peer.sendRaw(new byte[0]);
            peer.sendRaw(new byte[5]);
            peer.sendRaw(new byte[ProtocolConfig.HEADER_SIZE]);
            byte[] noise = new byte[100];
            rnd.nextBytes(noise);
            peer.sendRaw(noise);
            byte[] big = new byte[60000];
            rnd.nextBytes(big);
            peer.sendRaw(big);
            byte[] valid = PacketCodec.encode(Packet.data(1, 0, new byte[10]));
            peer.sendRaw(Arrays.copyOf(valid, valid.length - 3));          // truncated
            valid[0] = 9;
            peer.sendRaw(valid);                                           // wrong version
            assertNull(peer.recv(300), "garbage must not be answered");
        }
        Path file = randomFile(tmp, "after-garbage.bin", 10_000, 1);
        TransferResult r = send(server, file, fastConfig(), NetworkConditions.none());
        assertDelivered(r, server, file); // server still fully functional
    }

    // Path traversal / unsafe names
    @Test
    void unsafeFileNamesAreRejectedAndNothingIsWrittenOutsideDownloads() throws Exception {
        String[] bad = {"../evil.txt", "..\\evil.txt", "/etc/passwd", "a/b.txt", "C:\\x.txt", "..", ".", "CON",
            "nul.txt", ".hidden", "name with ; semicolon", "trailing.", "a\u0000b", "x".repeat(250)};
        try (RawPeer peer = new RawPeer(server)) {
            long id = 0x1000;
            for (String name : bad) {
                Packet r = peer.handshake(id++, name, 10, 10);
                assertEquals(PacketType.ERROR, r.type(), "name accepted: " + name.replace('\u0000', '?'));
                assertEquals(ErrorCode.BAD_FILENAME, r.errorCode(), name);
            }
            // empty name cannot even be encoded as a valid SYN payload: server answers BAD_REQUEST
            Packet r = peer.handshake(id++, "", 10, 10);
            assertEquals(ErrorCode.BAD_REQUEST, r.errorCode());
            // a harmless name is fine
            assertEquals(PacketType.SYN_ACK, peer.handshake(id, "fine-name_1.txt", 10, 10).type());
        }
        assertFalse(Files.exists(tmp.resolve("evil.txt")));
        try (Stream<Path> s = Files.list(tmp)) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().contains("evil")));
        }
        try (Stream<Path> s = Files.list(dl)) {
            assertTrue(s.allMatch(p -> p.getFileName().toString().startsWith("fine-name_1.txt")),
                    "only the one accepted session may have created files");
        }
    }

    @Test
    void existingFilesAreNeverOverwritten() throws Exception {
        Files.createDirectories(dl);
        Files.writeString(dl.resolve("existing.txt"), "KEEP ME");
        Path src = randomFile(tmp, "existing.txt", 3000, 4);
        TransferResult r = send(server, src, fastConfig(), NetworkConditions.none());
        Path out = assertDelivered(r, server, src);
        assertEquals("existing (1).txt", out.getFileName().toString());
        assertEquals("KEEP ME", Files.readString(dl.resolve("existing.txt")));
    }

    @Test
    void oversizedFileIsRejected() throws Exception {
        server.close();
        server = startServer(dl, fastConfig().maxFileSizeBytes(100), NetworkConditions.none());
        try (RawPeer peer = new RawPeer(server)) {
            Packet r = peer.handshake(5, "big.bin", 1000, 10);
            assertEquals(ErrorCode.FILE_TOO_LARGE, r.errorCode());
        }
    }

    @Test
    void idleSessionIsAbortedAndPartialFilesAreRemoved() throws Exception {
        server.close();
        server = startServer(dl, fastConfig().sessionIdleTimeoutMs(300), NetworkConditions.none());
        try (RawPeer peer = new RawPeer(server)) {
            peer.handshake(8, "idle.bin", 100, 10);
            peer.send(Packet.data(8, 0, new byte[10]));
            peer.recv(1000);
            assertEquals(1, server.activeSessions());
            long deadline = System.currentTimeMillis() + 5000;
            while (server.activeSessions() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(0, server.activeSessions());
        }
        try (Stream<Path> s = Files.list(dl)) {
            assertEquals(0, s.count(), "partial + placeholder files must be deleted");
        }
    }
}
