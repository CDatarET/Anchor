package reliableudp.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Phase 2: wire-format tests (also covers test case 8: invalid or truncated packets). */
class PacketCodecTest {

    @Test
    void roundTripAllPacketTypes() throws Exception {
        byte[] sha = new byte[32];
        Arrays.fill(sha, (byte) 7);
        Packet[] packets = {
            Packet.syn(0xDEADBEEFL, new TransferInfo(123456789012L, 1024, "report.pdf")),
            Packet.synAck(42, 64),
            Packet.data(42, 0xFFFFFFFFL, new byte[] {1, 2, 3}),
            Packet.ack(42, 10, 12, 60),
            Packet.fin(42, 99),
            Packet.finAck(42, 99, new FinalReport(true, sha, 5000, 12, 3, 2, 1)),
            Packet.error(42, ErrorCode.BAD_FILENAME, "no way")
        };
        for (Packet p : packets) {
            Packet q = PacketCodec.decode(PacketCodec.encode(p));
            assertEquals(p.type(), q.type());
            assertEquals(p.sessionId(), q.sessionId());
            assertEquals(p.seq(), q.seq());
            assertEquals(p.ack(), q.ack());
            assertEquals(p.window(), q.window());
            assertArrayEquals(p.payload(), q.payload());
        }
    }

    @Test
    void headerLayoutIsBigEndianAndDocumented() {
        Packet p = new Packet(PacketType.DATA, 0x0A0B0C0DL, 0x01020304L, 0x05060708L, 0x1122, new byte[] {9, 8});
        byte[] w = PacketCodec.encode(p);
        assertEquals(ProtocolConfig.HEADER_SIZE + 2, w.length);
        assertEquals(1, w[0]);                       // version
        assertEquals(3, w[1]);                       // DATA
        assertEquals(0x11, w[2]); assertEquals(0x22, w[3]);   // window
        assertEquals(0x0A, w[4]); assertEquals(0x0D, w[7]);   // session id, MSB first
        assertEquals(0x01, w[8]); assertEquals(0x04, w[11]);  // seq
        assertEquals(0x05, w[12]); assertEquals(0x08, w[15]); // ack
        assertEquals(0, w[16]); assertEquals(2, w[17]);       // payload length
        assertEquals(9, w[22]); assertEquals(8, w[23]);       // payload
    }

    @Test
    void truncatedPacketsAreRejected() {
        byte[] w = PacketCodec.encode(Packet.data(1, 1, new byte[100]));
        for (int len : new int[] {0, 1, 10, ProtocolConfig.HEADER_SIZE - 1, ProtocolConfig.HEADER_SIZE, w.length - 1}) {
            int l = len;
            assertThrows(PacketFormatException.class, () -> PacketCodec.decode(w, 0, l), "len=" + l);
        }
    }

    @Test
    void trailingGarbageIsRejected() {
        byte[] w = PacketCodec.encode(Packet.data(1, 1, new byte[10]));
        byte[] longer = Arrays.copyOf(w, w.length + 5);
        assertThrows(PacketFormatException.class, () -> PacketCodec.decode(longer));
    }

    @Test
    void unsupportedVersionAndUnknownTypeAreRejected() {
        byte[] w = PacketCodec.encode(Packet.fin(1, 1));
        byte[] badVersion = w.clone();
        badVersion[0] = 2;
        PacketFormatException e1 = assertThrows(PacketFormatException.class, () -> PacketCodec.decode(badVersion));
        assertTrue(e1.getMessage().contains("version"));
        byte[] badType = w.clone();
        badType[1] = 99;
        PacketFormatException e2 = assertThrows(PacketFormatException.class, () -> PacketCodec.decode(badType));
        assertTrue(e2.getMessage().contains("type"));
    }

    @Test
    void lengthFieldMustMatchDatagramLength() {
        byte[] w = PacketCodec.encode(Packet.data(1, 1, new byte[10]));
        w[17] = 11; // lie about the payload length
        PacketFormatException e = assertThrows(PacketFormatException.class, () -> PacketCodec.decode(w));
        assertTrue(e.getMessage().contains("length"));
    }

