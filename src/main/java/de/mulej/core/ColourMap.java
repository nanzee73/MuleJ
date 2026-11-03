package de.mulej.core;

import java.util.Arrays;

/**
 * Colours swapped or replaced in the finished picture: {@code map[c]} is the colour shown instead of colour c.
 * <p>
 * It is applied after the colour step, so the C64 rules stay kept: a swap only exchanges colours, a replacement only
 * merges two; no cell gets more colours. One exception: in multicolour characters the cell colour can only be 0–7,
 * such cells keep their colour if the new one is 8–15.
 */
public final class ColourMap {
    private final int[] map;

    private ColourMap(int[] map) { this.map = map; }

    public static ColourMap identity() {
        int[] m = new int[16];
        for (int i = 0; i < 16; i++) m[i] = i;
        return new ColourMap(m);
    }

    /** From "0,1,2,…" (16 numbers); anything else gives the identity. */
    public static ColourMap parse(String s) {
        try {
            String[] p = s.trim().split("\\s*,\\s*");
            if (p.length != 16) return identity();
            int[] m = new int[16];
            for (int i = 0; i < 16; i++) {
                m[i] = Integer.parseInt(p[i]);
                if (m[i] < 0 || m[i] > 15) return identity();
            }
            return new ColourMap(m);
        } catch (RuntimeException e) {
            return identity();
        }
    }

    public int get(int c) { return map[c & 15]; }

    public boolean isIdentity() {
        for (int i = 0; i < 16; i++) if (map[i] != i) return false;
        return true;
    }

    /** Wherever colour a is shown now, b is shown and vice versa. */
    public ColourMap swap(int a, int b) {
        int[] m = map.clone();
        for (int i = 0; i < 16; i++) m[i] = map[i] == a ? b : map[i] == b ? a : map[i];
        return new ColourMap(m);
    }

    /** Wherever colour a is shown now, b is shown. */
    public ColourMap replace(int a, int b) {
        int[] m = map.clone();
        for (int i = 0; i < 16; i++) if (map[i] == a) m[i] = b;
        return new ColourMap(m);
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append(i > 0 ? "," : "").append(map[i]);
        return sb.toString();
    }

    /** The changed colours, e.g. "6 → 14, 14 → 6". */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++)
            if (map[i] != i) sb.append(sb.length() > 0 ? ", " : "").append(i).append(" → ").append(map[i]);
        return sb.toString();
    }

    @Override public boolean equals(Object o) { return o instanceof ColourMap c && Arrays.equals(map, c.map); }

    @Override public int hashCode() { return Arrays.hashCode(map); }

    // ------------------------------------------------------------------ applying

    /**
     * A checked bitmap with the colours changed: the picture is redrawn and encoded again (palette colours only,
     * fixed background), which keeps the rules since no cell gets more colours. A bitmap that breaks the rules is
     * returned unchanged.
     */
    public BitmapConverter.Result apply(BitmapConverter.Result r, BitmapConverter.Settings s) {
        if (isIdentity() || !r.ok()) return r;
        int[] idx = r.indices().clone();
        for (int i = 0; i < idx.length; i++) idx[i] = get(idx[i]);
        int w = r.mode().width;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(320, 200, java.awt.image.BufferedImage.TYPE_INT_RGB);
        int pw = 320 / w;
        for (int y = 0; y < 200; y++)
            for (int x = 0; x < 320; x++) img.setRGB(x, y, s.palette().rgb(idx[y * w + x / pw]));
        BitmapConverter.Settings snap = new BitmapConverter.Settings(s.mode(), s.palette(), s.space(), s.pair(), true,
                r.mode().sharedBackground ? get(r.background()) : -1, false, de.c64gfx.dither.Ditherer.Settings.NONE);
        BitmapConverter.Result out;
        try {
            out = BitmapConverter.convert(img, snap, BitmapConverter.Edits.none());
        } catch (BitmapConverter.ConversionException e) {
            return r;                                       // cannot happen for a palette picture of C64 size
        }
        return new BitmapConverter.Result(out.mode(), out.indices(), out.clashCells(), r.fixedCells(), out.background(),
                out.koala(), out.hires());
    }

    /** A text screen with the colours changed (characters stay). */
    public PetsciiImage apply(PetsciiImage p) {
        if (isIdentity()) return p;
        PetsciiImage o = new PetsciiImage();
        o.charset = p.charset;
        System.arraycopy(p.codes, 0, o.codes, 0, p.codes.length);
        for (int i = 0; i < p.colours.length; i++) o.colours[i] = get(p.colours[i]);
        o.background = get(p.background);
        o.border = get(p.border);
        return o;
    }

    /**
     * A character set picture with the colours changed (characters stay; tiles are built again).
     * Multicolour character cells only take colours 0–7: if the new colour is 8–15 the cell keeps its colour.
     */
    public CharsetImage apply(CharsetImage c) {
        if (isIdentity()) return c;
        CharsetImage o = new CharsetImage();
        o.multicolor = c.multicolor;
        o.background = get(c.background);
        o.border = get(c.border);
        o.mc1 = c.multicolor ? get(c.mc1) : c.mc1;
        o.mc2 = c.multicolor ? get(c.mc2) : c.mc2;
        o.chars = c.chars;
        o.uniqueBefore = c.uniqueBefore;
        System.arraycopy(c.screen, 0, o.screen, 0, c.screen.length);
        for (int i = 0; i < c.colours.length; i++) {
            if (!c.multicolor) { o.colours[i] = get(c.colours[i]); continue; }
            int n = get(c.colours[i] & 7);
            o.colours[i] = n <= 7 ? 8 | n : c.colours[i];
            if (n > 7) o.keptCells++;
        }
        CharsetConverter.tiles(o, c.tileSize);
        return o;
    }
}
