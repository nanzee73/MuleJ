package de.mulej;

import de.mulej.gui.MainWindow;

import de.c64gfx.swing.AppConfig;

import javax.swing.*;
import java.util.List;
import java.util.Locale;

/** Entry point: without arguments the GUI starts, with arguments the command line tools run. */
public final class App {
    public static final String NAME = "MuleJ";
    /** From the jar manifest (set by the build: tag version, "dev-<commit>" or the version in build.gradle.kts). */
    public static final String VERSION = version();

    private static String version() {
        String v = App.class.getPackage() != null ? App.class.getPackage().getImplementationVersion() : null;
        return v != null ? v : "dev";
    }

    private App() {}

    /** Tells C64GfxLib the app name, settings node, icons and texts. Call before anything else. */
    public static void configure() {
        AppConfig.install(new AppConfig(NAME, "de/mulej", "/de/mulej/icons/mulej-",
                "de.mulej.i18n.messages", List.of(Locale.ENGLISH, Locale.GERMAN), true));   // follows the system language until one is chosen
    }

    public static void main(String[] args) {
        configure();
        if (args.length > 0) {
            System.exit(Cli.run(args));
        }
        de.c64gfx.swing.Platform.initBeforeAwt(NAME);
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
            MainWindow w = new MainWindow();
            installDesktopHandlers(w);
            w.setVisible(true);
        });
    }

    /** macOS application menu: About and Quit (⌘Q) call into the app, like in FlipperJ. */
    private static void installDesktopHandlers(MainWindow w) {
        if (!java.awt.Desktop.isDesktopSupported()) return;
        java.awt.Desktop d = java.awt.Desktop.getDesktop();
        try {
            if (d.isSupported(java.awt.Desktop.Action.APP_ABOUT)) d.setAboutHandler(e -> w.showAbout());
            if (d.isSupported(java.awt.Desktop.Action.APP_QUIT_HANDLER)) d.setQuitHandler((e, r) -> {
                if (w.requestQuit()) r.performQuit();       // asks about an unsaved project first
                else r.cancelQuit();
            });
        } catch (Exception ignored) {
            // not supported on this platform
        }
    }
}
