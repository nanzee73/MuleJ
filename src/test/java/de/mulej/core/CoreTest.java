package de.mulej.core;

import de.mulej.color.ColorMatcher;
import de.mulej.palette.Palette;
import de.mulej.palette.Palettes;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    private static final Palette COLODORE = Palettes.COLODORE;

    /** A valid multicolor picture: per 4×8 cell the background plus at most 3 random colours, as 160×200 indices. */
    static int[] randomMulticolor(long seed, int bg) {
        Random r = new Random(seed);
        int[] idx = new int[160 * 200];
        for (int cell = 0; cell < 1000; cell++) {
            int[] cols = {bg, r.nextInt(16), r.nextInt(16), r.nextInt(16)};
            int cx = (cell % 40) * 4, cy = (cell / 40) * 8;
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 4; x++)
                    idx[(cy + y) * 160 + cx + x] = r.nextInt(3) == 0 ? bg : cols[r.nextInt(4)];
        }
        return idx;
    }

    static BufferedImage wide(int[] idx160, Palette p) {
        int[] doubled = new int[320 * 200];
        for (int i = 0; i < idx160.length; i++) doubled[2 * i] = doubled[2 * i + 1] = idx160[i];
        return Palettizer.render(doubled, 320, 200, p);
    }

    @Test
    void palettizeKeepsPaletteColours() {
        BufferedImage img = new BufferedImage(16, 1, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < 16; i++) img.setRGB(i, 0, COLODORE.rgb(i));
        BufferedImage out = Palettizer.palettize(img, COLODORE, ColorMatcher.Space.EUCLID);
        for (int i = 0; i < 16; i++) assertEquals(COLODORE.rgb(i), out.getRGB(i, 0) & 0xFFFFFF);
    }

    @Test
    void palettizeUsesPlainEuclideanDistance() {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0x101010);
        assertEquals(0x000000, Palettizer.palettize(img, COLODORE, ColorMatcher.Space.EUCLID).getRGB(0, 0) & 0xFFFFFF);
        img.setRGB(0, 0, 0xF0F0F0);
        assertEquals(0xFFFFFF, Palettizer.palettize(img, COLODORE, ColorMatcher.Space.EUCLID).getRGB(0, 0) & 0xFFFFFF);
    }

    @Test
    void pixelizeDirections() {
        BufferedImage img = new BufferedImage(5, 1, BufferedImage.TYPE_INT_RGB);
        int[] v = {0x000010, 0x000020, 0x000030, 0x000040, 0x000050};
        img.setRGB(0, 0, 5, 1, v, 0, 5);
        assertArrayEquals(new int[]{0x10, 0x10, 0x30, 0x30, 0x50}, row(Pixelizer.pixelize(img, Pixelizer.Direction.L2R)));
        assertArrayEquals(new int[]{0x20, 0x20, 0x40, 0x40, 0x50}, row(Pixelizer.pixelize(img, Pixelizer.Direction.R2L)));
        assertArrayEquals(new int[]{0x18, 0x18, 0x38, 0x38, 0x50}, row(Pixelizer.pixelize(img, Pixelizer.Direction.AVERAGE)));
        assertEquals(2, Pixelizer.halfWidth(img, Pixelizer.Direction.L2R).getWidth());
    }

    private static int[] row(BufferedImage img) {
        int[] r = new int[img.getWidth()];
        for (int x = 0; x < r.length; x++) r[x] = img.getRGB(x, 0) & 0xFFFFFF;
        return r;
    }

    @Test
    void koalaRoundTripIsLossless() throws Exception {
        for (int bg : new int[]{0, 6, 15}) {
            int[] idx = randomMulticolor(42 + bg, bg);
            KoalaConverter.Result r = KoalaConverter.convert(wide(idx, COLODORE),
                    new KoalaConverter.Settings(COLODORE, ColorMatcher.Space.EUCLID, Pixelizer.Direction.L2R, false, bg, false));
            assertTrue(r.ok());
            assertTrue(r.clashCells().isEmpty());
            assertArrayEquals(idx, r.koala().indices());
            byte[] file = r.koala().toBytes();
            assertEquals(10003, file.length);
            assertEquals(0x00, file[0]);
            assertEquals(0x60, file[1]);
            assertEquals(bg, file[10002]);
            assertEquals(r.koala(), KoalaImage.read(file));
            // 160×200 input gives the same file
            KoalaConverter.Result half = KoalaConverter.convert(Palettizer.render(idx, 160, 200, COLODORE),
                    new KoalaConverter.Settings(COLODORE, ColorMatcher.Space.EUCLID, Pixelizer.Direction.L2R, false, bg, false));
            assertArrayEquals(file, half.koala().toBytes());
        }
    }

    @Test
    void koalaEncodingMatchesOldTool() throws Exception {
        // one cell: background 0, colours 2, 5, 7 → screen = $52, colour RAM = 7, bits 10/01/11 by colour index
        int[] idx = new int[160 * 200];
        idx[0] = 2; idx[1] = 5; idx[2] = 7; idx[3] = 0;
        KoalaImage k = KoalaConverter.encode(idx, 0);
        assertEquals(0x52, k.screen[0] & 0xFF);
        assertEquals(7, k.colorRam[0]);
        assertEquals(0b10_01_11_00, k.bitmap[0] & 0xFF);
    }

    @Test
    void clashesAreReportedAndRepaired() throws Exception {
        int[] idx = randomMulticolor(7, 0);
        // put 5 colours into cell 3 (x 12..15, y 0..7)
        int[] five = {1, 2, 3, 4, 5};
        for (int i = 0; i < 5; i++) idx[(i % 8) * 160 + 12 + i % 4] = five[i];
        BufferedImage img = wide(idx, COLODORE);
        KoalaConverter.Settings s = new KoalaConverter.Settings(COLODORE, ColorMatcher.Space.EUCLID,
                Pixelizer.Direction.L2R, true, 0, false);
        KoalaConverter.Result bad = KoalaConverter.convert(img, s);
        assertFalse(bad.ok());
        assertTrue(bad.clashCells().contains(3));

        KoalaConverter.Result fixed = KoalaConverter.convert(img, new KoalaConverter.Settings(COLODORE,
                ColorMatcher.Space.EUCLID, Pixelizer.Direction.L2R, true, 0, true));
        assertTrue(fixed.ok());
        assertEquals(bad.clashCells(), fixed.clashCells());
        Set<Integer> colours = new HashSet<>();
        for (int y = 0; y < 8; y++) for (int x = 12; x < 16; x++) colours.add(fixed.koala().pixel(x, y));
        colours.remove(0);
        assertTrue(colours.size() <= 3, "cell 3 still has " + colours);
    }

    @Test
    void wrongSizeAndForeignColoursAreRejected() {
        KoalaConverter.Settings s = KoalaConverter.Settings.defaults(COLODORE);
        assertThrows(KoalaConverter.ConversionException.class,
                () -> KoalaConverter.convert(new BufferedImage(321, 200, BufferedImage.TYPE_INT_RGB), s));
        BufferedImage img = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        img.setRGB(10, 10, 0x123456);
        KoalaConverter.Settings exact = new KoalaConverter.Settings(COLODORE, ColorMatcher.Space.EUCLID,
                Pixelizer.Direction.L2R, false, -1, false);
        assertThrows(KoalaConverter.ConversionException.class, () -> KoalaConverter.convert(img, exact));
    }

    @Test
    void backgroundIsMostCommonColour() {
        int[] idx = new int[100];
        for (int i = 0; i < 60; i++) idx[i] = 6;
        assertEquals(6, KoalaConverter.mostCommon(idx));
        assertEquals(0, KoalaConverter.mostCommon(new int[]{0, 0, 3, 3}));   // tie: lower index
    }
}
