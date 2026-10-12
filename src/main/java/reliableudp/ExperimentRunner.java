package reliableudp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import reliableudp.metrics.MetricsLogger;
import reliableudp.metrics.TransferMetrics;
import reliableudp.protocol.ProtocolConfig;
import reliableudp.simulation.NetworkConditions;
import reliableudp.transport.TransferResult;
import reliableudp.util.ArgParser;
import reliableudp.util.Logger;

/**
 * Repeatable performance experiment: sends the same file under several network conditions,
 * several times each, and writes (1) a CSV with one measured row per transfer and (2) a text
 * summary table of per-scenario means computed from those rows.
 *
 * <p>By default a server is started inside this process on an ephemeral loopback port (the
 * client and server still talk through real UDP sockets). Impairments are applied to BOTH
 * directions with the same parameters. Use {@code --server host:port} to test against a
 * separately started server instead (then only client-side impairment applies).
 *
 * <p>The scenarios below are <em>test configurations</em>, not claimed results.
 */
public final class ExperimentRunner {

    /** One test configuration. {@code timeoutMs} is chosen above the expected RTT of that scenario. */
    record Scenario(String name, double loss, int delayMs, int jitterMs, int timeoutMs) { }

    static final List<Scenario> SCENARIOS = List.of(
            new Scenario("baseline",           0.00,   0, 0,  50),
            new Scenario("low-loss",           0.05,  10, 0, 100),
            new Scenario("moderate-loss",      0.10,  25, 0, 150),
            new Scenario("high-delay",         0.00, 100, 0, 400),
            new Scenario("combined",           0.10, 100, 0, 400));

    private static final Set<String> OPTIONS = Set.of("file", "file-size", "repeats", "seed", "window", "chunk",
            "out", "server", "verbose");

    private ExperimentRunner() {
    }

    public static void main(String[] args) throws Exception {
        try {
            run(new ArgParser(args, Set.of("verbose")).allow(OPTIONS));
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("""
                    Usage: java -cp out reliableudp.ExperimentRunner [--file path | --file-size bytes] [--repeats n]
                             [--window n] [--chunk bytes] [--seed n] [--out logs] [--server host:port] [--verbose]
                    Defaults: --file-size 262144 --repeats 3 --window 32 --chunk 1024 --seed 42 --out logs""");
            System.exit(2);
        }
    }

    private static void run(ArgParser a) throws Exception {
        Logger.setLevel(a.has("verbose") ? Logger.Level.INFO : Logger.Level.OFF);
        int repeats = a.getInt("repeats", 3);
        int window = a.getInt("window", 32);
        int chunk = a.getInt("chunk", 1024);
        long seed = a.getLong("seed", 42);
        Path outDir = Paths.get(a.get("out", "logs"));
        Files.createDirectories(outDir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path csv = outDir.resolve("experiment_" + stamp + ".csv");
        Path summary = outDir.resolve("experiment_summary_" + stamp + ".txt");

        Path workDir = Files.createTempDirectory("reliableudp-exp");
        Path file;
        if (a.has("file")) {
            file = Paths.get(a.get("file", ""));
        } else {
            byte[] data = new byte[a.getInt("file-size", 262_144)];
            new Random(seed).nextBytes(data); // same bytes in every run with the same seed
            file = workDir.resolve("experiment.bin");
            Files.write(file, data);
        }
        System.out.printf("Experiment file: %s (%d bytes), %d repeats per scenario, window=%d, chunk=%d, seed=%d%n",
                file, Files.size(file), repeats, window, chunk, seed);

        List<String[]> table = new ArrayList<>();
        for (Scenario sc : SCENARIOS) {
            ProtocolConfig cfg = new ProtocolConfig().chunkSize(chunk).windowSize(window)
                    .retransmitTimeoutMs(sc.timeoutMs()).maxRetransmissions(30).lingerMillis(1000);
            Server server = null;
            InetSocketAddress target;
            if (a.has("server")) {
                String[] hp = a.get("server", "").split(":");
                if (hp.length != 2) {
                    throw new IllegalArgumentException("--server must look like host:port");
                }
                target = new InetSocketAddress(InetAddress.getByName(hp[0]), Integer.parseInt(hp[1]));
            } else {
                server = new Server(0, workDir.resolve("downloads-" + sc.name()), cfg,
                        new NetworkConditions(sc.loss(), sc.delayMs(), sc.jitterMs(), 0, 0, seed + 5000));
                server.start();
                target = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.getPort());
            }
            List<TransferMetrics> runs = new ArrayList<>();
            for (int i = 0; i < repeats; i++) {
                NetworkConditions net = new NetworkConditions(sc.loss(), sc.delayMs(), sc.jitterMs(), 0, 0, seed + i);
                TransferResult r;
                try {
                    r = Client.transfer(target, file, cfg, net, sc.name(), null);
                } finally {
                    net.close();
                }
                MetricsLogger.append(csv, r.metrics());
                runs.add(r.metrics());
                System.out.printf(Locale.ROOT, "  %-14s run %d/%d: %s in %.2f s, goodput %.2f Mbps, retransmissions %d%s%n",
                        sc.name(), i + 1, repeats, r.success() ? "ok" : "FAILED", r.metrics().durationSeconds(),
                        TransferMetrics.toMbps(r.metrics().goodputBytesPerSec()), r.metrics().retransmissions(),
                        r.success() ? "" : " (" + r.error() + ")");
            }
            if (server != null) {
                server.close();
            }
            table.add(summarise(sc, runs));
        }

        String text = render(table, file, repeats, window, chunk, seed);
        Files.writeString(summary, text);
        System.out.println();
        System.out.println(text);
        System.out.println("CSV    : " + csv.toAbsolutePath());
        System.out.println("Summary: " + summary.toAbsolutePath());
        deleteRecursively(workDir);
    }

