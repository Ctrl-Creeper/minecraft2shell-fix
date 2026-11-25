package dev.duckducktw.m2sfix.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FaceGuardTest {

    private byte[] malicious;
    private byte[] clean;

    @BeforeEach
    void setUp() {
        clearProperties();
        // These tests describe the recognise-then-mitigate mode; the safe channel is covered by
        // safeChannelProtectsEvenCleanFonts below.
        System.setProperty(FixPolicy.PROP_POLICY, "hintless");
        FaceGuard.clearForTests();
        malicious = TestFonts.read(TestFonts.MALICIOUS);
        clean = TestFonts.read(TestFonts.CLEAN);
    }

    @AfterEach
    void tearDown() {
        clearProperties();
        FaceGuard.clearForTests();
    }

    @Test
    void safeChannelProtectsEvenCleanFonts() {
        System.setProperty(FixPolicy.PROP_POLICY, "safe");
        FaceGuard.clearForTests();
        FaceGuard.inspectNewFace(0x2000L, buffer(clean), clean.length);

        assertEquals(0x8002, FaceGuard.adjustLoadFlags(0x2000L, 0),
                "the safe channel must strip hinting from every font, not just flagged ones");
        assertEquals(0x8006, FaceGuard.adjustLoadFlags(0x2000L, 0x4));
    }

    @Test
    void safeChannelStillNeverBlocksAGlyph() {
        System.setProperty(FixPolicy.PROP_POLICY, "safe");
        FaceGuard.clearForTests();
        FaceGuard.inspectNewFace(0x3000L, buffer(malicious), malicious.length);

        assertEquals(0, FaceGuard.beforeLoadGlyph(0x3000L),
                "the safe channel degrades rendering, it never refuses a glyph");
    }

    @Test
    void defaultPolicyMakesSuspiciousFaceHintless() {
        boolean flagged = FaceGuard.inspectNewFace(0x1000L, buffer(malicious), malicious.length);

        assertTrue(flagged);
        assertEquals(0x8002, FaceGuard.adjustLoadFlags(0x1000L, 0));
        assertEquals(0, FaceGuard.beforeLoadGlyph(0x1000L));
    }

    @Test
    void cleanFaceIsNeverTouched() {
        assertFalse(FaceGuard.inspectNewFace(0x2000L, buffer(clean), clean.length));
        assertEquals(0, FaceGuard.adjustLoadFlags(0x2000L, 0));
        assertEquals(0, FaceGuard.beforeLoadGlyph(0x2000L));
    }

    @Test
    void noHintingIsOrEdAndExistingFlagsArePreserved() {
        FaceGuard.inspectNewFace(0x3000L, buffer(malicious), malicious.length);

        assertEquals(0x8002, FaceGuard.adjustLoadFlags(0x3000L, 0x2));
        assertEquals(0x8006, FaceGuard.adjustLoadFlags(0x3000L, 0x4));
        assertEquals(0x8007, FaceGuard.adjustLoadFlags(0x3000L, 0x5));
    }

    @Test
    void blockPolicyRefusesGlyphsWithoutNativeCall() {
        System.setProperty(FixPolicy.PROP_POLICY, "block");
        FaceGuard.clearForTests();

        assertTrue(FaceGuard.inspectNewFace(0x4000L, buffer(malicious), malicious.length));
        assertEquals(FaceGuard.FT_ERR_INVALID_FILE_FORMAT, FaceGuard.beforeLoadGlyph(0x4000L));
    }

    @Test
    void offPolicyAndDisabledGuardDoNothing() {
        System.setProperty(FixPolicy.PROP_POLICY, "off");
        FaceGuard.clearForTests();
        assertFalse(FaceGuard.inspectNewFace(0x5000L, buffer(malicious), malicious.length));
        assertEquals(0, FaceGuard.adjustLoadFlags(0x5000L, 0));
        assertEquals(0, FaceGuard.beforeLoadGlyph(0x5000L));

        System.clearProperty(FixPolicy.PROP_POLICY);
        System.setProperty(FixPolicy.PROP_ENABLED, "false");
        FaceGuard.clearForTests();
        assertFalse(FaceGuard.inspectNewFace(0x5001L, buffer(malicious), malicious.length));
        assertEquals(0, FaceGuard.adjustLoadFlags(0x5001L, 0));
        assertEquals(0, FaceGuard.beforeLoadGlyph(0x5001L));
    }

    @Test
    void unparsableFontDefaultsToHintless() {
        byte[] garbage = "this is definitely not an sfnt file".getBytes(StandardCharsets.UTF_8);

        assertTrue(FaceGuard.inspectNewFace(0x6000L, buffer(garbage), garbage.length));
        assertEquals(0x8002, FaceGuard.adjustLoadFlags(0x6000L, 0));
    }

    @Test
    void unparsableFontCanBeAllowed() {
        System.setProperty(FixPolicy.PROP_UNPARSABLE, "allow");
        FaceGuard.clearForTests();
        byte[] garbage = "this is definitely not an sfnt file".getBytes(StandardCharsets.UTF_8);

        assertFalse(FaceGuard.inspectNewFace(0x7000L, buffer(garbage), garbage.length));
        assertEquals(0, FaceGuard.adjustLoadFlags(0x7000L, 0));
    }

    @Test
    void sha256AllowlistTreatsSuspiciousFontAsClean() throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(malicious));
        System.setProperty(FixPolicy.PROP_SHA256_ALLOW, " " + hash.toUpperCase() + " ");
        FaceGuard.clearForTests();

        assertFalse(FaceGuard.inspectNewFace(0x8000L, buffer(malicious), malicious.length));
        assertEquals(0, FaceGuard.adjustLoadFlags(0x8000L, 0));
        assertEquals(0, FaceGuard.beforeLoadGlyph(0x8000L));
    }

    @Test
    void clearForTestsForgetsFaces() {
        assertTrue(FaceGuard.inspectNewFace(0x9000L, buffer(malicious), malicious.length));
        assertEquals(0x8002, FaceGuard.adjustLoadFlags(0x9000L, 0));

        FaceGuard.clearForTests();

        assertEquals(0, FaceGuard.adjustLoadFlags(0x9000L, 0));
        assertEquals(0, FaceGuard.beforeLoadGlyph(0x9000L));
    }

    @Test
    void toleratesNullAndAbsurdInputs() {
        assertFalse(FaceGuard.inspectNewFace(0xA000L, null, 10));
        assertFalse(FaceGuard.inspectNewFace(0xA001L, buffer(malicious), 0));
        assertFalse(FaceGuard.inspectNewFace(0xA002L, ByteBuffer.allocate(0), 1));
        assertTrue(FaceGuard.inspectNewFace(0xA003L, buffer(malicious), Long.MAX_VALUE));
        assertEquals(0x8002, FaceGuard.adjustLoadFlags(0xA003L, 0));
    }

    @Test
    void faceMapIsBounded() {
        for (int i = 0; i < FaceGuard.MAX_ENTRIES + 200; i++) {
            assertTrue(FaceGuard.inspectNewFace(i, buffer(malicious), malicious.length));
        }
        assertEquals(0x8002, FaceGuard.adjustLoadFlags(FaceGuard.MAX_ENTRIES + 199L, 0));
    }

    @Test
    void onlyTheCallersBytesAreRead() {
        ByteBuffer buffer = ByteBuffer.allocate(malicious.length + 8);
        buffer.position(4);
        buffer.put(malicious);
        buffer.position(4);
        buffer.limit(4 + malicious.length);

        assertTrue(FaceGuard.inspectNewFace(0xB000L, buffer, malicious.length));
        assertEquals(4, buffer.position());
        assertEquals(4 + malicious.length, buffer.limit());
    }

    private static ByteBuffer buffer(byte[] bytes) {
        return ByteBuffer.wrap(bytes);
    }

    private static void clearProperties() {
        System.clearProperty(FixPolicy.PROP_ENABLED);
        System.clearProperty(FixPolicy.PROP_POLICY);
        System.clearProperty(FixPolicy.PROP_UNPARSABLE);
        System.clearProperty(FixPolicy.PROP_LOG);
        System.clearProperty(FixPolicy.PROP_SHA256_ALLOW);
    }
}
