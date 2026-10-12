package reliableudp.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server-side defence against path traversal. The client-supplied name is never used as a path:
 * it must be a plain file name made of "safe" characters, and the final location is re-checked to
 * be a direct child of the downloads directory. Existing files are never overwritten.
 */
public final class SafePaths {

    private static final Pattern SAFE_NAME = Pattern.compile("[\\p{L}\\p{N}._ ()\\-]{1,200}");
    private static final Set<String> WINDOWS_RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private SafePaths() {
    }

    /** @return null if {@code name} is acceptable, otherwise a reason string. */
    public static String validateFileName(String name) {
        if (name == null || name.isEmpty()) {
            return "empty file name";
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > 200) {
            return "file name too long";
        }
        if (name.equals(".") || name.equals("..") || name.contains("..")) {
            return "'..' is not allowed";
        }
        if (!SAFE_NAME.matcher(name).matches()) {
            return "file name contains path separators or unsafe characters";
        }
        if (name.startsWith(".") || name.startsWith(" ") || name.endsWith(" ") || name.endsWith(".")) {
            return "file name may not start/end with '.' or space";
        }
        String base = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        if (WINDOWS_RESERVED.contains(base.toUpperCase(Locale.ROOT))) {
            return "reserved device name";
        }
        return null;
    }

    /**
     * Atomically reserves a fresh, non-existing file inside {@code dir} named after {@code name}
     * (adding " (1)", " (2)", ... before the extension when needed).
     *
     * @throws IllegalArgumentException if the name is unsafe
     */
    public static Path reserveTarget(Path dir, String name) throws IOException {
        String problem = validateFileName(name);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        Path root = dir.toAbsolutePath().normalize();
        Files.createDirectories(root);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 0; i < 10_000; i++) {
            String candidate = i == 0 ? name : stem + " (" + i + ")" + ext;
            Path p = root.resolve(candidate).normalize();
            if (!root.equals(p.getParent())) {
                throw new IllegalArgumentException("resolved path escapes the downloads directory");
            }
            try {
                return Files.createFile(p); // CREATE_NEW semantics: fails if it exists, no overwrite
            } catch (FileAlreadyExistsException e) {
                // try the next suffix
            }
        }
        throw new IOException("could not find a free file name for " + name);
    }
}
