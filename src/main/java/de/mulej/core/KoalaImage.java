package de.mulej.core;

import de.mulej.palette.Palette;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;

/**
 * Koala Painter picture: 8000 bytes bitmap, 1000 bytes screen RAM, 1000 bytes color RAM, 1 byte background,
 * stored with load address $6000 (10003 bytes).
 * <p>
 * Bit pairs: 00 = background ($D021), 01 = screen RAM high nibble, 10 = screen RAM low nibble, 11 = color RAM.
 */
public final class KoalaImage {
    public static final int COLS = 40, ROWS = 25, CELLS = COLS * ROWS;
    public static final int FILE_SIZE = 2 + 8000 + 1000 + 1000 + 1;
    public static final int LOAD_ADDRESS = 0x6000;

    public final byte[] bitmap = new byte[8000];
    public final byte[] screen = new byte[CELLS];
    public final byte[] colorRam = new byte[CELLS];
    public int background;

    /** File bytes including the load address $6000. */
    public byte[] toBytes() {
        byte[] out = new byte[FILE_SIZE];
        out[0] = (byte) (LOAD_ADDRESS & 0xFF);
        out[1] = (byte) (LOAD_ADDRESS >> 8);
        System.arraycopy(bitmap, 0, out, 2, 8000);
        System.arraycopy(screen, 0, out, 8002, CELLS);
        System.arraycopy(colorRam, 0, out, 9002, CELLS);
        out[10002] = (byte) (background & 15);
        return out;
    }

    /** Accepts files with load address (10003 bytes) and without (10001 bytes). */
    public static KoalaImage read(byte[] data) throws IOException {
        int off;
        if (data.length >= FILE_SIZE) off = 2;
        else if (data.length == FILE_SIZE - 2) off = 0;
        else throw new IOException("Not a Koala file: " + data.length + " bytes, expected " + FILE_SIZE + ".");
        KoalaImage k = new KoalaImage();
        System.arraycopy(data, off, k.bitmap, 0, 8000);
        System.arraycopy(data, off + 8000, k.screen, 0, CELLS);
        System.arraycopy(data, off + 9000, k.colorRam, 0, CELLS);
        k.background = data[off + 10000] & 15;
        return k;
    }

    /** Palette index of multicolor pixel (x 0..159, y 0..199). */
    public int pixel(int x, int y) {
        int cell = (y >> 3) * COLS + (x >> 2);
        int b = bitmap[cell * 8 + (y & 7)] & 0xFF;
        int pair = (b >> (6 - 2 * (x & 3))) & 3;
        return switch (pair) {
            case 0 -> background;
            case 1 -> (screen[cell] >> 4) & 15;
            case 2 -> screen[cell] & 15;
            default -> colorRam[cell] & 15;
        };
    }

    /** 160×200 index map. */
    public int[] indices() {
        int[] idx = new int[160 * 200];
        for (int y = 0; y < 200; y++) for (int x = 0; x < 160; x++) idx[y * 160 + x] = pixel(x, y);
        return idx;
    }

    /** 320×200 picture, every multicolor pixel drawn two pixels wide. */
    public BufferedImage render(Palette p) {
        BufferedImage out = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[320];
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 160; x++) row[2 * x] = row[2 * x + 1] = p.rgb(pixel(x, y));
            out.setRGB(0, y, 320, 1, row, 0, 320);
        }
        return out;
    }

    @Override public boolean equals(Object o) {
        return o instanceof KoalaImage k && Arrays.equals(toBytes(), k.toBytes());
    }

    @Override public int hashCode() { return Arrays.hashCode(toBytes()); }
}
