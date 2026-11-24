package dev.duckducktw.m2sfix.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable view of the {@code m2sfix.*} system properties.
 *
 * <p>Properties are read once at class initialization. The reload hook exists
 * only so tests can exercise several policies in one JVM; production code has
 * no reason to call it.</p>
 */
public final class FixPolicy {

    public enum Policy {
        /** Load flagged fonts with {@code FT_LOAD_NO_HINTING}. */
        HINTLESS,
        /** Refuse to load any glyph from a flagged font. */
        BLOCK,
        /** Do nothing. */
        OFF
    }

    public enum UnparsablePolicy {
        /** Treat a font we cannot parse like a suspicious font. */
        HINTLESS,
        /** Leave an unparsable font alone. */
        ALLOW
    }

    public static final String PROP_ENABLED = "m2sfix.enabled";
    public static final String PROP_POLICY = "m2sfix.policy";
    public static final String PROP_UNPARSABLE = "m2sfix.unparsable";
    public static final String PROP_LOG = "m2sfix.log";
    public static final String PROP_SHA256_ALLOW = "m2sfix.sha256.allow";
    public static final String PROP_NOTIFY = "m2sfix.notify";

    private static volatile Settings settings = load();

    private FixPolicy() {
    }

    public static boolean enabled() {
        return current().enabled();
    }

    public static Policy policy() {
        return current().policy();
    }

    public static UnparsablePolicy unparsablePolicy() {
        return current().unparsablePolicy();
    }

    public static boolean logEnabled() {
        return current().logEnabled();
    }

    public static Set<String> sha256Allowlist() {
        return current().sha256Allowlist();
    }

    /** In-game warning dialog on/off. */
    public static boolean notifyEnabled() {
        return current().notifyEnabled();
    }

    /** Re-reads all properties. Test-only convenience. */
    public static void reloadForTests() {
        settings = load();
    }

    static Settings current() {
        return settings;
    }

    private static Settings load() {
        boolean enabled = parseBoolean(readProperty(PROP_ENABLED), true);
        Policy policy = parsePolicy(readProperty(PROP_POLICY));
        UnparsablePolicy unparsable = parseUnparsable(readProperty(PROP_UNPARSABLE));
        boolean log = parseBoolean(readProperty(PROP_LOG), true);
        boolean notify = parseBoolean(readProperty(PROP_NOTIFY), true);
        Set<String> allowlist = parseAllowlist(readProperty(PROP_SHA256_ALLOW));
        return new Settings(enabled, policy, unparsable, log, notify, allowlist);
    }

    private static String readProperty(String name) {
        try {
            return System.getProperty(name);
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private static boolean parseBoolean(String raw, boolean defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> defaultValue;
        };
    }

    private static Policy parsePolicy(String raw) {
        if (raw == null || raw.isBlank()) {
            return Policy.HINTLESS;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "block" -> Policy.BLOCK;
            case "off" -> Policy.OFF;
            default -> Policy.HINTLESS;
        };
    }

    private static UnparsablePolicy parseUnparsable(String raw) {
        if (raw != null && raw.trim().equalsIgnoreCase("allow")) {
            return UnparsablePolicy.ALLOW;
        }
        return UnparsablePolicy.HINTLESS;
    }

    private static Set<String> parseAllowlist(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> hashes = new HashSet<>();
        for (String part : raw.split(",")) {
            String hash = part.trim().toLowerCase(Locale.ROOT);
            if (hash.length() == 64 && hash.chars().allMatch(FixPolicy::isHexDigit)) {
                hashes.add(hash);
            }
        }
        return Collections.unmodifiableSet(hashes);
    }

    private static boolean isHexDigit(int character) {
        return (character >= '0' && character <= '9')
                || (character >= 'a' && character <= 'f');
    }

    record Settings(
            boolean enabled,
            Policy policy,
            UnparsablePolicy unparsablePolicy,
            boolean logEnabled,
            boolean notifyEnabled,
            Set<String> sha256Allowlist) {
    }
}
