package dev.duckducktw.m2sfix.agent;

import dev.duckducktw.m2sfix.core.FaceGuard;
import dev.duckducktw.m2sfix.core.FixPolicy;
import java.nio.ByteBuffer;

/**
 * Glue between the injected bytecode and {@link FaceGuard}.
 *
 * <p>Every method here is called from bytecode that this agent spliced into
 * {@code org.lwjgl.util.freetype.FreeType}, so it must be cheap and it must
 * never throw: an exception would escape into the game's font loader.</p>
 */
public final class AgentBridge {

    private AgentBridge() {
    }

    /**
     * Called right after a face was created from memory.
     *
     * @param result the value {@code FT_New_Memory_Face} is about to return
     * @return the possibly rewritten return value
     */
    public static int afterNewFace(ByteBuffer file, long fileSizeHint, Object facePointerBuffer, int result) {
        try {
            if (result != 0) {
                return result;
            }
            long address = readPointer(facePointerBuffer);
            // LWJGL's four-argument overload passes the *face index* in the slot
            // named file_size, so the buffer itself is the only reliable size.
            long size = file == null ? fileSizeHint : file.remaining();
            boolean flagged = FaceGuard.inspectNewFace(address, file, size);
            if (flagged && FixPolicy.policy() == FixPolicy.Policy.BLOCK) {
                return FaceGuard.FT_ERR_INVALID_FILE_FORMAT;
            }
            return result;
        } catch (Throwable ignored) {
            return result;
        }
    }

    /**
     * Called right before {@code FT_Load_Glyph} reads the glyph.
     *
     * @param flags the caller's {@code FT_Load_Glyph} flags
     * @return the flags to actually use
     */
    public static int adjustFlags(Object face, int flags) {
        try {
            long address = readAddress(face);
            if (address == 0L) {
                return flags;
            }
            return FaceGuard.adjustLoadFlags(address, flags);
        } catch (Throwable ignored) {
            return flags;
        }
    }

    private static long readAddress(Object face) {
        if (face == null) {
            return 0L;
        }
        try {
            Object value = face.getClass().getMethod("address").invoke(face);
            return value instanceof Number number ? number.longValue() : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static long readPointer(Object pointerBuffer) {
        if (pointerBuffer == null) {
            return 0L;
        }
        try {
            Object value = pointerBuffer.getClass().getMethod("get", int.class).invoke(pointerBuffer, 0);
            return value instanceof Number number ? number.longValue() : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }
}
