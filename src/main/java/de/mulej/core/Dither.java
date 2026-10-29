package de.mulej.core;

/** Dithering methods: error diffusion and ordered (Bayer) dithering. */
public enum Dither {
    NONE("None", null, 0, 0),
    FLOYD_STEINBERG("Floyd-Steinberg", new int[][]{{1, 0, 7}, {-1, 1, 3}, {0, 1, 5}, {1, 1, 1}}, 16, 0),
    ATKINSON("Atkinson", new int[][]{{1, 0, 1}, {2, 0, 1}, {-1, 1, 1}, {0, 1, 1}, {1, 1, 1}, {0, 2, 1}}, 8, 0),
    JARVIS("Jarvis-Judice-Ninke", new int[][]{
            {1, 0, 7}, {2, 0, 5},
            {-2, 1, 3}, {-1, 1, 5}, {0, 1, 7}, {1, 1, 5}, {2, 1, 3},
            {-2, 2, 1}, {-1, 2, 3}, {0, 2, 5}, {1, 2, 3}, {2, 2, 1}}, 48, 0),
    STUCKI("Stucki", new int[][]{
            {1, 0, 8}, {2, 0, 4},
            {-2, 1, 2}, {-1, 1, 4}, {0, 1, 8}, {1, 1, 4}, {2, 1, 2},
            {-2, 2, 1}, {-1, 2, 2}, {0, 2, 4}, {1, 2, 2}, {2, 2, 1}}, 42, 0),
    SIERRA_LITE("Sierra Lite", new int[][]{{1, 0, 2}, {-1, 1, 1}, {0, 1, 1}}, 4, 0),
    BAYER2("Bayer 2×2 (ordered)", null, 0, 2),
    BAYER4("Bayer 4×4 (ordered)", null, 0, 4),
    BAYER8("Bayer 8×8 (ordered)", null, 0, 8);

    public final String label;
    /** Entries {dx, dy, weight}. */
    public final int[][] kernel;
    public final int divisor;
    private final int[][] matrix;

    Dither(String label, int[][] kernel, int divisor, int bayerSize) {
        this.label = label;
        this.kernel = kernel;
        this.divisor = divisor;
        this.matrix = bayerSize > 0 ? bayer(bayerSize) : null;
    }

    public boolean isErrorDiffusion() { return kernel != null; }
    public boolean isOrdered() { return matrix != null; }

    /** Threshold for ordered dithering in the range -0.5..0.5. */
    public float threshold(int x, int y) {
        int n = matrix.length;
        return (matrix[y % n][x % n] + 0.5f) / (n * n) - 0.5f;
    }

    /** Recursive Bayer matrix: M(2n) = [[4M, 4M+2], [4M+3, 4M+1]]. */
    static int[][] bayer(int n) {
        int[][] m = {{0}};
        for (int size = 1; size < n; size *= 2) {
            int[][] next = new int[size * 2][size * 2];
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++) {
                    int v = 4 * m[y][x];
                    next[y][x] = v;
                    next[y][x + size] = v + 2;
                    next[y + size][x] = v + 3;
                    next[y + size][x + size] = v + 1;
                }
            m = next;
        }
        return m;
    }

    @Override public String toString() { return de.mulej.i18n.I18n.tr("dither." + name()); }
}
