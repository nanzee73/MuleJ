package de.mulej.core;

import de.c64gfx.color.ColorMatcher;
import de.c64gfx.dither.Ditherer;
import de.c64gfx.palette.Palette;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Turns a 320×200 picture into a character set, a screen and optional meta tiles (for demo effects).
 *
 * <h2>Colours</h2>
 * Hires characters: one shared background plus one colour per cell. Multicolour characters: background, $D022 and
 * $D023 for the whole screen plus one colour 0–7 per cell. The shared colours are found by trying the candidates
 * on the whole picture; each cell takes the colour that reproduces it best.
 *
 * <h2>Merging similar characters</h2>
 * Every cell first gets its own exact character. If there are more than allowed, the characters are merged with
 * k-means: a cell's "distance" to a character is the colour error it would cause, a group's character takes in
 * every pixel the value with the smallest summed error of its cells, and the cell colours are refitted to the
 * merged characters. So similar cells share a character and the eye hardly notices.
 */
public final class CharsetConverter {

    /**
     * @param multicolor multicolour characters (double-wide pixels, 4 colours per cell)
     * @param maxChars   1..256 characters at most
     * @param tileSize   1, 2 or 4: meta tiles of n×n characters
     * @param background -1 = automatic, else fixed ($D021)
     * @param mc1        -1 = automatic, else fixed ($D022, multicolour only)
     * @param mc2        -1 = automatic, else fixed ($D023, multicolour only)
     */
    public record Settings(boolean multicolor, int maxChars, int tileSize, int background, int mc1, int mc2) {
        public Settings {
            maxChars = Math.max(2, Math.min(256, maxChars));
            if (tileSize != 2 && tileSize != 4) tileSize = 1;
        }

        public static Settings defaults() { return new Settings(false, 256, 1, -1, -1, -1); }
    }

    static final int ITERATIONS = 20;

    private CharsetConverter() {}

    public static CharsetImage convert(BufferedImage src, Palette pal, ColorMatcher.Space space, Settings s,
                                       Ditherer.Settings dither) {
        if (src.getWidth() != 320 || src.getHeight() != 200)
            throw new IllegalArgumentException("The character set needs a 320 x 200 picture.");
        boolean mc = s.multicolor();
        int w = mc ? 160 : 320, cellW = mc ? 4 : 8, np = cellW * 8;
        ColorMatcher m = new ColorMatcher(pal, space, 50, 50, false);
        float[] rgb = toFloat(src, mc);
        float[] cost = m.errorTable(rgb);
        Work k = new Work(cost, w, cellW, np, mc);

        // ---- shared colours and cell colours
        int[] glob = mc ? multicolourGlobals(k, s) : new int[]{hiresBackground(k, s.background())};
        k.glob = glob;
        for (int c = 0; c < CharsetImage.CELLS; c++) k.cellColour[c] = k.bestCellColour(c);

        // ---- optional dithering with the colours of each cell
        if (dither != null && dither.active()) {
            int[][] allowed = new int[CharsetImage.CELLS][];
            for (int c = 0; c < allowed.length; c++) allowed[c] = k.colours(c);
            int[] idx = Ditherer.run(rgb, w, 200, m, pal, dither, allowed, cellW, 8);
            float[] d = new float[rgb.length];
            for (int i = 0; i < idx.length; i++) {
                d[i * 3] = pal.r(idx[i]); d[i * 3 + 1] = pal.g(idx[i]); d[i * 3 + 2] = pal.b(idx[i]);
            }
            k.cost = m.errorTable(d);
        }

        // ---- every cell its own best pattern, then merge
        byte[][] own = new byte[CharsetImage.CELLS][];
        for (int c = 0; c < own.length; c++) own[c] = k.bestPattern(c, k.cellColour[c]);
        Map<String, byte[]> unique = new LinkedHashMap<>();
        for (byte[] p : own) unique.putIfAbsent(key(p), p);
        boolean needBlank = s.tileSize() > 1;
        List<byte[]> centres;
        int[] assign;
        if (unique.size() + (needBlank && !unique.containsKey(key(new byte[np])) ? 1 : 0) <= s.maxChars()) {
            centres = new ArrayList<>(unique.values());
            assign = new int[CharsetImage.CELLS];
            Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < centres.size(); i++) index.put(key(centres.get(i)), i);
            for (int c = 0; c < own.length; c++) assign[c] = index.get(key(own[c]));
        } else {
            int target = s.maxChars() - (needBlank ? 1 : 0);
            Object[] r = kMeans(k, own, Math.max(1, target));
            @SuppressWarnings("unchecked") List<byte[]> cs = (List<byte[]>) r[0];
            centres = cs;
            assign = (int[]) r[1];
        }

