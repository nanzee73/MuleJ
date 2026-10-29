package de.mulej.core;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Reading, writing and small image helpers shared by all tools. */
public final class Images {
    private Images() {}

    /**
     * Reads a picture. Koala (.kla/.koa) and Art Studio (.art) files are drawn with the given palette,
     * everything else goes through ImageIO.
     */
    public static BufferedImage read(Path file, de.mulej.palette.Palette koalaPalette) throws IOException {
        if (isKoala(file)) return KoalaImage.read(Files.readAllBytes(file)).render(koalaPalette);
        if (isArtStudio(file)) return HiresImage.read(Files.readAllBytes(file)).render(koalaPalette);
        BufferedImage img = ImageIO.read(file.toFile());
        if (img == null) throw new IOException(file.getFileName() + " is no readable image file.");
        return toRgb(img);
    }

    public static boolean isArtStudio(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".art") || n.endsWith(".aas") || n.endsWith(".hpi");
    }

    /** A C64 bitmap file MuleJ can read (Koala or Art Studio); it is drawn with the selected palette. */
    public static boolean isC64File(Path file) { return isKoala(file) || isArtStudio(file); }

    public static boolean isKoala(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".kla") || n.endsWith(".koa");
    }

    /** Copy in TYPE_INT_RGB; transparent areas become white (like the old c64helpers). */
    public static BufferedImage toRgb(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, Color.WHITE, null);
        g.dispose();
        return out;
    }

    public static void writePng(BufferedImage img, Path file) throws IOException {
        if (!ImageIO.write(img, "png", file.toFile())) throw new IOException("No PNG writer available.");
    }

    /** "dir/name.ext" → "dir/name" + suffix. */
    public static Path sibling(Path file, String suffix) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        Path dir = file.toAbsolutePath().getParent();
        return dir.resolve(base + suffix);
    }
}
