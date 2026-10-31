package de.mulej.core;

import de.c64gfx.dither.Ditherer;
import de.c64gfx.format.HiresImage;
import de.c64gfx.format.KoalaImage;

import de.c64gfx.color.ColorMatcher;
import de.c64gfx.palette.Palette;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.stream.IntStream;

/**
 * Turns a 320×200 (or 160×200) picture into C64 bitmap data – multicolor (Koala) or hires (Art Studio) –
 * and checks the colour restrictions of the mode (formerly c64koalaverter / c64imager, multicolor only).
 * <p>
 * Stages: pixel pairs (multicolor) → palette colours (optionally dithered) → background → automatic repair →
 * manual corrections ({@link Edits}) → colour check → encoding. Data is only produced when no cell breaks the rules.
 */
public final class BitmapConverter {

    /**
     * @param pair       which pixel of a 2×1 pair is used (multicolor, 320 wide input)
     * @param palettize  map colours to the palette first; off = every pixel must already be a palette colour
     * @param background multicolor: -1 = most common colour, 0..15 = fixed
     * @param fixClashes cells that break the rules get the colours that reproduce them best (automatic correction)
     * @param dither     dithering (only with palettize): every cell gets its best colours, then the picture is
     *                   dithered with exactly those – clashes cannot occur then
     */
    public record Settings(C64Mode mode, Palette palette, ColorMatcher.Space space, Pixelizer.Direction pair,
                           boolean palettize, int background, boolean fixClashes, Ditherer.Settings dither) {
        public static Settings defaults(C64Mode mode, Palette p) {
            return new Settings(mode, p, ColorMatcher.Space.EUCLID, Pixelizer.Direction.L2R, true, -1, false,
                    Ditherer.Settings.NONE);
        }

        public boolean dithering() { return palettize && dither != null && dither.active(); }

        public Settings withFix(boolean fix) {
            return new Settings(mode, palette, space, pair, palettize, background, fix, dither);
        }
    }

    /**
     * Manual corrections, in the mode's own pixel coordinates.
     *
     * @param cellColours colours a cell may use (without the shared background), at most {@code coloursPerCell}
     * @param pixels      single pixels (y * width + x) set to a colour
     */
    public record Edits(Map<Integer, int[]> cellColours, Map<Integer, Integer> pixels) {
        public static Edits none() { return new Edits(Map.of(), Map.of()); }

        public boolean isEmpty() { return cellColours.isEmpty() && pixels.isEmpty(); }
    }

    /**
     * @param indices     palette index per mode pixel (after all corrections)
     * @param clashCells  cells that still break the rules (empty = data was produced)
     * @param fixedCells  cells that were corrected automatically
     * @param background  shared background colour (multicolor), -1 for hires
     * @param koala       multicolor data, null if clashes remain or in hires mode
     * @param hires       hires data, null if clashes remain or in multicolor mode
     */
    public record Result(C64Mode mode, int[] indices, List<Integer> clashCells, List<Integer> fixedCells,
                         int background, KoalaImage koala, HiresImage hires) {
        public boolean ok() { return clashCells.isEmpty(); }

        public byte[] fileBytes() {
            return koala != null ? koala.toBytes() : hires != null ? hires.toBytes() : null;
        }

        /** 320×200 picture of the indices (multicolor pixels drawn two wide). */
        public BufferedImage render(Palette p) {
            if (koala != null) return de.c64gfx.swing.Images.fromRgb(koala.render(p), 320, 200);
            if (hires != null) return de.c64gfx.swing.Images.fromRgb(hires.render(p), 320, 200);
            int f = mode.pixelWidth();
            int[] wide = new int[320 * 200];
            for (int i = 0; i < wide.length; i++) wide[i] = indices[(i / 320) * mode.width + (i % 320) / f];
            return Palettizer.render(wide, 320, 200, p);
        }

        /** Colours used in a cell, with their pixel counts (index = colour). */
        public int[] cellCounts(int cell) { return counts(mode, indices, cell); }
    }

    /** Raised when the input cannot be converted at all (wrong size, colour outside the palette). */
    public static final class ConversionException extends Exception {
        public ConversionException(String msg) { super(msg); }
    }

