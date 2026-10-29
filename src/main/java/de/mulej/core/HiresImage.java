package de.mulej.core;

import de.mulej.palette.Palette;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Hires bitmap in OCP Art Studio format: load address $2000, 8000 bytes bitmap (cell by cell), 1000 bytes screen RAM,
 * 7 bytes filler (the first one is used as border colour by some programs) – 9009 bytes.
 * <p>
 * Screen RAM: high nibble = colour of set bits, low nibble = colour of cleared bits.
 */
public final class HiresImage {
    public static final int LOAD_ADDRESS = 0x2000;
    public static final int FILE_SIZE = 2 + 8000 + 1000 + 7;

    public final byte[] bitmap = new byte[8000];
    public final byte[] screen = new byte[C64Mode.CELLS];
    public int border;

    public byte[] toBytes() {
        byte[] out = new byte[FILE_SIZE];
        out[0] = (byte) (LOAD_ADDRESS & 0xFF);
        out[1] = (byte) (LOAD_ADDRESS >> 8);
        System.arraycopy(bitmap, 0, out, 2, 8000);
        System.arraycopy(screen, 0, out, 8002, C64Mode.CELLS);
        out[9002] = (byte) (border & 15);
        return out;
    }

    /** Accepts 9002–9009 bytes with load address (the filler may be missing) and 9000+ bytes without. */
    public static HiresImage read(byte[] data) throws IOException {
        int off;
        if (data.length >= 9002 && (data[0] & 0xFF) == 0x00 && (data[1] & 0xFF) == 0x20) off = 2;
        else if (data.length >= 9000 && data.length < 9002) off = 0;
        else throw new IOException("Not an Art Studio file: " + data.length + " bytes, expected " + FILE_SIZE + ".");
        HiresImage h = new HiresImage();
        System.arraycopy(data, off, h.bitmap, 0, 8000);
        System.arraycopy(data, off + 8000, h.screen, 0, C64Mode.CELLS);
        h.border = off + 9000 < data.length ? data[off + 9000] & 15 : 0;
        return h;
    }

    public int pixel(int x, int y) {
        int cell = (y >> 3) * C64Mode.COLS + (x >> 3);
        int bit = (bitmap[cell * 8 + (y & 7)] >> (7 - (x & 7))) & 1;
        return bit == 1 ? (screen[cell] >> 4) & 15 : screen[cell] & 15;
    }

    public int[] indices() {
        int[] idx = new int[320 * 200];
        for (int y = 0; y < 200; y++) for (int x = 0; x < 320; x++) idx[y * 320 + x] = pixel(x, y);
        return idx;
    }

    public BufferedImage render(Palette p) {
        return Palettizer.render(indices(), 320, 200, p);
    }

    /** Cell colours sorted by colour index: the lowest goes to the cleared bits (low nibble), the second to set bits. */
    static HiresImage encode(int[] idx) {
        HiresImage h = new HiresImage();
        for (int cell = 0; cell < C64Mode.CELLS; cell++) {
            int cx = (cell % C64Mode.COLS) * 8, cy = (cell / C64Mode.COLS) * 8;
            boolean[] used = new boolean[16];
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) used[idx[(cy + y) * 320 + cx + x]] = true;
            int lo = -1, hi = -1;
            for (int i = 0; i < 16; i++) {
                if (!used[i]) continue;
                if (lo < 0) lo = i;
                else if (hi < 0) hi = i;
            }
            if (hi < 0) hi = 0;
            h.screen[cell] = (byte) ((hi << 4) | lo);
            for (int y = 0; y < 8; y++) {
                int b = 0;
                for (int x = 0; x < 8; x++) b = (b << 1) | (idx[(cy + y) * 320 + cx + x] == lo ? 0 : 1);
                h.bitmap[cell * 8 + y] = (byte) b;
            }
        }
        return h;
    }
}
