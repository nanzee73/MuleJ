package de.mulej.core;

import de.c64gfx.palette.Palette;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * A picture as character set + screen (+ optional meta tiles): the raw material for demo effects.
 * <ul>
 *   <li>hires characters: every cell shows its character in its own colour (colour RAM 0–15) on the shared
 *       background ($D021);</li>
 *   <li>multicolour characters: pixel values %00 = background ($D021), %01 = $D022, %10 = $D023,
 *       %11 = the cell colour (colour RAM 8–15, i.e. colour 0–7 with bit 3 set).</li>
 * </ul>
 * With meta tiles of n×n characters the screen is described as a map of tile numbers; the map has
 * ⌈40/n⌉×⌈25/n⌉ entries, cells outside the screen are filled with the blank character (code 0).
 */
public final class CharsetImage {
    public static final int COLS = 40, ROWS = 25, CELLS = COLS * ROWS;

    public boolean multicolor;
    public int background, mc1, mc2, border;
    /** 8 bytes per character. */
    public byte[][] chars = new byte[0][];
    /** Character code per cell. */
    public final int[] screen = new int[CELLS];
    /** Colour RAM value per cell (multicolour: 8–15). */
    public final int[] colours = new int[CELLS];
    /** Number of different characters the picture had before they were merged. */
    public int uniqueBefore;

    /** Edge length of the meta tiles in characters (1 = no tiles). */
    public int tileSize = 1;
    public int mapCols = COLS, mapRows = ROWS;
    /** Per tile n×n character codes (row by row) and colour RAM values. */
    public int[][] tiles = new int[0][], tileColours = new int[0][];
    /** Tile number per map position. */
    public int[] map = new int[0];

    public int charCount() { return chars.length; }

    public int tileCount() { return tiles.length; }

    /** The tile map needs two bytes per entry (more than 256 tiles). */
    public boolean map16() { return tiles.length > 256; }

    /** Pixel value (0/1 hires, 0–3 multicolour) of character {@code c} at x (0..7 hires, 0..3 multicolour), y. */
    public int value(int c, int x, int y) {
        int b = chars[c][y] & 0xFF;
        return multicolor ? (b >> (6 - 2 * x)) & 3 : (b >> (7 - x)) & 1;
    }

    private int colourOf(int value, int cellColour) {
        if (!multicolor) return value == 0 ? background : cellColour & 15;
        return switch (value) {
            case 0 -> background;
            case 1 -> mc1;
            case 2 -> mc2;
            default -> cellColour & 7;
        };
    }

    /** The screen as the C64 shows it (320×200; multicolour pixels are two pixels wide). */
    public BufferedImage render(Palette p) {
        BufferedImage img = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        for (int cell = 0; cell < CELLS; cell++) {
            int cx = (cell % COLS) * 8, cy = (cell / COLS) * 8;
            drawChar(img, p, screen[cell], colours[cell], cx, cy);
        }
        return img;
    }