    private BitmapConverter() {}

    public static boolean isValidSize(BufferedImage img) {
        return (img.getWidth() == 320 || img.getWidth() == 160) && img.getHeight() == 200;
    }

    public static Result convert(BufferedImage src, Settings s) throws ConversionException {
        return convert(src, s, Edits.none());
    }

    public static Result convert(BufferedImage src, Settings s, Edits edits) throws ConversionException {
        if (!isValidSize(src))
            throw new ConversionException("Image resolution must be 320 x 200 (or 160 x 200), not "
                    + src.getWidth() + " x " + src.getHeight() + ".");
        C64Mode mode = s.mode();
        BufferedImage work = modeImage(src, mode, s.pair());
        ColorMatcher m = new ColorMatcher(s.palette(), s.space(), 50, 50, false);
        float[] rgb = de.c64gfx.swing.Images.toFloat(work);
        int[] idx = s.palettize() ? Palettizer.indices(work, m) : exactIndices(work, s.palette(), mode);
        int bg = !mode.sharedBackground ? -1 : s.background() >= 0 ? s.background() & 15 : mostCommon(idx);

        List<Integer> fixed = new ArrayList<>();
        if (s.dithering()) {
            int[][] allowed = new int[C64Mode.CELLS][];
            IntStream.range(0, C64Mode.CELLS).parallel().forEach(cell -> {
                int[] own = edits.cellColours().get(cell);
                allowed[cell] = own != null ? withBackground(own, bg) : bestColours(mode, rgb, cell, bg, m);
            });
            idx = Ditherer.run(rgb, mode.width, 200, m, s.palette(), s.dither(), allowed, mode.cellW, C64Mode.CELL_H);
        } else {
            for (int cell = 0; cell < C64Mode.CELLS; cell++) {
                int[] own = edits.cellColours().get(cell);
                if (own != null) {
                    remap(mode, idx, rgb, cell, withBackground(own, bg), m);
                } else if (s.fixClashes() && breaksRules(mode, counts(mode, idx, cell), bg)) {
                    remap(mode, idx, rgb, cell, bestColours(mode, rgb, cell, bg, m), m);
                    fixed.add(cell);
                }
            }
        }
        for (Map.Entry<Integer, Integer> e : edits.pixels().entrySet()) {
            if (e.getKey() >= 0 && e.getKey() < idx.length) idx[e.getKey()] = e.getValue() & 15;
        }

        List<Integer> clashes = check(mode, idx, bg);
        KoalaImage koala = null;
        HiresImage hires = null;
        if (clashes.isEmpty()) {
            if (mode == C64Mode.MULTICOLOR) koala = KoalaImage.encode(idx, bg);
            else hires = HiresImage.encode(idx);
        }
        return new Result(mode, idx, List.copyOf(clashes), List.copyOf(fixed), bg, koala, hires);
    }

