package de.mulej.core;

import de.mulej.color.ColorMatcher;
import de.mulej.palette.Palette;

import java.awt.image.BufferedImage;
import java.util.stream.IntStream;

/**
 * Replaces every pixel by the nearest of the 16 palette colors (formerly c64palettizer).
 * With {@link ColorMatcher.Space#EUCLID} and Colodore the result is identical to the old tool.
 */
public final class Palettizer {
    private static final int[] ALL = IntStream.range(0, 16).toArray();

    private Palettizer() {}

    /** Palette index (0..15) for every pixel, row by row. */
    public static int[] indices(BufferedImage src, ColorMatcher m) {
        int w = src.getWidth(), h = src.getHeight();
        int[] out = new int[w * h];
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                out[y * w + x] = m.nearest((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, ALL, 16);
            }
        });
        return out;
    }

    public static BufferedImage palettize(BufferedImage src, Palette palette, ColorMatcher m) {
        return render(indices(src, m), src.getWidth(), src.getHeight(), palette);
    }

    public static BufferedImage palettize(BufferedImage src, Palette palette, ColorMatcher.Space space) {
        return palettize(src, palette, new ColorMatcher(palette, space, 50, 50, false));
    }

    /** Palette indices with optional dithering (all 16 colours allowed everywhere). */
    public static int[] indices(BufferedImage src, Palette palette, ColorMatcher m, Ditherer.Settings dither) {
        if (dither == null || !dither.active()) return indices(src, m);
        return Ditherer.run(Ditherer.toFloat(src), src.getWidth(), src.getHeight(), m, palette, dither, null, 1, 1);
    }

    public static BufferedImage palettize(BufferedImage src, Palette palette, ColorMatcher.Space space,
                                          Ditherer.Settings dither) {
        ColorMatcher m = new ColorMatcher(palette, space, 50, 50, false);
        return render(indices(src, palette, m, dither), src.getWidth(), src.getHeight(), palette);
    }

    /** Draws an index map with the given palette. */
    public static BufferedImage render(int[] idx, int w, int h, Palette palette) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) row[x] = palette.rgb(idx[y * w + x]);
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        return out;
    }
}
