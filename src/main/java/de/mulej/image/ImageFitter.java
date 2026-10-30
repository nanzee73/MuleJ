package de.mulej.image;

import java.awt.*;
import java.awt.image.BufferedImage;

/** Crops and scales an arbitrary image to 320×200. */
public final class ImageFitter {
    public static final int W = 320, H = 200;

    public enum ScaleMode {
        STRETCH("Stretch"), FIT("Fit (letterbox)"), FILL("Fill (crop)"),
        /** No scaling: the frame is exactly 320×200 source pixels; smaller pictures get a border. */
        NONE("1:1 (320×200 frame)");
        private final String label;
        ScaleMode(String l) { label = l; }
        @Override public String toString() { return de.mulej.i18n.I18n.tr("scale." + name()); }
    }

    public enum Filter {
        NEAREST("Nearest neighbour"), BILINEAR("Bilinear"), BICUBIC("Bicubic"), AREA("Area average (smooth)");
        private final String label;
        Filter(String l) { label = l; }
        @Override public String toString() { return de.mulej.i18n.I18n.tr("scalefilter." + name()); }
    }

    /**
     * Display aspect ratio of the target picture.
     * PAL pixels are slightly narrower than tall (≈0.9365), so 320×200 appears as ≈1.50:1 on screen.
     */
    public enum Aspect {
        PAL("C64 PAL (≈1.50:1)", 320 * 0.9365 / 200), SQUARE("Square pixels (1.60:1)", 1.6),
        NTSC("C64 NTSC (≈1.20:1)", 320 * 0.75 / 200), FREE("Free", 0);
        public final String label;
        public final double ratio;
        Aspect(String l, double r) { label = l; ratio = r; }
        @Override public String toString() { return de.mulej.i18n.I18n.tr("aspect." + name()); }
    }

    /** Settings; crop == null means the whole image. */
    public record Settings(Rectangle crop, ScaleMode mode, Filter filter, Aspect aspect, Color fill) {
        public static Settings defaults() { return new Settings(null, ScaleMode.FIT, Filter.AREA, Aspect.PAL, Color.BLACK); }
        public Settings withCrop(Rectangle r) { return new Settings(r, mode, filter, aspect, fill); }

        /**
         * Starting point for a picture that is not C64-sized: the largest centered frame in the C64 aspect ratio
         * (PAL, as on a real monitor), filled into 320×200. Larger pictures are scaled down (area average),
         * smaller ones are scaled up along their shorter side (bicubic) – there is never a border.
         * Pixel art that fits 320×200 (hires) or 160×200 (multicolor) by a whole-number factor, e.g. 160×100 or
         * 640×400, is taken completely and scaled exactly with nearest neighbour, so it stays crisp.
         */
        public static Settings suggested(BufferedImage img) {
            int w = img.getWidth(), h = img.getHeight();
            Settings d = defaults();
            if (isWholeNumberScale(w, h)) return new Settings(null, ScaleMode.STRETCH, Filter.NEAREST, Aspect.FREE, d.fill());
            double a = d.aspect().ratio;
            int cw = w, ch = h;
            if ((double) w / h > a) cw = Math.max(1, (int) Math.round(h * a));
            else ch = Math.max(1, (int) Math.round(w / a));
            Filter f = ch >= H ? Filter.AREA : Filter.BICUBIC;
            return new Settings(new Rectangle((w - cw) / 2, (h - ch) / 2, cw, ch), ScaleMode.FILL, f, d.aspect(), d.fill());
        }

        /** w×h is 320×200 or 160×200 scaled by a whole number (up or down), e.g. 160×100, 80×100, 640×400. */
        static boolean isWholeNumberScale(int w, int h) {
            for (int bw : new int[]{W, W / 2}) {
                if (w * H != h * bw) continue;                       // not the same proportions
                if ((h >= H && h % H == 0) || (h < H && H % h == 0)) return true;
            }
            return false;
        }

