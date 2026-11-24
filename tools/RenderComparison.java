import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Bitmap;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FT_GlyphSlot;
import org.lwjgl.util.freetype.FreeType;

/**
 * Renders one line of text twice through the real FreeType — with hinting (vanilla behaviour) and
 * with {@code FT_LOAD_NO_HINTING} (this tool's safe channel) — and writes a labelled comparison PNG.
 *
 * <p>usage: RenderComparison &lt;font&gt; &lt;text&gt; &lt;size&gt; &lt;out.png&gt; [zoom]</p>
 *
 * <p>A glyph index may be forced with {@code --glyphs=a,b,c} to bypass the font's cmap (useful for
 * .ttc CJK faces whose subfont mapping is awkward).</p>
 */
public final class RenderComparison {

    private static final int BG = 0x14161A;
    private static final int FG = 0xEDEFF2;

    public static void main(String[] args) throws Exception {
        Path fontPath = Path.of(args[0]);
        String text = args[1];
        int size = Integer.parseInt(args[2]);
        Path out = Path.of(args[3]);
        int zoom = args.length > 4 ? Integer.parseInt(args[4]) : 4;

        PointerBuffer libraryPtr = BufferUtils.createPointerBuffer(1);
        check(FreeType.FT_Init_FreeType(libraryPtr), "FT_Init_FreeType");
        long library = libraryPtr.get(0);

        byte[] bytes = Files.readAllBytes(fontPath);
        ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
        data.put(bytes).flip();
        PointerBuffer facePtr = BufferUtils.createPointerBuffer(1);
        check(FreeType.FT_New_Memory_Face(library, data, 0L, facePtr), "FT_New_Memory_Face");
        FT_Face face = FT_Face.create(facePtr.get(0));
        check(FreeType.FT_Set_Pixel_Sizes(face, 0, size), "FT_Set_Pixel_Sizes");
        FreeType.FT_Select_Charmap(face, FreeType.FT_ENCODING_UNICODE);

        // FT_LOAD_RENDER (0x4) is needed for the bitmap to be produced at all.
        BufferedImage hinted = renderLine(face, text, 0x4);
        BufferedImage plain = renderLine(face, text, 0x4 | 0x2 /* FT_LOAD_NO_HINTING */);

        // Scale first, then size the canvas from the scaled images: sizing from the unscaled ones
        // clips the pasted rows (that bug produced a blank "hinted" row at 9 px).
        BufferedImage hintedScaled = scale(hinted, zoom);
        BufferedImage plainScaled = scale(plain, zoom);

        int pad = 14;
        int labelWidth = 150;
        int contentWidth = Math.max(hintedScaled.getWidth(), plainScaled.getWidth());
        int rowHeight = Math.max(hintedScaled.getHeight(), plainScaled.getHeight()) + pad;
        int width = labelWidth + contentWidth + pad * 2;
        int height = rowHeight * 2 + pad * 2;

        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(BG));
        g.fillRect(0, 0, width, height);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        g.setColor(new Color(0x9AA0A8));
        g.drawString("hinted", pad, pad + 18);
        g.drawString("(vanilla)", pad, pad + 33);
        g.drawString("no hinting", pad, rowHeight + pad + 18);
        g.drawString("(this tool)", pad, rowHeight + pad + 33);

        g.drawImage(hintedScaled, labelWidth, pad, null);
        g.drawImage(plainScaled, labelWidth, rowHeight + pad, null);
        g.dispose();

        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        ImageIO.write(canvas, "png", out.toFile());

        int litHinted = litPixels(hinted);
        int litPlain = litPixels(plain);
        System.out.println("lit pixels: hinted=" + litHinted + " plain=" + litPlain);
        if (litHinted < 50 || litPlain < 50) {
            throw new IllegalStateException("a row rendered almost nothing (hinted=" + litHinted
                    + ", plain=" + litPlain + ") - refusing to ship a blank comparison");
        }
        System.out.println("wrote " + out + " (" + width + "x" + height
                + ", content " + contentWidth + "x" + rowHeight + ", zoom x" + zoom + ")");

