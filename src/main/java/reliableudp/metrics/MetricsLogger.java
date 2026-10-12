package reliableudp.metrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/** Writes {@link TransferMetrics} to CSV (one row per transfer) and renders a text summary. */
public final class MetricsLogger {

    public static final String[] COLUMNS = {
        "timestamp_iso", "scenario", "role", "file_name", "file_size_bytes", "chunk_size", "window_size",
        "timeout_ms", "loss_rate", "delay_ms", "jitter_ms", "corrupt_rate", "seed",
        "success", "integrity_match", "duration_ms",
        "throughput_bytes_per_s", "throughput_mbps", "goodput_bytes_per_s", "goodput_mbps",
        "unique_data_packets", "data_packets_sent", "ack_packets_received",
        "retransmissions", "timeouts", "control_retransmissions",
        "simulated_dropped", "simulated_corrupted", "invalid_packets_detected", "checksum_failures_detected",
        "rtt_est_ms", "rtt_min_ms", "rtt_max_ms", "rtt_samples",
        "server_data_received", "server_duplicates", "server_out_of_order", "server_invalid_packets",
        "source_sha256", "dest_sha256", "error"
    };

    private MetricsLogger() {
    }

    /** Appends one row (and the header first if the file is new or empty). */
    public static synchronized void append(Path csv, TransferMetrics m) throws IOException {
        if (csv.getParent() != null) {
            Files.createDirectories(csv.getParent());
        }
        boolean needHeader = !Files.exists(csv) || Files.size(csv) == 0;
        StringBuilder sb = new StringBuilder();
        if (needHeader) {
            sb.append(String.join(",", COLUMNS)).append('\n');
        }
        sb.append(row(m)).append('\n');
        Files.writeString(csv, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** @return the CSV row (no trailing newline) in {@link #COLUMNS} order. */
    public static String row(TransferMetrics m) {
        double tp = m.throughputBytesPerSec();
        double gp = m.goodputBytesPerSec();
        String[] v = {
            m.startedAt.toString(), m.scenario, m.role, m.fileName, str(m.fileSizeBytes), str(m.chunkSize),
            str(m.windowSize), str(m.timeoutMs), num(m.lossRate, 4), str(m.delayMs), str(m.jitterMs),
            num(m.corruptRate, 4), str(m.seed),
            str(m.success), str(m.integrityMatch), num(m.durationSeconds() * 1000, 3),
            num(tp, 1), num(TransferMetrics.toMbps(tp), 4), num(gp, 1), num(TransferMetrics.toMbps(gp), 4),
            str(m.uniqueDataPackets), str(m.dataPacketsSent), str(m.ackPacketsReceived),
            str(m.retransmissions), str(m.timeouts), str(m.controlRetransmissions),
            str(m.simulatedDropped), str(m.simulatedCorrupted), str(m.invalidPacketsReceived),
            str(m.checksumFailures),
            num(m.srttMs, 3), num(m.minRttMs, 3), num(m.maxRttMs, 3), str(m.rttSamples),
            str(m.serverDataReceived), str(m.serverDuplicates), str(m.serverOutOfOrder), str(m.serverInvalid),
            m.sourceSha256, m.destinationSha256, m.error
        };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(v[i]));
        }
        return sb.toString();
    }

    /** Human-readable multi-line summary for the console / GUI. */
    public static String summary(TransferMetrics m) {
        double tp = m.throughputBytesPerSec();
        double gp = m.goodputBytesPerSec();
        return String.format(Locale.ROOT,
                "---- Transfer summary ----%n"
                + "Result            : %s%s%n"
                + "File              : %s (%d bytes)%n"
                + "Duration          : %.3f s%n"
                + "Throughput        : %.0f B/s (%.3f Mbps)  [payload incl. retransmissions]%n"
                + "Goodput           : %.0f B/s (%.3f Mbps)  [file bytes delivered intact]%n"
                + "DATA sent         : %d (unique %d, retransmissions %d)%n"
                + "ACKs received     : %d%n"
                + "Timeouts          : %d (control-packet retransmissions: %d)%n"
                + "Simulated drops   : %d, simulated corruptions: %d (client-side, outgoing)%n"
                + "Invalid received  : %d (checksum failures: %d)%n"
                + "RTT estimate      : %s ms (min %s, max %s, %d samples)%n"
                + "Server saw        : %d DATA arrivals, %d duplicates, %d out-of-order, %d invalid%n"
                + "SHA-256 source    : %s%n"
                + "SHA-256 received  : %s%n"
                + "Integrity         : %s",
                m.success ? "SUCCESS" : "FAILED", m.error.isEmpty() ? "" : " (" + m.error + ")",
                m.fileName, m.fileSizeBytes, m.durationSeconds(),
                tp, TransferMetrics.toMbps(tp), gp, TransferMetrics.toMbps(gp),
                m.dataPacketsSent, m.uniqueDataPackets, m.retransmissions,
                m.ackPacketsReceived, m.timeouts, m.controlRetransmissions,
                m.simulatedDropped, m.simulatedCorrupted, m.invalidPacketsReceived, m.checksumFailures,
                num(m.srttMs, 2), num(m.minRttMs, 2), num(m.maxRttMs, 2), m.rttSamples,
                m.serverDataReceived, m.serverDuplicates, m.serverOutOfOrder, m.serverInvalid,
                m.sourceSha256.isEmpty() ? "n/a" : m.sourceSha256,
                m.destinationSha256.isEmpty() ? "n/a (receiver result not available)" : m.destinationSha256,
                m.destinationSha256.isEmpty() ? "NOT VERIFIED" : (m.integrityMatch ? "MATCH" : "MISMATCH"));
    }

    private static String str(long v) { return Long.toString(v); }
    private static String str(boolean v) { return Boolean.toString(v); }

    private static String num(double v, int decimals) {
        return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    private static String escape(String s) {
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
