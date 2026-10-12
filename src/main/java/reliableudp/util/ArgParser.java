package reliableudp.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Minimal command-line parser: positional arguments, {@code --key value} options and {@code --flag}s. */
public final class ArgParser {

    private final List<String> positional = new ArrayList<>();
    private final Map<String, String> options = new HashMap<>();

    /**
     * @param args  the raw arguments
     * @param flags names (without "--") of options that take no value
     */
    public ArgParser(String[] args, Set<String> flags) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                int eq = key.indexOf('=');
                if (eq >= 0) {
                    options.put(key.substring(0, eq), key.substring(eq + 1));
                } else if (flags.contains(key)) {
                    options.put(key, "true");
                } else if (i + 1 < args.length) {
                    options.put(key, args[++i]);
                } else {
                    throw new IllegalArgumentException("option --" + key + " needs a value");
                }
            } else {
                positional.add(a);
            }
        }
    }

    /** @throws IllegalArgumentException if an option is not in {@code allowed} (catches typos). */
    public ArgParser allow(Set<String> allowed) {
        for (String k : options.keySet()) {
            if (!allowed.contains(k)) {
                throw new IllegalArgumentException("unknown option --" + k);
            }
        }
        return this;
    }

    public List<String> positional() { return positional; }
    public boolean has(String key) { return options.containsKey(key); }
    public String get(String key, String def) { return options.getOrDefault(key, def); }

    public int getInt(String key, int def) {
        try {
            return options.containsKey(key) ? Integer.parseInt(options.get(key).trim()) : def;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " must be an integer");
        }
    }

    public long getLong(String key, long def) {
        try {
            return options.containsKey(key) ? Long.parseLong(options.get(key).trim()) : def;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " must be an integer");
        }
    }

    public double getDouble(String key, double def) {
        try {
            return options.containsKey(key) ? Double.parseDouble(options.get(key).trim()) : def;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " must be a number");
        }
    }
}
