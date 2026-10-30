package de.mulej.core;

import de.c64gfx.format.HiresImage;
import de.c64gfx.format.KoalaImage;
import de.c64gfx.prg.Asm6502;

/**
 * Runnable C64 program that shows a multicolor or hires bitmap or a PETSCII text screen: {@code LOAD"NAME",8,1} (or ,8) and {@code RUN}.
 * Any key returns to BASIC with the normal text screen.
 * <p>
 * Memory map (VIC bank 0, everything already in place after loading, nothing is unpacked):
 * <pre>
 * $0801        BASIC line 10 SYS2061
 * $080D        viewer code
 * $0C00-$0FE7  screen RAM (bitmaps: the VIC reads it there directly; PETSCII: copied to $0400)
 * $1000-$13E7  colour RAM data (multicolor, PETSCII), copied to $D800
 * $13E8 / $13E9 background ($D021) / border ($D020)
 * $2000-$3F3F  bitmap
 * </pre>
 * $1000-$1FFF cannot hold screen or bitmap for the VIC (it sees the character ROM there), but the CPU can use it for data.
 */
public final class ViewerPrg {
    static final int BASIC = 0x0801, SCREEN = 0x0C00, COLOR_DATA = 0x1000, BG = 0x13E8, BORDER = 0x13E9, BITMAP = 0x2000;

    private ViewerPrg() {}

    /** Program for a checked bitmap (multicolor or hires). */
    public static byte[] build(BitmapConverter.Result r) {
        if (r.koala() != null) return multicolor(r.koala());
        if (r.hires() != null) return hires(r.hires());
        throw new IllegalArgumentException("The picture breaks the colour rules – no program possible.");
    }

    public static byte[] multicolor(KoalaImage k) {
        return build(true, k.bitmap, k.screen, k.colorRam, k.background, k.background);
    }

    /**
     * Text screen: the screen codes go to $0400, the colours to $D800; upper or lower case character set.
     * Shift+C= is switched off so the character set cannot be toggled while the picture is shown.
     */
    public static byte[] petscii(PetsciiImage p) {
        Asm6502 a = new Asm6502(BASIC);
        a.bytes(0x0B, 0x08, 0x0A, 0x00, 0x9E, '2', '0', '6', '1', 0x00, 0x00, 0x00);
        a.ldaImm(0).staAbs(0xC6);
        a.ldaImm(0x80).staAbs(0x0291);                     // no Shift+C= character set switch
        a.ldaAbs(0xDD02).oraImm(0x03).staAbs(0xDD02);
        a.ldaAbs(0xDD00).oraImm(0x03).staAbs(0xDD00);      // VIC bank 0
        a.ldaImm(0x1B).staAbs(0xD011);                     // text mode
        a.ldaImm(0xC8).staAbs(0xD016);
        a.ldaImm(p.charset == Charset.LOWER ? 0x17 : 0x15).staAbs(0xD018);
        a.ldaAbs(BG).staAbs(0xD021);
        a.ldaAbs(BORDER).staAbs(0xD020);
        a.ldxImm(0).label("copy");
        for (int off : new int[]{0, 0x100, 0x200, 0x2E8}) {
            a.ldaAbsX(SCREEN + off).staAbsX(0x0400 + off);
            a.ldaAbsX(COLOR_DATA + off).staAbsX(0xD800 + off);
        }
        a.inx().bne("copy");
        a.label("wait").jsr(0xFFE4).beq("wait");
        a.ldaImm(0x00).staAbs(0x0291);                     // Shift+C= allowed again
        a.ldaImm(0x15).staAbs(0xD018);
        a.ldaImm(0x0E).staAbs(0xD020);
        a.ldaImm(0x06).staAbs(0xD021);
        a.ldaImm(0x0E).staAbs(0x0286);                     // cursor colour light blue
        a.jsr(0xE544);
        a.rts();
        if (a.pc() > SCREEN) throw new IllegalStateException("Viewer code too long");
        byte[] codes = new byte[PetsciiImage.CELLS], cols = new byte[PetsciiImage.CELLS];
        for (int i = 0; i < PetsciiImage.CELLS; i++) { codes[i] = (byte) p.codes[i]; cols[i] = (byte) p.colours[i]; }
        a.org(SCREEN).bytes(codes);
        a.org(COLOR_DATA).bytes(cols);
        a.org(BG).bytes(p.background & 15, p.border & 15);
        return withLoadAddress(a.assemble());
    }

    public static byte[] hires(HiresImage h) {
        return build(false, h.bitmap, h.screen, new byte[C64Mode.CELLS], 0, h.border);
    }

    static byte[] build(boolean multicolor, byte[] bitmap, byte[] screen, byte[] colorRam, int background, int border) {
        Asm6502 a = new Asm6502(BASIC);
        // 10 SYS2061
        a.bytes(0x0B, 0x08, 0x0A, 0x00, 0x9E, '2', '0', '6', '1', 0x00, 0x00, 0x00);
        if (a.pc() != 0x080D) throw new IllegalStateException("BASIC line has the wrong length");

        a.ldaImm(0).staAbs(0xC6);                          // empty the keyboard buffer
        // VIC bank 0 ($DD00 bits 0/1 = %11)
        a.ldaAbs(0xDD02).oraImm(0x03).staAbs(0xDD02);
        a.ldaAbs(0xDD00).oraImm(0x03).staAbs(0xDD00);
        a.ldaImm(0x3B).staAbs(0xD011);                     // bitmap mode, screen on, 25 rows
        a.ldaImm(multicolor ? 0xD8 : 0xC8).staAbs(0xD016); // multicolor on/off, 40 columns
        a.ldaImm(0x38).staAbs(0xD018);                     // screen $0C00, bitmap $2000
        a.ldaAbs(BG).staAbs(0xD021);
        a.ldaAbs(BORDER).staAbs(0xD020);
        // colour RAM: 1000 bytes in four overlapping blocks of 256
        a.ldxImm(0).label("copy");
        for (int off : new int[]{0, 0x100, 0x200, 0x2E8}) a.ldaAbsX(COLOR_DATA + off).staAbsX(0xD800 + off);
        a.inx().bne("copy");
        // wait for a key (KERNAL GETIN)
        a.label("wait").jsr(0xFFE4).beq("wait");
        // back to the normal text screen
        a.ldaImm(0x1B).staAbs(0xD011);
        a.ldaImm(0xC8).staAbs(0xD016);
        a.ldaImm(0x15).staAbs(0xD018);
        a.ldaImm(0x0E).staAbs(0xD020);
        a.ldaImm(0x06).staAbs(0xD021);
        a.jsr(0xE544);                                     // clear screen
        a.rts();
        if (a.pc() > SCREEN) throw new IllegalStateException("Viewer code too long");

        a.org(SCREEN).bytes(screen);
        a.org(COLOR_DATA).bytes(colorRam);
        a.org(BG).bytes(background & 15, border & 15);
        a.org(BITMAP).bytes(bitmap);

        return withLoadAddress(a.assemble());
    }

    private static byte[] withLoadAddress(byte[] code) {
        byte[] prg = new byte[code.length + 2];
        prg[0] = (byte) (BASIC & 0xFF);
        prg[1] = (byte) (BASIC >> 8);
        System.arraycopy(code, 0, prg, 2, code.length);
        return prg;
    }
}
