package de.mulej.core;

import de.c64gfx.color.ColorMatcher;
import de.c64gfx.dither.Dither;
import de.c64gfx.dither.Ditherer;
import de.c64gfx.palette.Palette;
import de.c64gfx.palette.Palettes;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CharsetTest {
    private static final Palette P = Palettes.COLODORE;

    /** A screen of {@code count} random hires characters in random colours on background 6. */
    static CharsetImage randomHires(int count, long seed) {
        Random r = new Random(seed);
        CharsetImage c = new CharsetImage();
        c.background = 6;
        c.border = 6;
        c.chars = new byte[count][8];
        for (byte[] ch : c.chars) r.nextBytes(ch);
        for (int i = 0; i < CharsetImage.CELLS; i++) {
            c.screen[i] = r.nextInt(count);
            int col;
            do col = r.nextInt(16); while (col == 6);
            c.colours[i] = col;
        }
        return c;
    }

    @Test
    void aCharacterScreenIsFoundAgainExactly() {
        CharsetImage orig = randomHires(100, 5);
        BufferedImage img = orig.render(P);
        CharsetImage back = CharsetConverter.convert(img, P, ColorMatcher.Space.EUCLID, CharsetConverter.Settings.defaults(), null);
        assertEquals(6, back.background);
        assertTrue(back.charCount() <= 100);
        BufferedImage again = back.render(P);
        for (int y = 0; y < 200; y++)
            for (int x = 0; x < 320; x++) assertEquals(img.getRGB(x, y), again.getRGB(x, y), "at " + x + "," + y);
    }

    @Test
    void theLimitIsKeptAndSimilarCharactersAreMerged() {
        BufferedImage photo = PipelineTest.photo(320, 200);
        for (int max : new int[]{16, 64, 256}) {
            CharsetImage c = CharsetConverter.convert(photo, P, ColorMatcher.Space.YUV,
                    new CharsetConverter.Settings(false, max, 1, -1, -1, -1), null);
            assertTrue(c.charCount() <= max, max + ": " + c.charCount());
            for (int s : c.screen) assertTrue(s < c.charCount());
            assertEquals(c.charCount() * 8, c.charsetBytes().length);
            // no character appears twice
            Set<String> seen = new HashSet<>();
            for (byte[] ch : c.chars) assertTrue(seen.add(java.util.Arrays.toString(ch)));
        }
    }

    @Test
    void multicolourCharactersUseFourColours() {
        BufferedImage photo = Pixelizer.pixelize(PipelineTest.photo(320, 200), Pixelizer.Direction.L2R);
        CharsetImage c = CharsetConverter.convert(photo, P, ColorMatcher.Space.YUV,
                new CharsetConverter.Settings(true, 128, 1, -1, -1, -1),
                new Ditherer.Settings(Dither.FLOYD_STEINBERG, 80, false, true));
        assertTrue(c.multicolor);
        assertTrue(c.charCount() <= 128);
        assertNotEquals(c.background, c.mc1);
        assertNotEquals(c.mc1, c.mc2);
        for (int col : c.colours) assertTrue(col >= 8 && col <= 15, "colour RAM " + col);
        int[] rgb = c.render(P).getRGB(0, 0, 320, 200, null, 0, 320);
        for (int cell = 0; cell < CharsetImage.CELLS; cell++) {
            Set<Integer> allowed = Set.of(P.rgb(c.background), P.rgb(c.mc1), P.rgb(c.mc2), P.rgb(c.colours[cell] & 7));
            int cx = (cell % 40) * 8, cy = (cell / 40) * 8;
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 8; x += 2) {
                    int a = rgb[(cy + y) * 320 + cx + x] & 0xFFFFFF;
                    assertTrue(allowed.contains(a));
                    assertEquals(a, rgb[(cy + y) * 320 + cx + x + 1] & 0xFFFFFF);   // double-wide pixels
                }
        }
        // fixed shared colours are used as given
        CharsetImage f = CharsetConverter.convert(photo, P, ColorMatcher.Space.YUV,
                new CharsetConverter.Settings(true, 64, 1, 0, 11, 12), null);
        assertEquals(0, f.background);
        assertEquals(11, f.mc1);
        assertEquals(12, f.mc2);
    }

    @Test
    void metaTilesRebuildTheScreen() {
        BufferedImage photo = PipelineTest.photo(320, 200);
        for (int n : new int[]{2, 4}) {
            CharsetImage c = CharsetConverter.convert(photo, P, ColorMatcher.Space.YUV,
                    new CharsetConverter.Settings(false, 96, n, -1, -1, -1), null);
            assertEquals((40 + n - 1) / n, c.mapCols);
            assertEquals((25 + n - 1) / n, c.mapRows);
            assertTrue(c.charCount() <= 96);
            assertEquals(0, c.value(0, 0, 0));                 // code 0 is the blank character
            for (int y = 0; y < 8; y++) assertEquals(0, c.chars[0][y]);
            for (int sy = 0; sy < 25; sy++)
                for (int sx = 0; sx < 40; sx++) {
                    int t = c.map[(sy / n) * c.mapCols + sx / n], i = (sy % n) * n + sx % n;
                    assertEquals(c.screen[sy * 40 + sx], c.tiles[t][i]);
                    assertEquals(c.colours[sy * 40 + sx], c.tileColours[t][i]);
                }
            assertEquals(c.tileCount() * n * n, c.tileBytes().length);
            assertEquals(c.map.length * (c.map16() ? 2 : 1), c.mapBytes().length);
        }
    }

    @Test
    void kickAssemblerSourceAndViewer() {
        CharsetImage c = CharsetConverter.convert(PipelineTest.photo(320, 200), P, ColorMatcher.Space.YUV,
                new CharsetConverter.Settings(true, 200, 2, -1, -1, -1), null);
        String asm = c.kickAssembler("pic");
        assertEquals(asm, new String(asm.getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII), "ASCII only");
        assertTrue(asm.contains(".const CHAR_COUNT  = " + c.charCount()));
        assertTrue(asm.contains(".align $800"));
        for (String f : new String[]{"pic_charset.bin", "pic_screen.bin", "pic_colours.bin", "pic_tiles.bin",
                "pic_tilecolours.bin", "pic_map.bin"})
            assertTrue(asm.contains(".import binary \"" + f + "\""), f);
        byte[] prg = ViewerPrg.charset(c);
        assertEquals(0x01, prg[0]);
        assertEquals(0x08, prg[1]);
        // the character set ends up at $2000
        int off = 0x2000 - 0x0801 + 2;
        for (int i = 0; i < c.charCount() * 8; i++) assertEquals(c.charsetBytes()[i], prg[off + i]);
        assertEquals(128, c.renderCharset(P).getWidth());
    }
}
