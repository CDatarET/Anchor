package reliableudp.protocol;

import reliableudp.util.FileChecksum;

/**
 * Receiver's final status, carried in the FIN_ACK payload (57 bytes on the wire).
 *
 * @param success             true if every byte arrived, size matched, and the file was committed
 * @param sha256              SHA-256 of the reconstructed file (32 bytes; all zero on failure)
 * @param bytesWritten        number of bytes written to the reconstructed file
 * @param dataPacketsReceived valid DATA arrivals at the server, including duplicates
 * @param duplicates          DATA packets that had already been received
 * @param outOfOrder          DATA packets that arrived ahead of the next expected sequence number
 * @param invalidPackets      datagrams from this peer rejected by validation (corrupt/malformed)
 */
public record FinalReport(boolean success, byte[] sha256, long bytesWritten, long dataPacketsReceived,
                          long duplicates, long outOfOrder, long invalidPackets) {

    public static final int WIRE_SIZE = 1 + 32 + 8 + 4 + 4 + 4 + 4;

    public String sha256Hex() {
        return FileChecksum.toHex(sha256);
    }
}
