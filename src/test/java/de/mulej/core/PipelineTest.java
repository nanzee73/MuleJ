package de.mulej.core;

import de.mulej.color.ColorMatcher;
import de.mulej.core.Pipeline.Step;
import de.mulej.core.Pipeline.Target;
import de.mulej.image.ImageAdjust;
import de.mulej.image.ImageFitter;
import de.mulej.palette.Palette;
import de.mulej.palette.Palettes;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PipelineTest {
    private static final Palette COLODORE = Palettes.COLODORE;

    /** A smooth photo-like picture with free colours. */
    static BufferedImage photo(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int r = 128 + (int) (100 * Math.sin(x / 23.0)), g = 128 + (int) (100 * Math.cos(y / 17.0));
                int b = (x * 255 / w + y * 255 / h) / 2;
                img.setRGB(x, y, r << 16 | g << 8 | b);
            }
        return img;
    }

    /** A picture made only of palette colours, with double-wide pixels. */
    static BufferedImage paletteArt(Palette p, boolean pairs) {
        BufferedImage img = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(5);
        for (int y = 0; y < 200; y++)
            for (int x = 0; x < 320; x += 2) {
                int c = p.rgb((x / 16 + y / 10) % 16);
                img.setRGB(x, y, c);
                img.setRGB(x + 1, y, pairs ? c : p.rgb(r.nextInt(16)));
            }
        return img;
    }

    @Test
    void stepsDependOnTheTarget() {
        assertTrue(Pipeline.steps(Target.MULTICOLOR).contains(Step.PIXELIZER));
        assertFalse(Pipeline.steps(Target.HIRES).contains(Step.PIXELIZER));
        assertFalse(Pipeline.steps(Target.PETSCII).contains(Step.PIXELIZER));
        assertEquals(6, Pipeline.steps(Target.MULTICOLOR).size());
        assertEquals(5, Pipeline.steps(Target.PETSCII).size());
    }

    @Test
    void automaticEntry() {
        assertEquals(Step.FRAME, Pipeline.detect(photo(1024, 700), Target.MULTICOLOR, COLODORE).entry());
        assertEquals(Step.PREPARE, Pipeline.detect(photo(320, 200), Target.MULTICOLOR, COLODORE).entry());
        assertEquals(Step.RESULT, Pipeline.detect(paletteArt(COLODORE, true), Target.MULTICOLOR, COLODORE).entry());
        assertEquals(Step.PIXELIZER, Pipeline.detect(paletteArt(COLODORE, false), Target.MULTICOLOR, COLODORE).entry());
        assertEquals(Step.RESULT, Pipeline.detect(paletteArt(COLODORE, false), Target.HIRES, COLODORE).entry());
        assertEquals(Step.COLOURS, Pipeline.detect(paletteArt(COLODORE, true), Target.PETSCII, COLODORE).entry());
        // a picture in another palette is recognised, and that palette is suggested
        Pipeline.Detection d = Pipeline.detect(paletteArt(Palettes.PEPTO, true), Target.MULTICOLOR, COLODORE);
        assertEquals(Step.RESULT, d.entry());
        assertEquals(Palettes.PEPTO, d.palette());
        // enlarged 2× for editing on the phone: still C64 size
        BufferedImage x2 = ImageFitter.scale(paletteArt(COLODORE, true), 640, 400, ImageFitter.Filter.NEAREST);
        assertEquals(Step.RESULT, Pipeline.detect(x2, Target.MULTICOLOR, COLODORE).entry());
    }

    @Test
    void enlargedPicturesAreTakenBackExactly() {
        BufferedImage art = paletteArt(COLODORE, false);
        for (int f : new int[]{2, 3, 4}) {
            BufferedImage big = ImageFitter.scale(art, 320 * f, 200 * f, ImageFitter.Filter.NEAREST);
            BufferedImage back = ImageFitter.takeExactly(big);
            for (int y = 0; y < 200; y++)
                for (int x = 0; x < 320; x++) assertEquals(art.getRGB(x, y), back.getRGB(x, y), f + "x at " + x + "," + y);
        }
    }

    @Test
    void wholeChainForEveryTarget() {
        BufferedImage src = photo(800, 600);
        for (Target t : Target.values()) {
            Pipeline.Params p = Pipeline.Params.defaults(t);
            p = new Pipeline.Params(t, Step.FRAME, ImageFitter.Settings.suggested(src), new ImageAdjust.Settings(10, 20, 110, 120),
                    p.pair(), p.palette(), ColorMatcher.Space.YUV, new Ditherer.Settings(Dither.FLOYD_STEINBERG, 80, false, true),
                    -1, true, p.petscii(), p.edits());
            Pipeline.Outputs o = Pipeline.run(src, p);
            assertNull(o.error(), t + ": " + o.error());
            assertEquals(320, o.framed().getWidth());
            assertEquals(200, o.prepared().getHeight());
            assertTrue(o.ok(), t.toString());
            assertNotNull(o.colours());
            if (t == Target.PETSCII) assertNotNull(o.petscii()); else assertNotNull(o.bitmap().fileBytes());
            // reusing the earlier steps gives the same result
            Pipeline.Outputs again = Pipeline.run(src, p, o, Step.COLOURS);
            assertSame(o.prepared(), again.prepared());
        }
    }

    @Test
    void entryAtResultOnlySnapsToThePalette() {
        // slightly changed palette colours (e.g. after JPEG) are snapped back, nothing is dithered
        BufferedImage art = paletteArt(COLODORE, true);
        BufferedImage noisy = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 200; y++)
            for (int x = 0; x < 320; x++) {
                int c = art.getRGB(x, y);
                int r = Math.min(255, ((c >> 16) & 0xFF) + 6), g = Math.max(0, ((c >> 8) & 0xFF) - 5);
                noisy.setRGB(x, y, r << 16 | g << 8 | (c & 0xFF));
            }
        Pipeline.Params d = Pipeline.Params.defaults(Target.MULTICOLOR);
        Pipeline.Params p = new Pipeline.Params(Target.MULTICOLOR, Step.RESULT, null, d.adjust(), d.pair(), COLODORE,
                d.space(), new Ditherer.Settings(Dither.FLOYD_STEINBERG, 100, false, true), -1, true, d.petscii(), d.edits());
        Pipeline.Outputs o = Pipeline.run(noisy, p);
        assertSame(o.framed(), o.prepared());
        int[] idx = o.bitmap().indices();
        for (int y = 0; y < 200; y++)
            for (int x = 0; x < 160; x++)
                assertEquals(art.getRGB(2 * x, y) & 0xFFFFFF, COLODORE.rgb(idx[y * 160 + x]));
    }

    @Test
    void entriesForPetsciiNeverSkipTheCharacters() {
        List<Step> e = Pipeline.entries(Target.PETSCII, paletteArt(COLODORE, true));
        assertFalse(e.contains(Step.RESULT));
        assertTrue(e.contains(Step.COLOURS));
        assertEquals(List.of(Step.FRAME), Pipeline.entries(Target.MULTICOLOR, photo(500, 500)));
    }
}
