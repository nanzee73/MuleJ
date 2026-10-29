package de.mulej.core;

/**
 * C64 bitmap modes and their colour restrictions.
 * Pixel coordinates are in the mode's own resolution (multicolor: 160×200, hires: 320×200).
 */
public enum C64Mode {
    /** 160×200, per 4×8 cell 3 free colours plus one background colour for the whole picture (Koala Painter). */
    MULTICOLOR(160, 4, 3, true, "kla"),
    /** 320×200, per 8×8 cell 2 free colours (Art Studio). */
    HIRES(320, 8, 2, false, "art");

    public static final int HEIGHT = 200, COLS = 40, ROWS = 25, CELLS = COLS * ROWS, CELL_H = 8;

    /** Width in mode pixels. */
    public final int width;
    /** Cell width in mode pixels. */
    public final int cellW;
    /** Free colours per cell (without the shared background). */
    public final int coloursPerCell;
    /** Whether a background colour is shared by all cells. */
    public final boolean sharedBackground;
    /** File extension of the output. */
    public final String extension;

    C64Mode(int width, int cellW, int coloursPerCell, boolean sharedBackground, String extension) {
        this.width = width;
        this.cellW = cellW;
        this.coloursPerCell = coloursPerCell;
        this.sharedBackground = sharedBackground;
        this.extension = extension;
    }

    /** Horizontal size of one mode pixel in 320×200 picture pixels. */
    public int pixelWidth() { return 320 / width; }

    public int cellOf(int x, int y) { return (y / CELL_H) * COLS + x / cellW; }

    public int cellX(int cell) { return (cell % COLS) * cellW; }

    public int cellY(int cell) { return (cell / COLS) * CELL_H; }

    @Override public String toString() { return de.mulej.i18n.I18n.tr("mode." + name()); }
}
