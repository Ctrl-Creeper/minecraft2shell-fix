package dev.duckducktw.m2sfix.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class TtfScannerTest {

    @Test
    void cleanFontIsCleanAndReportsShzDiagnostics() {
        ScanResult result = TtfScanner.scan(TestFonts.read(TestFonts.CLEAN));

        assertEquals(ScanResult.Verdict.CLEAN, result.verdict());
        assertTrue(result.reason().contains("SHZ count=1"), result.reason());
    }

    @Test
    void idefInFpgmIsSuspicious() {
        ScanResult result = TtfScanner.scan(TestFonts.read(TestFonts.MALICIOUS));

        assertEquals(ScanResult.Verdict.SUSPICIOUS, result.verdict());
        assertTrue(result.reason().contains("IDEF"), result.reason());
        assertTrue(result.reason().contains("0x36"), "the hijacked opcode must be named: " + result.reason());
        assertTrue(result.reason().contains("fpgm"), result.reason());
    }

    @Test
    void pushPayloadBytesAreNeverDecodedAsOpcodes() {
        ScanResult result = TtfScanner.scan(TestFonts.read(TestFonts.PUSHB_DECOY));

        assertEquals(ScanResult.Verdict.CLEAN, result.verdict(), result.reason());
    }

    @Test
    void idefInCompositeGlyphIsSuspicious() {
        ScanResult result = TtfScanner.scan(TestFonts.read(TestFonts.NESTED_COMPOSITE));

        assertEquals(ScanResult.Verdict.SUSPICIOUS, result.verdict());
        assertTrue(result.reason().contains("IDEF instruction in glyph"), result.reason());
    }

    @Test
    void idefInSimpleGlyphIsSuspicious() {
        ScanResult result = TtfScanner.scan(TestFonts.read(TestFonts.SIMPLE_GLYPH_IDEF));

        assertEquals(ScanResult.Verdict.SUSPICIOUS, result.verdict());
        assertTrue(result.reason().contains("IDEF instruction in glyph"), result.reason());
    }

    @Test
    void fdefOperandIsNotAnOpcode() {
        byte[] font = TestFonts.patchTable(
                TestFonts.read(TestFonts.CLEAN), "fpgm", new byte[]{0x2C, (byte) 0x89});

        ScanResult result = TtfScanner.scan(font);

        assertEquals(ScanResult.Verdict.CLEAN, result.verdict(), result.reason());
    }

    @Test
    void missingGlyfStillScansFpgm() {
        byte[] font = TestFonts.withoutGlyf(TestFonts.read(TestFonts.MALICIOUS));

        ScanResult result = TtfScanner.scan(font);

        assertEquals(ScanResult.Verdict.SUSPICIOUS, result.verdict());
        assertTrue(result.reason().contains("fpgm"), result.reason());
    }

    @Test
    void acceptsAllSfntVersionTags() {
        byte[] clean = TestFonts.read(TestFonts.CLEAN);

        for (String version : List.of("true", "OTTO", "typ1")) {
            ScanResult result = TtfScanner.scan(TestFonts.withSfntVersion(clean, version));
            assertEquals(ScanResult.Verdict.CLEAN, result.verdict(), version + ": " + result.reason());
        }
    }

    @Test
    void scansEveryFaceInATrueTypeCollection() {
        byte[] clean = TestFonts.read(TestFonts.CLEAN);
        byte[] malicious = TestFonts.read(TestFonts.MALICIOUS);

        ScanResult onlyClean = TtfScanner.scan(TestFonts.collection(clean, clean));
        assertEquals(ScanResult.Verdict.CLEAN, onlyClean.verdict(), onlyClean.reason());

        ScanResult withMalicious = TtfScanner.scan(TestFonts.collection(clean, malicious));
        assertEquals(ScanResult.Verdict.SUSPICIOUS, withMalicious.verdict());
        assertTrue(withMalicious.reason().contains("face 1"), withMalicious.reason());
    }

    @Test
    void truncatedCollectionIsUnparsable() {
        byte[] ttc = TestFonts.collection(TestFonts.read(TestFonts.CLEAN));
        byte[] truncated = new byte[16];
        System.arraycopy(ttc, 0, truncated, 0, truncated.length);
        TestFonts.putInt(truncated, 8, 1000);

        assertEquals(ScanResult.Verdict.UNPARSABLE, TtfScanner.scan(truncated).verdict());
    }

    @Test
    void badInputsAreUnparsableAndNeverThrow() {
        assertEquals(ScanResult.Verdict.UNPARSABLE, TtfScanner.scan(null).verdict());
        assertEquals(ScanResult.Verdict.UNPARSABLE, TtfScanner.scan(new byte[0]).verdict());
        assertEquals(ScanResult.Verdict.UNPARSABLE, TtfScanner.scan(new byte[]{1, 2, 3}).verdict());
        assertEquals(ScanResult.Verdict.UNPARSABLE, TtfScanner.scan("wOFFnot-a-font".getBytes()).verdict());

        byte[] clean = TestFonts.read(TestFonts.CLEAN);
        byte[] truncatedDirectory = clean.clone();
        truncatedDirectory[4] = (byte) 0xFF;
        truncatedDirectory[5] = (byte) 0xFF;
        assertEquals(ScanResult.Verdict.UNPARSABLE, TtfScanner.scan(truncatedDirectory).verdict());
    }

    @Test
    void realWorldFontsContainZeroSuspiciousFonts() throws IOException {
        Path fontsRoot = Path.of("/usr/share/fonts");
        Assumptions.assumeTrue(Files.isDirectory(fontsRoot), "no /usr/share/fonts on this machine");

        List<String> suspicious = new ArrayList<>();
        long scanned = 0;
        try (Stream<Path> paths = Files.walk(fontsRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!filename.endsWith(".ttf") && !filename.endsWith(".otf")) {
                    continue;
                }
                byte[] bytes;
                try {
                    bytes = Files.readAllBytes(path);
                } catch (IOException ignored) {
                    continue;
                }
                scanned++;
                ScanResult result = TtfScanner.scan(bytes);
                assertNotNull(result);
                if (result.verdict() == ScanResult.Verdict.SUSPICIOUS) {
                    suspicious.add(path + ": " + result.reason());
                }
            }
        }

        assertTrue(suspicious.isEmpty(), "suspicious real-world fonts: " + suspicious);
        assertFalse(scanned < 0);
    }
}
