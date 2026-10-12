package reliableudp.util;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UtilTest {

    @TempDir
    Path tmp;

    @Test
    void sha256MatchesKnownVectors() throws Exception {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                FileChecksum.toHex(FileChecksum.sha256("abc".getBytes(StandardCharsets.US_ASCII))));
        Path empty = Files.createFile(tmp.resolve("empty"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", FileChecksum.sha256Hex(empty));
        Path f = tmp.resolve("abc.txt");
        Files.writeString(f, "abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", FileChecksum.sha256Hex(f));
    }

    @Test
    void anyChangedByteChangesTheDigest() throws Exception {
        Path a = tmp.resolve("a");
        Path b = tmp.resolve("b");
        byte[] data = new byte[10_000];
        Files.write(a, data);
        data[9_999] = 1;
        Files.write(b, data);
        assertNotEquals(FileChecksum.sha256Hex(a), FileChecksum.sha256Hex(b));
    }

    @Test
    void safePathsAcceptOrdinaryNamesOnly() {
        for (String ok : new String[] {"a.txt", "my file (1).pdf", "données.csv", "x", "A-b_c.tar.gz"}) {
            assertNull(SafePaths.validateFileName(ok), ok);
        }
        for (String bad : new String[] {"", "../x", "a/../b", "/abs", "a\\b", "..", ".", "C:x", "con", "NUL.txt", "a|b",
            "a*b", "x?", ".dot", "trail.", " lead", "ctl\n"}) {
            assertNotNull(SafePaths.validateFileName(bad), bad);
        }
    }

    @Test
    void reserveTargetNeverOverwritesAndStaysInsideTheDirectory() throws Exception {
        Path dir = tmp.resolve("d");
        Path p1 = SafePaths.reserveTarget(dir, "f.txt");
        Path p2 = SafePaths.reserveTarget(dir, "f.txt");
        Path p3 = SafePaths.reserveTarget(dir, "f.txt");
        assertEquals("f.txt", p1.getFileName().toString());
        assertEquals("f (1).txt", p2.getFileName().toString());
        assertEquals("f (2).txt", p3.getFileName().toString());
        assertEquals(dir.toAbsolutePath().normalize(), p3.getParent());
        assertThrows(IllegalArgumentException.class, () -> SafePaths.reserveTarget(dir, "../escape"));
    }

    @Test
    void argParserHandlesFlagsOptionsAndPositionals() {
        ArgParser a = new ArgParser(new String[] {"127.0.0.1", "5000", "--loss", "0.1", "--verbose", "--delay=20", "f.bin"},
                Set.of("verbose"));
        assertEquals(java.util.List.of("127.0.0.1", "5000", "f.bin"), a.positional());
        assertTrue(a.has("verbose"));
        assertEquals(0.1, a.getDouble("loss", 0));
        assertEquals(20, a.getInt("delay", 0));
        assertEquals(7, a.getInt("missing", 7));
        assertThrows(IllegalArgumentException.class, () -> a.allow(Set.of("loss")));
        assertThrows(IllegalArgumentException.class, () -> new ArgParser(new String[] {"--loss"}, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ArgParser(new String[] {"--loss", "abc"}, Set.of()).getDouble("loss", 0));
    }
}
