package dev.duckducktw.m2sfix.mod;

import dev.duckducktw.m2sfix.core.FaceGuard;
import dev.duckducktw.m2sfix.core.FixPolicy;
import java.nio.ByteBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.util.freetype.FT_Face;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The mod-side counterpart of the agent: same two hook points, applied through
 * Mixin instead of {@code java.lang.instrument}.
 *
 * <p>The target is {@code org.lwjgl.util.freetype.FreeType} — a library class,
 * not a Minecraft class — so nothing has to be remapped and the mixin works on
 * every loader that can host Mixin with the same configuration. Minecraft
 * itself opens every font through these two methods (verified with
 * {@code javap} on the 26.3 client):</p>
 *
 * <pre>
 * TrueTypeGlyphProviderDefinition.load(...) -&gt; FT_New_Memory_Face(...)
 * TrueTypeGlyphProvider.loadGlyph(...)      -&gt; FT_Load_Glyph(...)
 * </pre>
 */
@Mixin(targets = "org.lwjgl.util.freetype.FreeType", remap = false)
public class FreeTypeTargetMixin {

    /**
     * Lints the font once per face and, in {@code block} mode, rejects a
     * flagged font by rewriting the return code.
     */
    @Inject(method = "FT_New_Memory_Face", at = @At("RETURN"), cancellable = true, remap = false)
    private static void m2sfix$afterNewFace(
            long library,
            ByteBuffer file,
            long fileSize,
            PointerBuffer facePointerBuffer,
            CallbackInfoReturnable<Integer> callback) {
        try {
            int result = callback.getReturnValue();
            if (result != 0) {
                return;
            }
            long address = 0L;
            if (facePointerBuffer != null) {
                try {
                    address = facePointerBuffer.get(0);
                } catch (Throwable ignored) {
                    // Unknown address: the verdict is still recorded under 0.
                }
            }
            long size = file == null ? fileSize : file.remaining();
            boolean flagged = FaceGuard.inspectNewFace(address, file, size);
            if (flagged && FixPolicy.policy() == FixPolicy.Policy.BLOCK) {
                callback.setReturnValue(FaceGuard.FT_ERR_INVALID_FILE_FORMAT);
            }
        } catch (Throwable ignored) {
            // A guard failure must never break font loading.
        }
    }

    /**
     * Adds {@code FT_LOAD_NO_HINTING} for a flagged face, which keeps the
     * TrueType bytecode interpreter (and therefore the exploit) from running.
     */
    @ModifyVariable(
            method = "FT_Load_Glyph",
            at = @At("HEAD"),
            argsOnly = true,
            index = 2,
            remap = false)
    private static int m2sfix$adjustLoadFlags(int flags, FT_Face face, int glyphIndex) {
        try {
            long address = face == null ? 0L : face.address();
            if (address == 0L) {
                return flags;
            }
            return FaceGuard.adjustLoadFlags(address, flags);
        } catch (Throwable ignored) {
            return flags;
        }
    }
}
