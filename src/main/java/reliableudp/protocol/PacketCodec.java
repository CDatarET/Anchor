package reliableudp.protocol;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Explicit binary encoder/decoder. Nothing is serialised with Java serialisation.
 *
 * <pre>
 * Header (22 bytes, big-endian / network byte order)
 *  offset size field
 *   0      1   version        (must be 1)
 *   1      1   type           (PacketType code)
 *   2      2   window         (unsigned; receiver's free slots, meaningful in SYN_ACK / ACK)
 *   4      4   sessionId      (unsigned)
 *   8      4   seq            (unsigned)
 *  12      4   ack            (unsigned)
 *  16      2   payloadLength  (unsigned; must equal datagramLength - 22)
 *  18      4   checksum       (CRC-32 over bytes 0..17 followed by the payload)
 *  22      n   payload
 * </pre>
 */
public final class PacketCodec {

    private static final int CHECKSUM_OFFSET = 18;

    private PacketCodec() {
    }

    // ---- packets -----------------------------------------------------------------------------

    public static byte[] encode(Packet p) {
        int plen = p.payload().length;
        ByteBuffer b = ByteBuffer.allocate(ProtocolConfig.HEADER_SIZE + plen); // big-endian by default
        b.put((byte) ProtocolConfig.VERSION);
        b.put((byte) p.type().code());
        b.putShort((short) p.window());
        b.putInt((int) p.sessionId());
        b.putInt((int) p.seq());
        b.putInt((int) p.ack());
        b.putShort((short) plen);
        b.position(ProtocolConfig.HEADER_SIZE);
        b.put(p.payload());
        b.putInt(CHECKSUM_OFFSET, (int) checksum(b.array(), 0, plen));
        return b.array();
    }

    /**
     * Decodes and validates one datagram.
     *
     * @throws PacketFormatException if the datagram is truncated, has an unsupported version or
     *                               unknown type, an invalid/inconsistent length, or a bad checksum
     */
    public static Packet decode(byte[] buf, int offset, int length) throws PacketFormatException {
        if (length < ProtocolConfig.HEADER_SIZE) {
            throw new PacketFormatException("truncated: " + length + " bytes < header size");
        }
        if (length > ProtocolConfig.MAX_PACKET_SIZE) {
            throw new PacketFormatException("oversized datagram: " + length);
        }
        ByteBuffer b = ByteBuffer.wrap(buf, offset, length);
        int version = b.get() & 0xFF;
        if (version != ProtocolConfig.VERSION) {
            throw new PacketFormatException("unsupported protocol version " + version);
        }
        int typeCode = b.get() & 0xFF;
        PacketType type = PacketType.fromCode(typeCode);
        if (type == null) {
            throw new PacketFormatException("unknown packet type " + typeCode);
        }
        int window = b.getShort() & 0xFFFF;
        long sessionId = b.getInt() & ProtocolConfig.U32_MAX;
        long seq = b.getInt() & ProtocolConfig.U32_MAX;
        long ack = b.getInt() & ProtocolConfig.U32_MAX;
        int payloadLength = b.getShort() & 0xFFFF;
        long wireChecksum = b.getInt() & ProtocolConfig.U32_MAX;

        if (payloadLength > ProtocolConfig.MAX_PAYLOAD) {
            throw new PacketFormatException("payload length too large: " + payloadLength);
        }
        if (length != ProtocolConfig.HEADER_SIZE + payloadLength) {
            throw new PacketFormatException("length mismatch: header says " + payloadLength
                    + " payload bytes but datagram has " + (length - ProtocolConfig.HEADER_SIZE));
        }
        byte[] copy = Arrays.copyOfRange(buf, offset, offset + length);
        long computed = checksum(copy, 0, payloadLength);
        if (computed != wireChecksum) {
            throw new PacketFormatException("checksum mismatch", true);
        }
        // Per-type structural rules
        switch (type) {
            case DATA -> {
                if (payloadLength == 0) {
                    throw new PacketFormatException("DATA with empty payload");
                }
            }
            case ACK, SYN_ACK, FIN -> {
                if (payloadLength != 0) {
                    throw new PacketFormatException(type + " must not carry a payload");
                }
            }
            default -> { /* SYN, FIN_ACK, ERROR payloads are validated by their own decoders */ }
        }
        byte[] payload = Arrays.copyOfRange(copy, ProtocolConfig.HEADER_SIZE, copy.length);
        return new Packet(type, sessionId, seq, ack, window, payload);
    }

    public static Packet decode(byte[] datagram) throws PacketFormatException {
        return decode(datagram, 0, datagram.length);
    }

    /** CRC-32 over header bytes [0,18) and the payload starting at HEADER_SIZE. */
    private static long checksum(byte[] frame, int offset, int payloadLength) {
        CRC32 crc = new CRC32();
        crc.update(frame, offset, CHECKSUM_OFFSET);
        crc.update(frame, offset + ProtocolConfig.HEADER_SIZE, payloadLength);
        return crc.getValue();
    }

    // ---- SYN payload -------------------------------------------------------------------------

    /** SYN payload: u64 fileSize, u32 chunkSize, u16 nameLength, name (UTF-8). */
    public static byte[] encodeTransferInfo(TransferInfo info) {
        byte[] name = info.fileName().getBytes(StandardCharsets.UTF_8);
        if (name.length > ProtocolConfig.MAX_FILENAME_BYTES) {
            throw new IllegalArgumentException("file name too long");
        }
        ByteBuffer b = ByteBuffer.allocate(8 + 4 + 2 + name.length);
        b.putLong(info.fileSize());
        b.putInt(info.chunkSize());
        b.putShort((short) name.length);
        b.put(name);
        return b.array();
    }

    public static TransferInfo decodeTransferInfo(byte[] payload) throws PacketFormatException {
        try {
            ByteBuffer b = ByteBuffer.wrap(payload);
            long size = b.getLong();
            long chunk = b.getInt() & ProtocolConfig.U32_MAX;
            int nameLen = b.getShort() & 0xFFFF;
            if (size < 0) {
                throw new PacketFormatException("negative file size");
            }
            if (chunk < 1 || chunk > ProtocolConfig.MAX_PAYLOAD) {
                throw new PacketFormatException("invalid chunk size " + chunk);
            }
            if (nameLen < 1 || nameLen > ProtocolConfig.MAX_FILENAME_BYTES || b.remaining() != nameLen) {
                throw new PacketFormatException("invalid file name length");
            }
            byte[] name = new byte[nameLen];
            b.get(name);
            return new TransferInfo(size, (int) chunk, new String(name, StandardCharsets.UTF_8));
        } catch (BufferUnderflowException e) {
            throw new PacketFormatException("truncated SYN payload");
        }
    }

    // ---- FIN_ACK payload ---------------------------------------------------------------------

    /** FIN_ACK payload: u8 status, 32-byte SHA-256, u64 bytes, u32 received, u32 dup, u32 ooo, u32 invalid. */
    public static byte[] encodeFinalReport(FinalReport r) {
        ByteBuffer b = ByteBuffer.allocate(FinalReport.WIRE_SIZE);
        b.put((byte) (r.success() ? 0 : 1));
        byte[] sha = r.sha256() == null ? new byte[32] : r.sha256();
        if (sha.length != 32) {
            throw new IllegalArgumentException("sha256 must be 32 bytes");
        }
        b.put(sha);
        b.putLong(r.bytesWritten());
        b.putInt((int) Math.min(r.dataPacketsReceived(), ProtocolConfig.U32_MAX));
        b.putInt((int) Math.min(r.duplicates(), ProtocolConfig.U32_MAX));
        b.putInt((int) Math.min(r.outOfOrder(), ProtocolConfig.U32_MAX));
        b.putInt((int) Math.min(r.invalidPackets(), ProtocolConfig.U32_MAX));
        return b.array();
    }

    public static FinalReport decodeFinalReport(byte[] payload) throws PacketFormatException {
        if (payload.length != FinalReport.WIRE_SIZE) {
            throw new PacketFormatException("FIN_ACK payload must be " + FinalReport.WIRE_SIZE + " bytes");
        }
        ByteBuffer b = ByteBuffer.wrap(payload);
        boolean ok = b.get() == 0;
        byte[] sha = new byte[32];
        b.get(sha);
        long bytes = b.getLong();
        long received = b.getInt() & ProtocolConfig.U32_MAX;
        long dup = b.getInt() & ProtocolConfig.U32_MAX;
        long ooo = b.getInt() & ProtocolConfig.U32_MAX;
        long invalid = b.getInt() & ProtocolConfig.U32_MAX;
        return new FinalReport(ok, sha, bytes, received, dup, ooo, invalid);
    }
}
