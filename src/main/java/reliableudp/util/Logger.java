package reliableudp.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Tiny thread-safe logger: console + optional log file + optional listeners (used by the GUI).
 * Messages use {@link String#format} syntax and are only formatted if their level is enabled.
 */
public final class Logger {

    public enum Level { DEBUG, INFO, WARN, ERROR, OFF }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Object LOCK = new Object();
    private static final List<Consumer<String>> SINKS = new CopyOnWriteArrayList<>();

    private static volatile Level level = Level.INFO;
    private static volatile boolean console = true;
    private static PrintWriter fileWriter;

    private Logger() {
    }

    public static void setLevel(Level l) { level = l; }
    public static Level getLevel() { return level; }
    public static void setConsoleEnabled(boolean enabled) { console = enabled; }
    public static void addSink(Consumer<String> sink) { SINKS.add(sink); }
    public static void removeSink(Consumer<String> sink) { SINKS.remove(sink); }

    /** Appends all further log lines to {@code file} (parent directories are created). */
    public static void setLogFile(Path file) throws IOException {
        synchronized (LOCK) {
            closeFile();
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            fileWriter = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND), true);
        }
    }

    public static void closeFile() {
        synchronized (LOCK) {
            if (fileWriter != null) {
                fileWriter.close();
                fileWriter = null;
            }
        }
    }

    public static boolean isEnabled(Level l) { return l.ordinal() >= level.ordinal() && level != Level.OFF; }

    public static void debug(String fmt, Object... args) { log(Level.DEBUG, fmt, args); }
    public static void info(String fmt, Object... args) { log(Level.INFO, fmt, args); }
    public static void warn(String fmt, Object... args) { log(Level.WARN, fmt, args); }
    public static void error(String fmt, Object... args) { log(Level.ERROR, fmt, args); }

    private static void log(Level l, String fmt, Object[] args) {
        if (!isEnabled(l)) {
            return;
        }
        String msg = args.length == 0 ? fmt : String.format(fmt, args);
        String line = LocalTime.now().format(TIME) + " " + String.format("%-5s", l) + " " + msg;
        synchronized (LOCK) {
            if (console) {
                (l.ordinal() >= Level.WARN.ordinal() ? System.err : System.out).println(line);
            }
            if (fileWriter != null) {
                fileWriter.println(line);
            }
        }
        for (Consumer<String> s : SINKS) {
            try {
                s.accept(line);
            } catch (RuntimeException ignored) {
                // a faulty listener must never break the protocol
            }
        }
    }
}
