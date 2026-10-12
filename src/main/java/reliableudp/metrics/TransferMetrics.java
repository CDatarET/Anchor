package reliableudp.metrics;

import java.time.Instant;
import reliableudp.protocol.ProtocolConfig;
import reliableudp.simulation.NetworkConditions;

/**
 * Measured results of one transfer. Every number here is counted or timed by the running code;
 * nothing is estimated from a formula except where the name says so (RTT is an <em>estimate</em>).
 *
 * <p>Definitions (see README):
 * <ul>
 *   <li><b>dataPacketsSent</b>: every DATA transmission handed to the network layer, including
 *       retransmissions and including those the simulator then dropped.</li>
 *   <li><b>throughput</b>: DATA payload bytes transmitted (incl. retransmissions) / duration.</li>
 *   <li><b>goodput</b>: file bytes delivered intact (only counted on verified success) / duration.</li>
 *   <li><b>RTT</b>: smoothed (EWMA, alpha=1/8) time between sending a DATA packet and receiving the ACK that
 *       echoes it; packets that were retransmitted are excluded (Karn's rule).</li>
 * </ul>
 * Written by one protocol thread; fields are volatile so a GUI thread can read them safely.
 */
public final class TransferMetrics {

    // ---- context ---------------------------------------------------------------------------
    volatile String scenario = "";
    volatile String role = "client";
    volatile String fileName = "";
    volatile long fileSizeBytes;
    volatile int chunkSize;
    volatile int windowSize;
    volatile int timeoutMs;
    volatile double lossRate;
    volatile int delayMs;
    volatile int jitterMs;
    volatile double corruptRate;
    volatile long seed;

    // ---- timing ----------------------------------------------------------------------------
    volatile Instant startedAt = Instant.now();
    volatile long startNanos;
    volatile long endNanos;

    // ---- counters --------------------------------------------------------------------------
    volatile long uniqueDataPackets;
    volatile long dataPacketsSent;
    volatile long payloadBytesSent;
    volatile long ackPacketsReceived;
    volatile long retransmissions;
    volatile long timeouts;
    volatile long controlPacketsSent;
    volatile long controlRetransmissions;
    volatile long simulatedDropped;
    volatile long simulatedCorrupted;
    volatile long invalidPacketsReceived;
    volatile long checksumFailures;

    // ---- RTT -------------------------------------------------------------------------------
    volatile double srttMs = Double.NaN;
    volatile double minRttMs = Double.NaN;
    volatile double maxRttMs = Double.NaN;
    volatile long rttSamples;

    // ---- outcome ---------------------------------------------------------------------------
    volatile boolean success;
    volatile boolean integrityMatch;
    volatile String sourceSha256 = "";
    volatile String destinationSha256 = "";
    volatile String error = "";
    volatile long serverDataReceived;
    volatile long serverDuplicates;
    volatile long serverOutOfOrder;
    volatile long serverInvalid;

    // ---- setup -----------------------------------------------------------------------------

    public void configure(String scenario, String role, String fileName, long fileSize,
                          ProtocolConfig cfg, NetworkConditions net) {
        this.scenario = scenario == null ? "" : scenario;
        this.role = role;
        this.fileName = fileName;
        this.fileSizeBytes = fileSize;
        this.chunkSize = cfg.chunkSize();
        this.windowSize = cfg.windowSize();
        this.timeoutMs = cfg.retransmitTimeoutMs();
        this.lossRate = net.lossRate();
        this.delayMs = net.delayMs();
        this.jitterMs = net.jitterMs();
        this.corruptRate = net.corruptRate();
        this.seed = net.seed();
    }

    public void markStart() {
        startedAt = Instant.now();
        startNanos = System.nanoTime();
        endNanos = 0;
    }

    public void markEnd() {
        endNanos = System.nanoTime();
    }

    // ---- counting --------------------------------------------------------------------------

