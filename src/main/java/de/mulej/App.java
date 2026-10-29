package de.mulej;

import de.mulej.gui.MainWindow;

import javax.swing.*;

/** Entry point: without arguments the GUI starts, with arguments the command line tools run. */
public final class App {
    public static final String NAME = "MuleJ";
    public static final String VERSION = "0.1.0";

    private App() {}

    public static void main(String[] args) {
        if (args.length > 0) {
            System.exit(Cli.run(args));
        }
        de.mulej.i18n.I18n.init();
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // fall back to the default look and feel
            }
            // GTK (Linux) draws the slider value above the thumb, where it gets cut off
            UIManager.put("Slider.paintValue", Boolean.FALSE);
            de.mulej.gui.Icons.installTaskbarIcon();
            new MainWindow().setVisible(true);
        });
    }
}
