package dev.duckducktw.m2sfix.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Defensive, allocation-light TrueType scanner.
 *
 * <p>The scanner looks for the {@code IDEF} opcode (0x89) in the font program,
 * the pre-program, and every glyph instruction stream. The instruction decoder
 * understands the variable-length PUSH opcodes so that payload bytes are never
 * mistaken for opcodes. It does not execute any font code.</p>
 *
 * <p>This class never throws to its caller. Recoverable damage (for example a
 * malformed glyph) simply stops that part of the scan; only an unusable sfnt
 * header or truncated table directory is reported as {@link
 * ScanResult.Verdict#UNPARSABLE}.</p>
 */
public final class TtfScanner {

    private static final int TAG_TTCF = 0x74746366; // 'ttcf'
    private static final int TAG_TRUE = 0x74727565; // 'true'
    private static final int TAG_OTTO = 0x4F54544F; // 'OTTO'
    private static final int TAG_TYP1 = 0x74797031; // 'typ1'
    private static final int TAG_VERSION_1 = 0x00010000;

    private static final int TAG_FPGM = 0x6670676D; // 'fpgm'
    private static final int TAG_PREP = 0x70726570; // 'prep'
    private static final int TAG_GLYF = 0x676C7966; // 'glyf'
    private static final int TAG_LOCA = 0x6C6F6361; // 'loca'
    private static final int TAG_HEAD = 0x68656164; // 'head'
    private static final int TAG_MAXP = 0x6D617870; // 'maxp'
    private static final int TAG_CVT = 0x63767420;  // 'cvt '

    private static final int OP_FDEF = 0x2C;
    private static final int OP_IDEF = 0x89;
    private static final int OP_SHZ = 0x36;
    private static final int OP_NPUSHB = 0x40;
    private static final int OP_NPUSHW = 0x41;

    private static final int MAX_COMPOSITE_COMPONENTS = 0xFFFF;

    private TtfScanner() {
    }

    /**
     * Scans a complete font file.
     *
     * @param font font bytes, may be {@code null}
     * @return a non-null result; never throws
     */
    public static ScanResult scan(byte[] font) {
        try {
            if (font == null) {
                return unparsable("font data is null");
            }
            if (font.length < 4) {
                return unparsable("font is too short for an sfnt header");
            }

            int version = u32(font, 0);
            if (version == TAG_TTCF) {
                return scanCollection(font);
            }
            if (!isSfntVersion(version)) {
                return unparsable("unsupported sfnt version 0x" + Integer.toHexString(version));
            }
            return toResult(scanFace(font, 0, font.length));
        } catch (RuntimeException e) {
            return unparsable("font scan failed: " + safeMessage(e));
        }
    }

    private static ScanResult scanCollection(byte[] data) {
        if (data.length < 12) {
            return unparsable("truncated TrueType collection header");
        }
        long faceCount = u32(data, 8);
        if (faceCount <= 0) {
            return unparsable("TrueType collection contains no faces");
        }
        long offsetsEnd = 12L + faceCount * 4L;
        if (offsetsEnd > data.length) {
            return unparsable("truncated TrueType collection offset table");
        }

        int faces = (int) faceCount;
        int totalIdefs = 0;
        int totalShz = 0;
        String firstIdefLocation = null;
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < faces; i++) {
            long offset = u32(data, (int) (12L + i * 4L));
            if (offset < 0 || offset + 12L > data.length) {
                failures.add("face " + i + " has an out-of-range offset");
                continue;
            }
            FaceOutcome outcome = scanFace(data, (int) offset, data.length);
            if (outcome.verdict == ScanResult.Verdict.SUSPICIOUS) {
                totalIdefs += outcome.idefCount;
                totalShz += outcome.shzCount;
                if (firstIdefLocation == null) {
                    firstIdefLocation = "face " + i + " " + outcome.firstIdefLocation;
                }
            } else if (outcome.verdict == ScanResult.Verdict.UNPARSABLE) {
                failures.add("face " + i + ": " + outcome.reason);
            } else {
                totalShz += outcome.shzCount;
            }
        }

        if (totalIdefs > 0) {
            return suspicious(firstIdefLocation, totalIdefs);
        }
        if (!failures.isEmpty()) {
            return unparsable(String.join("; ", failures));
        }
        return clean(totalShz);
    }

    private static FaceOutcome scanFace(byte[] data, int faceOffset, int faceLimit) {
        int limit = Math.min(faceLimit, data.length);
        if (faceOffset < 0 || (long) faceOffset + 12L > limit) {
            return FaceOutcome.unparsable("truncated sfnt header");
        }

        int version = u32(data, faceOffset);
        if (!isSfntVersion(version)) {
            return FaceOutcome.unparsable("unsupported sfnt version 0x" + Integer.toHexString(version));
        }

        int numTables = u16(data, faceOffset + 4);
        long directoryStart = (long) faceOffset + 12L;
        long directoryEnd = directoryStart + (long) numTables * 16L;
        if (directoryEnd > limit) {
            return FaceOutcome.unparsable("truncated table directory");
        }

        Map<Integer, Table> tables = new HashMap<>();
        for (int i = 0; i < numTables; i++) {
            int record = (int) (directoryStart + (long) i * 16L);
            int tag = u32(data, record);
            long offset = u32(data, record + 8);
            long length = u32(data, record + 12);
            if (length > 0
                    && offset >= 0
                    && length <= Integer.MAX_VALUE
                    && offset <= data.length
                    && offset + length <= data.length) {
                tables.putIfAbsent(tag, new Table((int) offset, (int) length));
            }
        }

        Stats stats = new Stats();
        scanTable(data, tables.get(TAG_FPGM), "fpgm", stats);
        scanTable(data, tables.get(TAG_PREP), "prep", stats);

        Table glyf = tables.get(TAG_GLYF);
        Table loca = tables.get(TAG_LOCA);
        Table head = tables.get(TAG_HEAD);
        Table maxp = tables.get(TAG_MAXP);
        tables.get(TAG_CVT); // located for completeness; not needed for the verdict
        if (glyf != null && loca != null && head != null && maxp != null) {
            scanGlyphs(data, glyf, loca, head, maxp, stats);
        }

        if (stats.idefCount > 0) {
            return new FaceOutcome(
                    ScanResult.Verdict.SUSPICIOUS,
                    "IDEF instruction in " + stats.firstIdefLocation,
                    stats.idefCount,
                    stats.firstIdefLocation,
                    stats.shzCount);
        }
        return new FaceOutcome(
                ScanResult.Verdict.CLEAN,
                "no IDEF instruction",
                0,
                null,
                stats.shzCount);
    }

    private static void scanTable(byte[] data, Table table, String label, Stats stats) {
        if (table == null || table.length <= 0) {
            return;
        }
        scanInstructions(data, table.offset, table.offset + table.length, label, stats);
    }

    private static void scanGlyphs(byte[] data, Table glyf, Table loca, Table head, Table maxp, Stats stats) {
        if (head.length < 54 || maxp.length < 6) {
            return;
        }

        int indexToLocFormat = s16(data, head.offset + 50);
        if (indexToLocFormat != 0 && indexToLocFormat != 1) {
            return;
        }

        int numGlyphs = u16(data, maxp.offset + 4);
        int entrySize = indexToLocFormat == 0 ? 2 : 4;
        long locaBytesNeeded = (long) (numGlyphs + 1) * entrySize;
        if (locaBytesNeeded > loca.length) {
            return;
        }

        long[] offsets = new long[numGlyphs + 1];
        for (int i = 0; i <= numGlyphs; i++) {
            long value = indexToLocFormat == 0
                    ? (long) u16(data, loca.offset + i * 2) * 2L
                    : u32(data, loca.offset + i * 4);
            if (value < 0 || value > glyf.length) {
                return;
            }
            offsets[i] = value;
        }

        for (int i = 0; i < numGlyphs; i++) {
            long start = offsets[i];
            long end = offsets[i + 1];
            if (start == end) {
                continue;
            }
            if (start < 0 || end < start || end > glyf.length || end - start < 10) {
                return; // malformed glyph data: stop, keep the verdict found so far
            }

            int glyphOffset = glyf.offset + (int) start;
            int glyphEnd = glyf.offset + (int) end;
            int numberOfContours = s16(data, glyphOffset);

            InstructionRange instructions = numberOfContours >= 0
                    ? simpleGlyphInstructions(data, glyphOffset, glyphEnd, numberOfContours)
                    : compositeGlyphInstructions(data, glyphOffset, glyphEnd);
            if (instructions == null) {
                return; // malformed glyph data: stop, keep the verdict found so far
            }
            scanInstructions(data, instructions.start, instructions.end, "glyph " + i, stats);
        }
    }

    private static InstructionRange simpleGlyphInstructions(
            byte[] data, int glyphOffset, int glyphEnd, int numberOfContours) {
        long cursor = (long) glyphOffset + 10L + (long) numberOfContours * 2L;
        if (cursor + 2L > glyphEnd) {
            return null;
        }
        int length = u16(data, (int) cursor);
        cursor += 2L;
        if (cursor + length > glyphEnd) {
            return null;
        }
        return new InstructionRange((int) cursor, (int) cursor + length);
    }

    private static InstructionRange compositeGlyphInstructions(byte[] data, int glyphOffset, int glyphEnd) {
        long cursor = (long) glyphOffset + 10L;
        int guard = 0;

        while (true) {
            if (cursor + 4L > glyphEnd) {
                return null;
            }
            int flags = u16(data, (int) cursor);
            cursor += 4L; // flags + glyphIndex

            cursor += (flags & 0x0001) != 0 ? 4L : 2L; // ARG_1_AND_2_ARE_WORDS
            if ((flags & 0x0008) != 0) {
                cursor += 2L; // WE_HAVE_A_SCALE
            } else if ((flags & 0x0040) != 0) {
                cursor += 4L; // WE_HAVE_AN_X_AND_Y_SCALE
            } else if ((flags & 0x0080) != 0) {
                cursor += 8L; // WE_HAVE_A_TWO_BY_TWO
            }

            if (cursor > glyphEnd) {
                return null;
            }
            if ((flags & 0x0020) == 0) { // MORE_COMPONENTS
                break;
            }
            if (++guard > MAX_COMPOSITE_COMPONENTS) {
                return null;
            }
        }

        if (cursor + 2L > glyphEnd) {
            return null;
        }
        int length = u16(data, (int) cursor);
        cursor += 2L;
        if (cursor + length > glyphEnd) {
            return null;
        }
        return new InstructionRange((int) cursor, (int) cursor + length);
    }

    /**
     * Decodes a TrueType instruction stream without executing it.
     *
     * <p>Only PUSH-family instructions carry inline data. FDEF and IDEF each
     * carry one operand byte (the definition number), exactly as FreeType's
     * {@code Ins_FDEF}/{@code Ins_IDEF} read it.</p>
     */
    private static void scanInstructions(byte[] data, int start, int end, String location, Stats stats) {
        int ip = Math.max(0, start);
        int limit = Math.min(end, data.length);

        while (ip < limit) {
            int opcode = data[ip] & 0xFF;

            if (opcode == OP_IDEF) {
                stats.addIdef(location);
                ip = Math.min(limit, ip + 2);
                continue;
            }
            if (opcode == OP_FDEF) {
                ip = Math.min(limit, ip + 2);
                continue;
            }
            if (opcode == OP_SHZ) {
                stats.shzCount++;
                ip++;
                continue;
            }
            if (opcode == OP_NPUSHB || opcode == OP_NPUSHW) {
                int count = ip + 1 < limit ? data[ip + 1] & 0xFF : 0;
                long skip = opcode == OP_NPUSHB ? 2L + count : 2L + 2L * count;
                ip = (int) Math.min((long) limit, (long) ip + skip);
                continue;
            }
            if (opcode >= 0xB0 && opcode <= 0xB7) {
                int count = (opcode & 0x07) + 1;
                ip = Math.min(limit, ip + 1 + count);
                continue;
            }
            if (opcode >= 0xB8 && opcode <= 0xBF) {
                int count = (opcode & 0x07) + 1;
                ip = Math.min(limit, ip + 1 + 2 * count);
                continue;
            }
            ip++;
        }
    }

    private static ScanResult toResult(FaceOutcome outcome) {
        return switch (outcome.verdict) {
            case SUSPICIOUS -> suspicious(outcome.firstIdefLocation, outcome.idefCount);
            case UNPARSABLE -> unparsable(outcome.reason);
            case CLEAN -> clean(outcome.shzCount);
        };
    }

    private static boolean isSfntVersion(int version) {
        return version == TAG_VERSION_1
                || version == TAG_TRUE
                || version == TAG_OTTO
                || version == TAG_TYP1;
    }

    private static ScanResult clean(int shzCount) {
        return new ScanResult(
                ScanResult.Verdict.CLEAN,
                "no IDEF instruction; SHZ count=" + shzCount);
    }

    private static ScanResult suspicious(String location, int count) {
        String safeLocation = Objects.requireNonNullElse(location, "unknown");
        String occurrences = count == 1 ? "1 occurrence" : count + " occurrences";
        return new ScanResult(
                ScanResult.Verdict.SUSPICIOUS,
                "IDEF instruction in " + safeLocation + " (" + occurrences + ")");
    }

    private static ScanResult unparsable(String reason) {
        return new ScanResult(ScanResult.Verdict.UNPARSABLE, reason);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int s16(byte[] data, int offset) {
        return (short) u16(data, offset);
    }

    private static int u32(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private record Table(int offset, int length) {
    }

    private record InstructionRange(int start, int end) {
    }

    private record FaceOutcome(
            ScanResult.Verdict verdict,
            String reason,
            int idefCount,
            String firstIdefLocation,
            int shzCount) {

        static FaceOutcome unparsable(String reason) {
            return new FaceOutcome(ScanResult.Verdict.UNPARSABLE, reason, 0, null, 0);
        }
    }

    private static final class Stats {
        int idefCount;
        String firstIdefLocation;
        int shzCount;

        void addIdef(String location) {
            idefCount++;
            if (firstIdefLocation == null) {
                firstIdefLocation = location;
            }
        }
    }
}
