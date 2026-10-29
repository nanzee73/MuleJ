package de.mulej.color;

import de.mulej.palette.Palette;

import java.util.stream.IntStream;

/**
 * Color distance between arbitrary RGB values and the 16 palette colors.
 * Supports RGB, YUV (with weights for U and V) and HSL (cylindrical, so hue wraps around correctly).
 * Optionally, luma deviations are penalized more ("respect luma ramp").
 */
public final class ColorMatcher {

    /** EUCLID = plain euclidean RGB distance, as in the old c64palettizer; RGB = luma-weighted RGB. */
    public enum Space { EUCLID, RGB, YUV, HSL }

    private final Space space;
    private final float w0, w1, w2;
    private final float lumaWeight;
    private final float[][] pal = new float[16][];
    private final float[] palLuma = new float[16];

    /**
     * @param weightU         U weight (slider 0..100, 50 = 1.0), YUV only
     * @param weightV         V weight (slider 0..100, 50 = 1.0), YUV only
     * @param respectLumaRamp additionally penalize brightness deviations
     */
    public ColorMatcher(Palette palette, Space space, int weightU, int weightV, boolean respectLumaRamp) {
        this.space = space;
        switch (space) {
            case YUV -> { w0 = 1f; w1 = weightU / 50f; w2 = weightV / 50f; }
            case HSL -> { w0 = 1f; w1 = 0.7f; w2 = 0.7f; }
            case EUCLID -> { w0 = 1f; w1 = 1f; w2 = 1f; }
            default -> { w0 = 0.299f * 3; w1 = 0.587f * 3; w2 = 0.114f * 3; }
        }
        this.lumaWeight = respectLumaRamp ? 2f : 0f;
        for (int i = 0; i < 16; i++) {
            pal[i] = convert(palette.r(i), palette.g(i), palette.b(i));
            palLuma[i] = luma(palette.r(i), palette.g(i), palette.b(i));
        }
    }

    public static float luma(float r, float g, float b) { return 0.299f * r + 0.587f * g + 0.114f * b; }

    /** Converts RGB (0..255) into the working color space. */
    public float[] convert(float r, float g, float b) {
        switch (space) {
            case YUV: {
                float y = 0.299f * r + 0.587f * g + 0.114f * b;
                float u = -0.14713f * r - 0.28886f * g + 0.436f * b;
                float v = 0.615f * r - 0.51499f * g - 0.10001f * b;
                return new float[]{y, u, v};
            }
            case HSL: {
                float rf = r / 255f, gf = g / 255f, bf = b / 255f;
                float max = Math.max(rf, Math.max(gf, bf)), min = Math.min(rf, Math.min(gf, bf));
                float l = (max + min) / 2f, s = 0f, h = 0f;
                float d = max - min;
                if (d > 1e-6f) {
                    s = l > 0.5f ? d / (2f - max - min) : d / (max + min);
                    if (max == rf) h = (gf - bf) / d + (gf < bf ? 6 : 0);
                    else if (max == gf) h = (bf - rf) / d + 2;
                    else h = (rf - gf) / d + 4;
                    h /= 6f;
                }
                double ang = h * 2 * Math.PI;
                // fade saturation towards black/white so very dark/bright pixels don't get hue-dominated
                float chroma = s * (1f - Math.abs(2f * l - 1f));
                return new float[]{l * 255f, (float) (Math.cos(ang) * chroma * 255f), (float) (Math.sin(ang) * chroma * 255f)};
            }
            default:
                return new float[]{r, g, b};
        }
    }

    /** Squared distance of an already converted value to palette color idx. */
    public float distConverted(float[] c, float lumaOfC, int idx) {
        float[] p = pal[idx];
        float d0 = c[0] - p[0], d1 = c[1] - p[1], d2 = c[2] - p[2];
        float d = w0 * d0 * d0 + w1 * d1 * d1 + w2 * d2 * d2;
        if (lumaWeight > 0) {
            float dl = lumaOfC - palLuma[idx];
            d += lumaWeight * dl * dl;
        }
        return d;
    }

    /** Squared distance of an RGB value to palette color idx. */
    public float dist(float r, float g, float b, int idx) {
        return distConverted(convert(r, g, b), luma(r, g, b), idx);
    }

    /** Nearest color out of a set of allowed indices. */
    public int nearest(float r, float g, float b, int[] allowed, int count) {
        float[] c = convert(r, g, b);
        float l = luma(r, g, b);
        int best = allowed[0];
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            float d = distConverted(c, l, allowed[i]);
            if (d < bd) { bd = d; best = allowed[i]; }
        }
        return best;
    }

    /**
     * Error table: for every pixel the distance to all 16 colors (layout: pixel*16 + color).
     * @param rgb pixels as float[n*3]
     */
    public float[] errorTable(float[] rgb) {
        int n = rgb.length / 3;
        float[] t = new float[n * 16];
        IntStream.range(0, n).parallel().forEach(i -> {
            float r = rgb[i * 3], g = rgb[i * 3 + 1], b = rgb[i * 3 + 2];
            float[] c = convert(r, g, b);
            float l = luma(r, g, b);
            for (int k = 0; k < 16; k++) t[i * 16 + k] = distConverted(c, l, k);
        });
        return t;
    }
}
