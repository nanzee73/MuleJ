package de.mulej.core;

import java.awt.image.BufferedImage;

/**
 * Turns an image into C64 multicolor pixels (2×1) by pairing neighbouring columns (formerly c64pixelizer).
 * The image keeps its size.
 */
public final class Pixelizer {
    /** Which pixel of a pair survives. */
    public enum Direction {
        /** Left to right: every even column is copied onto the odd column right of it. */
        L2R,
        /** Right to left: every odd column is copied onto the even column left of it. */
        R2L,
        /** Both pixels get the average of the pair. */
        AVERAGE;

        public String fileSuffix() {
            return switch (this) {
                case L2R -> "_pixelized_l2r";
                case R2L -> "_pixelized_r2l";
                case AVERAGE -> "_pixelized_avg";
            };
        }

        @Override public String toString() { return de.c64gfx.swing.I18n.tr("pixel." + name()); }
    }

    private Pixelizer() {}

    public static BufferedImage pixelize(BufferedImage src, Direction dir) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            src.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x + 1 < w; x += 2) {
                int v = switch (dir) {
                    case L2R -> row[x];
                    case R2L -> row[x + 1];
                    case AVERAGE -> average(row[x], row[x + 1]);
                };
                row[x] = v;
                row[x + 1] = v;
            }
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        return out;
    }

    /** Half width: one pixel per pair (160×200 from 320×200). An odd last column is dropped. */
    public static BufferedImage halfWidth(BufferedImage src, Direction dir) {
        int w = src.getWidth() / 2, h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[src.getWidth()], half = new int[w];
        for (int y = 0; y < h; y++) {
            src.getRGB(0, y, src.getWidth(), 1, row, 0, src.getWidth());
            for (int x = 0; x < w; x++) {
                int a = row[2 * x], b = row[2 * x + 1];
                half[x] = switch (dir) {
                    case L2R -> a;
                    case R2L -> b;
                    case AVERAGE -> average(a, b);
                };
            }
            out.setRGB(0, y, w, 1, half, 0, w);
        }
        return out;
    }

    static int average(int a, int b) {
        int r = (((a >> 16) & 0xFF) + ((b >> 16) & 0xFF) + 1) >> 1;
        int g = (((a >> 8) & 0xFF) + ((b >> 8) & 0xFF) + 1) >> 1;
        int bl = ((a & 0xFF) + (b & 0xFF) + 1) >> 1;
        return (r << 16) | (g << 8) | bl;
    }
}
