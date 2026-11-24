package dev.duckducktw.m2sfix.core;

/**
 * Result of a defensive TrueType font scan.
 *
 * @param verdict what the scanner concluded
 * @param reason  a short human-readable explanation, safe to log
 */
public record ScanResult(Verdict verdict, String reason) {

    public enum Verdict {
        /** No IDEF instruction was found. */
        CLEAN,
        /** An IDEF instruction (the primitive used by the exploit) was found. */
        SUSPICIOUS,
        /** The input is not a font structure we can inspect. */
        UNPARSABLE
    }

    public ScanResult {
        if (verdict == null) {
            verdict = Verdict.UNPARSABLE;
        }
        if (reason == null || reason.isBlank()) {
            reason = verdict == Verdict.CLEAN ? "no IDEF instruction" : verdict.name().toLowerCase();
        }
    }

    public static ScanResult clean() {
        return new ScanResult(Verdict.CLEAN, "no IDEF instruction; SHZ count=0");
    }
}
