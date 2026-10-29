package de.mulej.core;

import de.mulej.color.ColorMatcher;
import de.mulej.palette.Palette;

import java.awt.image.BufferedImage;

/**
 * Error diffusion or ordered dithering onto palette indices (same method as in FlipperJ).
 * Optionally every pixel may only use the colours allowed in its cell (Koala: background + 3 per 4×8 cell).
 */
public final class Ditherer {

    /**
     * @param strength   0..100 %
     * @param serpentine scan every second line from right to left
     * @param bleeding   with cells: let the diffused error cross cell borders
     */
    public record Settings(Dither dither, int strength, boolean serpentine, boolean bleeding) {
        public static final Settings NONE = new Settings(Dither.NONE, 80, false, true);

        public boolean active() { return dither != Dither.NONE && strength > 0; }
    }

    private Ditherer() {}

    /** Pixels as float[w*h*3] (0..255). */
    public static float[] toFloat(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        float[] f = new float[w * h * 3];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 3, c = row[x];
                f[i] = (c >> 16) & 0xFF;
                f[i + 1] = (c >> 8) & 0xFF;
                f[i + 2] = c & 0xFF;
            }
        }
        return f;
    }

    private static float clamp(float v) { return v < 0 ? 0 : Math.min(255, v); }

    private static final int[] ALL = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};

    /**
     * @param allowed colours per cell (cells row by row, {@code w / cellW} per row), null = all 16 everywhere
     * @return palette index per pixel
     */
    public static int[] run(float[] rgb, int w, int h, ColorMatcher m, Palette pal, Settings s,
                            int[][] allowed, int cellW, int cellH) {
        Dither d = s.dither();
        float strength = s.strength() / 100f;
        float[] err = new float[w * h * 3];
        int[] out = new int[w * h];
        int cols = allowed != null ? w / cellW : 1;
        for (int y = 0; y < h; y++) {
            int dir = s.serpentine() && (y & 1) == 1 ? -1 : 1;
            for (int xi = 0; xi < w; xi++) {
                int x = dir > 0 ? xi : w - 1 - xi;
                int i = y * w + x;
                int[] al = allowed == null ? ALL : allowed[(y / cellH) * cols + x / cellW];
                float r = clamp(rgb[i * 3] + err[i * 3]);
                float g = clamp(rgb[i * 3 + 1] + err[i * 3 + 1]);
                float b = clamp(rgb[i * 3 + 2] + err[i * 3 + 2]);
                float mr = r, mg = g, mb = b;
                if (d.isOrdered()) {
                    float t = d.threshold(x, y) * strength * 96f;
                    mr = clamp(r + t); mg = clamp(g + t); mb = clamp(b + t);
                }
                int k = al.length == 1 ? al[0] : m.nearest(mr, mg, mb, al, al.length);
                out[i] = k;
                if (!d.isErrorDiffusion() || strength <= 0) continue;
                float er = (r - pal.r(k)) * strength, eg = (g - pal.g(k)) * strength, eb = (b - pal.b(k)) * strength;
                for (int[] kv : d.kernel) {
                    int nx = x + kv[0] * dir, ny = y + kv[1];
                    if (nx < 0 || nx >= w || ny >= h) continue;
                    if (allowed != null && !s.bleeding() && (nx / cellW != x / cellW || ny / cellH != y / cellH)) continue;
                    float wt = (float) kv[2] / d.divisor;
                    int j = (ny * w + nx) * 3;
                    err[j] += er * wt;
                    err[j + 1] += eg * wt;
                    err[j + 2] += eb * wt;
                }
            }
        }
        return out;
    }
}
