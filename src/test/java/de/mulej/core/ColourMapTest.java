package de.mulej.core;

import de.c64gfx.color.ColorMatcher;
import de.c64gfx.dither.Ditherer;
import de.c64gfx.palette.Palette;
import de.c64gfx.palette.Palettes;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class ColourMapTest {
    private static final Palette P = Palettes.COLODORE;

    @Test
    void swapAndReplaceCompose() {
        ColourMap m = ColourMap.identity();
        assertTrue(m.isIdentity());
        m = m.swap(6, 14);
        assertEquals(14, m.get(6));
        assertEquals(6, m.get(14));
        m = m.swap(6, 14);                               // twice = back
        assertTrue(m.isIdentity());
        m = m.swap(1, 2).replace(2, 5);                  // 1 shows 2, then every shown 2 becomes 5
        assertEquals(5, m.get(1));
        assertEquals(1, m.get(2));
        assertEquals("1 → 5, 2 → 1", m.describe());
        assertEquals(m, ColourMap.parse(m.toString()));
        assertTrue(ColourMap.parse("nonsense").isIdentity());
        assertTrue(ColourMap.parse("0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,99").isIdentity());
    }

    @Test
    void bitmapsKeepTheRules() throws Exception {
        BufferedImage photo = PipelineTest.photo(320, 200);
        for (C64Mode mode : C64Mode.values()) {
            BufferedImage src = mode == C64Mode.MULTICOLOR ? Pixelizer.pixelize(photo, Pixelizer.Direction.L2R) : photo;
            BitmapConverter.Settings s = new BitmapConverter.Settings(mode, P, ColorMatcher.Space.YUV,
                    Pixelizer.Direction.L2R, true, -1, true, Ditherer.Settings.NONE);
            BitmapConverter.Result r = BitmapConverter.convert(src, s);
            assertTrue(r.ok());
            int a = r.indices()[0], b = a == 1 ? 2 : 1;
            ColourMap m = ColourMap.identity().swap(a, b).replace(r.indices()[500], 7);
            BitmapConverter.Result x = m.apply(r, s);
            assertTrue(x.ok(), mode.toString());
            for (int i = 0; i < r.indices().length; i++) assertEquals(m.get(r.indices()[i]), x.indices()[i], mode + " @" + i);
            if (mode.sharedBackground) assertEquals(m.get(r.background()), x.background());
            assertNotNull(x.fileBytes());
        }
    }

    @Test
    void textScreensAndCharacterSets() {
        PetsciiImage p = PetsciiTest.randomScreen(4, Charset.UPPER, true);
        PetsciiImage q = ColourMap.identity().swap(6, 0).apply(p);
        assertEquals(0, q.background);
        assertArrayEquals(p.codes, q.codes);
        for (int i = 0; i < p.colours.length; i++) assertEquals(p.colours[i] == 0 ? 6 : p.colours[i], q.colours[i]);

        CharsetImage c = CharsetConverter.convert(Pixelizer.pixelize(PipelineTest.photo(320, 200), Pixelizer.Direction.L2R),
                P, ColorMatcher.Space.YUV, new CharsetConverter.Settings(true, 100, 2, -1, -1, -1), null);
        int cell = c.colours[0] & 7;
        CharsetImage d = ColourMap.identity().replace(cell, 12).apply(c);   // 12 is not possible as cell colour
        assertTrue(d.keptCells > 0);
        for (int v : d.colours) assertTrue(v >= 8 && v <= 15);
        assertEquals(c.charCount(), d.charCount());
        int other = cell == 3 ? 4 : 3;
        CharsetImage e = ColourMap.identity().swap(cell, other).apply(c);
        assertEquals(0, e.keptCells);
        for (int i = 0; i < c.colours.length; i++) {
            int o = c.colours[i] & 7;
            assertEquals(8 | (o == cell ? other : o == other ? cell : o), e.colours[i]);
        }
        // tiles are built again from the new colours
        for (int sy = 0; sy < 25; sy++)
            for (int sx = 0; sx < 40; sx++)
                assertEquals(e.colours[sy * 40 + sx], e.tileColours[e.map[(sy / 2) * e.mapCols + sx / 2]][(sy % 2) * 2 + sx % 2]);
    }
}
