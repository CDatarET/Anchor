package reliableudp.transport;

/** Receives progress updates from the sender (used by the CLI and the GUI). */
@FunctionalInterface
public interface ProgressListener {
    /**
     * @param ackedBytes  file bytes whose DATA packets have been acknowledged so far
     * @param totalBytes  size of the file
     */
    void onProgress(long ackedBytes, long totalBytes);
}
