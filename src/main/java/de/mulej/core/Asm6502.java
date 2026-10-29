package de.mulej.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal 6502 assembler with labels, so the displayer code stays readable and branch offsets
 * are computed automatically. Only the instructions actually needed are implemented.
 */
public final class Asm6502 {
    private enum Kind { ABS, REL, LOW, HIGH }

    private record Fixup(int offset, String label, Kind kind) {}

    private final int origin;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final Map<String, Integer> labels = new HashMap<>();
    private final List<Fixup> fixups = new ArrayList<>();

    public Asm6502(int origin) { this.origin = origin; }

    public int pc() { return origin + out.size(); }

    public Asm6502 label(String name) {
        if (labels.put(name, pc()) != null) throw new IllegalStateException("Duplicate label: " + name);
        return this;
    }

    public int addressOf(String name) {
        Integer a = labels.get(name);
        if (a == null) throw new IllegalStateException("Unknown label: " + name);
        return a;
    }

    /** Pads with zeros up to the given address. */
    public Asm6502 org(int address) {
        if (address < pc()) throw new IllegalStateException(String.format("org $%04X is before PC $%04X", address, pc()));
        while (pc() < address) out.write(0);
        return this;
    }

    public Asm6502 bytes(int... b) { for (int v : b) out.write(v & 0xFF); return this; }
    public Asm6502 bytes(byte[] b) { out.write(b, 0, b.length); return this; }

    private Asm6502 op(int opcode) { out.write(opcode); return this; }
    private Asm6502 op8(int opcode, int v) { out.write(opcode); out.write(v & 0xFF); return this; }
    private Asm6502 op16(int opcode, int v) { out.write(opcode); out.write(v & 0xFF); out.write((v >> 8) & 0xFF); return this; }

    private Asm6502 withFixup(int opcode, String label, Kind kind, int size) {
        out.write(opcode);
        fixups.add(new Fixup(out.size(), label, kind));
        for (int i = 0; i < size; i++) out.write(0);
        return this;
    }

    // loads / stores
    public Asm6502 ldaImm(int v) { return op8(0xA9, v); }
    public Asm6502 ldaImmLow(String l) { return withFixup(0xA9, l, Kind.LOW, 1); }
    public Asm6502 ldaImmHigh(String l) { return withFixup(0xA9, l, Kind.HIGH, 1); }
    public Asm6502 ldaZp(int zp) { return op8(0xA5, zp); }
    public Asm6502 ldaAbs(int a) { return op16(0xAD, a); }
    public Asm6502 ldaAbs(String l) { return withFixup(0xAD, l, Kind.ABS, 2); }
    public Asm6502 ldaAbsX(int a) { return op16(0xBD, a); }
    public Asm6502 ldaAbsX(String l) { return withFixup(0xBD, l, Kind.ABS, 2); }
    public Asm6502 ldaIndY(int zp) { return op8(0xB1, zp); }
    public Asm6502 staAbs(int a) { return op16(0x8D, a); }
    public Asm6502 staAbsX(int a) { return op16(0x9D, a); }
    public Asm6502 staZp(int zp) { return op8(0x85, zp); }
    public Asm6502 staIndY(int zp) { return op8(0x91, zp); }
    public Asm6502 ldxImm(int v) { return op8(0xA2, v); }
    public Asm6502 ldyImm(int v) { return op8(0xA0, v); }

    // arithmetic / logic
    public Asm6502 andImm(int v) { return op8(0x29, v); }
    public Asm6502 oraImm(int v) { return op8(0x09, v); }
    public Asm6502 eorImm(int v) { return op8(0x49, v); }
    public Asm6502 cmpAbs(int a) { return op16(0xCD, a); }
    public Asm6502 cpxImm(int v) { return op8(0xE0, v); }
    public Asm6502 bitAbs(int a) { return op16(0x2C, a); }
    public Asm6502 incZp(int zp) { return op8(0xE6, zp); }
    public Asm6502 inx() { return op(0xE8); }
    public Asm6502 iny() { return op(0xC8); }
    public Asm6502 dex() { return op(0xCA); }

    // control flow
    public Asm6502 sei() { return op(0x78); }
    public Asm6502 nop() { return op(0xEA); }
    public Asm6502 rti() { return op(0x40); }
    public Asm6502 jmp(String l) { return withFixup(0x4C, l, Kind.ABS, 2); }
    public Asm6502 jsr(int a) { return op16(0x20, a); }
    public Asm6502 rts() { return op(0x60); }
    public Asm6502 bne(String l) { return withFixup(0xD0, l, Kind.REL, 1); }
    public Asm6502 beq(String l) { return withFixup(0xF0, l, Kind.REL, 1); }
    public Asm6502 bpl(String l) { return withFixup(0x10, l, Kind.REL, 1); }
    public Asm6502 bmi(String l) { return withFixup(0x30, l, Kind.REL, 1); }

    /** Ensures that timing-critical code does not cross a page boundary (would cost an extra cycle). */
    public void assertSamePage(String from, String to) {
        if ((addressOf(from) >> 8) != (addressOf(to) >> 8))
            throw new IllegalStateException("Timing-critical code crosses a page boundary: " + from + ".." + to);
    }

    public byte[] assemble() {
        byte[] b = out.toByteArray();
        for (Fixup f : fixups) {
            int target = addressOf(f.label());
            switch (f.kind()) {
                case REL -> {
                    int rel = target - (origin + f.offset() + 1);
                    if (rel < -128 || rel > 127) throw new IllegalStateException("Branch out of range: " + f.label());
                    b[f.offset()] = (byte) rel;
                }
                case LOW -> b[f.offset()] = (byte) target;
                case HIGH -> b[f.offset()] = (byte) (target >> 8);
                case ABS -> {
                    b[f.offset()] = (byte) target;
                    b[f.offset() + 1] = (byte) (target >> 8);
                }
            }
        }
        return b;
    }
}
