package de.mulej.core;

import de.c64gfx.dither.Dither;
import de.c64gfx.dither.Ditherer;
import de.c64gfx.format.HiresImage;
import de.c64gfx.format.KoalaImage;

import de.c64gfx.color.ColorMatcher;
import de.c64gfx.swing.ImageFitter;
import de.c64gfx.palette.Palette;
import de.c64gfx.palette.Palettes;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    private static final Palette COLODORE = Palettes.COLODORE;

    /** A valid picture for the mode: per cell at most the allowed colours (plus background), in mode pixels. */
    static int[] randomValid(C64Mode mode, long seed, int bg) {
        Random r = new Random(seed);
        int[] idx = new int[mode.width * 200];
        for (int cell = 0; cell < C64Mode.CELLS; cell++) {
            int[] cols = new int[mode.coloursPerCell + (bg >= 0 ? 1 : 0)];
            for (int i = 0; i < cols.length; i++) cols[i] = r.nextInt(16);
            if (bg >= 0) cols[cols.length - 1] = bg;
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < mode.cellW; x++)
                    idx[(mode.cellY(cell) + y) * mode.width + mode.cellX(cell) + x] = cols[r.nextInt(cols.length)];
        }
        return idx;
    }

    /** 320×200 picture of mode pixels. */
    static BufferedImage wide(C64Mode mode, int[] idx, Palette p) {
        int f = mode.pixelWidth();
        int[] w = new int[320 * 200];
        for (int i = 0; i < w.length; i++) w[i] = idx[(i / 320) * mode.width + (i % 320) / f];
        return Palettizer.render(w, 320, 200, p);
    }

    static BitmapConverter.Settings exact(C64Mode mode, int bg) {
        return new BitmapConverter.Settings(mode, COLODORE, ColorMatcher.Space.EUCLID, Pixelizer.Direction.L2R,
                false, bg, false, Ditherer.Settings.NONE);
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
    void ditheringMixesColours() {
        // a flat grey between black and dark grey: without dithering one colour, with dithering a mix
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x252525));
        g.fillRect(0, 0, 64, 64);
        g.dispose();
        Set<Integer> plain = colours(Palettizer.palettize(img, COLODORE, ColorMatcher.Space.EUCLID));
        Set<Integer> dithered = colours(Palettizer.palettize(img, COLODORE, ColorMatcher.Space.EUCLID,
                new Ditherer.Settings(Dither.FLOYD_STEINBERG, 100, false, true)));
        assertEquals(1, plain.size());
        assertTrue(dithered.size() >= 2, "dithered colours: " + dithered);
        Set<Integer> bayer = colours(Palettizer.palettize(img, COLODORE, ColorMatcher.Space.EUCLID,
                new Ditherer.Settings(Dither.BAYER4, 100, false, true)));
        assertTrue(bayer.size() >= 2);
    }

    private static Set<Integer> colours(BufferedImage img) {
        Set<Integer> s = new HashSet<>();
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) s.add(img.getRGB(x, y) & 0xFFFFFF);
        return s;
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
            int[] idx = randomValid(C64Mode.MULTICOLOR, 42 + bg, bg);
            BitmapConverter.Result r = BitmapConverter.convert(wide(C64Mode.MULTICOLOR, idx, COLODORE),
                    exact(C64Mode.MULTICOLOR, bg));
            assertTrue(r.ok());
            assertArrayEquals(idx, r.koala().indices());
            byte[] file = r.fileBytes();
            assertEquals(10003, file.length);
            assertEquals(0x00, file[0]);
            assertEquals(0x60, file[1]);
            assertEquals(bg, file[10002]);
            assertEquals(r.koala(), KoalaImage.read(file));
            // 160×200 input gives the same file
            BitmapConverter.Result half = BitmapConverter.convert(Palettizer.render(idx, 160, 200, COLODORE),
                    exact(C64Mode.MULTICOLOR, bg));
            assertArrayEquals(file, half.fileBytes());
        }
    }

    @Test
    void hiresRoundTripIsLossless() throws Exception {
        int[] idx = randomValid(C64Mode.HIRES, 99, -1);
        BitmapConverter.Result r = BitmapConverter.convert(wide(C64Mode.HIRES, idx, COLODORE), exact(C64Mode.HIRES, -1));
        assertTrue(r.ok(), "clashes: " + r.clashCells());
        assertNull(r.koala());
        byte[] file = r.fileBytes();
        assertEquals(9009, file.length);
        assertEquals(0x00, file[0]);
        assertEquals(0x20, file[1]);
        assertArrayEquals(idx, HiresImage.read(file).indices());
    }

    @Test
    void koalaEncodingMatchesOldTool() {
        // one cell: background 0, colours 2, 5, 7 → screen = $52, colour RAM = 7, bits 10/01/11 by colour index
        int[] idx = new int[160 * 200];
        idx[0] = 2; idx[1] = 5; idx[2] = 7; idx[3] = 0;
        KoalaImage k = KoalaImage.encode(idx, 0);
        assertEquals(0x52, k.screen[0] & 0xFF);
        assertEquals(7, k.colorRam[0]);
        assertEquals(0b10_01_11_00, k.bitmap[0] & 0xFF);
    }

    /** Valid multicolor picture with 5 colours in cell 3 (x 12..15, y 0..7). */
    private static int[] withClash() {
        int[] idx = randomValid(C64Mode.MULTICOLOR, 7, 0);
        int[] five = {1, 2, 3, 4, 5};
        for (int i = 0; i < 5; i++) idx[i * 160 + 12 + i % 4] = five[i];
        return idx;
    }

    private static Set<Integer> cellColours(BitmapConverter.Result r, int cell, int bg) {
        Set<Integer> s = new HashSet<>();
        int[] c = r.cellCounts(cell);
        for (int i = 0; i < 16; i++) if (c[i] > 0 && i != bg) s.add(i);
        return s;
    }

    @Test
    void checkReportsAndAutomaticCorrectionRepairs() throws Exception {
        BufferedImage img = wide(C64Mode.MULTICOLOR, withClash(), COLODORE);
        BitmapConverter.Settings s = new BitmapConverter.Settings(C64Mode.MULTICOLOR, COLODORE,
                ColorMatcher.Space.EUCLID, Pixelizer.Direction.L2R, true, 0, false, Ditherer.Settings.NONE);
        BitmapConverter.Result bad = BitmapConverter.convert(img, s);
        assertFalse(bad.ok());
        assertTrue(bad.clashCells().contains(3));
        assertNull(bad.fileBytes());

        BitmapConverter.Result fixed = BitmapConverter.convert(img, s.withFix(true));
        assertTrue(fixed.ok());
        assertTrue(fixed.fixedCells().contains(3));
        assertTrue(cellColours(fixed, 3, 0).size() <= 3);
    }

    @Test
    void manualCorrections() throws Exception {
        BufferedImage img = wide(C64Mode.MULTICOLOR, withClash(), COLODORE);
        BitmapConverter.Settings s = exact(C64Mode.MULTICOLOR, 0);
        // cell 3 may only use red and cyan
        BitmapConverter.Result r = BitmapConverter.convert(img, s,
                new BitmapConverter.Edits(Map.of(3, new int[]{2, 3}), Map.of()));
        assertTrue(r.ok());
        assertTrue(Set.of(2, 3).containsAll(cellColours(r, 3, 0)));
        // painting a fourth colour into a full cell breaks the rules again
        int[] valid = randomValid(C64Mode.MULTICOLOR, 5, 0);
        BufferedImage ok = wide(C64Mode.MULTICOLOR, valid, COLODORE);
        int cell = 0;
        Set<Integer> used = cellColours(BitmapConverter.convert(ok, s), cell, 0);
        int extra = 1;
        while (used.contains(extra) || extra == 0) extra++;
        Map<Integer, Integer> paint = new java.util.HashMap<>();
        // three pixels of the cell get colours that are not used yet → at least 4 colours
        for (int i = 0, c = extra; i < 3; i++, c++) {
            while (used.contains(c) || c == 0) c++;
            paint.put(i, c);
        }
        BitmapConverter.Result painted = BitmapConverter.convert(ok, s, new BitmapConverter.Edits(Map.of(), paint));
        assertFalse(painted.ok());
        assertEquals(java.util.List.of(cell), painted.clashCells());
    }

    @Test
    void ditheredBitmapsAlwaysKeepTheRules() throws Exception {
        BufferedImage img = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(3);
        for (int y = 0; y < 200; y++)
            for (int x = 0; x < 320; x++) img.setRGB(x, y, (x * 255 / 320) << 16 | (y * 255 / 200) << 8 | r.nextInt(256));
        for (C64Mode mode : C64Mode.values()) {
            for (Dither d : new Dither[]{Dither.FLOYD_STEINBERG, Dither.BAYER4}) {
                BitmapConverter.Settings s = new BitmapConverter.Settings(mode, COLODORE, ColorMatcher.Space.YUV,
                        Pixelizer.Direction.AVERAGE, true, -1, false, new Ditherer.Settings(d, 80, true, false));
                BitmapConverter.Result res = BitmapConverter.convert(img, s);
                assertTrue(res.ok(), mode + " " + d + ": " + res.clashCells().size());
                assertTrue(BitmapConverter.check(mode, res.indices(), res.background()).isEmpty());
            }
        }
    }

    @Test
    void wrongSizeAndForeignColoursAreRejected() {
        BitmapConverter.Settings s = BitmapConverter.Settings.defaults(C64Mode.MULTICOLOR, COLODORE);
        assertThrows(BitmapConverter.ConversionException.class,
                () -> BitmapConverter.convert(new BufferedImage(321, 200, BufferedImage.TYPE_INT_RGB), s));
        BufferedImage img = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        img.setRGB(10, 10, 0x123456);
        assertThrows(BitmapConverter.ConversionException.class, () -> BitmapConverter.convert(img, exact(C64Mode.HIRES, -1)));
    }

    @Test
    void backgroundIsMostCommonColour() {
        int[] idx = new int[100];
        for (int i = 0; i < 60; i++) idx[i] = 6;
        assertEquals(6, BitmapConverter.mostCommon(idx));
        assertEquals(0, BitmapConverter.mostCommon(new int[]{0, 0, 3, 3}));   // tie: lower index
    }

    @Test
    void fitterOneToOneAndUpscaling() {
        BufferedImage big = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        big.setRGB(40, 50, 0xFF0000);
        ImageFitter.Settings one = new ImageFitter.Settings(new Rectangle(40, 50, 320, 200), ImageFitter.ScaleMode.NONE,
                ImageFitter.Filter.NEAREST, ImageFitter.Aspect.SQUARE, Color.BLACK);
        BufferedImage f = ImageFitter.fit(big, one);
        assertEquals(320, f.getWidth());
        assertEquals(0xFF0000, f.getRGB(0, 0) & 0xFFFFFF);          // exactly 1:1, no scaling

        // too small for 1:1: the rest gets the border colour
        BufferedImage small = new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, 100, 50); g.dispose();
        BufferedImage s1 = ImageFitter.fit(small, ImageFitter.Settings.oneToOne(small));
        assertEquals(0x000000, s1.getRGB(0, 0) & 0xFFFFFF);
        assertEquals(0xFFFFFF, s1.getRGB(160, 100) & 0xFFFFFF);

        // whole-number upscale: 160×100 → exactly 2×, crisp
        BufferedImage px = new BufferedImage(160, 100, BufferedImage.TYPE_INT_RGB);
        px.setRGB(1, 1, 0x00FF00);
        ImageFitter.Settings sug = ImageFitter.Settings.suggested(px);
        assertEquals(ImageFitter.Filter.NEAREST, sug.filter());
        BufferedImage up = ImageFitter.fit(px, sug);
        assertEquals(0x00FF00, up.getRGB(2, 2) & 0xFFFFFF);
        assertEquals(0x00FF00, up.getRGB(3, 3) & 0xFFFFFF);
        assertEquals(0x000000, up.getRGB(4, 2) & 0xFFFFFF);

        // bigger pictures: a frame with the target aspect, scaled down
        ImageFitter.Settings bigSug = ImageFitter.Settings.suggested(new BufferedImage(1600, 1600, BufferedImage.TYPE_INT_RGB));
        assertEquals(ImageFitter.ScaleMode.FILL, bigSug.mode());
        assertEquals(1600, bigSug.crop().width);
    }

    @Test
    void viewerPrgContainsThePictureAtTheVicAddresses() throws Exception {
        int[] idx = randomValid(C64Mode.MULTICOLOR, 11, 6);
        BitmapConverter.Result r = BitmapConverter.convert(wide(C64Mode.MULTICOLOR, idx, COLODORE), exact(C64Mode.MULTICOLOR, 6));
        byte[] prg = ViewerPrg.build(r);
        assertEquals(0x01, prg[0]);
        assertEquals(0x08, prg[1]);
        // 10 SYS2061
        assertArrayEquals(new byte[]{0x0B, 0x08, 0x0A, 0x00, (byte) 0x9E, '2', '0', '6', '1', 0}, java.util.Arrays.copyOfRange(prg, 2, 12));
        KoalaImage k = r.koala();
        assertArrayEquals(k.screen, java.util.Arrays.copyOfRange(prg, at(0x0C00), at(0x0C00) + 1000));
        assertArrayEquals(k.colorRam, java.util.Arrays.copyOfRange(prg, at(0x1000), at(0x1000) + 1000));
        assertEquals(6, prg[at(0x13E8)]);
        assertArrayEquals(k.bitmap, java.util.Arrays.copyOfRange(prg, at(0x2000), at(0x2000) + 8000));
        assertEquals(at(0x3F40), prg.length);

        BitmapConverter.Result h = BitmapConverter.convert(wide(C64Mode.HIRES, randomValid(C64Mode.HIRES, 12, -1), COLODORE),
                exact(C64Mode.HIRES, -1));
        byte[] hp = ViewerPrg.build(h);
        assertArrayEquals(h.hires().bitmap, java.util.Arrays.copyOfRange(hp, at(0x2000), at(0x2000) + 8000));
        // a picture that breaks the rules gives no program
        BitmapConverter.Result bad = BitmapConverter.convert(wide(C64Mode.MULTICOLOR, withClash(), COLODORE), exact(C64Mode.MULTICOLOR, 0));
        assertThrows(IllegalArgumentException.class, () -> ViewerPrg.build(bad));
    }

    /** File offset of a C64 address in a program loaded at $0801. */
    private static int at(int address) { return address - 0x0801 + 2; }

    @Test
    void importFrameUsesTheC64AspectRatio() {
        double pal = ImageFitter.Aspect.PAL.ratio;
        for (int[] size : new int[][]{{4000, 2252}, {2252, 4000}, {1024, 700}, {200, 150}, {100, 300}, {1000, 150}}) {
            ImageFitter.Settings s = ImageFitter.Settings.suggested(new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB));
            assertEquals(ImageFitter.ScaleMode.FILL, s.mode());
            Rectangle c = s.crop();
            assertEquals(pal, (double) c.width / c.height, 0.02, size[0] + "x" + size[1]);
            // largest frame: it touches the shorter side of the picture
            assertTrue(c.width == size[0] || c.height == size[1]);
        }
        // small pictures are scaled up along the shorter side: no border
        BufferedImage small = new BufferedImage(200, 150, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, 200, 150); g.dispose();
        BufferedImage up = ImageFitter.fit(small, ImageFitter.Settings.suggested(small));
        for (int[] p : new int[][]{{0, 0}, {319, 0}, {0, 199}, {319, 199}, {160, 0}, {0, 100}})
            assertEquals(0xFFFFFF, up.getRGB(p[0], p[1]) & 0xFFFFFF);
    }
}
