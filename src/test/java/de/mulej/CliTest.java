package de.mulej;

import de.c64gfx.format.HiresImage;
import de.c64gfx.format.KoalaImage;

import de.c64gfx.format.HiresImage;
import de.c64gfx.format.KoalaImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CliTest {
    @TempDir Path dir;

    private Path picture(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, (x * 255 / w) << 16 | (y * 255 / h) << 8);
        Path p = dir.resolve("pic.png");
        ImageIO.write(img, "png", p.toFile());
        return p;
    }

    @Test
    void writesFilesLikeTheOldTools() throws Exception {
        Path in = picture(320, 200);
        assertEquals(0, Cli.run(new String[]{"palettize", "-if", in.toString()}));
        assertTrue(Files.exists(dir.resolve("pic_palettized.png")));
        assertEquals(0, Cli.run(new String[]{"pixelize", in.toString()}));
        assertTrue(Files.exists(dir.resolve("pic_pixelized_l2r.png")));
        assertTrue(Files.exists(dir.resolve("pic_pixelized_r2l.png")));
        assertEquals(0, Cli.run(new String[]{"koala", in.toString(), "--fix-clashes"}));
        assertEquals(KoalaImage.FILE_SIZE, Files.size(dir.resolve("pic.kla")));
        assertEquals(0, Cli.run(new String[]{"topng", dir.resolve("pic.kla").toString(), dir.resolve("back.png").toString()}));
        assertEquals(320, ImageIO.read(dir.resolve("back.png").toFile()).getWidth());
        assertEquals(0, Cli.run(new String[]{"hires", in.toString(), "--dither", "fs"}));
        assertEquals(HiresImage.FILE_SIZE, Files.size(dir.resolve("pic.art")));
        assertEquals(0, Cli.run(new String[]{"koala", in.toString(), "-o", dir.resolve("p.kla").toString(),
                "--fix-clashes", "--prg", dir.resolve("p.prg").toString()}));
        assertEquals(2 + 0x3F40 - 0x0801, Files.size(dir.resolve("p.prg")));
        assertEquals(0, Cli.run(new String[]{"check", dir.resolve("pic.kla").toString(), "--no-palettize"}));
        assertEquals(0, Cli.run(new String[]{"check", dir.resolve("pic.art").toString(), "--mode", "hires", "--no-palettize"}));
        Path noise = dir.resolve("noise.png");
        BufferedImage n = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        java.util.Random r = new java.util.Random(1);
        for (int y = 0; y < 200; y++) for (int x = 0; x < 320; x++) n.setRGB(x, y, r.nextInt(0x1000000));
        ImageIO.write(n, "png", noise.toFile());
        assertEquals(2, Cli.run(new String[]{"check", noise.toString()}));               // noise breaks the rules
        assertEquals(0, Cli.run(new String[]{"check", in.toString()}));                  // a smooth gradient does not
        assertEquals(0, Cli.run(new String[]{"palettize", in.toString(), "-o", dir.resolve("d.png").toString(),
                "--dither", "atkinson", "--strength", "60"}));
        assertEquals(0, Cli.run(new String[]{"petscii", in.toString(), "--contrast", "20", "--prg", dir.resolve("t.prg").toString()}));
        assertTrue(Files.readString(dir.resolve("pic.petmate")).startsWith("{\"version\":2"));
        assertEquals(0, Cli.run(new String[]{"topng", dir.resolve("pic.petmate").toString(), dir.resolve("t.png").toString()}));
        assertEquals(1, Cli.run(new String[]{"petscii", in.toString(), "--charset", "klingon"}));
    }

    @Test
    void exitCodes() throws Exception {
        assertEquals(1, Cli.run(new String[]{"frobnicate", "x.png"}));
        assertEquals(1, Cli.run(new String[]{"palettize"}));
        assertEquals(0, Cli.run(new String[]{"--help"}));
        Path big = picture(640, 400);
        assertEquals(2, Cli.run(new String[]{"koala", big.toString()}));                // wrong size
        assertEquals(0, Cli.run(new String[]{"koala", big.toString(), "--fit", "fill", "--fix-clashes"}));
        assertEquals(0, Cli.run(new String[]{"koala", big.toString(), "-o", dir.resolve("one.kla").toString(),
                "--fit", "none", "--dither", "bayer4"}));
        assertEquals(1, Cli.run(new String[]{"koala", big.toString(), "--dither", "plaid"}));
        assertEquals(2, Cli.run(new String[]{"palettize", dir.resolve("missing.png").toString()}));
    }
}