    @Test
    void everySingleBitFlipIsDetected() {
        byte[] w = PacketCodec.encode(Packet.data(77, 5, "hello world".getBytes(StandardCharsets.UTF_8)));
        int detected = 0;
        for (int i = 0; i < w.length; i++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] c = w.clone();
                c[i] ^= (byte) (1 << bit);
                assertThrows(PacketFormatException.class, () -> PacketCodec.decode(c));
                detected++;
            }
        }
        assertEquals(w.length * 8, detected);
    }

    @Test
    void checksumFailureIsReportedAsCorruption() {
        byte[] w = PacketCodec.encode(Packet.data(77, 5, new byte[] {1, 2, 3, 4}));
        w[ProtocolConfig.HEADER_SIZE] ^= 0x40; // flip a payload bit only
        PacketFormatException e = assertThrows(PacketFormatException.class, () -> PacketCodec.decode(w));
        assertTrue(e.isChecksumFailure());
    }

    @Test
    void perTypePayloadRulesAreEnforced() {
        // A syntactically valid frame (good CRC) that breaks a per-type rule
        byte[] ackWithPayload = PacketCodec.encode(new Packet(PacketType.ACK, 1, 1, 1, 1, new byte[] {1}));
        assertThrows(PacketFormatException.class, () -> PacketCodec.decode(ackWithPayload));
        byte[] emptyData = PacketCodec.encode(new Packet(PacketType.DATA, 1, 1, 0, 0, new byte[0]));
        assertThrows(PacketFormatException.class, () -> PacketCodec.decode(emptyData));
    }

    @Test
    void maximumPayloadFitsInOneDatagram() throws Exception {
        byte[] big = new byte[ProtocolConfig.MAX_PAYLOAD];
        byte[] w = PacketCodec.encode(Packet.data(1, 0, big));
        assertEquals(ProtocolConfig.MAX_PACKET_SIZE, w.length);
        assertEquals(big.length, PacketCodec.decode(w).payload().length);
        assertThrows(IllegalArgumentException.class, () -> Packet.data(1, 0, new byte[ProtocolConfig.MAX_PAYLOAD + 1]));
    }

    @Test
    void packetFieldRangesAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new Packet(PacketType.ACK, -1, 0, 0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new Packet(PacketType.ACK, 0, 1L << 32, 0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new Packet(PacketType.ACK, 0, 0, 0, 70000, null));
    }

    @Test
    void transferInfoRoundTripAndValidation() throws Exception {
        TransferInfo in = new TransferInfo(1L << 33, 1400, "naïve file.txt");
        TransferInfo out = PacketCodec.decodeTransferInfo(PacketCodec.encodeTransferInfo(in));
        assertEquals(in, out);
        assertEquals(((1L << 33) + 1399) / 1400, in.totalPackets());
        assertEquals(0, new TransferInfo(0, 1024, "e").totalPackets());
        assertThrows(PacketFormatException.class, () -> PacketCodec.decodeTransferInfo(new byte[5]));
        byte[] zeroChunk = PacketCodec.encodeTransferInfo(new TransferInfo(10, 1, "a"));
        zeroChunk[8] = zeroChunk[9] = zeroChunk[10] = zeroChunk[11] = 0;
        assertThrows(PacketFormatException.class, () -> PacketCodec.decodeTransferInfo(zeroChunk));
    }

    @Test
    void finalReportRoundTrip() throws Exception {
        byte[] sha = new byte[32];
        for (int i = 0; i < 32; i++) {
            sha[i] = (byte) i;
        }
        FinalReport r = new FinalReport(false, sha, 1234, 10, 2, 3, 4);
        FinalReport q = PacketCodec.decodeFinalReport(PacketCodec.encodeFinalReport(r));
        assertFalse(q.success());
        assertArrayEquals(sha, q.sha256());
        assertEquals(1234, q.bytesWritten());
        assertEquals(10, q.dataPacketsReceived());
        assertEquals(2, q.duplicates());
        assertEquals(3, q.outOfOrder());
        assertEquals(4, q.invalidPackets());
        assertThrows(PacketFormatException.class, () -> PacketCodec.decodeFinalReport(new byte[10]));
    }

    @Test
    void errorPacketCarriesCodeAndText() throws Exception {
        Packet p = PacketCodec.decode(PacketCodec.encode(Packet.error(9, ErrorCode.UNKNOWN_SESSION, "who are you")));
        assertEquals(ErrorCode.UNKNOWN_SESSION, p.errorCode());
        assertEquals("who are you", p.errorText());
    }
}
