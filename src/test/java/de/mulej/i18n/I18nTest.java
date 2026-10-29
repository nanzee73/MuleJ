package de.mulej.i18n;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class I18nTest {

    private static Set<String> keys(String file) throws Exception {
        Properties p = new Properties();
        try (InputStream in = I18nTest.class.getResourceAsStream("/de/mulej/i18n/" + file)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return new TreeSet<>(p.stringPropertyNames());
    }

    @Test
    void everyLanguageHasTheSameKeys() throws Exception {
        Set<String> en = keys("messages.properties");
        Set<String> de = keys("messages_de.properties");
        Set<String> missing = new TreeSet<>(en);
        missing.removeAll(de);
        Set<String> extra = new TreeSet<>(de);
        extra.removeAll(en);
        assertEquals(0, missing.size() + extra.size(), "missing in de: " + missing + ", extra in de: " + extra);
    }

    /** Every literal key used in the sources ("x.y" passed to tr/text/tip/label/titled/...) must exist. */
    @Test
    void everyUsedKeyExists() throws Exception {
        Set<String> en = keys("messages.properties");
        java.util.regex.Pattern call = java.util.regex.Pattern.compile(
                "(?:tr|text|tip|label|titled|fixedLabel|group|setStatus|addFilter|chooser)\\((?:[^\"()]*?,\\s*)?\"([a-zA-Z][a-zA-Z0-9]*(?:\\.[a-zA-Z0-9]+)+)\"");
        Set<String> missing = new TreeSet<>();
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            for (var f : (Iterable<java.nio.file.Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                var m = call.matcher(java.nio.file.Files.readString(f));
                while (m.find()) if (!en.contains(m.group(1))) missing.add(m.group(1) + " (" + f.getFileName() + ")");
            }
        }
        assertEquals(Set.of(), missing);
    }

    /** Keys built at runtime from enum names and colour numbers. */
    @Test
    void generatedKeysExist() throws Exception {
        Set<String> en = keys("messages.properties");
        Set<String> need = new TreeSet<>();
        for (String t : new String[]{"PALETTIZE", "PIXELIZE", "KOALA"}) {
            need.add("tool." + t); need.add("tool." + t + ".tip"); need.add("view.result." + t);
        }
        for (var s : de.mulej.color.ColorMatcher.Space.values()) need.add("space." + s.name());
        for (var d : de.mulej.core.Pixelizer.Direction.values()) { need.add("pixel." + d.name()); need.add("pixel." + d.name() + ".tip"); }
        for (var m : de.mulej.image.ImageFitter.ScaleMode.values()) need.add("scale." + m.name());
        for (var f : de.mulej.image.ImageFitter.Filter.values()) need.add("scalefilter." + f.name());
        for (var a : de.mulej.image.ImageFitter.Aspect.values()) need.add("aspect." + a.name());
        for (int i = 0; i < 16; i++) need.add("color." + i);
        need.removeAll(en);
        assertEquals(Set.of(), need);
    }
}