        FreeType.FT_Done_Face(face);
        FreeType.FT_Done_Library(library);
        MemoryUtil.memFree(data);
    }

    private static int litPixels(BufferedImage image) {
        int lit = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFF) > 60) {
                    lit++;
                }
            }
        }
        return lit;
    }

    /** Renders one line into an RGB image with a dark background and white text. */
    private static BufferedImage renderLine(FT_Face face, String text, int flags) {
        FT_GlyphSlot slot = face.glyph();
        long penX = 2;

        java.util.List<long[]> placed = new java.util.ArrayList<>();
        java.util.List<BufferedImage> ink = new java.util.ArrayList<>();
        int minTop = Integer.MAX_VALUE;
        int maxBottom = Integer.MIN_VALUE;

        for (int i = 0; i < text.length(); i++) {
            int codepoint = text.codePointAt(i);
            if (Character.isHighSurrogate(text.charAt(i))) {
                i++;
            }
            long glyphIndex = FreeType.FT_Get_Char_Index(face, codepoint);
            if (glyphIndex == 0) {
                glyphIndex = FreeType.FT_Get_Char_Index(face, '?');
            }
            if (FreeType.FT_Load_Glyph(face, (int) glyphIndex, flags) != 0) {
                continue;
            }
            FT_Bitmap bitmap = slot.bitmap();
            int bw = bitmap.width();
            int bh = bitmap.rows();
            int left = slot.bitmap_left();
            int top = slot.bitmap_top();

            // Coverage image: 0 = no ink, 255 = full ink.
            BufferedImage coverage = new BufferedImage(Math.max(1, bw), Math.max(1, bh),
                    BufferedImage.TYPE_BYTE_GRAY);
            ByteBuffer src = bw > 0 && bh > 0 ? bitmap.buffer(Math.max(1, bw * bh)) : null;
            if (src != null) {
                int pitch = bitmap.pitch();
                for (int y = 0; y < bh; y++) {
                    for (int x = 0; x < bw; x++) {
                        int index = y * pitch + x;
                        if (index >= 0 && index < src.capacity()) {
                            coverage.getRaster().setSample(x, y, 0, src.get(index) & 0xFF);
                        }
                    }
                }
            }
            int drawY = -top;
            minTop = Math.min(minTop, drawY);
            maxBottom = Math.max(maxBottom, drawY + bh);
            placed.add(new long[]{(int) (penX + left), drawY});
            ink.add(coverage);
            penX += slot.advance().x() >> 6;
        }

        if (placed.isEmpty()) {
            minTop = 0;
            maxBottom = 1;
        }
        int width = (int) penX + 4;
        int height = Math.max(4, maxBottom - minTop + 4);
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                result.setRGB(x, y, BG);
            }
        }
        for (int i = 0; i < placed.size(); i++) {
            BufferedImage coverage = ink.get(i);
            int dx = (int) placed.get(i)[0];
            int dy = (int) placed.get(i)[1] - minTop + 2;
            for (int y = 0; y < coverage.getHeight(); y++) {
                for (int x = 0; x < coverage.getWidth(); x++) {
                    int alpha = coverage.getRaster().getSample(x, y, 0);
                    if (alpha == 0) {
                        continue;
                    }
                    int tx = dx + x;
                    int ty = dy + y;
                    if (tx < 0 || ty < 0 || tx >= width || ty >= height) {
                        continue;
                    }
                    result.setRGB(tx, ty, blend(result.getRGB(tx, ty), FG, alpha));
                }
            }
        }
        return result;
    }

    /** Alpha-blends {@code over} onto {@code under} with the given coverage (0..255). */
    private static int blend(int under, int over, int alpha) {
        int a = Math.min(255, Math.max(0, alpha));
        int r = (((over >> 16) & 0xFF) * a + ((under >> 16) & 0xFF) * (255 - a)) / 255;
        int g = (((over >> 8) & 0xFF) * a + ((under >> 8) & 0xFF) * (255 - a)) / 255;
        int b = ((over & 0xFF) * a + (under & 0xFF) * (255 - a)) / 255;
        return (r << 16) | (g << 8) | b;
    }

    /** Nearest-neighbour upscale so individual pixels stay visible. */
    private static BufferedImage scale(BufferedImage source, int zoom) {
        if (zoom <= 1) {
            return source;
        }
        BufferedImage scaled = new BufferedImage(
                source.getWidth() * zoom, source.getHeight() * zoom, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < scaled.getHeight(); y++) {
            for (int x = 0; x < scaled.getWidth(); x++) {
                scaled.setRGB(x, y, source.getRGB(x / zoom, y / zoom));
            }
        }
        return scaled;
    }

    private static void check(int error, String what) {
        if (error != 0) {
            throw new IllegalStateException(what + " failed: " + error);
        }
    }
}
