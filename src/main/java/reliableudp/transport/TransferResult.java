package reliableudp.transport;

import reliableudp.metrics.TransferMetrics;
import reliableudp.protocol.FinalReport;

/**
 * Outcome of a client-side transfer.
 *
 * @param success         protocol finished, receiver reported success AND the SHA-256 digests match
 * @param error           reason for failure ("" on success)
 * @param metrics         measured statistics
 * @param serverReport    the receiver's final report, or {@code null} if it was never received
 * @param sourceSha256    SHA-256 (hex) of the local file
 * @param integrityMatch  receiver's digest equals {@code sourceSha256}
 */
public record TransferResult(boolean success, String error, TransferMetrics metrics, FinalReport serverReport,
                             String sourceSha256, boolean integrityMatch) {
}
