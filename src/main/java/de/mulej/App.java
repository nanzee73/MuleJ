package de.mulej;

import de.mulej.gui.MainWindow;

import de.c64gfx.swing.AppConfig;

import javax.swing.*;
import java.util.List;
import java.util.Locale;

/** Entry point: without arguments the GUI starts, with arguments the command line tools run. */
public final class App {
    public static final String NAME = "MuleJ";
    public static final String VERSION = "0.1.0";

    private App() {}

    /** Tells C64GfxLib the app name, settings node, icons and texts. Call before anything else. */
    public static void configure() {
        AppConfig.install(new AppConfig(NAME, "de/mulej", "/de/mulej/icons/mulej-",
                "de.mulej.i18n.messages", List.of(Locale.ENGLISH, Locale.GERMAN), false));
    }

    public static void main(String[] args) {
        configure();
        if (args.length > 0) {
            System.exit(Cli.run(args));
        }
        de.c64gfx.swing.I18n.init();
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // fall back to the default look and feel
            }
            // GTK (Linux) draws the slider value above the thumb, where it gets cut off
            UIManager.put("Slider.paintValue", Boolean.FALSE);
            de.c64gfx.swing.Icons.installTaskbarIcon();
            new MainWindow().setVisible(true);
        });
    }
}
