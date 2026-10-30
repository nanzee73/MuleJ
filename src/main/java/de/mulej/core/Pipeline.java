package de.mulej.core;

import de.mulej.color.ColorMatcher;
import de.mulej.image.ImageAdjust;
import de.mulej.image.ImageFitter;
import de.mulej.palette.Palette;
import de.mulej.palette.Palettes;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * The conversion chain of MuleJ: original → frame 320×200 → prepare → pixelizer (multicolor only) → C64 colours → result.
 * <p>
 * The chain can start later ("entry"): steps before the entry are passed through unchanged – for pictures that
 * were already brought to 320×200, palettized or edited outside MuleJ. Without the colour step (entry = result)
 * every pixel is only snapped to the nearest palette colour, so slightly changed colours (e.g. after JPEG) are fine.
 */
public final class Pipeline {

    /** What the picture becomes. */
    public enum Target {
        MULTICOLOR(C64Mode.MULTICOLOR), HIRES(C64Mode.HIRES), PETSCII(null);

        /** Bitmap mode, null for PETSCII. */
        public final C64Mode mode;

        Target(C64Mode mode) { this.mode = mode; }

        @Override public String toString() { return de.mulej.i18n.I18n.tr("target." + name()); }
    }

    public enum Step {
        ORIGINAL, FRAME, PREPARE, PIXELIZER, COLOURS, RESULT;

        @Override public String toString() { return de.mulej.i18n.I18n.tr("step." + name()); }
    }

    /** The steps of a target in order (the pixelizer only exists for multicolor). */
    public static List<Step> steps(Target t) {
        List<Step> l = new ArrayList<>(List.of(Step.values()));
        if (t != Target.MULTICOLOR) l.remove(Step.PIXELIZER);
        return l;
    }

    /** Steps the chain may start at for this target and picture. */
    public static List<Step> entries(Target t, BufferedImage src) {
        List<Step> l = new ArrayList<>();
        l.add(Step.FRAME);
        if (src == null || ImageFitter.canTakeExactly(src)) {
            for (Step s : steps(t)) if (s.ordinal() > Step.FRAME.ordinal()) l.add(s);
            if (t == Target.PETSCII) l.remove(Step.RESULT);   // the characters have to be computed
        }
        return l;
    }

    /**
     * All settings of the chain.
     *
     * @param entry first step that is applied; earlier ones are passed through
     * @param fit   frame and scaling; null = the picture is taken as it is (C64 size or a whole-number multiple)
     */
    public record Params(Target target, Step entry, ImageFitter.Settings fit, ImageAdjust.Settings adjust,
                         Pixelizer.Direction pair, Palette palette, ColorMatcher.Space space,
                         Ditherer.Settings dither, int background, boolean fixClashes,
                         PetsciiConverter.Settings petscii, BitmapConverter.Edits edits) {

        public static Params defaults(Target t) {
            return new Params(t, Step.FRAME, null, ImageAdjust.Settings.NEUTRAL, Pixelizer.Direction.L2R,
                    Palettes.COLODORE, ColorMatcher.Space.EUCLID, Ditherer.Settings.NONE, -1, false,
                    PetsciiConverter.Settings.defaults(), BitmapConverter.Edits.none());
        }

        public boolean skipped(Step s) {
            if (s == Step.ORIGINAL) return false;
            if (s == Step.PIXELIZER && target != Target.MULTICOLOR) return true;
            return s.ordinal() < entry.ordinal();
        }
    }

    /**
     * Results of all steps. {@code bitmap} is set for multicolor/hires, {@code petscii} for PETSCII.
     * {@code error} is set if the colour step failed.
     */
    public record Outputs(BufferedImage original, BufferedImage framed, BufferedImage prepared, BufferedImage pixelized,
                          BitmapConverter.Result bitmap, PetsciiImage petscii, BufferedImage colours, String error) {

        public BufferedImage image(Step s) {
            return switch (s) {
                case ORIGINAL -> original;
                case FRAME -> framed;
                case PREPARE -> prepared;
                case PIXELIZER -> pixelized;
                case COLOURS, RESULT -> colours;
            };
        }

        /** Colour check passed (always true for PETSCII). */
        public boolean ok() {
            return error == null && (petscii != null ? petscii.check().isEmpty() : bitmap != null && bitmap.ok());
        }
    }

    private Pipeline() {}

    /** Runs the whole chain. */
    public static Outputs run(BufferedImage src, Params p) {
        return run(src, p, null, Step.FRAME);
    }

