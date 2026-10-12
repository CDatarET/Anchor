package reliableudp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import reliableudp.protocol.ErrorCode;
import reliableudp.protocol.FinalReport;
import reliableudp.protocol.Packet;
import reliableudp.protocol.PacketCodec;
import reliableudp.protocol.PacketFormatException;
import reliableudp.protocol.ProtocolConfig;
import reliableudp.protocol.TransferInfo;
import reliableudp.simulation.NetworkConditions;
import reliableudp.transport.ReliableReceiver;
import reliableudp.transport.TransferSession;
import reliableudp.util.ArgParser;
import reliableudp.util.FileChecksum;
import reliableudp.util.Logger;
import reliableudp.util.SafePaths;

/**
 * UDP file-receiving server. A single thread reads datagrams and dispatches them to per-session
 * state ({@link TransferSession} + {@link ReliableReceiver}), so there is no shared mutable state
 * between threads apart from the socket itself.
 *
 * <p>Usage: {@code java reliableudp.Server <port> [--dir downloads] [--loss p] [--delay ms] ...}
 */
public final class Server implements java.io.Closeable {

    /** Record of a finished session, exposed for tests and tooling. */
    public record CompletedTransfer(long sessionId, Path file, String sha256, boolean success) { }

    private static final int POLL_MILLIS = 100;

    private final DatagramSocket socket;
    private final Path downloadsDir;
    private final ProtocolConfig cfg;
    private final NetworkConditions net;
    private final Map<String, TransferSession> sessions = new HashMap<>();
    private final Map<InetSocketAddress, Long> invalidByPeer = new HashMap<>();
    private final List<CompletedTransfer> completed = new CopyOnWriteArrayList<>();
    private final byte[] rxBuffer = new byte[ProtocolConfig.MAX_PACKET_SIZE + 1];

    private volatile boolean running;
    private volatile int activeSessionCount;
    private Thread thread;

    public Server(int port, Path downloadsDir, ProtocolConfig cfg, NetworkConditions net) throws IOException {
        this.cfg = cfg.copy().validate();
        this.downloadsDir = downloadsDir.toAbsolutePath().normalize();
        this.net = net;
        Files.createDirectories(this.downloadsDir);
        this.socket = new DatagramSocket(port);
        try {
            socket.setReceiveBufferSize(1 << 20);
        } catch (SocketException ignored) {
            // best effort; the OS default is used otherwise
        }
        socket.setSoTimeout(POLL_MILLIS);
    }

    public int getPort() { return socket.getLocalPort(); }
    public int activeSessions() { return activeSessionCount; }
    public List<CompletedTransfer> completedTransfers() { return completed; }
    public NetworkConditions networkConditions() { return net; }

    /** Starts the receive loop on a background thread. */
    public void start() {
        running = true;
        thread = new Thread(this::run, "udp-server");
        thread.setDaemon(true);
        thread.start();
    }

    /** Runs the receive loop on the calling thread until {@link #close()} is called. */
    public void run() {
        running = true;
        Logger.info("[server] listening on UDP port %d, saving to %s (%s; impairments: %s)",
                getPort(), downloadsDir, cfg, net.isEnabled() ? net.toString() : "none");
        DatagramPacket dp = new DatagramPacket(rxBuffer, rxBuffer.length);
        long lastReap = System.nanoTime();
        while (running) {
            dp.setLength(rxBuffer.length);
            try {
                socket.receive(dp);
                InetSocketAddress from = new InetSocketAddress(dp.getAddress(), dp.getPort());
                try {
                    Packet p = PacketCodec.decode(rxBuffer, 0, dp.getLength());
                    handle(p, from);
                } catch (PacketFormatException e) {
                    countInvalid(from);
                    Logger.warn("[server] invalid datagram from %s (%d bytes): %s", from, dp.getLength(), e.getMessage());
                }
            } catch (SocketTimeoutException e) {
                // idle tick
            } catch (SocketException e) {
                if (!running || socket.isClosed()) {
                    break;
                }
                // e.g. Windows ICMP "connection reset" after sending to a client that already left
                Logger.debug("[server] transient socket error: %s", e.getMessage());
            } catch (IOException | RuntimeException e) {
                Logger.error("[server] error while handling a datagram: %s", e);
            }
            long now = System.nanoTime();
            if (now - lastReap > 100_000_000L) {
                reapSessions();
                lastReap = now;
            }
        }
        for (TransferSession s : new ArrayList<>(sessions.values())) {
            abortSession(s, "server shutting down");
        }
        Logger.info("[server] stopped");
    }

