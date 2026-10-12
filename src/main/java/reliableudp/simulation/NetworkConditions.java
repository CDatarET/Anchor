package reliableudp.simulation;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import reliableudp.protocol.Packet;
import reliableudp.protocol.PacketCodec;
import reliableudp.util.Logger;

/**
 * Network-impairment layer applied to <em>outgoing</em> packets of one endpoint. Every protocol
 * packet the application sends goes through {@link #send}; this class then really drops it,
 * delays it, corrupts it or duplicates it before it reaches the {@link DatagramSocket}.
 *
 * <p>Everything here is <b>simulated</b>: a packet dropped by this class never reaches the
 * operating system. It is not related to genuine packet loss on a real network or NIC.
 *
 * <p>For each packet the random draws always happen in the same order (loss, corruption,
 * duplication, jitter), so a fixed seed yields a reproducible decision sequence for a given
 * sequence of sends. (Real-time effects such as timeouts can still change that sequence between
 * runs; seeds make experiments <em>repeatable</em>, not bit-identical.)
 */
public final class NetworkConditions implements Closeable {

    private final double lossRate;
    private final int delayMs;
    private final int jitterMs;
    private final double corruptRate;
    private final double duplicateRate;
    private final long seed;
    private final Random rng;
    private final boolean enabled;
    private volatile Predicate<Packet> dropRule; // deterministic hook for tests
    private ScheduledExecutorService scheduler;

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong corrupted = new AtomicLong();
    private final AtomicLong duplicated = new AtomicLong();
    private final AtomicLong delayed = new AtomicLong();

    /**
     * @param lossRate      probability in [0,1] that an outgoing packet is silently discarded
     * @param delayMs       fixed one-way delay added to every outgoing packet
     * @param jitterMs      extra random delay, uniform in [0, jitterMs]; may reorder packets
     * @param corruptRate   probability in [0,1] that one random bit of the packet is flipped
     * @param duplicateRate probability in [0,1] that the packet is sent twice
     * @param seed          seed of the pseudo-random generator
     */
    public NetworkConditions(double lossRate, int delayMs, int jitterMs, double corruptRate,
                             double duplicateRate, long seed) {
        checkRate(lossRate, "loss");
        checkRate(corruptRate, "corrupt");
        checkRate(duplicateRate, "duplicate");
        if (delayMs < 0 || jitterMs < 0) {
            throw new IllegalArgumentException("delay/jitter must be >= 0");
        }
        this.lossRate = lossRate;
        this.delayMs = delayMs;
        this.jitterMs = jitterMs;
        this.corruptRate = corruptRate;
        this.duplicateRate = duplicateRate;
        this.seed = seed;
        this.rng = new Random(seed);
        this.enabled = lossRate > 0 || delayMs > 0 || jitterMs > 0 || corruptRate > 0 || duplicateRate > 0;
    }

    /** An impairment-free pass-through (the simulator "disabled completely"). */
    public static NetworkConditions none() {
        return new NetworkConditions(0, 0, 0, 0, 0, 0);
    }

    /** Convenience factory: loss + fixed delay only. */
    public static NetworkConditions of(double lossRate, int delayMs, long seed) {
        return new NetworkConditions(lossRate, delayMs, 0, 0, 0, seed);
    }

    private static void checkRate(double r, String name) {
        if (r < 0 || r > 1 || Double.isNaN(r)) {
            throw new IllegalArgumentException(name + " rate must be within [0,1]");
        }
    }

    /**
     * Test hook: packets for which the predicate returns true are dropped (and counted as
     * simulated drops). Used to make failures such as "the first two FIN_ACKs are lost" exact.
     */
    public NetworkConditions withDropRule(Predicate<Packet> rule) {
        this.dropRule = rule;
        return this;
    }

    public boolean isEnabled() { return enabled || dropRule != null; }
    public double lossRate() { return lossRate; }
    public int delayMs() { return delayMs; }
    public int jitterMs() { return jitterMs; }
    public double corruptRate() { return corruptRate; }
    public double duplicateRate() { return duplicateRate; }
    public long seed() { return seed; }

    public long submittedCount() { return submitted.get(); }
    public long droppedCount() { return dropped.get(); }
    public long corruptedCount() { return corrupted.get(); }
    public long duplicatedCount() { return duplicated.get(); }
    public long delayedCount() { return delayed.get(); }

    /**
     * Sends {@code packet} to {@code dest} through the impairment layer.
     *
     * @return false if the simulator dropped the packet, true if it was (or will be) handed to the socket
     */
    public boolean send(DatagramSocket socket, Packet packet, InetSocketAddress dest) throws IOException {
        submitted.incrementAndGet();
        byte[] bytes = PacketCodec.encode(packet);
        Predicate<Packet> rule = dropRule;
        if (rule != null && rule.test(packet)) {
            dropped.incrementAndGet();
            Logger.debug("[sim] DROP (rule) %s", packet);
            return false;
        }
        if (!enabled) {
            transmit(socket, bytes, dest);
            return true;
        }

        boolean drop;
        boolean corrupt;
        boolean duplicate;
        long jitter;
        int flipByte = 0;
        int flipBit = 0;
        synchronized (rng) {
            drop = lossRate > 0 && rng.nextDouble() < lossRate;
            corrupt = corruptRate > 0 && rng.nextDouble() < corruptRate;
            if (corrupt) {
                flipByte = rng.nextInt(bytes.length);
                flipBit = rng.nextInt(8);
            }
            duplicate = duplicateRate > 0 && rng.nextDouble() < duplicateRate;
            jitter = jitterMs > 0 ? rng.nextInt(jitterMs + 1) : 0;
        }
        if (drop) {
            dropped.incrementAndGet();
            Logger.debug("[sim] DROP %s", packet);
            return false;
        }
        if (corrupt) {
            bytes = bytes.clone();
            bytes[flipByte] ^= (byte) (1 << flipBit);
            corrupted.incrementAndGet();
            Logger.debug("[sim] CORRUPT %s (byte %d bit %d)", packet, flipByte, flipBit);
        }
        long delay = delayMs + jitter;
        transmit(socket, bytes, dest, delay);
        if (duplicate) {
            duplicated.incrementAndGet();
            Logger.debug("[sim] DUPLICATE %s", packet);
            transmit(socket, bytes, dest, delay);
        }
        return true;
    }

    private void transmit(DatagramSocket socket, byte[] bytes, InetSocketAddress dest, long delay) throws IOException {
        if (delay <= 0) {
            transmit(socket, bytes, dest);
            return;
        }
        delayed.incrementAndGet();
        scheduler().schedule(() -> {
            try {
                transmit(socket, bytes, dest);
            } catch (IOException | RuntimeException e) {
                Logger.debug("[sim] delayed send failed: %s", e);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private static void transmit(DatagramSocket socket, byte[] bytes, InetSocketAddress dest) throws IOException {
        socket.send(new DatagramPacket(bytes, bytes.length, dest));
    }

    private synchronized ScheduledExecutorService scheduler() {
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "network-conditions-delay");
                t.setDaemon(true);
                return t;
            });
        }
        return scheduler;
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public String toString() {
        return String.format("loss=%.1f%% delay=%dms jitter=%dms corrupt=%.1f%% dup=%.1f%% seed=%d",
                lossRate * 100, delayMs, jitterMs, corruptRate * 100, duplicateRate * 100, seed);
    }
}