        /** 1:1 frame of 320×200 source pixels, centered. */
        public static Settings oneToOne(BufferedImage img) {
            Settings d = defaults();
            return new Settings(new Rectangle((img.getWidth() - W) / 2, (img.getHeight() - H) / 2, W, H),
                    ScaleMode.NONE, Filter.NEAREST, Aspect.SQUARE, d.fill());
        }
    }

    private ImageFitter() {}

    /** 320×200, 160×200 or a whole-number multiple/fraction of them: can be taken without choosing a frame. */
    public static boolean canTakeExactly(BufferedImage img) {
        return isNativeSize(img) || Settings.isWholeNumberScale(img.getWidth(), img.getHeight());
    }

    /**
     * The picture at 320×200 without filtering: 160×200 gets double-wide pixels, whole-number multiples are
     * reduced to one pixel per block, fractions are enlarged with nearest neighbour.
     */
    public static BufferedImage takeExactly(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[W];
        for (int y = 0; y < H; y++) {
            int sy = (int) ((y + 0.5) * h / H);
            for (int x = 0; x < W; x++) row[x] = src.getRGB((int) ((x + 0.5) * w / W), sy);
            out.setRGB(0, y, W, 1, row, 0, W);
        }
        return out;
    }

    /** True for 320×200 (hires) or 160×200 (multicolor) images. */
    public static boolean isNativeSize(BufferedImage img) {
        return (img.getWidth() == 320 || img.getWidth() == 160) && img.getHeight() == 200;
    }

    public static BufferedImage fit(BufferedImage src, Settings s) {
        // C64-sized pictures are taken 1:1 (160 wide = multicolor, pixels get doubled)
        if (s.crop() == null && isNativeSize(src)) return scale(src, W, H, Filter.NEAREST);
        if (s.mode() == ScaleMode.NONE) return oneToOne(src, s);

        Rectangle c = s.crop() != null ? s.crop().intersection(new Rectangle(0, 0, src.getWidth(), src.getHeight()))
                : new Rectangle(0, 0, src.getWidth(), src.getHeight());
        BufferedImage cropped = src.getSubimage(c.x, c.y, Math.max(1, c.width), Math.max(1, c.height));

        double targetAspect = s.aspect().ratio > 0 ? s.aspect().ratio : (double) W / H;
        double pixelAspect = targetAspect * H / W;      // width of one target pixel relative to its height

        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(s.fill());
        g.fillRect(0, 0, W, H);
        if (s.mode() == ScaleMode.STRETCH) {
            g.drawImage(scale(cropped, W, H, s.filter()), 0, 0, null);
        } else {
            double screenW = W * pixelAspect;            // target width in "screen units"
            double scaleW = screenW / cropped.getWidth(), scaleH = (double) H / cropped.getHeight();
            double sc = s.mode() == ScaleMode.FIT ? Math.min(scaleW, scaleH) : Math.max(scaleW, scaleH);
            int dw = Math.max(1, (int) Math.round(cropped.getWidth() * sc / pixelAspect));
            int dh = Math.max(1, (int) Math.round(cropped.getHeight() * sc));
            g.drawImage(scale(cropped, dw, dh, s.filter()), (W - dw) / 2, (H - dh) / 2, null);
        }
        g.dispose();
        return out;
    }

    /** Copies the 320×200 frame without scaling; parts outside the picture get the fill colour. */
    private static BufferedImage oneToOne(BufferedImage src, Settings s) {
        Rectangle c = s.crop() != null ? s.crop()
                : new Rectangle((src.getWidth() - W) / 2, (src.getHeight() - H) / 2, W, H);
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(s.fill());
        g.fillRect(0, 0, W, H);
        g.drawImage(src, -c.x, -c.y, null);
        g.dispose();
        return out;
    }

    public static BufferedImage scale(BufferedImage src, int w, int h, Filter f) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        if (f == Filter.AREA) {
            g.drawImage(src.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING), 0, 0, null);
        } else {
            Object hint = switch (f) {
                case NEAREST -> RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR;
                case BILINEAR -> RenderingHints.VALUE_INTERPOLATION_BILINEAR;
                default -> RenderingHints.VALUE_INTERPOLATION_BICUBIC;
            };
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
        }
        g.dispose();
        return out;
    }
}
