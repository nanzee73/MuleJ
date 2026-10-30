package de.mulej.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The two C64 character sets (256 characters each, 8 bytes per character; codes 128–255 are the reversed characters).
 * The data is the C64 character ROM as shipped with Petmate (MIT), so MuleJ and Petmate show the same glyphs.
 */
public enum Charset {
    /** Upper case + graphics – the usual set for PETSCII art. */
    UPPER("upper"),
    /** Lower and upper case. */
    LOWER("lower");

    /** Name in Petmate files. */
    public final String petmateName;
    private byte[] data;

    Charset(String petmateName) { this.petmateName = petmateName; }

    /** 2048 bytes: character c, row r at c * 8 + r; bit 7 = leftmost pixel. */
    public synchronized byte[] data() {
        if (data == null) {
            try (InputStream in = Charset.class.getResourceAsStream("/de/mulej/charset/" + petmateName + ".bin")) {
                if (in == null) throw new IOException("character set " + petmateName + " missing");
                data = in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return data;
    }

    public boolean pixel(int code, int x, int y) {
        return ((data()[(code & 255) * 8 + y] >> (7 - x)) & 1) != 0;
    }

    /**
     * Whether a character is a letter, digit or punctuation mark (in the upper case set codes 0–63 and their
     * reversed versions; in the lower case set additionally 64–95). Space and reversed space are not.
     */
    public boolean isText(int code) {
        int c = code & 127;
        if (c == 32) return false;
        return this == UPPER ? c < 64 : c < 96;
    }

    public static Charset byName(String n) {
        for (Charset c : values()) if (c.petmateName.equalsIgnoreCase(n) || c.name().equalsIgnoreCase(n)) return c;
        throw new IllegalArgumentException("Unknown character set: " + n + " (upper, lower)");
    }

    @Override public String toString() { return de.mulej.i18n.I18n.tr("charset." + name()); }
}
