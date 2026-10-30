package de.mulej.help;

import de.c64gfx.swing.AppConfig;
import de.c64gfx.swing.HelpDialog;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The user guide exists in every language, and every link inside it has a target. */
class HelpTest {
    private static final String PREFIX = "/de/mulej/help/help";

    private static Set<String> find(String html, String regex) {
        Set<String> s = new TreeSet<>();
        Matcher m = Pattern.compile(regex).matcher(html);
        while (m.find()) s.add(m.group(1));
        return s;
    }

    @Test
    void everyLanguageHasACompleteGuide() throws Exception {
        Set<String> english = find(HelpDialog.load(PREFIX, "en"), "<a name=\"([^\"]+)\">");
        for (Locale l : java.util.List.of(java.util.Locale.ENGLISH, java.util.Locale.GERMAN)) {
            String lang = l.getLanguage();
            assertTrue(getClass().getResource(PREFIX + "_" + lang + ".html") != null, "guide missing for " + lang);
            String html = HelpDialog.load(PREFIX, lang);
            Set<String> anchors = find(html, "<a name=\"([^\"]+)\">");
            assertEquals(english, anchors, "sections of " + lang);
            for (String link : find(html, "<a href=\"#([^\"]+)\">"))
                assertTrue(anchors.contains(link), lang + ": link #" + link + " has no target");
        }
    }
}
