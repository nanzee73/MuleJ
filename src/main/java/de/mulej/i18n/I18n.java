package de.mulej.i18n;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.prefs.Preferences;

/**
 * User interface translations.
 * <p>
 * Texts live in {@code de/mulej/i18n/messages*.properties} (UTF-8). English is the base bundle.
 * Components register their texts with {@code bind(...)}; switching the language re-applies all bindings,
 * so the UI changes language without a restart. The chosen language is stored in the user preferences.
 * Swing's own dialogs (file chooser, color chooser, message boxes) follow via the default locale.
 */
public final class I18n {
    public static final List<Locale> SUPPORTED = List.of(Locale.ENGLISH, Locale.GERMAN);
    private static final String BUNDLE = "de.mulej.i18n.messages";
    private static final String PREF_KEY = "language";
    private static final List<Runnable> bindings = new ArrayList<>();
    private static Locale locale;
    private static ResourceBundle bundle;

    private I18n() {}

    private static Preferences prefs() { return Preferences.userRoot().node("de/mulej"); }

    /** Initializes from the stored preference; without one the UI starts in English. */
    public static synchronized void init() {
        String stored = null;
        try {
            stored = prefs().get(PREF_KEY, null);
        } catch (Exception ignored) {
            // preferences not available
        }
        Locale l = stored != null ? Locale.forLanguageTag(stored) : Locale.ENGLISH;
        apply(supported(l));
    }

    private static Locale supported(Locale l) {
        for (Locale s : SUPPORTED) if (s.getLanguage().equals(l.getLanguage())) return s;
        return Locale.ENGLISH;
    }

    private static void apply(Locale l) {
        locale = l;
        Locale.setDefault(l);
        JComponent.setDefaultLocale(l);
        bundle = ResourceBundle.getBundle(BUNDLE, l,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    /** Switches the language, stores it and updates every bound text. */
    public static synchronized void setLocale(Locale l) {
        apply(supported(l));
        try {
            prefs().put(PREF_KEY, locale.toLanguageTag());
        } catch (Exception ignored) {
            // preferences not available
        }
        for (Runnable r : new ArrayList<>(bindings)) r.run();
    }

    public static synchronized Locale locale() {
        if (locale == null) init();
        return locale;
    }

    /** Translated text; with arguments it is used as a {@link String#format} pattern. */
    public static String tr(String key, Object... args) {
        if (bundle == null) init();
        String s;
        try {
            s = bundle.getString(key);
        } catch (MissingResourceException e) {
            s = key;
        }
        return args.length == 0 ? s : String.format(locale, s, args);
    }

    /** Runs the action now and again after every language switch. */
    public static synchronized void bind(Runnable r) {
        bindings.add(r);
        r.run();
    }

    public static <T extends AbstractButton> T text(T b, String key) {
        bind(() -> b.setText(tr(key)));
        return b;
    }

    public static JLabel label(String key) {
        JLabel l = new JLabel();
        bind(() -> l.setText(tr(key)));
        return l;
    }

    public static <T extends JComponent> T tip(T c, String key, Object... args) {
        bind(() -> c.setToolTipText(tr(key, args)));
        return c;
    }

    public static TitledBorder titled(JComponent owner, String key) {
        TitledBorder b = new TitledBorder("");
        bind(() -> {
            b.setTitle(tr(key));
            owner.repaint();
        });
        return b;
    }
}