    private static String[] summarise(Scenario sc, List<TransferMetrics> runs) {
        double dur = 0, gp = 0, retx = 0, rtt = 0, drops = 0;
        int rttRuns = 0;
        int ok = 0;
        for (TransferMetrics m : runs) {
            dur += m.durationSeconds();
            gp += TransferMetrics.toMbps(m.goodputBytesPerSec());
            retx += m.retransmissions();
            drops += m.simulatedDropped();
            if (!Double.isNaN(m.srttMs())) {
                rtt += m.srttMs();
                rttRuns++;
            }
            if (m.success() && m.integrityMatch()) {
                ok++;
            }
        }
        int n = runs.size();
        return new String[] {
            sc.name(),
            String.format(Locale.ROOT, "%.0f%%", sc.loss() * 100),
            sc.delayMs() + " ms",
            String.format(Locale.ROOT, "%.3f", dur / n),
            String.format(Locale.ROOT, "%.3f", gp / n),
            String.format(Locale.ROOT, "%.1f", retx / n),
            String.format(Locale.ROOT, "%.1f", drops / n),
            rttRuns == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f", rtt / rttRuns),
            ok + "/" + n
        };
    }

    private static String render(List<String[]> rows, Path file, int repeats, int window, int chunk, long seed)
            throws IOException {
        String[] head = {"scenario", "loss", "1-way delay", "duration s", "goodput Mbps", "retx", "sim drops",
            "RTT est ms", "integrity ok"};
        StringBuilder sb = new StringBuilder();
        sb.append("Reliable UDP experiment summary (means over ").append(repeats).append(" runs per scenario)\n");
        sb.append("File: ").append(file.getFileName()).append(", ").append(Files.size(file)).append(" bytes; window=")
                .append(window).append(", chunk=").append(chunk).append(", base seed=").append(seed).append('\n');
        sb.append("Impairments are simulated and applied to both directions (client->server and server->client).\n");
        sb.append("'retx' = DATA retransmissions by the client; 'sim drops' = packets the CLIENT-side simulator dropped.\n");
        sb.append("All values are measured; compare scenarios using the CSV, not this table alone.\n\n");
        int[] w = new int[head.length];
        for (int i = 0; i < head.length; i++) {
            w[i] = head[i].length();
            for (String[] r : rows) {
                w[i] = Math.max(w[i], r[i].length());
            }
        }
        appendRow(sb, head, w);
        StringBuilder sep = new StringBuilder();
        for (int x : w) {
            sep.append("-".repeat(x)).append("-+-");
        }
        sb.append(sep, 0, sep.length() - 3).append('\n');
        for (String[] r : rows) {
            appendRow(sb, r, w);
        }
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, String[] cells, int[] w) {
        for (int i = 0; i < cells.length; i++) {
            sb.append(String.format("%-" + w[i] + "s", cells[i]));
            sb.append(i < cells.length - 1 ? " | " : "\n");
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temp dir cleanup is best effort
        }
    }
}