    /**
     * Runs the chain from step {@code from} on; earlier results are taken from {@code previous}
     * (which must have been computed from the same picture with the same earlier settings).
     */
    public static Outputs run(BufferedImage src, Params p, Outputs previous, Step from) {
        boolean reuse = previous != null;
        BufferedImage framed = reuse && from.ordinal() > Step.FRAME.ordinal() ? previous.framed : frame(src, p);
        BufferedImage prepared = reuse && from.ordinal() > Step.PREPARE.ordinal() ? previous.prepared
                : p.skipped(Step.PREPARE) || ImageAdjust.Settings.NEUTRAL.equals(p.adjust()) ? framed
                : ImageAdjust.toImage(ImageAdjust.toAdjustedRgb(framed, p.adjust(), true), 320, 200);
        BufferedImage pixelized = reuse && from.ordinal() > Step.PIXELIZER.ordinal() ? previous.pixelized
                : p.skipped(Step.PIXELIZER) ? prepared : Pixelizer.pixelize(prepared, p.pair());
        try {
            if (p.target() == Target.PETSCII) {
                PetsciiImage pet = PetsciiConverter.convert(pixelized, p.palette(), p.space(), p.petscii());
                return new Outputs(src, framed, prepared, pixelized, null, pet, pet.render(p.palette()), null);
            }
            BitmapConverter.Result r = BitmapConverter.convert(pixelized, bitmapSettings(p), p.edits());
            return new Outputs(src, framed, prepared, pixelized, r, null, r.render(p.palette()), null);
        } catch (Exception e) {
            return new Outputs(src, framed, prepared, pixelized, null, null, null, e.getMessage());
        }
    }

    /**
     * Settings of the colour step for multicolor/hires. Without the colour step (entry = result) every pixel is
     * only snapped to the palette: no dithering, no automatic correction.
     */
    public static BitmapConverter.Settings bitmapSettings(Params p) {
        boolean snap = p.skipped(Step.COLOURS);
        return new BitmapConverter.Settings(p.target().mode, p.palette(), p.space(), p.pair(), true, p.background(),
                !snap && p.fixClashes(), snap ? Ditherer.Settings.NONE : p.dither());
    }

    /** Step 2: the picture brought to 320×200. */
    static BufferedImage frame(BufferedImage src, Params p) {
        if (p.fit() != null && !p.skipped(Step.FRAME)) return ImageFitter.fit(src, p.fit());
        if (ImageFitter.canTakeExactly(src)) return ImageFitter.takeExactly(src);
        return ImageFitter.fit(src, p.fit() != null ? p.fit() : ImageFitter.Settings.suggested(src));
    }

    // ------------------------------------------------------------------ automatic entry

    /** Result of {@link #detect}: where to start, with which palette, and why. */
    public record Detection(Step entry, Palette palette, String reasonKey) {}

    /**
     * Finds out how far a picture already is:
     * <ul>
     *   <li>not 320×200 / 160×200 (or a whole-number multiple) → start with the frame</li>
     *   <li>C64 size with free colours → start with "prepare"</li>
     *   <li>only colours of a C64 palette (small deviations allowed) → multicolor with unequal pixel pairs:
     *       pixelizer; PETSCII: colours (the characters still have to be found); otherwise: result</li>
     * </ul>
     */
    public static Detection detect(BufferedImage src, Target t, Palette preferred) {
        if (!ImageFitter.canTakeExactly(src)) return new Detection(Step.FRAME, preferred, "detect.frame");
        BufferedImage img = ImageFitter.takeExactly(src);
        Palette pal = paletteOf(img, preferred);
        if (pal == null) return new Detection(Step.PREPARE, preferred, "detect.prepare");
        if (t == Target.PETSCII) return new Detection(Step.COLOURS, pal, "detect.coloursPetscii");
        if (t == Target.MULTICOLOR && !pairsEqual(img))
            return new Detection(Step.PIXELIZER, pal, "detect.pixelizer");
        return new Detection(Step.RESULT, pal, "detect.result");
    }

    /** Max. RGB distance of a pixel to its palette colour that still counts as "palette colour" (JPEG, phone apps). */
    static final int PALETTE_TOLERANCE = 28;

    /** The palette (preferred first, then the built-in ones) that ≥ 99 % of the pixels belong to, or null. */
    static Palette paletteOf(BufferedImage img, Palette preferred) {
        List<Palette> candidates = new ArrayList<>();
        candidates.add(preferred);
        for (Palette p : Palettes.builtIns()) if (!p.equals(preferred)) candidates.add(p);
        int w = img.getWidth(), h = img.getHeight(), n = w * h, allowedMisses = n / 100;
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        for (Palette p : candidates) {
            int misses = 0;
            for (int i = 0; i < n && misses <= allowedMisses; i++) if (!near(px[i], p)) misses++;
            if (misses <= allowedMisses) return p;
        }
        return null;
    }

    private static boolean near(int rgb, Palette p) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        for (int i = 0; i < 16; i++) {
            int dr = r - p.r(i), dg = g - p.g(i), db = b - p.b(i);
            if (dr * dr + dg * dg + db * db <= PALETTE_TOLERANCE * PALETTE_TOLERANCE) return true;
        }
        return false;
    }

    /** ≥ 99 % of the horizontal pixel pairs have (almost) the same colour. */
    static boolean pairsEqual(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight(), pairs = 0, unequal = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x + 1 < w; x += 2) {
                pairs++;
                int a = img.getRGB(x, y), b = img.getRGB(x + 1, y);
                int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF), dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF), db = (a & 0xFF) - (b & 0xFF);
                if (dr * dr + dg * dg + db * db > PALETTE_TOLERANCE * PALETTE_TOLERANCE) unequal++;
            }
        return unequal * 100 <= pairs;
    }
}