    @Override
    public void close() {
        running = false;
        socket.close();
        if (thread != null) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        net.close();
    }

    // ---- packet dispatch ----------------------------------------------------------------------

    private void handle(Packet p, InetSocketAddress from) throws IOException {
        switch (p.type()) {
            case SYN -> onSyn(p, from);
            case DATA -> onData(p, from);
            case FIN -> onFin(p, from);
            default -> Logger.debug("[server] ignoring %s from %s", p, from);
        }
    }

    private void onSyn(Packet p, InetSocketAddress from) throws IOException {
        String key = TransferSession.key(from, p.sessionId());
        TransferInfo info;
        try {
            info = PacketCodec.decodeTransferInfo(p.payload());
        } catch (PacketFormatException e) {
            reply(from, p.sessionId(), ErrorCode.BAD_REQUEST, e.getMessage());
            return;
        }
        TransferSession existing = sessions.get(key);
        if (existing != null) {
            if (!info.equals(existing.info())) {
                reply(from, p.sessionId(), ErrorCode.SESSION_EXISTS, "session id already in use");
            } else if (existing.state() == TransferSession.State.ESTABLISHED) {
                // Retransmitted or duplicated SYN (our SYN_ACK may have been lost): answer again, idempotently.
                Logger.debug("[server] duplicate SYN for session %08x: repeating SYN_ACK", p.sessionId());
                send(Packet.synAck(p.sessionId(), existing.receiver().capacity()), from);
            } else {
                Logger.debug("[server] stale SYN for finished session %08x ignored", p.sessionId());
            }
            return;
        }
        String nameProblem = SafePaths.validateFileName(info.fileName());
        if (nameProblem != null) {
            Logger.warn("[server] rejected SYN from %s: unsafe file name (%s)", from, nameProblem);
            reply(from, p.sessionId(), ErrorCode.BAD_FILENAME, nameProblem);
            return;
        }
        if (info.fileSize() > cfg.maxFileSizeBytes()) {
            reply(from, p.sessionId(), ErrorCode.FILE_TOO_LARGE, "limit is " + cfg.maxFileSizeBytes() + " bytes");
            return;
        }
        if (info.totalPackets() > ProtocolConfig.U32_MAX / 2) {
            reply(from, p.sessionId(), ErrorCode.BAD_REQUEST, "too many packets; use a larger chunk size");
            return;
        }
        long active = sessions.values().stream().filter(s -> s.state() == TransferSession.State.ESTABLISHED).count();
        if (active >= cfg.maxConcurrentSessions()) {
            reply(from, p.sessionId(), ErrorCode.SERVER_BUSY, "too many concurrent transfers");
            return;
        }

        TransferSession s = new TransferSession(p.sessionId(), from);
        s.setInfo(info);
        Path target = SafePaths.reserveTarget(downloadsDir, info.fileName());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            s.setTargetFile(target);
            s.setReceiver(new ReliableReceiver(s, part, cfg.receiveWindow()));
        } catch (IOException e) {
            Files.deleteIfExists(target);
            reply(from, p.sessionId(), ErrorCode.INTERNAL_ERROR, "cannot create output file");
            throw e;
        }
        s.transition(TransferSession.State.ESTABLISHED);
        sessions.put(key, s);
        activeSessionCount = sessions.size();
        Logger.info("[server] session %08x from %s: '%s' %d bytes, %d packets of %d bytes -> %s",
                p.sessionId(), from, info.fileName(), info.fileSize(), info.totalPackets(), info.chunkSize(),
                target.getFileName());
        send(Packet.synAck(p.sessionId(), s.receiver().capacity()), from);
    }

    private void onData(Packet p, InetSocketAddress from) throws IOException {
        TransferSession s = sessions.get(TransferSession.key(from, p.sessionId()));
        if (s == null) {
            Logger.warn("[server] DATA for unknown session %08x from %s", p.sessionId(), from);
            reply(from, p.sessionId(), ErrorCode.UNKNOWN_SESSION, "no such session");
            return;
        }
        if (s.state() != TransferSession.State.ESTABLISHED) {
            Logger.debug("[server] stale DATA for finished session %08x ignored", p.sessionId());
            return;
        }
        s.touch();
        try {
            Packet ack = s.receiver().onData(p);
            if (ack != null) {
                send(ack, from);
            }
        } catch (IOException e) {
            Logger.error("[server] session %08x: write failed: %s", p.sessionId(), e);
            reply(from, p.sessionId(), ErrorCode.INTERNAL_ERROR, "write failed");
            abortSession(s, "write failed");
        }
    }

    private void onFin(Packet p, InetSocketAddress from) throws IOException {
        TransferSession s = sessions.get(TransferSession.key(from, p.sessionId()));
        if (s == null) {
            Logger.warn("[server] FIN for unknown session %08x from %s", p.sessionId(), from);
            reply(from, p.sessionId(), ErrorCode.UNKNOWN_SESSION, "no such session");
            return;
        }
        s.touch();
        if (s.state() == TransferSession.State.COMPLETE) {
            Logger.info("[server] session %08x: repeated FIN (our FIN_ACK was probably lost) -> resending FIN_ACK",
                    p.sessionId());
            send(s.finAck(), from);
            return;
        }
        ReliableReceiver rx = s.receiver();
        if (p.seq() != s.totalPackets()) {
            reply(from, p.sessionId(), ErrorCode.PROTOCOL_VIOLATION, "FIN seq must equal the packet count");
            return;
        }
        if (!rx.isComplete()) {
            Logger.warn("[server] session %08x: FIN but only %d of %d packets delivered", p.sessionId(),
                    rx.nextExpected(), s.totalPackets());
            reply(from, p.sessionId(), ErrorCode.INCOMPLETE_TRANSFER,
                    "received " + rx.nextExpected() + " of " + s.totalPackets() + " packets");
            return;
        }

        // All data present: close the part file, verify size, commit atomically, hash the final file.
        rx.close();
        boolean ok = false;
        byte[] digest = new byte[32];
        long written = rx.bytesWritten();
        try {
            if (written != s.info().fileSize()) {
                throw new IOException("size mismatch: wrote " + written + ", expected " + s.info().fileSize());
            }
            Files.move(rx.partFile(), s.targetFile(), StandardCopyOption.REPLACE_EXISTING); // replaces our own placeholder
            digest = FileChecksum.sha256(s.targetFile());
            ok = true;
        } catch (IOException e) {
            Logger.error("[server] session %08x: could not finalise file: %s", p.sessionId(), e);
            Files.deleteIfExists(rx.partFile());
            Files.deleteIfExists(s.targetFile());
        }
        long invalid = invalidByPeer.getOrDefault(from, 0L);
        FinalReport report = new FinalReport(ok, digest, written, rx.dataReceived(), rx.duplicates(),
                rx.outOfOrder(), invalid);
        s.setFinAck(Packet.finAck(p.sessionId(), s.totalPackets(), report));
        s.setLingerUntilNanos(System.nanoTime() + cfg.lingerMillis() * 1_000_000L);
        s.transition(ok ? TransferSession.State.COMPLETE : TransferSession.State.FAILED);
        completed.add(new CompletedTransfer(p.sessionId(), ok ? s.targetFile() : null, FileChecksum.toHex(digest), ok));
        Logger.info("[server] session %08x %s: %d bytes -> %s, SHA-256 %s (dup=%d, out-of-order=%d, invalid=%d)",
                p.sessionId(), ok ? "COMPLETE" : "FAILED", written, s.targetFile().getFileName(),
                FileChecksum.toHex(digest), rx.duplicates(), rx.outOfOrder(), invalid);
        send(s.finAck(), from);
        if (!ok) {
            sessions.remove(TransferSession.key(from, p.sessionId()));
            activeSessionCount = sessions.size();
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private void send(Packet p, InetSocketAddress to) throws IOException {
        Logger.debug("[server] SEND %s -> %s", p, to);
        net.send(socket, p, to);
    }

    /** Sends an ERROR packet. Not subject to impairment decisions beyond the normal send path. */
    private void reply(InetSocketAddress to, long sessionId, ErrorCode code, String text) throws IOException {
        send(Packet.error(sessionId, code, text), to);
    }

    private void countInvalid(InetSocketAddress from) {
        if (invalidByPeer.size() < 1024 || invalidByPeer.containsKey(from)) { // bounded memory
            invalidByPeer.merge(from, 1L, Long::sum);
        }
    }

    private void abortSession(TransferSession s, String reason) {
        Logger.warn("[server] session %08x aborted: %s", s.sessionId(), reason);
        s.transition(TransferSession.State.FAILED);
        try {
            if (s.receiver() != null) {
                s.receiver().close();
                Files.deleteIfExists(s.receiver().partFile());
            }
            if (s.targetFile() != null) {
                Files.deleteIfExists(s.targetFile());
            }
        } catch (IOException e) {
            Logger.warn("[server] cleanup failed: %s", e);
        }
        sessions.remove(TransferSession.key(s.peer(), s.sessionId()));
        activeSessionCount = sessions.size();
    }

    /** Removes lingering finished sessions and aborts idle ones. */
    private void reapSessions() {
        long now = System.nanoTime();
        List<TransferSession> idle = new ArrayList<>();
        Iterator<Map.Entry<String, TransferSession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            TransferSession s = it.next().getValue();
            if (s.state() == TransferSession.State.COMPLETE) {
                if (now - s.lingerUntilNanos() > 0) {
                    Logger.debug("[server] session %08x closed after linger", s.sessionId());
                    invalidByPeer.remove(s.peer());
                    it.remove();
                }
            } else if (s.idleNanos() > cfg.sessionIdleTimeoutMs() * 1_000_000L) {
                idle.add(s);
            }
        }
        for (TransferSession s : idle) {
            abortSession(s, "idle for " + cfg.sessionIdleTimeoutMs() + " ms");
        }
        activeSessionCount = sessions.size();
    }

    // ---- command line ---------------------------------------------------------------------------

    private static final Set<String> FLAGS = Set.of("verbose", "help");
    private static final Set<String> OPTIONS = Set.of("verbose", "help", "dir", "loss", "delay", "jitter",
            "corrupt", "dup", "seed", "window", "linger", "idle", "log-file", "max-size");

    public static void main(String[] args) {
        try {
            ArgParser a = new ArgParser(args, FLAGS).allow(OPTIONS);
            if (a.has("help") || a.positional().size() != 1) {
                System.out.println("""
                        Usage: java -cp out reliableudp.Server <port> [options]
                          --dir <path>       downloads directory (default: downloads)
                          --window <n>       receive window in packets (default: 64)
                          --linger <ms>      keep finished sessions this long to answer repeated FINs (default: 5000)
                          --idle <ms>        abort sessions idle this long (default: 30000)
                          --max-size <bytes> largest accepted file (default: 1073741824)
                          --loss <0..1>      simulated loss of packets this server SENDS (ACKs, SYN_ACK, FIN_ACK)
                          --delay <ms>       simulated one-way delay of packets this server sends
                          --jitter <ms>      extra random delay 0..jitter
                          --corrupt <0..1>   probability of flipping one bit in a sent packet
                          --dup <0..1>       probability of sending a packet twice
                          --seed <n>         random seed for the simulator (default: 1)
                          --log-file <path>  also write the log to this file
                          --verbose          log every packet
                        """);
                return;
            }
            Logger.setLevel(a.has("verbose") ? Logger.Level.DEBUG : Logger.Level.INFO);
            if (a.has("log-file")) {
                Logger.setLogFile(Paths.get(a.get("log-file", "")));
            }
            int port = Integer.parseInt(a.positional().get(0));
            ProtocolConfig cfg = new ProtocolConfig()
                    .receiveWindow(a.getInt("window", 64))
                    .lingerMillis(a.getInt("linger", 5000))
                    .sessionIdleTimeoutMs(a.getInt("idle", 30000))
                    .maxFileSizeBytes(a.getLong("max-size", 1L << 30));
            NetworkConditions net = new NetworkConditions(a.getDouble("loss", 0), a.getInt("delay", 0),
                    a.getInt("jitter", 0), a.getDouble("corrupt", 0), a.getDouble("dup", 0), a.getLong("seed", 1));
            Server server = new Server(port, Paths.get(a.get("dir", "downloads")), cfg, net);
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            server.run();
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage() + " (use --help)");
            System.exit(2);
        } catch (IOException e) {
            System.err.println("Server failed: " + e.getMessage());
            System.exit(1);
        }
    }
}
