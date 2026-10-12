package reliableudp.transport;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import reliableudp.metrics.TransferMetrics;
import reliableudp.protocol.FinalReport;
import reliableudp.protocol.Packet;
import reliableudp.protocol.PacketCodec;
import reliableudp.protocol.PacketFormatException;
import reliableudp.protocol.PacketType;
import reliableudp.protocol.ProtocolConfig;
import reliableudp.protocol.TransferInfo;
import reliableudp.simulation.NetworkConditions;
import reliableudp.util.Logger;

/**
 * Client-side protocol engine: handshake, <b>Selective Repeat</b> data transfer, orderly close.
 *
 * <pre>
 *  1. SYN  ------------------------------------>   (retransmitted on timeout)
 *     <------------------------------------ SYN_ACK  (session id confirmed, receiver window advertised)
 *  2. DATA(seq) x window ------------------------->   each in-flight packet has its own timer
 *     <--------------------------------------- ACK  (cumulative ack + echoed seq + window)
 *  3. FIN  ------------------------------------>   (after every DATA packet is acknowledged)
 *     <---------------------------------- FIN_ACK  (final status + SHA-256 of the reconstructed file)
 * </pre>
 *
 * Single-threaded event loop: send what the window allows, wait for an ACK until the earliest
 * retransmission deadline, process it, then retransmit whatever expired.
 */
public final class ReliableSender {

    private final DatagramSocket socket;
    private final InetSocketAddress server;
    private final NetworkConditions net;
    private final ProtocolConfig cfg;
    private final TransferSession session;
    private final TransferMetrics metrics;
    private final ProgressListener progress;
    private final long timeoutNanos;
    private final byte[] rxBuffer = new byte[ProtocolConfig.MAX_PACKET_SIZE + 1];

    private int advertisedWindow = 1;
    private int lastReportedPercent = -1;

    public ReliableSender(DatagramSocket socket, InetSocketAddress server, NetworkConditions net,
                          ProtocolConfig cfg, TransferSession session, TransferMetrics metrics,
                          ProgressListener progress) {
        this.socket = socket;
        this.server = server;
        this.net = net;
        this.cfg = cfg;
        this.session = session;
        this.metrics = metrics;
        this.progress = progress;
        this.timeoutNanos = cfg.retransmitTimeoutMs() * 1_000_000L;
    }

    /**
     * Sends {@code file} to the server and returns the receiver's final report.
     *
     * @throws TransferException if the peer is unreachable, the retry limit is hit, or the server reports an error
     */
    public FinalReport send(Path file) throws IOException, TransferException {
        long size = Files.size(file);
        String name = file.getFileName().toString();
        TransferInfo info = new TransferInfo(size, cfg.chunkSize(), name);
        session.setInfo(info);
        metrics.markStart();

        try {
            // ---- 1. handshake ---------------------------------------------------------------
            session.transition(TransferSession.State.SYN_SENT);
            Logger.info("[client] session %08x: sending SYN for '%s' (%d bytes, %d packets of %d bytes)",
                    session.sessionId(), name, size, info.totalPackets(), cfg.chunkSize());
            Packet synAck = exchange(Packet.syn(session.sessionId(), info), PacketType.SYN_ACK);
            advertisedWindow = Math.max(1, synAck.window());
            session.transition(TransferSession.State.ESTABLISHED);
            Logger.info("[client] handshake complete; receiver window=%d, our window=%d",
                    advertisedWindow, cfg.windowSize());

            // ---- 2. data --------------------------------------------------------------------
            sendData(file, info);

            // ---- 3. orderly close ------------------------------------------------------------
            session.transition(TransferSession.State.FIN_WAIT);
            Logger.info("[client] all %d DATA packets acknowledged; sending FIN", info.totalPackets());
            Packet finAck = exchange(Packet.fin(session.sessionId(), info.totalPackets()), PacketType.FIN_ACK);
            FinalReport report;
            try {
                report = PacketCodec.decodeFinalReport(finAck.payload());
            } catch (PacketFormatException e) {
                throw new TransferException("malformed FIN_ACK payload: " + e.getMessage(), e);
            }
            session.transition(TransferSession.State.COMPLETE);
            return report;
        } catch (TransferException | IOException | RuntimeException e) {
            session.transition(TransferSession.State.FAILED);
            throw e;
        } finally {
            metrics.markEnd();
        }
    }

