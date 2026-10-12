package reliableudp.protocol;

/** Contents of a SYN payload: what the client wants to send. */
public record TransferInfo(long fileSize, int chunkSize, String fileName) {

    /** @return number of DATA packets needed: ceil(fileSize / chunkSize); 0 for an empty file. */
    public long totalPackets() {
        return (fileSize + chunkSize - 1) / chunkSize;
    }
}
