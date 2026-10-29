package de.mulej.core;

import de.mulej.color.ColorMatcher;
import de.mulej.palette.Palette;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Converts a 320×200 (or 160×200) picture into a Koala Painter file (formerly c64koalaverter / c64imager).
 * <p>
 * Steps as in the old tool: pixel pairs → palette colors → background = most common color →
 * per 4×8 cell at most 3 colors besides the background → Koala data.
 * New: the colour clash check reports every offending cell, and can optionally repair them.
 */
public final class KoalaConverter {

    /**
     * @param palette    palette used for matching (and for the exact-colour check without palettizing)
     * @param space      matching colour space
     * @param pair       which pixel of a 2×1 pair is used (ignored for 160×200 input)
     * @param palettize  map colours to the palette first; off = every pixel must already be a palette colour
     * @param background -1 = most common colour, 0..15 = fixed
     * @param fixClashes reduce cells with more than 3 colours (besides background) to their 3 most frequent
     */
    public record Settings(Palette palette, ColorMatcher.Space space, Pixelizer.Direction pair,
                           boolean palettize, int background, boolean fixClashes) {
        public static Settings defaults(Palette p) {
            return new Settings(p, ColorMatcher.Space.EUCLID, Pixelizer.Direction.L2R, true, -1, false);
        }
    }

    /**
     * @param koala       the picture, null if colour clashes remain
     * @param indices     160×200 palette indices (after repairing, if enabled)
     * @param clashCells  cell numbers (0..999, row-major) with too many colours in the input
     * @param background  background colour that was used
     */
    public record Result(KoalaImage koala, int[] indices, List<Integer> clashCells, int background) {
        public boolean ok() { return koala != null; }
    }

    /** Raised when the input cannot be converted at all (wrong size, colour outside the palette). */
    public static final class ConversionException extends Exception {
        public ConversionException(String msg) { super(msg); }
    }

    private KoalaConverter() {}

    public static boolean isValidSize(BufferedImage img) {
        return (img.getWidth() == 320 || img.getWidth() == 160) && img.getHeight() == 200;
    }

    public static Result convert(BufferedImage src, Settings s) throws ConversionException {
        if (!isValidSize(src))
            throw new ConversionException("Image resolution must be 320 x 200 (or 160 x 200), not "
                    + src.getWidth() + " x " + src.getHeight() + ".");
        BufferedImage half = src.getWidth() == 160 ? Images.toRgb(src) : Pixelizer.halfWidth(src, s.pair());
        ColorMatcher m = new ColorMatcher(s.palette(), s.space(), 50, 50, false);
        int[] idx = s.palettize() ? Palettizer.indices(half, m) : exactIndices(half, s.palette());

        int bg = s.background() >= 0 ? s.background() & 15 : mostCommon(idx);
        List<Integer> clashes = new ArrayList<>();
        for (int cell = 0; cell < KoalaImage.CELLS; cell++) {
            int[] count = cellCounts(idx, cell);
            count[bg] = 0;
            int used = 0;
            for (int c : count) if (c > 0) used++;
            if (used <= 3) continue;
            clashes.add(cell);
            if (s.fixClashes()) repairCell(idx, half, cell, count, bg, m);
        }
        if (!clashes.isEmpty() && !s.fixClashes()) return new Result(null, idx, clashes, bg);
        return new Result(encode(idx, bg), idx, Collections.unmodifiableList(clashes), bg);
    }

    private static int[] exactIndices(BufferedImage img, Palette p) throws ConversionException {
        int w = img.getWidth(), h = img.getHeight();
        int[] idx = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y) & 0xFFFFFF, found = -1;
                for (int i = 0; i < 16 && found < 0; i++) if (p.rgb(i) == rgb) found = i;
                if (found < 0)
                    throw new ConversionException(String.format(
                            "Image color #%06X at %d,%d is not in the %s palette.", rgb, x * 2, y, p.name()));
                idx[y * w + x] = found;
            }
        }
        return idx;
    }

    /** Most common index; on a tie the lower index wins. */
    static int mostCommon(int[] idx) {
        int[] count = new int[16];
        for (int i : idx) count[i]++;
        int best = 0;
        for (int i = 1; i < 16; i++) if (count[i] > count[best]) best = i;
        return best;
    }

    private static int[] cellCounts(int[] idx, int cell) {
        int cx = (cell % KoalaImage.COLS) * 4, cy = (cell / KoalaImage.COLS) * 8;
        int[] count = new int[16];
        for (int y = 0; y < 8; y++) for (int x = 0; x < 4; x++) count[idx[(cy + y) * 160 + cx + x]]++;
        return count;
    }

    /** Keeps the three most frequent colours (ties: lower index); other pixels get the nearest allowed colour. */
    private static void repairCell(int[] idx, BufferedImage half, int cell, int[] count, int bg, ColorMatcher m) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < 16; i++) if (count[i] > 0) order.add(i);
        order.sort((a, b) -> count[b] != count[a] ? count[b] - count[a] : a - b);
        int[] allowed = {bg, order.get(0), order.get(1), order.get(2)};
        int cx = (cell % KoalaImage.COLS) * 4, cy = (cell / KoalaImage.COLS) * 8;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 4; x++) {
                int p = (cy + y) * 160 + cx + x, c = idx[p];
                if (c == bg || c == allowed[1] || c == allowed[2] || c == allowed[3]) continue;
                int rgb = half.getRGB(cx + x, cy + y);
                idx[p] = m.nearest((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, allowed, 4);
            }
        }
    }

    /**
     * Same byte layout as the old c64koalaverter: the cell colours sorted by colour index go to
     * screen low nibble (bits 10), screen high nibble (bits 01) and colour RAM (bits 11).
     */
    static KoalaImage encode(int[] idx, int bg) {
        KoalaImage k = new KoalaImage();
        k.background = bg;
        for (int cell = 0; cell < KoalaImage.CELLS; cell++) {
            int[] count = cellCounts(idx, cell);
            count[bg] = 0;
            int[] cols = new int[3];
            int n = 0;
            for (int i = 0; i < 16; i++) if (count[i] > 0) cols[n++] = i;
            int lo = n >= 1 ? cols[0] : 0, hi = n >= 2 ? cols[1] : 0, cr = n >= 3 ? cols[2] : 0;
            k.screen[cell] = (byte) ((hi << 4) | lo);
            k.colorRam[cell] = (byte) cr;
            int cx = (cell % KoalaImage.COLS) * 4, cy = (cell / KoalaImage.COLS) * 8;
            for (int y = 0; y < 8; y++) {
                int b = 0;
                for (int x = 0; x < 4; x++) {
                    int c = idx[(cy + y) * 160 + cx + x];
                    int bits = c == bg ? 0 : n >= 1 && c == cols[0] ? 2 : n >= 2 && c == cols[1] ? 1 : 3;
                    b = (b << 2) | bits;
                }
                k.bitmap[cell * 8 + y] = (byte) b;
            }
        }
        return k;
    }
}
