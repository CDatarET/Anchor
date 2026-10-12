package reliableudp;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Scanner;
import java.util.Set;
import reliableudp.metrics.MetricsLogger;
import reliableudp.metrics.TransferMetrics;
import reliableudp.protocol.FinalReport;
import reliableudp.protocol.ProtocolConfig;
import reliableudp.simulation.NetworkConditions;
import reliableudp.transport.ProgressListener;
import reliableudp.transport.ReliableSender;
import reliableudp.transport.TransferException;
import reliableudp.transport.TransferResult;
import reliableudp.transport.TransferSession;
import reliableudp.util.ArgParser;
import reliableudp.util.FileChecksum;
import reliableudp.util.Logger;

/**
 * Command-line client and reusable client API.
 *
 * <p>CLI: {@code java -cp out reliableudp.Client <host> <port> [file] [options]}
 * (if {@code file} is omitted you are prompted for it; {@code --gui} opens the Swing front-end).
 * Programmatic use: {@link #transfer}.
 */
public final class Client {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Client() {
    }

    /**
     * Sends one file and measures everything. Never throws for transfer-level problems: failures
     * are reported in the returned {@link TransferResult}.
     *
     * @param net      impairment applied to this client's outgoing packets (not closed by this method)
     * @param scenario free-text label stored in the CSV
     * @param listener progress callback, may be null
     */
    public static TransferResult transfer(InetSocketAddress server, Path file, ProtocolConfig cfg,
                                          NetworkConditions net, String scenario, ProgressListener listener) {
        ProtocolConfig config = cfg.copy().validate();
        TransferMetrics metrics = new TransferMetrics();
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        long size = 0;
        String sourceSha = "";
        try {
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                throw new IOException("not a readable file: " + file);
            }
            size = Files.size(file);
            sourceSha = FileChecksum.sha256Hex(file); // hashed before the clock starts
        } catch (IOException e) {
            metrics.configure(scenario, "client", fileName, size, config, net);
            metrics.setOutcome(false, false, sourceSha, "", e.getMessage());
            return new TransferResult(false, e.getMessage(), metrics, null, sourceSha, false);
        }
        metrics.configure(scenario, "client", fileName, size, config, net);

        long dropped0 = net.droppedCount();
        long corrupted0 = net.corruptedCount();
        FinalReport report = null;
        String error = "";

        try (DatagramSocket socket = new DatagramSocket()) {
            try {
                socket.setReceiveBufferSize(1 << 20);
            } catch (SocketException ignored) {
                // best effort
            }
            long sessionId = 1 + (RANDOM.nextLong() & 0x7FFFFFFFL); // 1 .. 2^31, never 0
            TransferSession session = new TransferSession(sessionId, server);
            ReliableSender sender = new ReliableSender(socket, server, net, config, session, metrics, listener);
            report = sender.send(file);
        } catch (TransferException e) {
            error = e.getMessage();
        } catch (IOException e) {
            error = "I/O error: " + e.getMessage();
        } finally {
            if (metrics.endNanos() == 0) {
                metrics.markEnd();
            }
        }