        // ---- codes: blank first (if used or needed), then in order of first use
        byte[] blank = new byte[np];
        List<byte[]> ordered = new ArrayList<>();
        Map<String, Integer> code = new HashMap<>();
        boolean blankUsed = needBlank;
        for (int c = 0; c < CharsetImage.CELLS && !blankUsed; c++) blankUsed = Arrays.equals(centres.get(assign[c]), blank);
        if (blankUsed) { ordered.add(blank); code.put(key(blank), 0); }
        CharsetImage out = new CharsetImage();
        out.multicolor = mc;
        out.background = glob[0];
        out.border = glob[0];
        if (mc) { out.mc1 = glob[1]; out.mc2 = glob[2]; }
        out.uniqueBefore = unique.size();
        for (int c = 0; c < CharsetImage.CELLS; c++) {
            byte[] p = centres.get(assign[c]);
            Integer i = code.get(key(p));
            if (i == null) { i = ordered.size(); ordered.add(p); code.put(key(p), i); }
            out.screen[c] = i;
            int col = k.cellColour[c];
            out.colours[c] = mc ? 8 | (col & 7) : col;
        }
        out.chars = new byte[ordered.size()][];
        for (int i = 0; i < ordered.size(); i++) out.chars[i] = toBytes(ordered.get(i), mc);
        tiles(out, s.tileSize());
        return out;
    }

    // ------------------------------------------------------------------ colours

    private static int hiresBackground(Work k, int fixed) {
        if (fixed >= 0) return fixed & 15;
        double[] total = new double[16];
        double[][] per = new double[CharsetImage.CELLS][];
        IntStream.range(0, CharsetImage.CELLS).parallel().forEach(c -> {
            double[] e = new double[16];
            for (int b = 0; b < 16; b++) {
                double best = Double.MAX_VALUE;
                for (int f = 0; f < 16; f++) best = Math.min(best, k.errorWith(c, new int[]{b, f}));
                e[b] = best;
            }
            per[c] = e;
        });
        for (double[] e : per) for (int b = 0; b < 16; b++) total[b] += e[b];
        int bg = 0;
        for (int b = 1; b < 16; b++) if (total[b] < total[bg]) bg = b;
        return bg;
    }

    /** Background, $D022, $D023: candidates are the colours most pixels are closest to. */
    private static int[] multicolourGlobals(Work k, Settings s) {
        int[] count = new int[16];
        for (int p = 0; p < k.cost.length / 16; p++) {
            int best = 0;
            for (int c = 1; c < 16; c++) if (k.cost[p * 16 + c] < k.cost[p * 16 + best]) best = c;
            count[best]++;
        }
        Integer[] order = new Integer[16];
        for (int i = 0; i < 16; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> count[b] - count[a]);
        int[] top6 = new int[6], top10 = new int[10];
        for (int i = 0; i < 10; i++) { if (i < 6) top6[i] = order[i]; top10[i] = order[i]; }
        int[] bgs = s.background() >= 0 ? new int[]{s.background() & 15} : top6;
        int[] c1 = s.mc1() >= 0 ? new int[]{s.mc1() & 15} : top10;
        int[] c2 = s.mc2() >= 0 ? new int[]{s.mc2() & 15} : top10;
        boolean bothAuto = s.mc1() < 0 && s.mc2() < 0;
        List<int[]> triples = new ArrayList<>();
        for (int b : bgs)
            for (int x : c1)
                for (int y : c2) {
                    if (bothAuto && x >= y) continue;
                    if (b == x || b == y || x == y) continue;
                    triples.add(new int[]{b, x, y});
                }
        if (triples.isEmpty()) {                        // fixed colours that coincide
            int b = bgs[0], x = c1[0], y = c2[0];
            return new int[]{b, x, y};
        }
        double[] err = new double[triples.size()];
        IntStream.range(0, triples.size()).parallel().forEach(t -> {
            int[] g = triples.get(t);
            double e = 0;
            for (int c = 0; c < CharsetImage.CELLS; c++) {
                double best = Double.MAX_VALUE;
                for (int cc = 0; cc < 8; cc++) best = Math.min(best, k.errorWith(c, new int[]{g[0], g[1], g[2], cc}));
                e += best;
            }
            err[t] = e;
        });
        int bi = 0;
        for (int t = 1; t < err.length; t++) if (err[t] < err[bi]) bi = t;
        return triples.get(bi);
    }

    // ------------------------------------------------------------------ merging

    /** k-means on the cells: {List<byte[]> centres, int[] assignment}. Cell colours in {@code k} are refitted. */
    private static Object[] kMeans(Work k, byte[][] own, int count) {
        int n = CharsetImage.CELLS;
        Random rnd = new Random(64);
        List<byte[]> centres = new ArrayList<>();
        // seed: the most frequent pattern, then k-means++ by the error a cell would get
        Map<String, Integer> freq = new HashMap<>();
        for (byte[] p : own) freq.merge(key(p), 1, Integer::sum);
        int start = 0;
        for (int c = 1; c < n; c++) if (freq.get(key(own[c])) > freq.get(key(own[start]))) start = c;
        centres.add(own[start].clone());
        double[] ownErr = new double[n], dist = new double[n];
        for (int c = 0; c < n; c++) ownErr[c] = k.patternError(c, own[c], k.cellColour[c]);
        for (int c = 0; c < n; c++) dist[c] = Math.max(0, k.patternError(c, centres.get(0), k.cellColour[c]) - ownErr[c]);
        while (centres.size() < count) {
            double sum = 0;
            for (double d : dist) sum += d;
            if (sum <= 0) break;
            double r = rnd.nextDouble() * sum;
            int pick = 0;
            for (; pick < n - 1; pick++) { r -= dist[pick]; if (r <= 0) break; }
            byte[] c0 = own[pick].clone();
            centres.add(c0);
            for (int c = 0; c < n; c++)
                dist[c] = Math.min(dist[c], Math.max(0, k.patternError(c, c0, k.cellColour[c]) - ownErr[c]));
        }
        int[] assign = new int[n];
        Arrays.fill(assign, -1);
        for (int it = 0; it < ITERATIONS; it++) {
            List<byte[]> cs = centres;
            int[] next = new int[n];
            IntStream.range(0, n).parallel().forEach(c -> {
                double best = Double.MAX_VALUE;
                int bi = 0;
                for (int i = 0; i < cs.size(); i++) {
                    double e = k.patternError(c, cs.get(i), k.cellColour[c]);
                    if (e < best) { best = e; bi = i; }
                }
                next[c] = bi;
            });
            boolean changed = !Arrays.equals(next, assign);
            assign = next;
            // cell colours that suit the shared character
            for (int c = 0; c < n; c++) k.cellColour[c] = k.bestColourFor(c, centres.get(assign[c]));
            // new centres
            List<byte[]> nc = new ArrayList<>();
            for (int i = 0; i < centres.size(); i++) nc.add(k.bestPatternFor(assign, i, centres.get(i)));
            // empty groups take the cell that is worst off
            boolean[] used = new boolean[centres.size()];
            for (int a : assign) used[a] = true;
            for (int i = 0; i < nc.size(); i++) {
                if (used[i]) continue;
                int worst = 0;
                double we = -1;
                for (int c = 0; c < n; c++) {
                    double e = k.patternError(c, nc.get(assign[c]), k.cellColour[c]) - ownErr[c];
                    if (e > we) { we = e; worst = c; }
                }
                nc.set(i, k.bestPattern(worst, k.cellColour[worst]));
                ownErr[worst] = Double.MAX_VALUE / 4;       // not twice
                changed = true;
            }
            centres = nc;
            if (!changed) break;
        }
        // final assignment with the final centres
        List<byte[]> cs = centres;
        int[] fin = new int[n];
        IntStream.range(0, n).parallel().forEach(c -> {
            double best = Double.MAX_VALUE;
            int bi = 0;
            for (int i = 0; i < cs.size(); i++) {
                int col = k.bestColourFor(c, cs.get(i));
                double e = k.patternError(c, cs.get(i), col);
                if (e < best) { best = e; bi = i; }
            }
            fin[c] = bi;
        });
        for (int c = 0; c < n; c++) k.cellColour[c] = k.bestColourFor(c, centres.get(fin[c]));
        return new Object[]{centres, fin};
    }

    // ------------------------------------------------------------------ tiles

    static void tiles(CharsetImage out, int n) {
        out.tileSize = n;
        out.mapCols = (CharsetImage.COLS + n - 1) / n;
        out.mapRows = (CharsetImage.ROWS + n - 1) / n;
        Map<String, Integer> index = new LinkedHashMap<>();
        List<int[]> codes = new ArrayList<>(), cols = new ArrayList<>();
        out.map = new int[out.mapCols * out.mapRows];
        int pad = out.multicolor ? 8 | (out.background & 7) : out.background;
        for (int my = 0; my < out.mapRows; my++)
            for (int mx = 0; mx < out.mapCols; mx++) {
                int[] t = new int[n * n], tc = new int[n * n];
                for (int y = 0; y < n; y++)
                    for (int x = 0; x < n; x++) {
                        int sx = mx * n + x, sy = my * n + y;
                        boolean in = sx < CharsetImage.COLS && sy < CharsetImage.ROWS;
                        t[y * n + x] = in ? out.screen[sy * CharsetImage.COLS + sx] : 0;
                        tc[y * n + x] = in ? out.colours[sy * CharsetImage.COLS + sx] : pad;
                    }
                String key = Arrays.toString(t) + Arrays.toString(tc);
                Integer i = index.get(key);
                if (i == null) { i = codes.size(); index.put(key, i); codes.add(t); cols.add(tc); }
                out.map[my * out.mapCols + mx] = i;
            }
        out.tiles = codes.toArray(new int[0][]);
        out.tileColours = cols.toArray(new int[0][]);
    }

    // ------------------------------------------------------------------ helpers

    private static String key(byte[] p) { return new String(p, java.nio.charset.StandardCharsets.ISO_8859_1); }

    /** Pattern (one value per pixel) → 8 bytes. */
    private static byte[] toBytes(byte[] p, boolean mc) {
        byte[] b = new byte[8];
        for (int y = 0; y < 8; y++) {
            int v = 0;
            if (mc) for (int x = 0; x < 4; x++) v |= (p[y * 4 + x] & 3) << (6 - 2 * x);
            else for (int x = 0; x < 8; x++) v |= (p[y * 8 + x] & 1) << (7 - x);
            b[y] = (byte) v;
        }
        return b;
    }

    /** Pixels as float RGB; multicolour: pixel pairs averaged to 160 pixels per row. */
    static float[] toFloat(BufferedImage src, boolean mc) {
        int w = mc ? 160 : 320;
        float[] f = new float[w * 200 * 3];
        for (int y = 0; y < 200; y++)
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 3;
                if (mc) {
                    int a = src.getRGB(2 * x, y), b = src.getRGB(2 * x + 1, y);
                    f[i] = (((a >> 16) & 0xFF) + ((b >> 16) & 0xFF)) / 2f;
                    f[i + 1] = (((a >> 8) & 0xFF) + ((b >> 8) & 0xFF)) / 2f;
                    f[i + 2] = ((a & 0xFF) + (b & 0xFF)) / 2f;
                } else {
                    int a = src.getRGB(x, y);
                    f[i] = (a >> 16) & 0xFF; f[i + 1] = (a >> 8) & 0xFF; f[i + 2] = a & 0xFF;
                }
            }
        return f;
    }

    /**
     * Error table and colour state. Pixel values: hires 0 = background, 1 = cell colour; multicolour 0 = background,
     * 1 = $D022, 2 = $D023, 3 = cell colour.
     */
    private static final class Work {
        float[] cost;
        final int w, cellW, np;
        final boolean mc;
        int[] glob;
        final int[] cellColour = new int[CharsetImage.CELLS];
        /** Pixel index per cell and position inside the cell. */
        private final int[][] pix = new int[CharsetImage.CELLS][];

        Work(float[] cost, int w, int cellW, int np, boolean mc) {
            this.cost = cost; this.w = w; this.cellW = cellW; this.np = np; this.mc = mc;
            for (int cell = 0; cell < pix.length; cell++) {
                int cx = (cell % CharsetImage.COLS) * cellW, cy = (cell / CharsetImage.COLS) * 8;
                pix[cell] = new int[np];
                for (int p = 0; p < np; p++) pix[cell][p] = (cy + p / cellW) * w + cx + p % cellW;
            }
        }

        int pixel(int cell, int p) { return pix[cell][p]; }

        int[] colours(int cell) {
            int[] c = Arrays.copyOf(glob, glob.length + 1);
            c[glob.length] = cellColour[cell];
            return c;
        }

        int values() { return glob.length + 1; }

        /** Error of a cell when every pixel may take the best of the given colours. */
        double errorWith(int cell, int[] cols) {
            double e = 0;
            for (int p = 0; p < np; p++) {
                int base = pixel(cell, p) * 16;
                float b = Float.MAX_VALUE;
                for (int c : cols) b = Math.min(b, cost[base + c]);
                e += b;
            }
            return e;
        }

        int bestCellColour(int cell) {
            int[] cols = colours(cell);
            int n = mc ? 8 : 16, best = 0;
            double be = Double.MAX_VALUE;
            for (int c = 0; c < n; c++) {
                cols[cols.length - 1] = c;
                double e = errorWith(cell, cols);
                if (e < be) { be = e; best = c; }
            }
            return best;
        }

        private int colourOf(int value, int cellCol) { return value < glob.length ? glob[value] : cellCol; }

        byte[] bestPattern(int cell, int cellCol) {
            byte[] p = new byte[np];
            int v = values();
            for (int i = 0; i < np; i++) {
                int base = pixel(cell, i) * 16, best = 0;
                for (int x = 1; x < v; x++) if (cost[base + colourOf(x, cellCol)] < cost[base + colourOf(best, cellCol)]) best = x;
                p[i] = (byte) best;
            }
            return p;
        }

        double patternError(int cell, byte[] pat, int cellCol) {
            double e = 0;
            for (int i = 0; i < np; i++) e += cost[pixel(cell, i) * 16 + colourOf(pat[i], cellCol)];
            return e;
        }

        /** The cell colour that suits a given pattern best (only pixels with the cell colour value count). */
        int bestColourFor(int cell, byte[] pat) {
            int v = values() - 1, n = mc ? 8 : 16, best = cellColour[cell];
            double be = Double.MAX_VALUE;
            boolean any = false;
            for (byte b : pat) if (b == v) { any = true; break; }
            if (!any) return best;
            for (int c = 0; c < n; c++) {
                double e = 0;
                for (int i = 0; i < np; i++) if (pat[i] == v) e += cost[pixel(cell, i) * 16 + c];
                if (e < be) { be = e; best = c; }
            }
            return best;
        }

        /** Centre of a group: per pixel the value with the smallest summed error of its cells. */
        byte[] bestPatternFor(int[] assign, int group, byte[] old) {
            int v = values();
            double[] sum = new double[np * v];
            boolean any = false;
            for (int c = 0; c < assign.length; c++) {
                if (assign[c] != group) continue;
                any = true;
                for (int i = 0; i < np; i++) {
                    int base = pixel(c, i) * 16;
                    for (int x = 0; x < v; x++) sum[i * v + x] += cost[base + colourOf(x, cellColour[c])];
                }
            }
            if (!any) return old;
            byte[] p = new byte[np];
            for (int i = 0; i < np; i++) {
                int best = 0;
                for (int x = 1; x < v; x++) if (sum[i * v + x] < sum[i * v + best]) best = x;
                p[i] = (byte) best;
            }
            return p;
        }
    }
}
