package reliableudp.protocol;

/**
 * Protocol constants (fixed by the wire format) and tunable parameters (per run).
 *
 * <p>Constants are {@code public static final}. Tunables have defaults and fluent setters; call
 * {@link #validate()} before use (the Client/Server do this for you).
 */
public final class ProtocolConfig {

    // ---- wire-format constants -------------------------------------------------------------
    /** Protocol version carried in every packet. Packets with another version are rejected. */
    public static final int VERSION = 1;
    /** Fixed header size in bytes. */
    public static final int HEADER_SIZE = 22;
    /** Largest possible IPv4 UDP payload (65535 - 20 IP - 8 UDP). */
    public static final int MAX_PACKET_SIZE = 65507;
    /** Largest DATA/other payload that still fits in one datagram. */
    public static final int MAX_PAYLOAD = MAX_PACKET_SIZE - HEADER_SIZE;
    /** Highest value representable in the unsigned 32-bit fields (session id, seq, ack). */
    public static final long U32_MAX = 0xFFFFFFFFL;
    /** Highest value of the unsigned 16-bit window field. */
    public static final int U16_MAX = 0xFFFF;
    /** Longest accepted file name in bytes (UTF-8). */
    public static final int MAX_FILENAME_BYTES = 255;

    // ---- tunables ---------------------------------------------------------------------------
    private int chunkSize = 1024;
    private int windowSize = 16;
    private int retransmitTimeoutMs = 200;
    private int maxRetransmissions = 10;
    private int receiveWindow = 64;
    private int lingerMillis = 5000;
    private int sessionIdleTimeoutMs = 30000;
    private long maxFileSizeBytes = 1L << 30;
    private int maxConcurrentSessions = 64;

    /** Bytes of file data per DATA packet (sender side). Keep &lt;= 1450 to avoid IP fragmentation on Ethernet. */
    public int chunkSize() { return chunkSize; }
    /** Maximum number of packets the sender may have "in the window" (sender side). */
    public int windowSize() { return windowSize; }
    /** Retransmission timeout in milliseconds (fixed, not adaptive). */
    public int retransmitTimeoutMs() { return retransmitTimeoutMs; }
    /** How many times one packet may be retransmitted before the sender gives up. */
    public int maxRetransmissions() { return maxRetransmissions; }
    /** Receiver buffer capacity in packets (server side); advertised to the sender. */
    public int receiveWindow() { return receiveWindow; }
    /** How long the server keeps a finished session so it can answer a retransmitted FIN. */
    public int lingerMillis() { return lingerMillis; }
    /** A session with no traffic for this long is aborted and its partial file deleted (server side). */
    public int sessionIdleTimeoutMs() { return sessionIdleTimeoutMs; }
    /** Largest file the server accepts. */
    public long maxFileSizeBytes() { return maxFileSizeBytes; }
    /** Maximum number of simultaneous transfer sessions on the server. */
    public int maxConcurrentSessions() { return maxConcurrentSessions; }

    public ProtocolConfig chunkSize(int v) { this.chunkSize = v; return this; }
    public ProtocolConfig windowSize(int v) { this.windowSize = v; return this; }
    public ProtocolConfig retransmitTimeoutMs(int v) { this.retransmitTimeoutMs = v; return this; }
    public ProtocolConfig maxRetransmissions(int v) { this.maxRetransmissions = v; return this; }
    public ProtocolConfig receiveWindow(int v) { this.receiveWindow = v; return this; }
    public ProtocolConfig lingerMillis(int v) { this.lingerMillis = v; return this; }
    public ProtocolConfig sessionIdleTimeoutMs(int v) { this.sessionIdleTimeoutMs = v; return this; }
    public ProtocolConfig maxFileSizeBytes(long v) { this.maxFileSizeBytes = v; return this; }
    public ProtocolConfig maxConcurrentSessions(int v) { this.maxConcurrentSessions = v; return this; }

    /** @throws IllegalArgumentException if any value is outside its allowed range. */
    public ProtocolConfig validate() {
        require(chunkSize >= 1 && chunkSize <= MAX_PAYLOAD, "chunkSize must be 1.." + MAX_PAYLOAD);
        require(windowSize >= 1 && windowSize <= U16_MAX, "windowSize must be 1.." + U16_MAX);
        require(retransmitTimeoutMs >= 1, "retransmitTimeoutMs must be >= 1");
        require(maxRetransmissions >= 0, "maxRetransmissions must be >= 0");
        require(receiveWindow >= 1 && receiveWindow <= U16_MAX, "receiveWindow must be 1.." + U16_MAX);
        require(lingerMillis >= 0, "lingerMillis must be >= 0");
        require(sessionIdleTimeoutMs >= 1, "sessionIdleTimeoutMs must be >= 1");
        require(maxFileSizeBytes >= 0, "maxFileSizeBytes must be >= 0");
        require(maxConcurrentSessions >= 1, "maxConcurrentSessions must be >= 1");
        return this;
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }

    public ProtocolConfig copy() {
        return new ProtocolConfig()
                .chunkSize(chunkSize).windowSize(windowSize).retransmitTimeoutMs(retransmitTimeoutMs)
                .maxRetransmissions(maxRetransmissions).receiveWindow(receiveWindow)
                .lingerMillis(lingerMillis).sessionIdleTimeoutMs(sessionIdleTimeoutMs)
                .maxFileSizeBytes(maxFileSizeBytes).maxConcurrentSessions(maxConcurrentSessions);
    }

    @Override
    public String toString() {
        return "chunk=" + chunkSize + "B window=" + windowSize + " timeout=" + retransmitTimeoutMs
                + "ms maxRetx=" + maxRetransmissions + " rcvWindow=" + receiveWindow;
    }
}
