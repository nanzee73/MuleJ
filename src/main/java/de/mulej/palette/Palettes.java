package de.mulej.palette;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Built-in palettes plus loading/saving of custom palettes.
 * The VICE palettes match the .vpl files shipped with VICE (data/C64).
 */
public final class Palettes {
    private Palettes() {}

    public static final Palette PEPTO = new Palette("Pepto (PAL)", new int[]{
            0x000000, 0xFFFFFF, 0x68372B, 0x70A4B2, 0x6F3D86, 0x588D43, 0x352879, 0xB8C76F,
            0x6F4F25, 0x433900, 0x9A6759, 0x444444, 0x6C6C6C, 0x9AD284, 0x6C5EB5, 0x959595});

    public static final Palette COLODORE = new Palette("Colodore", new int[]{
            0x000000, 0xFFFFFF, 0x813338, 0x75CEC8, 0x8E3C97, 0x56AC4D, 0x2E2C9B, 0xEDF171,
            0x8E5029, 0x553800, 0xC46C71, 0x4A4A4A, 0x7B7B7B, 0xA9FF9F, 0x706DEB, 0xB2B2B2});

    public static final Palette DEEKAY = new Palette("DeeKay", new int[]{
            0x000000, 0xFFFFFF, 0x882000, 0x68D0A8, 0xA838A0, 0x50B818, 0x181090, 0xF0E858,
            0xA04800, 0x472B1B, 0xC87870, 0x484848, 0x808080, 0x98FF98, 0x5090D0, 0xB8B8B8});

    public static final Palette PALETTE_OFFENCE = new Palette("PALette (PAL/Offence)", new int[]{
            0x000000, 0xD5D5D5, 0x72352C, 0x659FA6, 0x733A91, 0x568D35, 0x2E237D, 0xAEB75E,
            0x774F1E, 0x4B3C00, 0x9C635A, 0x474747, 0x6B6B6B, 0x8FC271, 0x675DB6, 0x8F8F8F});

    public static final Palette COMMUNITY = new Palette("Community Colors", new int[]{
            0x000000, 0xFFFFFF, 0xAF2A29, 0x62D8CC, 0xB03FB6, 0x4AC64A, 0x3739C4, 0xE4ED4E,
            0xB6591C, 0x683808, 0xEA746C, 0x4D4D4D, 0x848484, 0xA6FA9E, 0x707CE6, 0xB6B6B5});

    public static final Palette PTOING = new Palette("Ptoing", new int[]{
            0x000000, 0xFFFFFF, 0x8C3E34, 0x7ABFC7, 0x8D47B3, 0x68A941, 0x3E31A2, 0xD0DC71,
            0x905F25, 0x574200, 0xBB776D, 0x545454, 0x808080, 0xACEA88, 0x7C70DA, 0xABABAB});

    public static final Palette C64HQ = new Palette("C64HQ", new int[]{
            0x0A0A0A, 0xFFF8FF, 0x851F02, 0x65CDA8, 0xA73B9F, 0x4DAB19, 0x1A0C92, 0xEBE353,
            0xA94B02, 0x441E00, 0xD28074, 0x464646, 0x8B8B8B, 0x8EF68E, 0x4D91D1, 0xBABABA});

    public static final Palette CCS64 = new Palette("CCS64", new int[]{
            0x101010, 0xFFFFFF, 0xE04040, 0x60FFFF, 0xE060E0, 0x40E040, 0x4040E0, 0xFFFF40,
            0xE0A040, 0x9C7448, 0xFFA0A0, 0x545454, 0x888888, 0xA0FFA0, 0xA0A0FF, 0xC0C0C0});

    public static final Palette C64S = new Palette("C64S", new int[]{
            0x000000, 0xFCFCFC, 0xA80000, 0x54FCFC, 0xA800A8, 0x00A800, 0x0000A8, 0xFCFC00,
            0xA85400, 0x802C00, 0xFC5454, 0x545454, 0x808080, 0x54FC54, 0x5454FC, 0xA8A8A8});

    public static final Palette VICE_OLD = new Palette("VICE (old default)", new int[]{
            0x000000, 0xFDFEFC, 0xBE1A24, 0x30E6C6, 0xB41AE2, 0x1FD21E, 0x211BAE, 0xDFF60A,
            0xB84104, 0x6A3304, 0xFE4A57, 0x424540, 0x70746F, 0x59FE59, 0x5F53FE, 0xA4A7A2});

    /** All built-in palettes in display order. */
    public static List<Palette> builtIns() {
        return List.of(PEPTO, COLODORE, DEEKAY, PALETTE_OFFENCE, COMMUNITY, PTOING, C64HQ, CCS64, C64S, VICE_OLD);
    }

    /**
     * Loads a palette. Supports VICE (.vpl, "RR GG BB" per line), GIMP (.gpl, decimal "R G B name")
     * and plain hex lists (#RRGGBB or RRGGBB per line).
     */
    public static Palette load(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String fname = file.getFileName().toString();
        boolean gpl = fname.toLowerCase(Locale.ROOT).endsWith(".gpl")
                || (!lines.isEmpty() && lines.get(0).trim().startsWith("GIMP Palette"));
        List<Integer> colors = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#") && !line.matches("#[0-9a-fA-F]{6}\\b.*")) continue;
            if (gpl) {
                String[] p = line.split("\\s+");
                if (p.length >= 3 && p[0].matches("\\d+") && p[1].matches("\\d+") && p[2].matches("\\d+")) {
                    colors.add((Integer.parseInt(p[0]) << 16) | (Integer.parseInt(p[1]) << 8) | Integer.parseInt(p[2]));
                }
            } else if (line.matches("#?[0-9a-fA-F]{6}\\b.*")) {
                colors.add(Integer.parseInt(line.replace("#", "").substring(0, 6), 16));
            } else {
                String[] p = line.split("\\s+");
                if (p.length >= 3 && p[0].matches("[0-9a-fA-F]{1,2}") && p[1].matches("[0-9a-fA-F]{1,2}")
                        && p[2].matches("[0-9a-fA-F]{1,2}")) {
                    colors.add((Integer.parseInt(p[0], 16) << 16) | (Integer.parseInt(p[1], 16) << 8)
                            | Integer.parseInt(p[2], 16));
                }
            }
            if (colors.size() == 16) break;
        }
        if (colors.size() < 16) throw new IOException("File contains only " + colors.size() + " colors, 16 are required.");
        int[] arr = colors.stream().mapToInt(Integer::intValue).toArray();
        return new Palette(fname.replaceFirst("\\.[^.]+$", ""), arr);
    }

    /** Saves in VICE .vpl format. */
    public static void saveVpl(Palette p, Path file) throws IOException {
        StringBuilder sb = new StringBuilder("# VICE Palette file\n# Name: " + p.name() + "\n");
        for (int i = 0; i < 16; i++) sb.append(String.format("%02X %02X %02X 0%n", p.r(i), p.g(i), p.b(i)));
        Files.writeString(file, sb.toString());
    }
}
