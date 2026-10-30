package de.mulej.core;

import de.mulej.palette.Palette;

import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * A C64 text screen: 40×25 characters (screen codes), one foreground colour per character (colour RAM),
 * one background colour ($D021) and a border colour ($D020) for the whole screen.
 */
public final class PetsciiImage {
    public static final int COLS = 40, ROWS = 25, CELLS = COLS * ROWS;

    public final int[] codes = new int[CELLS];
    public final int[] colours = new int[CELLS];
    public int background;
    public int border;
    public Charset charset = Charset.UPPER;

    /** 320×200 picture as the C64 shows it. */
    public BufferedImage render(Palette p) {
        BufferedImage out = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[320];
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 320; x++) {
                int cell = (y >> 3) * COLS + (x >> 3);
                row[x] = p.rgb(charset.pixel(codes[cell], x & 7, y & 7) ? colours[cell] : background);
            }
            out.setRGB(0, y, 320, 1, row, 0, 320);
        }
        return out;
    }

    /** Palette index of every pixel (320×200). */
    public int[] indices() {
        int[] idx = new int[320 * 200];
        for (int y = 0; y < 200; y++)
            for (int x = 0; x < 320; x++) {
                int cell = (y >> 3) * COLS + (x >> 3);
                idx[y * 320 + x] = charset.pixel(codes[cell], x & 7, y & 7) ? colours[cell] : background;
            }
        return idx;
    }

    /**
     * Colour check: screen codes 0–255, colours 0–15, and no cell shows more than its foreground colour plus the
     * shared background (always true for data built from characters – the check guards against broken data).
     * Returns the cells that break the rules.
     */
    public java.util.List<Integer> check() {
        java.util.List<Integer> bad = new java.util.ArrayList<>();
        for (int i = 0; i < CELLS; i++)
            if (codes[i] < 0 || codes[i] > 255 || colours[i] < 0 || colours[i] > 15) bad.add(i);
        if (background < 0 || background > 15) for (int i = 0; i < CELLS; i++) if (!bad.contains(i)) bad.add(i);
        return bad;
    }

    /** Number of different characters used. */
    public int distinctCodes() {
        return (int) Arrays.stream(codes).distinct().count();
    }

    /** Petmate workspace (version 2, one screen) – open it in Petmate to edit the picture further. */
    public String toPetmate(String name) {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("{\"version\":2,\"screens\":[0],\"framebufs\":[{");
        sb.append("\"width\":40,\"height\":25,");
        sb.append("\"backgroundColor\":").append(background & 15).append(',');
        sb.append("\"borderColor\":").append(border & 15).append(',');
        sb.append("\"charset\":\"").append(charset.petmateName).append("\",");
        sb.append("\"name\":\"").append(jsonEscape(name)).append("\",");
        sb.append("\"framebuf\":[");
        for (int r = 0; r < ROWS; r++) {
            sb.append(r == 0 ? "[" : ",[");
            for (int c = 0; c < COLS; c++) {
                int i = r * COLS + c;
                sb.append(c == 0 ? "" : ",").append("{\"code\":").append(codes[i]).append(",\"color\":").append(colours[i]).append('}');
            }
            sb.append(']');
        }
        sb.append("]}],\"customFonts\":{}}");
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : (s == null ? "" : s).toCharArray()) {
            if (ch == '"' || ch == '\\') sb.append('\\').append(ch);
            else if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
            else sb.append(ch);
        }
        return sb.toString();
    }

    /**
     * Reads the first screen of a Petmate workspace. Only the fields MuleJ writes are needed;
     * the parser is deliberately small and tolerant.
     */
    public static PetsciiImage fromPetmate(String json) {
        PetsciiImage p = new PetsciiImage();
        p.background = intField(json, "backgroundColor", 0);
        p.border = intField(json, "borderColor", p.background);
        String cs = stringField(json, "charset");
        p.charset = cs != null && cs.equalsIgnoreCase("lower") ? Charset.LOWER : Charset.UPPER;
        int pos = json.indexOf("\"framebuf\"");
        if (pos < 0) throw new IllegalArgumentException("No screen in the Petmate file.");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\{\\s*\"code\"\\s*:\\s*(\\d+)\\s*,\\s*\"color\"\\s*:\\s*(\\d+)\\s*}")
                .matcher(json);
        m.region(pos, json.length());
        for (int i = 0; i < CELLS; i++) {
            if (!m.find()) throw new IllegalArgumentException("The Petmate screen is smaller than 40×25.");
            p.codes[i] = Integer.parseInt(m.group(1)) & 255;
            p.colours[i] = Integer.parseInt(m.group(2)) & 15;
        }
        return p;
    }

    private static int intField(String json, String name, int def) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + name + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) & 15 : def;
    }

    private static String stringField(String json, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
