package de.mulej.image;

import java.awt.*;
import java.awt.image.BufferedImage;

/** Crops and scales an arbitrary image to 320×200. */
public final class ImageFitter {
    public static final int W = 320, H = 200;

    public enum ScaleMode {
        STRETCH("Stretch"), FIT("Fit (letterbox)"), FILL("Fill (crop)");
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
    }

    private ImageFitter() {}

    /** True for 320×200 (hires) or 160×200 (multicolor) images. */
    public static boolean isNativeSize(BufferedImage img) {
        return (img.getWidth() == 320 || img.getWidth() == 160) && img.getHeight() == 200;
    }

    public static BufferedImage fit(BufferedImage src, Settings s) {
        // C64-sized pictures are taken 1:1 (160 wide = multicolor, pixels get doubled)
        if (s.crop() == null && isNativeSize(src)) return scale(src, W, H, Filter.NEAREST);

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
