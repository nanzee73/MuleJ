package de.mulej.core;

import de.c64gfx.color.ColorMatcher;
import de.c64gfx.dither.Ditherer;
import de.c64gfx.palette.Palette;

import java.awt.image.BufferedImage;
import java.util.stream.IntStream;

/**
 * Turns a 320×200 picture into PETSCII: for every 8×8 cell the character and foreground colour (plus one background
 * colour for the whole screen) that look most like the original.
 *
 * <h2>How a cell is rated</h2>
 * Two measures are mixed (slider "detail", 0..100 %):
 * <ul>
 *   <li><b>sharp</b>: the error pixel by pixel – rewards characters whose shape follows edges and lines;</li>
 *   <li><b>soft</b>: the error after blurring both the cell and the character (binomial 3×3 filter, twice) – rates
 *       brightness and colour the way the eye sees the cell from a distance, so checkerboards and half blocks
 *       stand in for in-between tones.</li>
 * </ul>
 * Colours are compared in a linear colour space (plain RGB, weighted RGB or YUV), so both errors can be written as
 * {@code |A|² − 2·D·S_c + |D|²·Q_c} with {@code D = fg − bg}: per cell only a few numbers per character have to be
 * computed, and trying all 256 characters × 15 foreground colours × 16 backgrounds stays fast.
 *
 * <h2>Background and error diffusion</h2>
 * The background is chosen by trying all 16 colours on the whole picture (or fixed). Then the cells are converted
 * row by row; the remaining average error of a cell is passed on to its neighbours (Floyd-Steinberg weights on cell
 * level, slider "error diffusion"), so areas and gradients keep their overall tone.
 *
 * <h2>Optional dithering</h2>
 * With dithering a first pass chooses the background and the foreground colour of every cell. The picture is then
 * dithered pixel by pixel with just these two colours per cell, and a second, pixel exact pass picks the characters
 * that follow the dither pattern best – so checkerboard and dot characters render the in-between tones.
 */
public final class PetsciiConverter {

    /**
     * @param charset       character set
     * @param textChars     allow letters, digits and punctuation (off = only graphics characters, space and reversed space)
     * @param background    -1 = automatic, 0..15 = fixed
     * @param detail        0..100: share of the sharp (pixel exact) measure; the rest is the soft measure
     * @param diffusion     0..100: how much of a cell's remaining error is passed on to the neighbours
     * @param calm          0..100: penalty for busy characters, so they only appear where they really help
     * @param dither        optional dithering before the characters are chosen ({@link Ditherer.Settings#NONE} = off)
     */
    public record Settings(Charset charset, boolean textChars, int background, int detail, int diffusion, int calm,
                           Ditherer.Settings dither) {
        public Settings {
            if (dither == null) dither = Ditherer.Settings.NONE;
        }

        public Settings(Charset charset, boolean textChars, int background, int detail, int diffusion, int calm) {
            this(charset, textChars, background, detail, diffusion, calm, Ditherer.Settings.NONE);
        }

        public static Settings defaults() { return new Settings(Charset.UPPER, false, -1, 40, 40, 30); }
    }

    /** Detail of the second pass with dithering: mostly pixel exact, so the characters follow the pattern. */
    static final int DITHER_DETAIL = 80;

    private static final int N = 64;

    private PetsciiConverter() {}

    public static PetsciiImage convert(BufferedImage src, Palette pal, ColorMatcher.Space space, Settings s) {
        if (src.getWidth() != 320 || src.getHeight() != 200)
            throw new IllegalArgumentException("PETSCII needs a 320 x 200 picture.");
        if (!s.dither().active()) return match(src, pal, space, s);
        // pass 1: background
        int bg = s.background() >= 0 ? s.background()
                : match(src, pal, space, new Settings(s.charset(), s.textChars(), -1, s.detail(), s.diffusion(), s.calm())).background;
        // per cell the colour that, mixed with the background, reproduces the cell best
        int[] fg = mixColours(src, pal, space, bg);
        BufferedImage dithered = dither(src, pal, space, bg, fg, s.dither());
        // pass 2: characters that follow the dither pattern
        return match(dithered, pal, space, new Settings(s.charset(), s.textChars(), bg, DITHER_DETAIL, 0, s.calm() / 3));
    }

