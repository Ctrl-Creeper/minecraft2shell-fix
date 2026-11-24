package dev.duckducktw.m2sfix.core;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared policy brain used by both the Java agent and the Mixin mod.
 *
 * <p>Every public method is defensive: it must never throw into Minecraft. A
 * face is remembered by its native address only after the scanner flags it, so
 * clean fonts have zero further overhead and are never touched.</p>
 */
public final class FaceGuard {

    public static final int FT_LOAD_NO_HINTING = 0x2;
    public static final int FT_ERR_INVALID_FILE_FORMAT = 0x02;
    public static final int MAX_ENTRIES = 4096;
    public static final int MAX_FONT_BYTES = 64 * 1024 * 1024;

    private static final ConcurrentHashMap<Long, ScanResult> FLAGGED_FACES = new ConcurrentHashMap<>();
    private static final Set<String> LOGGED_FONT_HASHES = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean DISABLED = new AtomicBoolean(false);

    private FaceGuard() {
    }

    /**
     * Inspects a face right after FreeType created it.
     *
     * @param faceAddress native {@code FT_Face} address (may be 0 if unknown)
     * @param fontData    font bytes as passed to {@code FT_New_Memory_Face}
     * @param size        declared font size
     * @return {@code true} when this face must lose hinting
     */
    public static boolean inspectNewFace(long faceAddress, ByteBuffer fontData, long size) {
        try {
            if (DISABLED.get()) {
                return false;
            }

            FixPolicy.Settings settings = FixPolicy.current();
            if (!settings.enabled() || settings.policy() == FixPolicy.Policy.OFF) {
                FLAGGED_FACES.remove(faceAddress);
                return false;
            }
            if (fontData == null || size <= 0 || !fontData.hasRemaining()) {
                return false;
            }

            long available = Math.min(size, fontData.remaining());
            boolean capped = available > MAX_FONT_BYTES;
            int bytesToRead = (int) Math.min(available, MAX_FONT_BYTES);
            if (bytesToRead <= 0) {
                return false;
            }

            byte[] bytes = new byte[bytesToRead];
            ByteBuffer view = fontData.duplicate();
            view.get(bytes, 0, bytesToRead);

            String hash = sha256Hex(bytes);
            if (hash != null && settings.sha256Allowlist().contains(hash)) {
                FLAGGED_FACES.remove(faceAddress);
                return false;
            }

            ScanResult result = TtfScanner.scan(bytes);
            if (capped && result.verdict() == ScanResult.Verdict.CLEAN) {
                result = new ScanResult(
                        ScanResult.Verdict.UNPARSABLE,
                        "font exceeds the " + MAX_FONT_BYTES + "-byte scan limit");
            }

            boolean flagged = switch (result.verdict()) {
                case CLEAN -> false;
                case SUSPICIOUS -> true;
                case UNPARSABLE -> settings.unparsablePolicy() == FixPolicy.UnparsablePolicy.HINTLESS;
            };
            if (!flagged) {
                FLAGGED_FACES.remove(faceAddress);
                return false;
            }

            remember(faceAddress, result);
            if (settings.logEnabled()) {
                logOnce(hash, result);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Returns the load flags to use for a face.
     *
     * @param faceAddress native {@code FT_Face} address
     * @param flags       caller-supplied {@code FT_Load_Glyph} flags
     * @return flags with {@link #FT_LOAD_NO_HINTING} added for flagged faces
     */
    public static int adjustLoadFlags(long faceAddress, int flags) {
        try {
            if (DISABLED.get() || !FLAGGED_FACES.containsKey(faceAddress)) {
                return flags;
            }
            FixPolicy.Settings settings = FixPolicy.current();
            if (!settings.enabled() || settings.policy() == FixPolicy.Policy.OFF) {
                return flags;
            }
            return flags | FT_LOAD_NO_HINTING;
        } catch (Throwable ignored) {
            return flags;
        }
    }

    /**
     * Called before the native {@code FT_Load_Glyph}.
     *
     * @param faceAddress native {@code FT_Face} address
     * @return {@code 0} to continue, or a FreeType error code to return early
     */
    public static int beforeLoadGlyph(long faceAddress) {
        try {
            if (DISABLED.get()) {
                return 0;
            }
            FixPolicy.Settings settings = FixPolicy.current();
            if (!settings.enabled() || settings.policy() != FixPolicy.Policy.BLOCK) {
                return 0;
            }
            return FLAGGED_FACES.containsKey(faceAddress) ? FT_ERR_INVALID_FILE_FORMAT : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Clears remembered faces and reloads policy. Test-only. */
    public static void clearForTests() {
        try {
            FLAGGED_FACES.clear();
            LOGGED_FONT_HASHES.clear();
            DISABLED.set(false);
            FixPolicy.reloadForTests();
        } catch (Throwable ignored) {
            // Test helper: never throw.
        }
    }

    /**
     * Permanently disables the guard. Used by the agent if its own installation
     * fails; a disabled guard is always preferable to a broken client.
     */
    public static void disableForFailure() {
        DISABLED.set(true);
        FLAGGED_FACES.clear();
    }

    private static void remember(long faceAddress, ScanResult result) {
        if (!FLAGGED_FACES.containsKey(faceAddress) && FLAGGED_FACES.size() >= MAX_ENTRIES) {
            Iterator<Long> iterator = FLAGGED_FACES.keySet().iterator();
            if (iterator.hasNext()) {
                FLAGGED_FACES.remove(iterator.next());
            }
        }
        FLAGGED_FACES.put(faceAddress, result);
    }

    private static void logOnce(String hash, ScanResult result) {
        String key = hash == null ? "unknown" : hash;
        if (!LOGGED_FONT_HASHES.add(key)) {
            return;
        }
        if (LOGGED_FONT_HASHES.size() > MAX_ENTRIES) {
            LOGGED_FONT_HASHES.clear();
        }
        System.err.println("[m2sfix] flagged font " + key
                + " (" + result.verdict() + "): " + result.reason());
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException | RuntimeException ignored) {
            return null;
        }
    }
}
