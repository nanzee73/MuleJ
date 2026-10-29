package de.mulej.gui;

import de.mulej.palette.Palette;
import de.mulej.palette.Palettes;

import javax.swing.*;

import static de.mulej.i18n.I18n.tr;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;

/** Editor for a custom palette: click a color to change it; load/save .vpl/.gpl/hex files. */
public class PaletteEditorDialog extends JDialog {
    private Palette palette;
    private boolean accepted;
    private final JButton[] swatches = new JButton[16];
    private final JTextField nameField = new JTextField(20);

    public PaletteEditorDialog(Frame owner, Palette start) {
        super(owner, tr("pal.title"), true);
        this.palette = start.renamed(tr("pal.custom"));
        nameField.setText(tr("pal.custom"));

        JPanel grid = new JPanel(new GridLayout(4, 4, 4, 4));
        for (int i = 0; i < 16; i++) {
            final int idx = i;
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(110, 44));
            b.setFocusPainted(false);
            b.addActionListener(e -> {
                Color chosen = JColorChooser.showDialog(this, tr("color." + idx), new Color(palette.rgb(idx)));
                if (chosen != null) {
                    palette = palette.withColor(idx, chosen.getRGB() & 0xFFFFFF);
                    updateSwatches();
                }
            });
            swatches[i] = b;
            grid.add(b);
        }
        updateSwatches();

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel(tr("pal.name")));
        top.add(nameField);
        JButton load = new JButton(tr("pal.load")), save = new JButton(tr("pal.save"));
        top.add(load);
        top.add(save);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton ok = new JButton(tr("ok")), cancel = new JButton(tr("cancel"));
        buttons.add(ok);
        buttons.add(cancel);

        load.addActionListener(e -> {
            JFileChooser fc = FileDialogs.chooser("pal.loadTitle", FileDialogs.lastDir("palette"));
            FileDialogs.addFilter(fc, "filter.palette", "vpl", "gpl", "hex", "txt");
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                FileDialogs.rememberDir("palette", fc.getSelectedFile());
                try {
                    palette = Palettes.load(fc.getSelectedFile().toPath());
                    nameField.setText(palette.name());
                    updateSwatches();
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this, ex.getMessage(), tr("pal.loadTitle"), JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        save.addActionListener(e -> {
            JFileChooser fc = FileDialogs.chooser("pal.saveTitle", FileDialogs.lastDir("palette"));
            FileDialogs.addFilter(fc, "filter.vpl", "vpl");
            if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                FileDialogs.rememberDir("palette", fc.getSelectedFile());
                Path p = fc.getSelectedFile().toPath();
                if (!p.toString().toLowerCase().endsWith(".vpl")) p = Path.of(p + ".vpl");
                try {
                    Palettes.saveVpl(palette.renamed(nameField.getText()), p);
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this, ex.getMessage(), tr("pal.saveTitle"), JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        ok.addActionListener(e -> { accepted = true; dispose(); });
        cancel.addActionListener(e -> dispose());

        setLayout(new BorderLayout(8, 8));
        add(top, BorderLayout.NORTH);
        add(grid, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
    }

    private void updateSwatches() {
        for (int i = 0; i < 16; i++) {
            Color c = new Color(palette.rgb(i));
            swatches[i].setBackground(c);
            swatches[i].setOpaque(true);
            swatches[i].setBorderPainted(true);
            float luma = (0.299f * c.getRed() + 0.587f * c.getGreen() + 0.114f * c.getBlue());
            swatches[i].setForeground(luma > 128 ? Color.BLACK : Color.WHITE);
            swatches[i].setText(String.format("<html><center>%d %s<br>#%06X</center></html>",
                    i, tr("color." + i), palette.rgb(i)));
        }
    }

    public boolean isAccepted() { return accepted; }

    public Palette getPalette() {
        String n = nameField.getText().isBlank() ? tr("pal.custom") : nameField.getText().trim();
        return palette.renamed(n);
    }
}
