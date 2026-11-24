import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FT_GlyphSlot;
import org.lwjgl.util.freetype.FreeType;

/**
 * Measures what losing hinting costs, glyph by glyph: loads the same face twice (hinted and
 * {@code FT_LOAD_NO_HINTING}) and compares the rendered bitmap geometry.
 *
 * usage: CompareHinting <font.ttf> <glyphs>
 */
public final class CompareHinting {

    public static void main(String[] args) throws Exception {
        Path font = Path.of(args[0]);
        int glyphs = args.length > 1 ? Integer.parseInt(args[1]) : 300;

        PointerBuffer libraryPtr = BufferUtils.createPointerBuffer(1);
        FreeType.FT_Init_FreeType(libraryPtr);
        long library = libraryPtr.get(0);

        byte[] bytes = Files.readAllBytes(font);
        ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
        data.put(bytes).flip();
        PointerBuffer facePtr = BufferUtils.createPointerBuffer(1);
        int error = FreeType.FT_New_Memory_Face(library, data, 0L, facePtr);
        if (error != 0) {
            System.out.println("FT_New_Memory_Face -> " + error);
            return;
        }
        FT_Face face = FT_Face.create(facePtr.get(0));
        FreeType.FT_Set_Pixel_Sizes(face, 0, 32);
        FreeType.FT_Select_Charmap(face, FreeType.FT_ENCODING_UNICODE);

        int compared = 0;
        int different = 0;
        int totalDeltaH = 0;
        int totalDeltaArea = 0;
        int worst = 0;

        for (int index = 0; index < glyphs; index++) {
            int hinted = FreeType.FT_Load_Glyph(face, index, 0);
            if (hinted != 0) {
                continue;
            }
            FT_GlyphSlot slot = face.glyph();
            int hw = slot.bitmap().width();
            int hh = slot.bitmap().rows();

            error = FreeType.FT_Load_Glyph(face, index, 2 /* FT_LOAD_NO_HINTING */);
            if (error != 0) {
                continue;
            }
            slot = face.glyph();
            int nw = slot.bitmap().width();
            int nh = slot.bitmap().rows();

            compared++;
            int deltaH = Math.abs(nh - hh);
            int deltaArea = Math.abs(nw * nh - hw * hh);
            totalDeltaH += deltaH;
            totalDeltaArea += deltaArea;
            worst = Math.max(worst, Math.max(deltaH, Math.abs(nw - hw)));
            if (nw != hw || nh != hh) {
                different++;
            }
        }

        System.out.printf(
                "glyphs compared=%d differing=%d (%.1f%%) avgΔheight=%.2fpx avgΔarea=%.2fpx² worstΔ=%dpx%n",
                compared, different, 100.0 * different / Math.max(1, compared),
                (double) totalDeltaH / Math.max(1, compared),
                (double) totalDeltaArea / Math.max(1, compared), worst);

        FreeType.FT_Done_Face(face);
        FreeType.FT_Done_Library(library);
        MemoryUtil.memFree(data);
    }
}
