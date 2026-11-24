package dev.duckducktw.m2sfix.core;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort extraction of the command a crafted font would run.
 *
 * <p>The published exploit stores its payload as text inside the font program, so a scan for
 * printable ASCII runs that look like a command (Windows executables, shells, URLs) gives a useful
 * hint for the warning dialog. This is a heuristic for the user interface only — the verdict never
 * depends on it.</p>
 */
public final class PayloadHint {

    private static final int MIN_LENGTH = 6;
    private static final int MAX_LENGTH = 200;
    private static final Pattern CANDIDATE = Pattern.compile("[\\x20-\\x7E]{" + MIN_LENGTH + ",}");
    private static final String[] INDICATORS = {
            ".exe", ".bat", ".cmd", ".ps1", ".dll", ".vbs", ".scr", ".msi",
            "powershell", "cmd.exe", "rundll32", "mshta", "regsvr32", "certutil",
            "/bin/sh", "/bin/bash", "curl ", "wget ", "http://", "https://", "invoke-"
    };

    private PayloadHint() {
    }

    /**
     * @param font raw font bytes (may be {@code null})
     * @return a printable command-like string, or {@code null} when nothing convincing was found
     */
    public static String find(byte[] font) {
        if (font == null || font.length == 0) {
            return null;
        }
        try {
            String text = new String(font, StandardCharsets.ISO_8859_1);
            Matcher matcher = CANDIDATE.matcher(text);
            String best = null;
            while (matcher.find()) {
                String candidate = matcher.group().trim();
                if (candidate.length() > MAX_LENGTH) {
                    continue;
                }
                if (!looksLikeCommand(candidate)) {
                    continue;
                }
                if (best == null || candidate.length() > best.length()) {
                    best = candidate;
                }
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean looksLikeCommand(String candidate) {
        String lower = candidate.toLowerCase(Locale.ROOT);
        for (String indicator : INDICATORS) {
            if (lower.contains(indicator)) {
                return true;
            }
        }
        return false;
    }
}
