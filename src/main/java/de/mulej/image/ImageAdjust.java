package de.mulej.image;

import java.awt.image.BufferedImage;

/** Pre-processing ("Prepare"): brightness, contrast, gamma, saturation. */
public final class ImageAdjust {
    /** brightness -100..100, contrast -100..100, gamma in percent (100 = neutral), saturation in percent (100 = neutral). */
    public record Settings(int brightness, int contrast, int gammaPercent, int saturationPercent) {
        public static final Settings NEUTRAL = new Settings(0, 0, 100, 100);
    }

    private ImageAdjust() {}

    /** Reads an image as float RGB (0..255) and applies the settings if enabled. */
    public static float[] toAdjustedRgb(BufferedImage img, Settings s, boolean enabled) {
        int w = img.getWidth(), h = img.getHeight();
        float[] out = new float[w * h * 3];
        float contrast = (100f + s.contrast()) / 100f;
        contrast = contrast * contrast;
        float gamma = Math.max(0.05f, s.gammaPercent() / 100f);
        float sat = s.saturationPercent() / 100f;
        float bright = s.brightness() * 1.28f;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                float r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                if (enabled) {
                    if (gamma != 1f) {
                        r = (float) (255.0 * Math.pow(r / 255.0, 1.0 / gamma));
                        g = (float) (255.0 * Math.pow(g / 255.0, 1.0 / gamma));
                        b = (float) (255.0 * Math.pow(b / 255.0, 1.0 / gamma));
                    }
                    r = (r - 128f) * contrast + 128f + bright;
                    g = (g - 128f) * contrast + 128f + bright;
                    b = (b - 128f) * contrast + 128f + bright;
                    float l = 0.299f * r + 0.587f * g + 0.114f * b;
                    r = l + (r - l) * sat;
                    g = l + (g - l) * sat;
                    b = l + (b - l) * sat;
                }
                int i = (y * w + x) * 3;
                out[i] = clamp(r);
                out[i + 1] = clamp(g);
                out[i + 2] = clamp(b);
            }
        }
        return out;
    }

    public static float clamp(float v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    public static BufferedImage toImage(float[] rgb, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 3;
                img.setRGB(x, y, ((int) clamp(rgb[i]) << 16) | ((int) clamp(rgb[i + 1]) << 8) | (int) clamp(rgb[i + 2]));
            }
        return img;
    }

    /** Halves the width by averaging neighbouring pixels (for multicolor pixels). */
    public static float[] halveWidth(float[] rgb, int w, int h) {
        int nw = w / 2;
        float[] out = new float[nw * h * 3];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < nw; x++)
                for (int c = 0; c < 3; c++)
                    out[(y * nw + x) * 3 + c] = (rgb[(y * w + 2 * x) * 3 + c] + rgb[(y * w + 2 * x + 1) * 3 + c]) * 0.5f;
        return out;
    }
}
