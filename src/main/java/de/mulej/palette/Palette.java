package de.mulej.palette;

import java.util.Arrays;

/** A C64 palette: 16 RGB values (0xRRGGBB) in VIC-II order. */
public final class Palette {
    public static final String[] COLOR_NAMES = {
            "Black", "White", "Red", "Cyan", "Purple", "Green", "Blue", "Yellow",
            "Orange", "Brown", "Light red", "Dark grey", "Mid grey", "Light green", "Light blue", "Light grey"
    };

    private final String name;
    private final int[] rgb;

    public Palette(String name, int[] rgb) {
        if (rgb.length != 16) throw new IllegalArgumentException("A palette needs 16 colors");
        this.name = name;
        this.rgb = Arrays.copyOf(rgb, 16);
    }

    public String name() { return name; }
    public int rgb(int i) { return rgb[i & 15]; }
    public int r(int i) { return (rgb[i & 15] >> 16) & 0xFF; }
    public int g(int i) { return (rgb[i & 15] >> 8) & 0xFF; }
    public int b(int i) { return rgb[i & 15] & 0xFF; }
    public int[] toArray() { return Arrays.copyOf(rgb, 16); }

    public Palette withColor(int index, int value) {
        int[] c = toArray();
        c[index] = value & 0xFFFFFF;
        return new Palette(name, c);
    }

    public Palette renamed(String newName) { return new Palette(newName, rgb); }

    @Override public String toString() { return name; }
}
