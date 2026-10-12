package reliableudp.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 helpers. SHA-256 is used here only for end-to-end <em>integrity</em> checking of the
 * transferred file; it is neither encryption nor authentication.
 */
public final class FileChecksum {

    private FileChecksum() {
    }

    public static byte[] sha256(Path file) throws IOException {
        MessageDigest md = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return md.digest();
    }

    public static byte[] sha256(byte[] data) {
        return newDigest().digest(data);
    }

    public static String sha256Hex(Path file) throws IOException {
        return toHex(sha256(file));
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e); // every JRE must provide it
        }
    }
}
