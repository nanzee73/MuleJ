package de.mulej.core;

import de.c64gfx.color.ColorMatcher;
import de.c64gfx.palette.Palette;
import de.c64gfx.palette.Palettes;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PetsciiTest {
    private static final Palette P = Palettes.COLODORE;

    /** Draws a known text screen. */
    static PetsciiImage randomScreen(long seed, Charset cs, boolean graphicsOnly) {
        Random r = new Random(seed);
        PetsciiImage p = new PetsciiImage();
        p.charset = cs;
        p.background = 6;
        p.border = 14;
        for (int i = 0; i < PetsciiImage.CELLS; i++) {
            int code;
            do code = r.nextInt(256); while (graphicsOnly && cs.isText(code));
            p.codes[i] = code;
            int c;
            do c = r.nextInt(16); while (c == 6);
            p.colours[i] = c;
        }
        return p;
    }

    @Test
    void charsetsAreTheC64Rom() {
        assertEquals(2048, Charset.UPPER.data().length);
        assertEquals(2048, Charset.LOWER.data().length);
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            assertFalse(Charset.UPPER.pixel(32, x, y));     // space
            assertTrue(Charset.UPPER.pixel(160, x, y));     // reversed space
        }
        assertTrue(Charset.UPPER.isText(1));                // A
        assertFalse(Charset.UPPER.isText(102));             // checkerboard
        assertFalse(Charset.UPPER.isText(32));
    }

    @Test
    void aRenderedScreenIsFoundAgain() {
        // the picture of a real text screen must be converted back to a picture that looks exactly the same
        PetsciiImage orig = randomScreen(3, Charset.UPPER, true);
        BufferedImage img = orig.render(P);
        PetsciiImage back = PetsciiConverter.convert(img, P, ColorMatcher.Space.EUCLID,
                new PetsciiConverter.Settings(Charset.UPPER, false, -1, 100, 0, 0));
        assertEquals(6, back.background);
        assertArrayEquals(orig.indices(), back.indices());
    }

    @Test
    void colourRulesAreKept() {
        BufferedImage photo = PipelineTest.photo(320, 200);
        for (Charset cs : Charset.values()) {
            PetsciiImage p = PetsciiConverter.convert(photo, P, ColorMatcher.Space.YUV,
                    new PetsciiConverter.Settings(cs, true, -1, 40, 40, 30));
            assertTrue(p.check().isEmpty());
            // every cell shows at most its foreground colour and the background
            int[] idx = p.indices();
            for (int cell = 0; cell < PetsciiImage.CELLS; cell++) {
                int cx = (cell % 40) * 8, cy = (cell / 40) * 8;
                for (int y = 0; y < 8; y++)
                    for (int x = 0; x < 8; x++) {
                        int c = idx[(cy + y) * 320 + cx + x];
                        assertTrue(c == p.background || c == p.colours[cell]);
                    }
            }
        }
        // without letters only graphics characters appear
        PetsciiImage g = PetsciiConverter.convert(photo, P, ColorMatcher.Space.YUV,
                new PetsciiConverter.Settings(Charset.UPPER, false, 0, 40, 40, 30));
        assertEquals(0, g.background);
        for (int c : g.codes) assertFalse(Charset.UPPER.isText(c), "letter " + c);
    }

    @Test
    void petmateRoundTrip() {
        PetsciiImage p = randomScreen(9, Charset.LOWER, false);
        String json = p.toPetmate("Test \"pic\"");
        assertTrue(json.startsWith("{\"version\":2,\"screens\":[0],\"framebufs\":[{\"width\":40,\"height\":25,"));
        assertTrue(json.contains("\"charset\":\"lower\""));
        assertTrue(json.contains("\"name\":\"Test \\\"pic\\\"\""));
        PetsciiImage back = PetsciiImage.fromPetmate(json);
        assertArrayEquals(p.codes, back.codes);
        assertArrayEquals(p.colours, back.colours);
        assertEquals(p.background, back.background);
        assertEquals(p.border, back.border);
        assertEquals(Charset.LOWER, back.charset);
    }

    @Test
    void viewerPrgContainsTheScreen() {
        PetsciiImage p = randomScreen(4, Charset.UPPER, true);
        byte[] prg = ViewerPrg.petscii(p);
        int at = 0x0C00 - 0x0801 + 2, col = 0x1000 - 0x0801 + 2, bg = 0x13E8 - 0x0801 + 2;
        for (int i = 0; i < PetsciiImage.CELLS; i++) {
            assertEquals(p.codes[i], prg[at + i] & 0xFF);
            assertEquals(p.colours[i], prg[col + i]);
        }
        assertEquals(6, prg[bg]);
        assertEquals(14, prg[bg + 1]);
    }
}
