package de.mulej;

import de.mulej.core.KoalaImage;
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
    }

    @Test
    void exitCodes() throws Exception {
        assertEquals(1, Cli.run(new String[]{"frobnicate", "x.png"}));
        assertEquals(1, Cli.run(new String[]{"palettize"}));
        assertEquals(0, Cli.run(new String[]{"--help"}));
        Path big = picture(640, 400);
        assertEquals(2, Cli.run(new String[]{"koala", big.toString()}));                // wrong size
        assertEquals(0, Cli.run(new String[]{"koala", big.toString(), "--fit", "fill", "--fix-clashes"}));
        assertEquals(2, Cli.run(new String[]{"palettize", dir.resolve("missing.png").toString()}));
    }
}