        metrics.setSimulation(net.droppedCount() - dropped0, net.corruptedCount() - corrupted0);
        String destSha = "";
        boolean match = false;
        if (report != null) {
            metrics.setServerReport(report.dataPacketsReceived(), report.duplicates(), report.outOfOrder(),
                    report.invalidPackets());
            if (report.success()) {
                destSha = report.sha256Hex();
                match = destSha.equals(sourceSha);
                if (!match) {
                    error = "SHA-256 mismatch between source and reconstructed file";
                }
            } else {
                error = "receiver reported that the transfer failed";
            }
        }
        boolean success = error.isEmpty() && report != null && report.success() && match;
        metrics.setOutcome(success, match, sourceSha, destSha, error);
        return new TransferResult(success, error, metrics, report, sourceSha, match);
    }

    // ---- command line ---------------------------------------------------------------------------

    private static final Set<String> FLAGS = Set.of("verbose", "help", "gui", "no-csv");
    private static final Set<String> OPTIONS = Set.of("verbose", "help", "gui", "no-csv", "chunk", "window",
            "timeout", "retries", "loss", "delay", "jitter", "corrupt", "dup", "seed", "csv", "scenario",
            "log-file");

    public static void main(String[] args) {
        int exit = 0;
        try {
            ArgParser a = new ArgParser(args, FLAGS).allow(OPTIONS);
            if (a.has("gui")) {
                ClientGui.main(args);
                return;
            }
            if (a.has("help") || a.positional().size() < 2 || a.positional().size() > 3) {
                printUsage();
                return;
            }
            Logger.setLevel(a.has("verbose") ? Logger.Level.DEBUG : Logger.Level.INFO);
            if (a.has("log-file")) {
                Logger.setLogFile(Paths.get(a.get("log-file", "")));
            }
            InetAddress host = InetAddress.getByName(a.positional().get(0));
            int port = Integer.parseInt(a.positional().get(1));
            String fileArg;
            if (a.positional().size() == 3) {
                fileArg = a.positional().get(2);
            } else {
                System.out.print("File to send: ");
                System.out.flush();
                Scanner in = new Scanner(System.in);
                fileArg = in.hasNextLine() ? in.nextLine().trim() : "";
                if (fileArg.length() >= 2 && fileArg.startsWith("\"") && fileArg.endsWith("\"")) {
                    fileArg = fileArg.substring(1, fileArg.length() - 1); // drag-and-drop paths on Windows
                }
                if (fileArg.isEmpty()) {
                    System.err.println("No file given.");
                    System.exit(2);
                }
            }
            ProtocolConfig cfg = new ProtocolConfig()
                    .chunkSize(a.getInt("chunk", 1024))
                    .windowSize(a.getInt("window", 16))
                    .retransmitTimeoutMs(a.getInt("timeout", 200))
                    .maxRetransmissions(a.getInt("retries", 10));
            NetworkConditions net = new NetworkConditions(a.getDouble("loss", 0), a.getInt("delay", 0),
                    a.getInt("jitter", 0), a.getDouble("corrupt", 0), a.getDouble("dup", 0), a.getLong("seed", 1));
            Logger.info("[client] target %s:%d, %s, impairments: %s", host.getHostAddress(), port, cfg,
                    net.isEnabled() ? net.toString() : "none");

            int[] lastDecile = {-1};
            ProgressListener progress = (acked, total) -> {
                int decile = total == 0 ? 10 : (int) (acked * 10 / total);
                if (decile != lastDecile[0]) {
                    lastDecile[0] = decile;
                    Logger.info("[client] progress %3d%%  (%d / %d bytes acknowledged)", decile * 10, acked, total);
                }
            };
            TransferResult result;
            try {
                result = transfer(new InetSocketAddress(host, port), Paths.get(fileArg), cfg, net,
                        a.get("scenario", "manual"), progress);
            } finally {
                net.close();
            }
            System.out.println(MetricsLogger.summary(result.metrics()));
            if (!a.has("no-csv")) {
                Path csv = Paths.get(a.get("csv", "logs/transfers.csv"));
                MetricsLogger.append(csv, result.metrics());
                System.out.println("Statistics appended to " + csv.toAbsolutePath());
            }
            exit = result.success() ? 0 : 1;
        } catch (UnknownHostException e) {
            System.err.println("Unknown host: " + e.getMessage());
            exit = 2;
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage() + " (use --help)");
            exit = 2;
        } catch (IOException e) {
            System.err.println("Client failed: " + e.getMessage());
            exit = 1;
        } finally {
            Logger.closeFile();
        }
        System.exit(exit);
    }

    private static void printUsage() {
        System.out.println("""
                Usage: java -cp out reliableudp.Client <host> <port> [file] [options]
                  (omit <file> to be prompted; --gui opens the Swing interface)
                Protocol options:
                  --chunk <bytes>    payload bytes per DATA packet (default 1024; <= 1450 avoids IP fragmentation)
                  --window <n>       sender window in packets (default 16)
                  --timeout <ms>     retransmission timeout (default 200)
                  --retries <n>      max retransmissions per packet before giving up (default 10)
                Simulated network impairment (applied to packets this client SENDS):
                  --loss <0..1>      probability a packet is dropped      (e.g. 0.05 = 5%)
                  --delay <ms>       fixed one-way delay
                  --jitter <ms>      extra random delay 0..jitter (can reorder packets)
                  --corrupt <0..1>   probability one bit of a packet is flipped
                  --dup <0..1>       probability a packet is sent twice
                  --seed <n>         random seed (default 1) for repeatable experiments
                Output:
                  --csv <path>       append statistics here (default logs/transfers.csv)
                  --no-csv           do not write a CSV row
                  --scenario <text>  label stored in the CSV
                  --log-file <path>  also write the log to a file
                  --verbose          log every packet sent/received
                """);
    }
}
