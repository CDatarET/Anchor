package reliableudp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import reliableudp.protocol.Packet;
import reliableudp.protocol.PacketCodec;
import reliableudp.protocol.PacketFormatException;
import reliableudp.protocol.ProtocolConfig;
import reliableudp.protocol.TransferInfo;
import reliableudp.simulation.NetworkConditions;
import reliableudp.transport.TransferResult;
import reliableudp.util.FileChecksum;
import reliableudp.util.Logger;

/** Shared helpers for the integration tests: real Server + real Client over UDP on loopback. */
public final class TestSupport {

    static {
        Logger.setLevel(Logger.Level.OFF);
    }

    private TestSupport() {
    }

    public static InetAddress loopback() throws IOException {
        return InetAddress.getByName("127.0.0.1");
    }

    /** Deterministic pseudo-random file. */
    public static Path randomFile(Path dir, String name, int size, long seed) throws IOException {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        Path p = dir.resolve(name);
        Files.write(p, data);
        return p;
    }

    /** Short timeouts keep tests fast on loopback. */
    public static ProtocolConfig fastConfig() {
        return new ProtocolConfig().chunkSize(512).windowSize(8).retransmitTimeoutMs(50)
                .maxRetransmissions(30).lingerMillis(3000).sessionIdleTimeoutMs(20000);
    }

    /** Starts a server on an ephemeral port. */
    public static Server startServer(Path downloads, ProtocolConfig cfg, NetworkConditions net) throws IOException {
        Server s = new Server(0, downloads, cfg, net);
        s.start();
        return s;
    }

    public static InetSocketAddress addressOf(Server s) throws IOException {
        return new InetSocketAddress(loopback(), s.getPort());
    }

    public static TransferResult send(Server server, Path file, ProtocolConfig cfg, NetworkConditions clientNet)
            throws IOException {
        try {
            return Client.transfer(addressOf(server), file, cfg, clientNet, "test", null);
        } finally {
            clientNet.close();
        }
    }

    /** Asserts success AND independently re-hashes the file the server wrote. */
    public static Path assertDelivered(TransferResult r, Server server, Path source) throws IOException {
        assertTrue(r.success(), "transfer failed: " + r.error());
        assertTrue(r.integrityMatch(), "client says SHA-256 mismatch");
        assertFalse(server.completedTransfers().isEmpty(), "server recorded no completed transfer");
        Server.CompletedTransfer ct = server.completedTransfers().get(server.completedTransfers().size() - 1);
        assertTrue(ct.success());
        assertNotNull(ct.file());
        assertTrue(Files.isRegularFile(ct.file()), "destination file missing");
        assertEquals(FileChecksum.sha256Hex(source), FileChecksum.sha256Hex(ct.file()), "destination SHA-256 differs");
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(ct.file()), "bytes differ");
        return ct.file();
    }

    /** A bare UDP socket that speaks the wire protocol by hand, to test the server's behaviour exactly. */
    public static final class RawPeer implements AutoCloseable {
        public final DatagramSocket socket;
        private final InetSocketAddress server;

        public RawPeer(Server s) throws IOException {
            this.socket = new DatagramSocket();
            this.server = addressOf(s);
        }

        public void send(Packet p) throws IOException {
            sendRaw(PacketCodec.encode(p));
        }

        public void sendRaw(byte[] bytes) throws IOException {
            socket.send(new DatagramPacket(bytes, bytes.length, server));
        }

        /** @return the next valid packet, or null if nothing arrives within {@code millis}. */
        public Packet recv(int millis) throws IOException {
            byte[] buf = new byte[ProtocolConfig.MAX_PACKET_SIZE + 1];
            DatagramPacket dp = new DatagramPacket(buf, buf.length);
            socket.setSoTimeout(millis);
            try {
                socket.receive(dp);
            } catch (SocketTimeoutException e) {
                return null;
            }
            try {
                return PacketCodec.decode(buf, 0, dp.getLength());
            } catch (PacketFormatException e) {
                throw new AssertionError("server sent an invalid packet: " + e.getMessage());
            }
        }

        public Packet handshake(long id, String name, long size, int chunk) throws IOException {
            send(Packet.syn(id, new TransferInfo(size, chunk, name)));
            return recv(2000);
        }

        @Override
        public void close() {
            socket.close();
        }
    }
}