    private void drawChar(BufferedImage img, Palette p, int code, int colour, int px, int py) {
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++) {
                int v = multicolor ? value(code, x >> 1, y) : value(code, x, y);
                img.setRGB(px + x, py + y, p.rgb(colourOf(v, colour)));
            }
    }

    /**
     * The character set as picture: 16 characters per row, each in the colour of the first cell that uses it
     * (unused: white), on the background.
     */
    public BufferedImage renderCharset(Palette p) {
        int rows = Math.max(1, (chars.length + 15) / 16);
        BufferedImage img = new BufferedImage(128, rows * 8, BufferedImage.TYPE_INT_RGB);
        int[] first = new int[chars.length];
        java.util.Arrays.fill(first, multicolor ? 9 : 1);
        boolean[] seen = new boolean[chars.length];
        for (int cell = 0; cell < CELLS; cell++)
            if (!seen[screen[cell]]) { seen[screen[cell]] = true; first[screen[cell]] = colours[cell]; }
        for (int i = 0; i < rows * 16; i++) {
            int px = (i % 16) * 8, py = (i / 16) * 8;
            if (i < chars.length) drawChar(img, p, i, first[i], px, py);
            else for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) img.setRGB(px + x, py + y, p.rgb(background));
        }
        return img;
    }

    // ------------------------------------------------------------------ binary data

    public byte[] charsetBytes() {
        byte[] b = new byte[chars.length * 8];
        for (int i = 0; i < chars.length; i++) System.arraycopy(chars[i], 0, b, i * 8, 8);
        return b;
    }

    public byte[] screenBytes() { return bytes(screen); }

    public byte[] colourBytes() { return bytes(colours); }

    public byte[] tileBytes() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int[] t : tiles) for (int v : t) o.write(v);
        return o.toByteArray();
    }

    public byte[] tileColourBytes() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int[] t : tileColours) for (int v : t) o.write(v);
        return o.toByteArray();
    }

    /** Tile numbers row by row; with more than 256 tiles two bytes each (low, high). */
    public byte[] mapBytes() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int v : map) {
            o.write(v & 0xFF);
            if (map16()) o.write(v >> 8);
        }
        return o.toByteArray();
    }

    private static byte[] bytes(int[] v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    // ------------------------------------------------------------------ KickAssembler

    /**
     * Base name that is safe inside KickAssembler source and as file name: only letters, digits, '.', '_' and '-'
     * (a quote or line break in a file name could otherwise inject assembler directives).
     */
    public static String safeName(String base) {
        String s = base == null ? "" : base.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^\\.+", "_");
        return s.isEmpty() ? "charset" : s;
    }

    /** File names of the binary parts for a base name. */
    public record Files(String charset, String screen, String colours, String tiles, String tileColours, String map) {
        public static Files of(String base) {
            base = safeName(base);
            return new Files(base + "_charset.bin", base + "_screen.bin", base + "_colours.bin",
                    base + "_tiles.bin", base + "_tilecolours.bin", base + "_map.bin");
        }
    }

    /** KickAssembler source (plain ASCII) that defines the constants and imports the binary parts. */
    public String kickAssembler(String base) {
        base = safeName(base);
        Files f = Files.of(base);
        StringBuilder s = new StringBuilder();
        s.append("// ").append(base).append(" - character set exported by MuleJ (KickAssembler)\n");
        s.append("//\n");
        s.append("// Show the screen (VIC bank with the charset, screen RAM at SCREEN_RAM):\n");
        s.append("//   lda #BACKGROUND ; sta $d021").append(multicolor ? " ; lda #MC1 ; sta $d022 ; lda #MC2 ; sta $d023" : "").append('\n');
        s.append("//   lda #").append(multicolor ? "$d8" : "$c8").append(" ; sta $d016").append(multicolor ? "   // multicolour on" : "").append('\n');
        s.append("//   lda #[[SCREEN_RAM & $3c00] >> 6] | [[charset & $3800] >> 10] ; sta $d018\n");
        s.append("//   copy screen -> SCREEN_RAM and colours -> $d800 (1000 bytes each)\n\n");
        s.append(String.format(Locale.ROOT, ".const CHAR_COUNT  = %d%n", chars.length));
        s.append(String.format(Locale.ROOT, ".const MULTICOLOR  = %d%n", multicolor ? 1 : 0));
        s.append(String.format(Locale.ROOT, ".const BACKGROUND  = $%02x   // $d021%n", background));
        if (multicolor) {
            s.append(String.format(Locale.ROOT, ".const MC1         = $%02x   // $d022%n", mc1));
            s.append(String.format(Locale.ROOT, ".const MC2         = $%02x   // $d023%n", mc2));
        }
        s.append(String.format(Locale.ROOT, ".const BORDER      = $%02x   // $d020%n", border));
        s.append(".const SCREEN_COLS = 40\n.const SCREEN_ROWS = 25\n");
        if (tileSize > 1) {
            s.append(String.format(Locale.ROOT, ".const TILE_SIZE   = %d     // %dx%d characters per tile%n", tileSize, tileSize, tileSize));
            s.append(String.format(Locale.ROOT, ".const TILE_COUNT  = %d%n", tiles.length));
            s.append(String.format(Locale.ROOT, ".const MAP_COLS    = %d%n", mapCols));
            s.append(String.format(Locale.ROOT, ".const MAP_ROWS    = %d%n", mapRows));
            s.append(String.format(Locale.ROOT, ".const MAP_16BIT   = %d     // 1: two bytes per entry (lo, hi)%n", map16() ? 1 : 0));
        }
        s.append("\n.align $800   // the VIC needs the character set on a 2 KB boundary\n");
        s.append("charset:     .import binary \"").append(f.charset()).append("\"   // CHAR_COUNT x 8 bytes\n");
        s.append("screen:      .import binary \"").append(f.screen()).append("\"   // 40x25 character codes\n");
        s.append("colours:     .import binary \"").append(f.colours()).append("\"   // 40x25 colour RAM values\n");
        if (tileSize > 1) {
            s.append("tiles:       .import binary \"").append(f.tiles()).append("\"   // per tile TILE_SIZE^2 codes, row by row\n");
            s.append("tilecolours: .import binary \"").append(f.tileColours()).append("\"   // per tile TILE_SIZE^2 colour RAM values\n");
            s.append("map:         .import binary \"").append(f.map()).append("\"   // MAP_COLS x MAP_ROWS tile numbers\n");
        }
        return s.toString();
    }
}