    /**
     * For every cell the foreground colour f whose mixes with the background (bg + t·(f − bg), 0 ≤ t ≤ 1) come
     * closest to the cell's pixels – the best pair for dithering with two colours.
     */
    static int[] mixColours(BufferedImage src, Palette pal, ColorMatcher.Space space, int bg) {
        Space sp = new Space(space);
        float[][] pc = new float[16][];
        for (int i = 0; i < 16; i++) pc[i] = sp.of(pal.r(i), pal.g(i), pal.b(i));
        int[] fg = new int[PetsciiImage.CELLS];
        IntStream.range(0, PetsciiImage.CELLS).parallel().forEach(cell -> {
            int cx = (cell % PetsciiImage.COLS) * 8, cy = (cell / PetsciiImage.COLS) * 8;
            float[][] px = new float[N][];
            for (int p = 0; p < N; p++) {
                int rgb = src.getRGB(cx + (p & 7), cy + (p >> 3));
                px[p] = sp.of((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
            }
            float[] b = pc[bg];
            double bestE = Double.MAX_VALUE;
            int best = bg == 0 ? 1 : 0;
            for (int f = 0; f < 16; f++) {
                if (f == bg) continue;
                float d0 = pc[f][0] - b[0], d1 = pc[f][1] - b[1], d2 = pc[f][2] - b[2];
                float dd = d0 * d0 + d1 * d1 + d2 * d2;
                double e = 0;
                for (float[] x : px) {
                    float a0 = x[0] - b[0], a1 = x[1] - b[1], a2 = x[2] - b[2];
                    float t = dd > 0 ? Math.max(0, Math.min(1, (a0 * d0 + a1 * d1 + a2 * d2) / dd)) : 0;
                    float r0 = a0 - t * d0, r1 = a1 - t * d1, r2 = a2 - t * d2;
                    e += r0 * r0 + r1 * r1 + r2 * r2;
                }
                if (e < bestE) { bestE = e; best = f; }
            }
            fg[cell] = best;
        });
        return fg;
    }

    /** Dithers the picture with two colours per 8×8 cell: background and the cell's foreground. */
    static BufferedImage dither(BufferedImage src, Palette pal, ColorMatcher.Space space, int bg, int[] fg,
                                Ditherer.Settings ds) {
        int[][] allowed = new int[PetsciiImage.CELLS][];
        for (int c = 0; c < allowed.length; c++) allowed[c] = fg[c] == bg ? new int[]{bg} : new int[]{bg, fg[c]};
        float[] rgb = de.c64gfx.swing.Images.toFloat(src);
        int[] idx = Ditherer.run(rgb, 320, 200, new ColorMatcher(pal, space, 50, 50, false), pal, ds, allowed, 8, 8);
        BufferedImage out = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < idx.length; i++) out.setRGB(i % 320, i / 320, pal.rgb(idx[i]));
        return out;
    }

    private static PetsciiImage match(BufferedImage src, Palette pal, ColorMatcher.Space space, Settings s) {
        Space sp = new Space(space);
        float d = Math.max(0, Math.min(100, s.detail())) / 100f;

        // ---- characters: sharp and soft masks, sums
        int[] chars = IntStream.range(0, 256).filter(c -> s.textChars() || !s.charset().isText(c)).toArray();
        int nc = chars.length;
        float[][] sharp = new float[nc][N], soft = new float[nc][];
        float[] q = new float[nc], msum = new float[nc], ones = new float[nc], busy = new float[nc];
        for (int k = 0; k < nc; k++) {
            for (int p = 0; p < N; p++) sharp[k][p] = s.charset().pixel(chars[k], p & 7, p >> 3) ? 1 : 0;
            soft[k] = blur(sharp[k]);
            float qs = 0, qb = 0, ms = 0, mb = 0;
            for (int p = 0; p < N; p++) {
                qs += sharp[k][p]; qb += soft[k][p] * soft[k][p];
                ms += sharp[k][p]; mb += soft[k][p];
            }
            q[k] = d * qs + (1 - d) * qb;
            msum[k] = d * ms + (1 - d) * mb;
            ones[k] = ms;
            int changes = 0;
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 8; x++) {
                    if (x < 7 && sharp[k][y * 8 + x] != sharp[k][y * 8 + x + 1]) changes++;
                    if (y < 7 && sharp[k][y * 8 + x] != sharp[k][(y + 1) * 8 + x]) changes++;
                }
            busy[k] = changes;
        }
        // calm: a busy character only wins if it is clearly better (penalty grows with the colour contrast)
        float calm = s.calm() / 100f * 0.15f;

        // ---- picture in the working colour space
        float[][] pc = new float[16][];
        for (int i = 0; i < 16; i++) pc[i] = sp.of(pal.r(i), pal.g(i), pal.b(i));
        float[] img = new float[320 * 200 * 3];
        for (int y = 0; y < 200; y++)
            for (int x = 0; x < 320; x++) {
                int rgb = src.getRGB(x, y);
                float[] v = sp.of((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
                System.arraycopy(v, 0, img, (y * 320 + x) * 3, 3);
            }
        Cells cells = new Cells(img, sharp, soft, d);

        // ---- background: try all 16 on the whole picture (without diffusion)
        int bg = s.background();
        if (bg < 0) {
            double[] total = new double[16];
            double[][] perCell = new double[PetsciiImage.CELLS][];
            IntStream.range(0, PetsciiImage.CELLS).parallel().forEach(cell -> {
                Stats st = cells.stats(cell, null);
                double[] e = new double[16];
                for (int b = 0; b < 16; b++) e[b] = best(st, b, pc, q, msum, busy, calm)[2];
                perCell[cell] = e;
            });
            for (double[] e : perCell) for (int b = 0; b < 16; b++) total[b] += e[b];
            bg = 0;
            for (int b = 1; b < 16; b++) if (total[b] < total[bg]) bg = b;
        }

        // ---- cells in reading order with error diffusion between cells
        PetsciiImage out = new PetsciiImage();
        out.charset = s.charset();
        out.background = bg;
        out.border = bg;
        float strength = Math.max(0, Math.min(100, s.diffusion())) / 100f;
        float[][] offset = new float[PetsciiImage.CELLS][3];
        for (int cell = 0; cell < PetsciiImage.CELLS; cell++) {
            Stats st = cells.stats(cell, offset[cell]);
            double[] r = best(st, bg, pc, q, msum, busy, calm);
            int k = (int) r[0], fg = (int) r[1];
            out.codes[cell] = chars[k];
            out.colours[cell] = fg;
            if (strength <= 0) continue;
            // average error of the cell: target mean – shown mean
            float share = ones[k] / N;
            float[] err = new float[3];
            for (int c = 0; c < 3; c++) {
                float shown = pc[bg][c] + (pc[fg][c] - pc[bg][c]) * share;
                err[c] = (st.mean[c] - shown) * strength;
            }
            int cx = cell % PetsciiImage.COLS, cy = cell / PetsciiImage.COLS;
            spread(offset, cx + 1, cy, err, 7f / 16, sp.limit);
            spread(offset, cx - 1, cy + 1, err, 3f / 16, sp.limit);
            spread(offset, cx, cy + 1, err, 5f / 16, sp.limit);
            spread(offset, cx + 1, cy + 1, err, 1f / 16, sp.limit);
        }
        return out;
    }

    private static void spread(float[][] off, int cx, int cy, float[] err, float w, float limit) {
        if (cx < 0 || cx >= PetsciiImage.COLS || cy >= PetsciiImage.ROWS) return;
        float[] o = off[cy * PetsciiImage.COLS + cx];
        for (int c = 0; c < 3; c++) o[c] = Math.max(-limit, Math.min(limit, o[c] + err[c] * w));
    }

    /** Best character and foreground for a cell with background b: {charIndex, fg, error}. */
    private static double[] best(Stats st, int b, float[][] pc, float[] q, float[] msum, float[] busy, float calm) {
        float[] bv = pc[b];
        // |A|² with A = X − bg, for the mixed measure
        double aa = st.xx - 2 * dot(bv, st.sx) + st.w * dot(bv, bv);
        double bestE = Double.MAX_VALUE;
        int bestK = 0, bestF = b;
        int nc = q.length;
        for (int f = 0; f < 16; f++) {
            if (f == b) continue;
            float d0 = pc[f][0] - bv[0], d1 = pc[f][1] - bv[1], d2 = pc[f][2] - bv[2];
            float dd = d0 * d0 + d1 * d1 + d2 * d2;
            for (int k = 0; k < nc; k++) {
                // S_c = S0_c − bg · M_c
                float s0 = st.s[k * 3] - bv[0] * msum[k];
                float s1 = st.s[k * 3 + 1] - bv[1] * msum[k];
                float s2 = st.s[k * 3 + 2] - bv[2] * msum[k];
                double e = aa - 2 * (d0 * s0 + d1 * s1 + d2 * s2) + dd * q[k] + calm * busy[k] * dd;
                if (e < bestE - 1e-6) { bestE = e; bestK = k; bestF = f; }
            }
        }
        return new double[]{bestK, bestF, bestE};
    }

    private static double dot(float[] a, float[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    /** Binomial blur [1 2 1]/4, twice in both directions, edges repeated (inside the 8×8 cell). */
    static float[] blur(float[] v) {
        float[] a = v.clone();
        for (int pass = 0; pass < 2; pass++) {
            float[] t = new float[N];
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 8; x++)
                    t[y * 8 + x] = (a[y * 8 + Math.max(0, x - 1)] + 2 * a[y * 8 + x] + a[y * 8 + Math.min(7, x + 1)]) / 4;
            float[] u = new float[N];
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 8; x++)
                    u[y * 8 + x] = (t[Math.max(0, y - 1) * 8 + x] + 2 * t[y * 8 + x] + t[Math.min(7, y + 1) * 8 + x]) / 4;
            a = u;
        }
        return a;
    }

    /** Numbers of one cell needed for the error formula. */
    private record Stats(double xx, float[] sx, float w, float[] s, float[] mean) {}

    private static final class Cells {
        final float[] img;
        final float[][] sharp, soft;
        final float d;

        Cells(float[] img, float[][] sharp, float[][] soft, float d) {
            this.img = img; this.sharp = sharp; this.soft = soft; this.d = d;
        }

        Stats stats(int cell, float[] offset) {
            int cx = (cell % PetsciiImage.COLS) * 8, cy = (cell / PetsciiImage.COLS) * 8;
            float[][] x = new float[3][N];
            for (int y = 0; y < 8; y++)
                for (int xx = 0; xx < 8; xx++) {
                    int i = ((cy + y) * 320 + cx + xx) * 3;
                    for (int c = 0; c < 3; c++) x[c][y * 8 + xx] = img[i + c] + (offset != null ? offset[c] : 0);
                }
            float[][] xb = {blur(x[0]), blur(x[1]), blur(x[2])};
            double xx2 = 0;
            float[] sx = new float[3], mean = new float[3];
            for (int c = 0; c < 3; c++)
                for (int p = 0; p < N; p++) {
                    xx2 += d * x[c][p] * x[c][p] + (1 - d) * xb[c][p] * xb[c][p];
                    sx[c] += d * x[c][p] + (1 - d) * xb[c][p];
                    mean[c] += x[c][p] / N;
                }
            int nc = sharp.length;
            float[] s = new float[nc * 3];
            for (int k = 0; k < nc; k++) {
                float[] ks = sharp[k], kb = soft[k];
                for (int c = 0; c < 3; c++) {
                    float a = 0, b = 0;
                    float[] xc = x[c], xbc = xb[c];
                    for (int p = 0; p < N; p++) { a += ks[p] * xc[p]; b += kb[p] * xbc[p]; }
                    s[k * 3 + c] = d * a + (1 - d) * b;
                }
            }
            // weight of |bg|² in |A|²: N for both measures
            return new Stats(xx2, sx, N, s, mean);
        }
    }

    /** Linear colour space in which plain euclidean distance is the matching measure. */
    private static final class Space {
        final float[] m;   // 3×3 matrix
        final float limit;

        Space(ColorMatcher.Space s) {
            switch (s) {
                case EUCLID -> m = new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
                case RGB -> {
                    float a = (float) Math.sqrt(0.299 * 3), b = (float) Math.sqrt(0.587 * 3), c = (float) Math.sqrt(0.114 * 3);
                    m = new float[]{a, 0, 0, 0, b, 0, 0, 0, c};
                }
                default -> m = new float[]{     // YUV (HSL is not linear, YUV is used instead)
                        0.299f, 0.587f, 0.114f,
                        -0.14713f, -0.28886f, 0.436f,
                        0.615f, -0.51499f, -0.10001f};
            }
            limit = 96f;
        }

        float[] of(float r, float g, float b) {
            return new float[]{m[0] * r + m[1] * g + m[2] * b, m[3] * r + m[4] * g + m[5] * b, m[6] * r + m[7] * g + m[8] * b};
        }
    }
}