    /** The picture in mode resolution: 160×200 from pixel pairs (multicolor) or 320×200 (hires). */
    static BufferedImage modeImage(BufferedImage src, C64Mode mode, Pixelizer.Direction pair) {
        if (src.getWidth() == mode.width) return Images.toRgb(src);
        if (mode == C64Mode.MULTICOLOR) return Pixelizer.halfWidth(src, pair);
        // 160 wide into hires: every pixel twice
        BufferedImage out = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 200; y++) for (int x = 0; x < 320; x++) out.setRGB(x, y, src.getRGB(x / 2, y));
        return out;
    }

    // ------------------------------------------------------------------ colour check

    /** Cells that use more colours than the mode allows. */
    public static List<Integer> check(C64Mode mode, int[] idx, int bg) {
        List<Integer> clashes = new ArrayList<>();
        for (int cell = 0; cell < C64Mode.CELLS; cell++) if (breaksRules(mode, counts(mode, idx, cell), bg)) clashes.add(cell);
        return clashes;
    }

    static boolean breaksRules(C64Mode mode, int[] count, int bg) {
        int used = 0;
        for (int i = 0; i < 16; i++) if (count[i] > 0 && i != bg) used++;
        return used > mode.coloursPerCell;
    }

    static int[] counts(C64Mode mode, int[] idx, int cell) {
        int cx = mode.cellX(cell), cy = mode.cellY(cell);
        int[] count = new int[16];
        for (int y = 0; y < C64Mode.CELL_H; y++)
            for (int x = 0; x < mode.cellW; x++) count[idx[(cy + y) * mode.width + cx + x]]++;
        return count;
    }

    // ------------------------------------------------------------------ correction

    private static int[] withBackground(int[] colours, int bg) {
        if (bg < 0) return colours.clone();
        int[] out = Arrays.copyOf(colours, colours.length + 1);
        out[colours.length] = bg;
        return out;
    }

    /** Every pixel of the cell gets the nearest of the allowed colours (measured on the original picture). */
    private static void remap(C64Mode mode, int[] idx, float[] rgb, int cell, int[] allowed, ColorMatcher m) {
        if (allowed.length == 0) return;
        int cx = mode.cellX(cell), cy = mode.cellY(cell);
        for (int y = 0; y < C64Mode.CELL_H; y++) {
            for (int x = 0; x < mode.cellW; x++) {
                int p = (cy + y) * mode.width + cx + x;
                idx[p] = m.nearest(rgb[p * 3], rgb[p * 3 + 1], rgb[p * 3 + 2], allowed, allowed.length);
            }
        }
    }

    /**
     * The colours (plus the shared background) that give the smallest total error for the pixels of a cell;
     * all combinations are tried. Ties: lower colour indices.
     */
    public static int[] bestColours(C64Mode mode, float[] rgb, int cell, int bg, ColorMatcher m) {
        int cx = mode.cellX(cell), cy = mode.cellY(cell), n = mode.cellW * C64Mode.CELL_H;
        float[][] d = new float[n][16];
        for (int y = 0; y < C64Mode.CELL_H; y++) {
            for (int x = 0; x < mode.cellW; x++) {
                int i = ((cy + y) * mode.width + cx + x) * 3;
                for (int k = 0; k < 16; k++) d[y * mode.cellW + x][k] = m.dist(rgb[i], rgb[i + 1], rgb[i + 2], k);
            }
        }
        int[] best = null;
        double bestErr = Double.MAX_VALUE;
        if (mode == C64Mode.MULTICOLOR) {
            for (int a = 0; a < 16; a++) {
                if (a == bg) continue;
                for (int b = a + 1; b < 16; b++) {
                    if (b == bg) continue;
                    for (int c = b + 1; c < 16; c++) {
                        if (c == bg) continue;
                        double e = 0;
                        for (float[] p : d) e += Math.min(Math.min(p[bg], p[a]), Math.min(p[b], p[c]));
                        if (e < bestErr) { bestErr = e; best = new int[]{a, b, c, bg}; }
                    }
                }
            }
        } else {
            for (int a = 0; a < 16; a++) {
                for (int b = a + 1; b < 16; b++) {
                    double e = 0;
                    for (float[] p : d) e += Math.min(p[a], p[b]);
                    if (e < bestErr) { bestErr = e; best = new int[]{a, b}; }
                }
            }
        }
        return best;
    }

    /** {@link #bestColours} without the background, e.g. as a suggestion for a manual cell correction. */
    public static int[] suggestColours(BufferedImage src, Settings s, int cell, int bg) {
        BufferedImage work = modeImage(src, s.mode(), s.pair());
        ColorMatcher m = new ColorMatcher(s.palette(), s.space(), 50, 50, false);
        int[] best = bestColours(s.mode(), de.c64gfx.swing.Images.toFloat(work), cell, bg, m);
        return s.mode().sharedBackground ? Arrays.copyOf(best, best.length - 1) : best;
    }

    // ------------------------------------------------------------------ helpers

    private static int[] exactIndices(BufferedImage img, Palette p, C64Mode mode) throws ConversionException {
        int w = img.getWidth(), h = img.getHeight();
        int[] idx = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y) & 0xFFFFFF, found = -1;
                for (int i = 0; i < 16 && found < 0; i++) if (p.rgb(i) == rgb) found = i;
                if (found < 0)
                    throw new ConversionException(String.format(
                            "Image colour #%06X at %d,%d is not in the %s palette.", rgb, x * mode.pixelWidth(), y, p.name()));
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
}