    public void countDataSent(int payloadLength, boolean retransmission) {
        dataPacketsSent++;
        payloadBytesSent += payloadLength;
        if (retransmission) {
            retransmissions++;
        } else {
            uniqueDataPackets++;
        }
    }

    public void countControlSent(boolean retransmission) {
        controlPacketsSent++;
        if (retransmission) {
            controlRetransmissions++;
        }
    }

    public void countAckReceived() { ackPacketsReceived++; }
    public void countTimeout() { timeouts++; }

    public void countInvalidPacket(boolean checksumFailure) {
        invalidPacketsReceived++;
        if (checksumFailure) {
            checksumFailures++;
        }
    }

    /** Adds an RTT sample (nanoseconds) taken from a never-retransmitted packet. */
    public void addRttSample(long nanos) {
        double ms = nanos / 1e6;
        srttMs = Double.isNaN(srttMs) ? ms : 0.875 * srttMs + 0.125 * ms;
        minRttMs = Double.isNaN(minRttMs) ? ms : Math.min(minRttMs, ms);
        maxRttMs = Double.isNaN(maxRttMs) ? ms : Math.max(maxRttMs, ms);
        rttSamples++;
    }

    public void setSimulation(long dropped, long corrupted) {
        simulatedDropped = dropped;
        simulatedCorrupted = corrupted;
    }

    public void setServerReport(long received, long duplicates, long outOfOrder, long invalid) {
        serverDataReceived = received;
        serverDuplicates = duplicates;
        serverOutOfOrder = outOfOrder;
        serverInvalid = invalid;
    }

    public void setOutcome(boolean success, boolean integrityMatch, String sourceSha, String destSha, String error) {
        this.success = success;
        this.integrityMatch = integrityMatch;
        this.sourceSha256 = sourceSha == null ? "" : sourceSha;
        this.destinationSha256 = destSha == null ? "" : destSha;
        this.error = error == null ? "" : error;
    }

    // ---- derived values --------------------------------------------------------------------

    public double durationSeconds() {
        long end = endNanos != 0 ? endNanos : System.nanoTime();
        return startNanos == 0 ? 0 : Math.max(0, end - startNanos) / 1e9;
    }

    public double throughputBytesPerSec() {
        double d = durationSeconds();
        return d > 0 ? payloadBytesSent / d : 0;
    }

    public double goodputBytesPerSec() {
        double d = durationSeconds();
        return d > 0 && success ? fileSizeBytes / d : 0;
    }

    public static double toMbps(double bytesPerSec) {
        return bytesPerSec * 8 / 1_000_000.0;
    }

    // ---- getters used by the CLI, tests and experiment runner -------------------------------

    public String scenario() { return scenario; }
    public String role() { return role; }
    public long fileSizeBytes() { return fileSizeBytes; }
    public long uniqueDataPackets() { return uniqueDataPackets; }
    public long dataPacketsSent() { return dataPacketsSent; }
    public long payloadBytesSent() { return payloadBytesSent; }
    public long ackPacketsReceived() { return ackPacketsReceived; }
    public long retransmissions() { return retransmissions; }
    public long timeouts() { return timeouts; }
    public long controlPacketsSent() { return controlPacketsSent; }
    public long controlRetransmissions() { return controlRetransmissions; }
    public long simulatedDropped() { return simulatedDropped; }
    public long simulatedCorrupted() { return simulatedCorrupted; }
    public long invalidPacketsReceived() { return invalidPacketsReceived; }
    public long checksumFailures() { return checksumFailures; }
    public double srttMs() { return srttMs; }
    public long rttSamples() { return rttSamples; }
    public boolean success() { return success; }
    public boolean integrityMatch() { return integrityMatch; }
    public String sourceSha256() { return sourceSha256; }
    public String destinationSha256() { return destinationSha256; }
    public String error() { return error; }
    public long serverDuplicates() { return serverDuplicates; }
    public long serverOutOfOrder() { return serverOutOfOrder; }
    public long serverInvalid() { return serverInvalid; }
    public long endNanos() { return endNanos; }
}
