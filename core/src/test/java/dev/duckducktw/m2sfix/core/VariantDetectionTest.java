package dev.duckducktw.m2sfix.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Variant coverage: every shape of hostile font the scanner is expected to catch, and every
 * legitimate-but-hinting-heavy shape it must leave alone.
 *
 * <p>The two directions matter equally. A false positive silently degrades a legit font's rendering
 * (hinting off), and a false negative means the mitigation does nothing at all, so both are asserted
 * here rather than trusted.</p>
 */
class VariantDetectionTest {

    private static Path fixture(String name) {
        return Path.of("src", "test", "resources", "fonts", name);
    }

    private static byte[] read(String name) throws IOException {
        return Files.readAllBytes(fixture(name));
    }

    // ---------------------------------------------------------------- must flag

    static Stream<String> hostileVariants() {
        return Stream.of(
                "malicious-idef.ttf",          // IDEF in fpgm
                "malicious-idef-prep.ttf",     // IDEF in prep
                "malicious-idef-decoyed.ttf",  // 0x89 in a push payload *and* a real IDEF
                "malicious-idef-longloca.ttf", // 32-bit loca table
                "malicious-small-stack.ttf",   // 1-glyph / tiny maxp
                "nested-composite.ttf",        // IDEF only in a composite glyph program
                "simple-glyph-idef.ttf"        // IDEF only in a simple glyph program
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hostileVariants")
    void hostileVariantsAreFlagged(String name) throws IOException {
        ScanResult result = TtfScanner.scan(read(name));

        assertEquals(ScanResult.Verdict.SUSPICIOUS, result.verdict(),
                name + " must be flagged, got " + result);
        assertTrue(result.reason().contains("IDEF"), name + " reason: " + result.reason());
    }

    @Test
    void hostileFaceInsideACollectionIsFlagged() throws IOException {
        ScanResult result = TtfScanner.scan(read("mixed-collection.ttc"));

        assertEquals(ScanResult.Verdict.SUSPICIOUS, result.verdict(),
                "a hostile second face in a .ttc must not be missed: " + result);
    }

    @Test
    void truncatedFontIsNotTreatedAsClean() throws IOException {
        ScanResult result = TtfScanner.scan(read("truncated.ttf"));

        assertTrue(result.verdict() != ScanResult.Verdict.CLEAN,
                "an unreadable font must never be reported clean: " + result);
    }

    @Test
    void everyHostileVariantIsAlsoFlaggedThroughTheGuard() throws IOException {
        FixPolicy.reloadForTests();
        FaceGuard.clearForTests();
        Map<String, Boolean> verdicts = new LinkedHashMap<>();
        for (String name : hostileVariants().toList()) {
            byte[] bytes = read(name);
            boolean flagged = FaceGuard.inspectNewFace(0x1000L + name.hashCode(), java.nio.ByteBuffer.wrap(bytes),
                    bytes.length);
            verdicts.put(name, flagged);
        }
        Path collection = fixture("mixed-collection.ttc");
        byte[] ttc = Files.readAllBytes(collection);
        verdicts.put("mixed-collection.ttc", FaceGuard.inspectNewFace(0x2000L, java.nio.ByteBuffer.wrap(ttc), ttc.length));

        verdicts.forEach((name, flagged) -> assertTrue(flagged, name + " must be flagged by the guard"));
    }

    // ------------------------------------------------------------- must not flag

    static Stream<String> benignVariants() {
        return Stream.of(
                "clean.ttf",                 // plain font
                "clean-hinting-heavy.ttf",   // FDEF + SHZ + SHPIX + WS, no IDEF
                "pushb-decoy.ttf"            // 0x89 only inside push payloads
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("benignVariants")
    void benignVariantsStayClean(String name) throws IOException {
        ScanResult result = TtfScanner.scan(read(name));

        assertEquals(ScanResult.Verdict.CLEAN, result.verdict(),
                name + " must not be flagged (false positive): " + result);
    }

    /**
     * The real-world false-positive check: every font installed on the build machine. Skipped
     * automatically on machines without a font directory.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/usr/share/fonts", "/usr/local/share/fonts"})
    void installedFontsAreNotFlagged(String root) {
        Path directory = Path.of(root);
        if (!Files.isDirectory(directory)) {
            return;
        }
        List<Path> fonts;
        try (Stream<Path> stream = Files.walk(directory)) {
            fonts = stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String lower = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".ttc");
                    })
                    .toList();
        } catch (IOException e) {
            return;
        }
        List<String> flagged = new java.util.ArrayList<>();
        for (Path font : fonts) {
            try {
                ScanResult result = TtfScanner.scan(Files.readAllBytes(font));
                if (result.verdict() == ScanResult.Verdict.SUSPICIOUS) {
                    flagged.add(font + ": " + result.reason());
                }
            } catch (IOException ignored) {
                // Unreadable file: not our business here.
            }
        }
        assertTrue(flagged.isEmpty(), "false positives in " + fonts.size() + " installed fonts: " + flagged);
    }

    // --------------------------------------------------------- guard behaviour

    @Test
    void guardLeavesBenignFacesWithTheirOriginalFlags() throws IOException {
        FixPolicy.reloadForTests();
        FaceGuard.clearForTests();
        byte[] clean = read("clean-hinting-heavy.ttf");
        long face = 0x3333L;

        FaceGuard.inspectNewFace(face, java.nio.ByteBuffer.wrap(clean), clean.length);

        assertEquals(0, FaceGuard.adjustLoadFlags(face, 0));
        assertEquals(4, FaceGuard.adjustLoadFlags(face, 4));
    }

    @Test
    void guardAddsOnlyTheNoHintingBitForHostileFaces() throws IOException {
        FixPolicy.reloadForTests();
        FaceGuard.clearForTests();
        byte[] hostile = read("malicious-idef-decoyed.ttf");
        long face = 0x4444L;

        assertTrue(FaceGuard.inspectNewFace(face, java.nio.ByteBuffer.wrap(hostile), hostile.length),
                "hostile face must be flagged");
        assertEquals(2, FaceGuard.adjustLoadFlags(face, 0));
        assertEquals(6, FaceGuard.adjustLoadFlags(face, 4));
    }

    @Test
    void missingDataIsIgnoredButUnreadableDataFailsClosed() throws IOException {
        FixPolicy.reloadForTests();
        FaceGuard.clearForTests();

        // No font at all: nothing to judge, nothing to flag.
        assertEquals(false, FaceGuard.inspectNewFace(1L, null, 0));
        assertEquals(false, FaceGuard.inspectNewFace(2L, java.nio.ByteBuffer.allocate(0), 0));

        // A font we cannot parse must fail closed instead of being called clean.
        byte[] clean = read("clean.ttf");
        assertEquals(true, FaceGuard.inspectNewFace(3L, java.nio.ByteBuffer.wrap(clean), 4));
    }

    @Test
    void fontLargerThanTheScanLimitFailsClosed() throws IOException {
        // Shrink the scan limit instead of allocating a 64 MiB buffer.
        System.setProperty(FixPolicy.PROP_MAX_SCAN_MIB, "1");
        try {
            FixPolicy.reloadForTests();
            FaceGuard.clearForTests();
            byte[] clean = read("clean.ttf");
            byte[] padded = java.util.Arrays.copyOf(clean, 1024 * 1024 + 4096);

            boolean flagged = FaceGuard.inspectNewFace(0x5555L, java.nio.ByteBuffer.wrap(padded), padded.length);

            assertTrue(flagged, "a font we cannot fully scan must not be treated as clean");
            assertEquals(2, FaceGuard.adjustLoadFlags(0x5555L, 0));
        } finally {
            System.clearProperty(FixPolicy.PROP_MAX_SCAN_MIB);
            FixPolicy.reloadForTests();
            FaceGuard.clearForTests();
        }
    }

    @Test
    void scannerIsTotalOverRandomBytes() {
        java.util.Random random = new java.util.Random(1234);
        for (int i = 0; i < 200; i++) {
            byte[] bytes = new byte[random.nextInt(4096)];
            random.nextBytes(bytes);
            ScanResult result = TtfScanner.scan(bytes);
            assertNotNull(result);
            assertNotNull(result.verdict());
        }
    }
}