    // ---- data phase: Selective Repeat -------------------------------------------------------

    private void sendData(Path file, TransferInfo info) throws IOException, TransferException {
        long total = info.totalPackets();
        SlidingWindow window = new SlidingWindow(total);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            while (!window.isComplete()) {
                // (a) fill the window: only as far as min(our window, receiver's advertised window)
                int effective = Math.max(1, Math.min(cfg.windowSize(), advertisedWindow));
                while (window.canSend(effective)) {
                    long seq = window.nextSeq();
                    byte[] chunk = readChunk(ch, seq, info);
                    SlidingWindow.Entry e = window.register(chunk, System.nanoTime(), timeoutNanos);
                    sendData(e, false);
                }
                // (b) wait for an ACK, but not past the earliest retransmission deadline
                Packet p = receive(window.nextDeadlineNanos());
                if (p != null) {
                    handleDataPhasePacket(p, window, info);
                }
                // (c) retransmit everything whose timer expired (only those: Selective Repeat)
                long now = System.nanoTime();
                for (SlidingWindow.Entry e : window.expired(now)) {
                    metrics.countTimeout();
                    if (e.retransmissions() >= cfg.maxRetransmissions()) {
                        throw new TransferException("packet seq=" + e.seq() + " still unacknowledged after "
                                + e.retransmissions() + " retransmissions; peer unreachable or link too lossy");
                    }
                    Logger.info("[client] TIMEOUT seq=%d -> retransmission #%d", e.seq(), e.retransmissions() + 1);
                    window.markRetransmitted(e, now, timeoutNanos);
                    sendData(e, true);
                }
            }
        }
        reportProgress(info.fileSize(), info.fileSize());
    }

    private void sendData(SlidingWindow.Entry e, boolean retransmission) throws IOException {
        Packet p = Packet.data(session.sessionId(), e.seq(), e.payload());
        metrics.countDataSent(e.payload().length, retransmission);
        Logger.debug("[client] SEND %s%s", p, retransmission ? " (retransmission)" : "");
        net.send(socket, p, server);
    }

    private void handleDataPhasePacket(Packet p, SlidingWindow window, TransferInfo info) throws TransferException {
        if (p.sessionId() != session.sessionId()) {
            Logger.debug("[client] ignoring packet for other session: %s", p);
            return;
        }
        switch (p.type()) {
            case ERROR -> throw new TransferException("server reported error " + p.errorCode() + ": " + p.errorText());
            case ACK -> {
                metrics.countAckReceived();
                advertisedWindow = Math.max(1, p.window());
                SlidingWindow.AckOutcome outcome = window.onAck(p.ack(), p.seq());
                SlidingWindow.Entry sel = outcome.selectiveEntry();
                if (sel != null && sel.retransmissions() == 0) { // Karn's rule: never sample a retransmitted packet
                    metrics.addRttSample(System.nanoTime() - sel.firstSentNanos());
                }
                Logger.debug("[client] RECV %s (base=%d, outstanding=%d)", p, window.base(), window.outstanding());
                if (outcome.newlyAcked() > 0) {
                    long acked = Math.min(info.fileSize(), window.ackedPackets() * (long) info.chunkSize());
                    reportProgress(acked, info.fileSize());
                }
            }
            default -> Logger.debug("[client] ignoring unexpected %s during data phase", p);
        }
    }

    private void reportProgress(long ackedBytes, long total) {
        if (progress == null) {
            return;
        }
        int percent = total == 0 ? 100 : (int) (ackedBytes * 100 / total);
        if (percent != lastReportedPercent) {
            lastReportedPercent = percent;
            progress.onProgress(ackedBytes, total);
        }
    }

    private byte[] readChunk(FileChannel ch, long seq, TransferInfo info) throws IOException {
        long pos = seq * info.chunkSize();
        int len = (int) Math.min(info.chunkSize(), info.fileSize() - pos);
        ByteBuffer buf = ByteBuffer.allocate(len);
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos + buf.position());
            if (n < 0) {
                throw new IOException("file shrank while it was being sent");
            }
        }
        return buf.array();
    }

    // ---- control exchange (SYN / FIN) with retransmission ----------------------------------

    /** Sends {@code request}, retransmitting on timeout, until a packet of type {@code expected} arrives. */
    private Packet exchange(Packet request, PacketType expected) throws IOException, TransferException {
        int attempts = 1 + cfg.maxRetransmissions();
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (attempt > 0) {
                metrics.countTimeout();
                Logger.info("[client] TIMEOUT waiting for %s -> retransmitting %s (attempt %d)",
                        expected, request.type(), attempt + 1);
            }
            metrics.countControlSent(attempt > 0);
            Logger.debug("[client] SEND %s", request);
            net.send(socket, request, server);
            long deadline = System.nanoTime() + timeoutNanos;
            Packet p;
            while ((p = receive(deadline)) != null) {
                if (p.sessionId() != session.sessionId()) {
                    continue;
                }
                if (p.type() == PacketType.ERROR) {
                    throw new TransferException("server reported error " + p.errorCode() + ": " + p.errorText());
                }
                if (p.type() == expected) {
                    Logger.debug("[client] RECV %s", p);
                    return p;
                }
                // anything else (late ACKs from the data phase, duplicates) is irrelevant here
            }
        }
        throw new TransferException("no " + expected + " from " + server + " after " + attempts
                + " attempts of " + request.type() + " (server unreachable or not responding)");
    }

    // ---- receiving --------------------------------------------------------------------------

    /**
     * Waits for the next valid packet from the server until {@code deadlineNanos}.
     *
     * @return the packet, or {@code null} if the deadline passed first
     */
    private Packet receive(long deadlineNanos) throws IOException {
        DatagramPacket dp = new DatagramPacket(rxBuffer, rxBuffer.length);
        while (true) {
            long remaining = deadlineNanos == Long.MAX_VALUE ? 1_000_000_000L : deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                return null;
            }
            socket.setSoTimeout((int) Math.max(1, Math.min(Integer.MAX_VALUE, (remaining + 999_999) / 1_000_000)));
            dp.setLength(rxBuffer.length);
            try {
                socket.receive(dp);
            } catch (SocketTimeoutException e) {
                continue; // re-evaluate the deadline
            } catch (SocketException e) {
                if (socket.isClosed()) {
                    throw e;
                }
                // Windows reports an ICMP "port unreachable" from an earlier send as a SocketException
                // on the next receive. It is not fatal: the retry logic decides when to give up.
                Logger.debug("[client] transient socket error: %s", e.getMessage());
                try {
                    Thread.sleep(2);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", ie);
                }
                continue;
            }
            InetSocketAddress from = new InetSocketAddress(dp.getAddress(), dp.getPort());
            if (dp.getPort() != server.getPort() || !dp.getAddress().equals(server.getAddress())) {
                Logger.debug("[client] ignoring datagram from unexpected peer %s", from);
                continue;
            }
            try {
                return PacketCodec.decode(rxBuffer, 0, dp.getLength());
            } catch (PacketFormatException e) {
                metrics.countInvalidPacket(e.isChecksumFailure());
                Logger.debug("[client] discarded invalid datagram: %s", e.getMessage());
            }
        }
    }
}
