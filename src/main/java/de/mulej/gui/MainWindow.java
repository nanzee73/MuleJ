package de.mulej.gui;

import de.mulej.App;
import de.mulej.color.ColorMatcher;
import de.mulej.core.*;
import de.mulej.i18n.I18n;
import de.mulej.image.ImageFitter;
import de.mulej.palette.Palette;
import de.mulej.palette.Palettes;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.function.Supplier;

import static de.mulej.i18n.I18n.tr;

/**
 * Main window, built like FlipperJ: tool and files on top, settings groups, colour check & correction,
 * previews (original / result) below. The result is recomputed automatically after every change;
 * "Save" writes the files.
 */
public class MainWindow extends JFrame {

    /** The former command line tools; BITMAP covers c64koalaverter (multicolor) and hires. */
    enum Tool { PALETTIZE, PIXELIZE, BITMAP }

    /** What a left click into the result does while the bitmap tool is active. */
    private enum EditTool { SELECT, PAINT }

    private static final double PAL_PIXEL = 0.9365;

    // tool
    private final JRadioButton[] toolButtons = new JRadioButton[Tool.values().length];

    // files
    private final JTextField inputField = new JTextField();
    private final JTextField outputField = new JTextField();
    private final JCheckBox toC64Size = I18n.tip(I18n.text(new JCheckBox("", true), "file.toC64"), "file.toC64.tip");
    /** Output folder chosen by the user; null = next to the input picture. */
    private File outputDirChosen;

    // palette & matching
    private final DefaultComboBoxModel<Palette> paletteModel = new DefaultComboBoxModel<>();
    private final JComboBox<Palette> paletteBox = new JComboBox<>(paletteModel);
    private final JComboBox<ColorMatcher.Space> spaceBox = new JComboBox<>(ColorMatcher.Space.values());

    // pixel pairs
    private final JRadioButton pairL2R = new JRadioButton("", true), pairR2L = new JRadioButton(), pairAvg = new JRadioButton();
    private final JCheckBox bothDirections = I18n.tip(I18n.text(new JCheckBox("", true), "pix.both"), "pix.both.tip");

    // dithering
    private final JComboBox<Dither> ditherBox = new JComboBox<>(Dither.values());
    private final JSlider strength = new JSlider(0, 100, 80);
    private final JCheckBox serpentine = I18n.text(new JCheckBox(), "dit.serpentine");
    private final JCheckBox bleeding = I18n.tip(I18n.text(new JCheckBox("", true), "dit.bleeding"), "dit.bleeding.tip");

    // C64 bitmap
    private final JRadioButton modeMc = new JRadioButton("", true), modeHires = new JRadioButton();
    private final JCheckBox bitmapPalettize = I18n.tip(I18n.text(new JCheckBox("", true), "bmp.palettize"), "bmp.palettize.tip");
    private final JComboBox<Integer> backgroundBox = new JComboBox<>();
    private final JCheckBox fixClashes = I18n.tip(I18n.text(new JCheckBox(), "bmp.fix"), "bmp.fix.tip");
    private final JCheckBox palAspect = I18n.text(new JCheckBox("", true), "view.palAspect");

    // colour check & correction
    private JPanel checkPanel;
    private final JLabel checkLabel = new JLabel(" ");
    private final JCheckBox markCells = I18n.tip(I18n.text(new JCheckBox("", true), "chk.mark"), "chk.mark.tip");
    private final JRadioButton toolSelect = I18n.tip(I18n.text(new JRadioButton("", true), "chk.select"), "chk.select.tip");
    private final JRadioButton toolPaint = I18n.tip(I18n.text(new JRadioButton(), "chk.paint"), "chk.paint.tip");
    private final JComboBox<Integer> paintColour = new JComboBox<>();
    private final JLabel cellLabel = new JLabel();
    private final JToggleButton[] swatches = new JToggleButton[16];
    private final JLabel cellHint = new JLabel(" ");
    private final JButton applyCell = I18n.tip(I18n.text(new JButton(), "chk.apply"), "chk.apply.tip");
    private final JButton autoCell = I18n.tip(I18n.text(new JButton(), "chk.auto"), "chk.auto.tip");
    private final JButton resetCell = I18n.tip(I18n.text(new JButton(), "chk.resetCell"), "chk.resetCell.tip");
    private final JButton resetAll = I18n.tip(I18n.text(new JButton(), "chk.resetAll"), "chk.resetAll.tip");
    private final Map<Integer, int[]> cellEdits = new HashMap<>();
    private final Map<Integer, Integer> pixelEdits = new HashMap<>();
    private int selectedCell = -1;

    // previews
    private final ImagePanel leftPanel = new ImagePanel(), rightPanel = new ImagePanel();
    private final JLabel leftTitle = new JLabel(), rightTitle = new JLabel();
    private final JCheckBox linkViews = I18n.tip(I18n.text(new JCheckBox("", true), "view.link"), "view.link.tip");
    private final JCheckBox showGrid = I18n.tip(I18n.text(new JCheckBox("", true), "view.grid"), "view.grid.tip");

    private final JButton cropButton = I18n.tip(I18n.text(new JButton(), "bottom.crop"), "bottom.crop.tip");
    private final JButton runButton = I18n.tip(I18n.text(new JButton(), "bottom.run"), "bottom.run.tip");
    private final JProgressBar progress = new JProgressBar();
    private final JLabel status = new JLabel(" ");
    private Supplier<String> statusText = () -> " ";
    private Supplier<String> checkText = () -> " ";

    // state
    private File inputFile;
    private BufferedImage source;
    /** Crop & scale to 320×200; null = not chosen (picture used as it is). */
    private ImageFitter.Settings fitSettings;
    private ImageFitter.Settings reframeBackup;
    /** {@link #source} brought to 320×200 with {@link #fitSettings}. */
    private BufferedImage fitted;
    private Computed result;
    private long generation;
    private SwingWorker<Computed, Void> worker;
    private final javax.swing.Timer updateTimer = new javax.swing.Timer(150, e -> recompute());
    private final javax.swing.Timer fitTimer = new javax.swing.Timer(120, e -> applyFit());

    /** Outcome of one computation (always for one tool). */
    private record Computed(Tool tool, BufferedImage image, BufferedImage second,
                            BitmapConverter.Result bitmap, String error) {}

    public MainWindow() {
        I18n.bind(() -> setTitle(tr("app.title", App.VERSION)));
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setIconImages(Icons.appIcons());
        for (Palette p : Palettes.builtIns()) paletteModel.addElement(p);
        paletteBox.setSelectedItem(Palettes.COLODORE);
        spaceBox.setRenderer(renderer(v -> tr("space." + ((ColorMatcher.Space) v).name()), null));
        for (int i = -1; i < 16; i++) backgroundBox.addItem(i);
        backgroundBox.setRenderer(renderer(v -> {
            int i = (Integer) v;
            return i < 0 ? tr("bmp.bgAuto") : i + " " + tr("color." + i);
        }, v -> (Integer) v));
        for (int i = 0; i < 16; i++) paintColour.addItem(i);
        paintColour.setRenderer(renderer(v -> v + " " + tr("color." + v), v -> (Integer) v));
        ditherBox.setSelectedItem(Dither.NONE);
        updateTimer.setRepeats(false);
        fitTimer.setRepeats(false);

        setJMenuBar(buildMenu());
        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.add(buildToolPanel());
        north.add(buildFilePanel());
        north.add(buildSettingsPanel());
        north.add(buildCheckPanel());
        root.add(north, BorderLayout.NORTH);
        root.add(buildPreviewPanel(), BorderLayout.CENTER);
        root.add(buildBottomPanel(), BorderLayout.SOUTH);
        setContentPane(root);
        installDragAndDrop(root);
        wireListeners();
        I18n.bind(() -> status.setText(statusText.get()));
        I18n.bind(() -> checkLabel.setText(checkText.get()));
        I18n.bind(() -> leftPanel.setPlaceholder(tr("preview.placeholder.left")));
        I18n.bind(() -> rightPanel.setPlaceholder(tr("preview.placeholder.right")));
        I18n.bind(this::updateTitles);
        I18n.bind(this::updateCellPanel);
        I18n.tip(leftPanel, "preview.left.tip");
        I18n.bind(() -> rightPanel.setToolTipText(tr(tool() == Tool.BITMAP ? "preview.edit.tip" : "preview.tip")));
        leftPanel.setRightClickAction(this::startReframe);
        rightPanel.setRightClickAction(null);
        toolChanged();
        pack();
        setMinimumSize(new Dimension(1100, 820));
        setLocationRelativeTo(null);
    }

    /** List renderer with translated text and an optional colour square (palette index). */
    private DefaultListCellRenderer renderer(java.util.function.Function<Object, String> text,
                                            java.util.function.Function<Object, Integer> colour) {
        return new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                    boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value == null ? "" : text.apply(value), index, selected, focus);
                Integer c = value == null || colour == null ? null : colour.apply(value);
                setIcon(c != null && c >= 0 ? new ColourIcon(palette().rgb(c), 12) : null);
                return this;
            }
        };
    }

    /** A filled square in a palette colour. */
    private record ColourIcon(int rgb, int size) implements Icon {
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(new Color(rgb));
            g.fillRect(x, y, size, size);
            g.setColor(Color.GRAY);
            g.drawRect(x, y, size - 1, size - 1);
        }
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
    }

    // ------------------------------------------------------------------ UI construction

    private JMenuBar buildMenu() {
        JMenuBar bar = new JMenuBar();
        JMenu file = I18n.text(new JMenu(), "menu.file");
        JMenuItem open = I18n.text(new JMenuItem(), "menu.file.open");
        open.addActionListener(e -> chooseInput());
        JMenuItem crop = I18n.text(new JMenuItem(), "menu.file.crop");
        crop.addActionListener(e -> openCropDialog());
        JMenuItem reframe = I18n.text(new JMenuItem(), "menu.file.reframe");
        reframe.addActionListener(e -> startReframe());
        JMenuItem exit = I18n.text(new JMenuItem(), "menu.file.exit");
        exit.addActionListener(e -> dispose());
        file.add(open);
        file.add(crop);
        file.add(reframe);
        file.addSeparator();
        file.add(exit);

        JMenu language = I18n.text(new JMenu(), "menu.language");
        ButtonGroup lg = new ButtonGroup();
        for (Locale l : I18n.SUPPORTED) {
            String name = l.getDisplayLanguage(l);
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(
                    name.substring(0, 1).toUpperCase(l) + name.substring(1),
                    l.getLanguage().equals(I18n.locale().getLanguage()));
            item.addActionListener(e -> switchLanguage(l));
            lg.add(item);
            language.add(item);
        }

        JMenu help = I18n.text(new JMenu(), "menu.help");
        JMenuItem about = I18n.text(new JMenuItem(), "menu.help.about");
        about.addActionListener(e -> JOptionPane.showMessageDialog(this, tr("about.text", App.VERSION),
                tr("about.title"), JOptionPane.INFORMATION_MESSAGE, aboutIcon()));
        help.add(about);
        bar.add(file);
        bar.add(language);
        bar.add(help);
        return bar;
    }

    private void switchLanguage(Locale l) {
        I18n.setLocale(l);
        SwingUtilities.updateComponentTreeUI(this);
        revalidate();
        repaint();
    }

    private JPanel buildToolPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        p.setBorder(BorderFactory.createEtchedBorder());
        p.add(I18n.label("tool.label"));
        ButtonGroup g = new ButtonGroup();
        for (Tool t : Tool.values()) {
            JRadioButton b = I18n.tip(I18n.text(new JRadioButton("", t == Tool.PALETTIZE), "tool." + t.name()),
                    "tool." + t.name() + ".tip");
            toolButtons[t.ordinal()] = b;
            g.add(b);
            p.add(b);
        }
        return p;
    }

    private JPanel buildFilePanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEtchedBorder());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        JButton selIn = I18n.text(new JButton(), "file.select"), selOut = I18n.text(new JButton(), "file.select");
        selIn.addActionListener(e -> chooseInput());
        selOut.addActionListener(e -> chooseOutput());
        inputField.setEditable(false);
        c.gridy = 0; c.gridx = 0; p.add(fixedLabel("file.input"), c);
        c.gridx = 1; c.weightx = 1; p.add(inputField, c);
        c.gridx = 2; c.weightx = 0; p.add(selIn, c);
        c.gridy = 1; c.gridx = 1;
        JPanel opts = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        opts.add(toC64Size);
        opts.add(I18n.label("file.hint"));
        p.add(opts, c);
        c.gridy = 2; c.gridx = 0; p.add(fixedLabel("file.output"), c);
        c.gridx = 1; c.weightx = 1; p.add(outputField, c);
        c.gridx = 2; c.weightx = 0; p.add(selOut, c);
        return p;
    }

    private JPanel palettePanel, pixelPanel, ditherPanel, bitmapPanel;

    private JPanel buildSettingsPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        c.weighty = 1;
        c.gridx = 0; p.add(buildPalettePanel(), c);
        c.gridx = 1; p.add(buildPixelPanel(), c);
        c.gridx = 2; p.add(buildDitherPanel(), c);
        c.gridx = 3; p.add(buildBitmapPanel(), c);
        return p;
    }

    private JPanel buildPalettePanel() {
        JPanel p = palettePanel = group("pal.group");
        GridBagConstraints c = gbc();
        JButton custom = I18n.text(new JButton(), "pal.customButton");
        custom.addActionListener(e -> editCustomPalette());
        c.gridy = 0; c.gridx = 0; c.gridwidth = 2; p.add(paletteBox, c);
        c.gridy = 1; c.gridx = 0; c.gridwidth = 1; p.add(custom, c);
        c.gridy = 2; c.gridx = 0; c.gridwidth = 2; p.add(fixedLabel("pal.matchIn"), c);
        c.gridy = 3; p.add(spaceBox, c);
        addDefaultButton(p, () -> {
            paletteBox.setSelectedItem(Palettes.COLODORE);
            spaceBox.setSelectedItem(ColorMatcher.Space.EUCLID);
        });
        return p;
    }

    private JPanel buildPixelPanel() {
        JPanel p = pixelPanel = group("pix.group");
        GridBagConstraints c = gbc();
        ButtonGroup g = new ButtonGroup();
        g.add(pairL2R); g.add(pairR2L); g.add(pairAvg);
        I18n.tip(I18n.text(pairL2R, "pixel.L2R"), "pixel.L2R.tip");
        I18n.tip(I18n.text(pairR2L, "pixel.R2L"), "pixel.R2L.tip");
        I18n.tip(I18n.text(pairAvg, "pixel.AVERAGE"), "pixel.AVERAGE.tip");
        c.gridx = 0;
        c.gridy = 0; p.add(pairL2R, c);
        c.gridy = 1; p.add(pairR2L, c);
        c.gridy = 2; p.add(pairAvg, c);
        c.gridy = 3; p.add(bothDirections, c);
        addDefaultButton(p, () -> {
            pairL2R.setSelected(true);
            bothDirections.setSelected(true);
            settingsChanged();
            updateOutputPath();
        });
        return p;
    }

    private JPanel buildDitherPanel() {
        JPanel p = ditherPanel = group("dit.group");
        GridBagConstraints c = gbc();
        ditherBox.setRenderer(renderer(Object::toString, null));
        c.gridy = 0; c.gridx = 0; c.gridwidth = 3; p.add(ditherBox, c);
        c.gridwidth = 1;
        JLabel value = new JLabel("80 %");
        strength.setPreferredSize(new Dimension(110, strength.getPreferredSize().height));
        strength.addChangeListener(e -> value.setText(strength.getValue() + " %"));
        c.gridy = 1; c.gridx = 0; p.add(fixedLabel("dit.strength"), c);
        c.gridx = 1; c.weightx = 1; p.add(strength, c);
        c.gridx = 2; c.weightx = 0; p.add(value, c);
        c.gridx = 0; c.gridwidth = 3;
        c.gridy = 2; p.add(serpentine, c);
        c.gridy = 3; p.add(bleeding, c);
        addDefaultButton(p, () -> {
            ditherBox.setSelectedItem(Dither.NONE);
            strength.setValue(80);
            serpentine.setSelected(false);
            bleeding.setSelected(true);
            settingsChanged();
        });
        return p;
    }

    private JPanel buildBitmapPanel() {
        JPanel p = bitmapPanel = group("bmp.group");
        GridBagConstraints c = gbc();
        ButtonGroup g = new ButtonGroup();
        g.add(modeMc); g.add(modeHires);
        I18n.tip(I18n.text(modeMc, "mode.MULTICOLOR"), "mode.MULTICOLOR.tip");
        I18n.tip(I18n.text(modeHires, "mode.HIRES"), "mode.HIRES.tip");
        c.gridx = 0; c.gridwidth = 2;
        c.gridy = 0; p.add(row(modeMc, modeHires), c);
        c.gridy = 1; p.add(bitmapPalettize, c);
        c.gridwidth = 1;
        c.gridy = 2; p.add(fixedLabel("bmp.background"), c);
        c.gridx = 1; p.add(backgroundBox, c);
        c.gridx = 0; c.gridwidth = 2;
        c.gridy = 3; p.add(fixClashes, c);
        c.gridy = 4; p.add(palAspect, c);
        addDefaultButton(p, () -> {
            bitmapPalettize.setSelected(true);
            backgroundBox.setSelectedIndex(0);
            fixClashes.setSelected(false);
            palAspect.setSelected(true);
            updateAspect();
            settingsChanged();
        });
        return p;
    }

    /** Colour check result, conflict navigation and the manual correction of single cells. */
    private JPanel buildCheckPanel() {
        JPanel p = checkPanel = group("chk.group");
        GridBagConstraints c = gbc();
        JButton prev = I18n.tip(new JButton("◀"), "chk.prev.tip"), next = I18n.tip(new JButton("▶"), "chk.next.tip");
        prev.setMargin(new Insets(1, 6, 1, 6));
        next.setMargin(new Insets(1, 6, 1, 6));
        prev.addActionListener(e -> jumpToClash(-1));
        next.addActionListener(e -> jumpToClash(1));
        ButtonGroup g = new ButtonGroup();
        g.add(toolSelect); g.add(toolPaint);
        JPanel line1 = new JPanel(new BorderLayout(8, 0));
        checkLabel.setFont(checkLabel.getFont().deriveFont(Font.BOLD));
        line1.add(checkLabel, BorderLayout.CENTER);
        JPanel nav = row(I18n.label("chk.conflict"), prev, next, markCells, Box.createHorizontalStrut(12),
                I18n.label("chk.click"), toolSelect, toolPaint, paintColour);
        line1.add(nav, BorderLayout.EAST);
        c.gridx = 0; c.gridy = 0; c.weightx = 1; p.add(line1, c);

        JPanel sw = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        sw.add(cellLabel);
        for (int i = 0; i < 16; i++) {
            JToggleButton b = new JToggleButton();
            b.setMargin(new Insets(1, 2, 1, 2));
            b.setFocusable(false);
            b.setHorizontalTextPosition(SwingConstants.CENTER);
            b.setVerticalTextPosition(SwingConstants.BOTTOM);
            b.setFont(b.getFont().deriveFont(10f));
            b.addActionListener(e -> updateCellHint());
            swatches[i] = b;
            sw.add(b);
        }
        sw.add(Box.createHorizontalStrut(8));
        sw.add(applyCell);
        sw.add(autoCell);
        sw.add(resetCell);
        sw.add(resetAll);
        c.gridy = 1; p.add(sw, c);
        c.gridy = 2; p.add(cellHint, c);
        applyCell.addActionListener(e -> applyCellColours());
        autoCell.addActionListener(e -> autoCellColours());
        resetCell.addActionListener(e -> resetSelectedCell());
        resetAll.addActionListener(e -> {
            cellEdits.clear();
            pixelEdits.clear();
            settingsChanged();
        });
        markCells.addActionListener(e -> updateRight());
        toolSelect.addActionListener(e -> editToolChanged());
        toolPaint.addActionListener(e -> editToolChanged());
        paintColour.setSelectedIndex(1);
        return p;
    }

    private static JPanel group(String titleKey) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(I18n.titled(p, titleKey));
        return p;
    }

    private static GridBagConstraints gbc() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(1, 3, 1, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        return c;
    }

    private static JPanel row(Component... comps) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        for (Component comp : comps) r.add(comp);
        return r;
    }

    /** A label whose minimum width always fits its (translated) text. */
    private static JLabel fixedLabel(String key) {
        JLabel l = new JLabel();
        I18n.bind(() -> {
            l.setText(tr(key));
            l.setMinimumSize(null);
            l.setMinimumSize(l.getPreferredSize());
        });
        return l;
    }

    /** Adds a "Defaults" button to the bottom right of a settings group. */
    private static void addDefaultButton(JPanel p, Runnable reset) {
        JButton b = I18n.tip(I18n.text(new JButton(), "defaults"), "defaults.tip");
        b.setMargin(new Insets(1, 6, 1, 6));
        b.addActionListener(e -> reset.run());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 99;
        c.gridwidth = GridBagConstraints.REMAINDER;
        c.anchor = GridBagConstraints.SOUTHEAST;
        c.weighty = 1;
        c.insets = new Insets(4, 3, 2, 3);
        p.add(b, c);
    }

    private JPanel buildPreviewPanel() {
        JPanel p = new JPanel(new GridLayout(1, 2, 6, 0));
        p.add(previewBox(leftPanel, leftTitle));
        p.add(previewBox(rightPanel, rightTitle));
        leftPanel.addViewListener(src -> { if (linkViews.isSelected() && sameSize()) rightPanel.copyViewFrom(src); });
        rightPanel.addViewListener(src -> { if (linkViews.isSelected() && sameSize()) leftPanel.copyViewFrom(src); });
        linkViews.addActionListener(e -> { if (linkViews.isSelected() && sameSize()) rightPanel.copyViewFrom(leftPanel); });
        showGrid.addActionListener(e -> {
            leftPanel.setShowGrid(showGrid.isSelected());
            rightPanel.setShowGrid(showGrid.isSelected());
        });
        return p;
    }

    /** Linking only makes sense while both previews show pictures of the same size. */
    private boolean sameSize() {
        BufferedImage a = leftPanel.getImage(), b = rightPanel.getImage();
        return a == null || b == null || (a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight());
    }

    private JPanel previewBox(ImagePanel panel, JLabel title) {
        JPanel box = new JPanel(new BorderLayout());
        box.setBorder(BorderFactory.createEtchedBorder());
        JPanel header = new JPanel(new BorderLayout());
        title.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        header.add(title, BorderLayout.WEST);
        JPanel tools = new JPanel(new WrapLayout(FlowLayout.RIGHT, 2, 0));
        tools.addComponentListener(new java.awt.event.ComponentAdapter() {
            private int lastWidth = -1;
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                if (tools.getWidth() != lastWidth) {
                    lastWidth = tools.getWidth();
                    SwingUtilities.invokeLater(tools::revalidate);
                }
            }
        });
        tools.add(toolButton("−", "zoom.out.tip", panel::zoomOut));
        tools.add(toolButton("+", "zoom.in.tip", panel::zoomIn));
        JButton fit = toolButton("", "zoom.fit.tip", panel::fit);
        I18n.text(fit, "zoom.fit");
        tools.add(fit);
        tools.add(toolButton("4×", "zoom.4x.tip", () -> panel.setPixelScale(4)));
        if (panel == leftPanel) {
            tools.add(Box.createHorizontalStrut(6));
            tools.add(linkViews);
            tools.add(showGrid);
        }
        header.add(tools, BorderLayout.CENTER);
        box.add(header, BorderLayout.NORTH);
        box.add(panel, BorderLayout.CENTER);
        return box;
    }

    private static Icon aboutIcon() {
        for (Image img : Icons.appIcons())
            if (img.getWidth(null) == 128) return new ImageIcon(img);
        return null;
    }

    private static JButton toolButton(String text, String tipKey, Runnable action) {
        JButton b = new JButton(text);
        I18n.tip(b, tipKey);
        b.setMargin(new Insets(1, 5, 1, 5));
        b.setFocusable(false);
        b.addActionListener(e -> action.run());
        return b;
    }

    private JPanel buildBottomPanel() {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton savePng = I18n.tip(I18n.text(new JButton(), "bottom.savePng"), "bottom.savePng.tip");
        cropButton.addActionListener(e -> openCropDialog());
        savePng.addActionListener(e -> saveResultPng());
        runButton.addActionListener(e -> run());
        buttons.add(cropButton);
        buttons.add(savePng);
        buttons.add(runButton);
        progress.setIndeterminate(true);
        progress.setPreferredSize(new Dimension(80, progress.getPreferredSize().height));
        progress.setVisible(false);
        JPanel st = new JPanel(new BorderLayout(6, 0));
        st.add(progress, BorderLayout.WEST);
        st.add(status, BorderLayout.CENTER);
        p.add(st, BorderLayout.CENTER);
        p.add(buttons, BorderLayout.EAST);
        return p;
    }

    /** Sets a translated status text (re-translated on language switch). */
    private void setStatus(String key, Object... args) {
        statusText = () -> tr(key, args);
        status.setText(statusText.get());
    }

    private void setCheck(String key, Object... args) {
        checkText = () -> tr(key, args);
        checkLabel.setText(checkText.get());
    }

    private void wireListeners() {
        for (JRadioButton b : toolButtons) b.addActionListener(e -> toolChanged());
        paletteBox.addActionListener(e -> {
            // C64 files as input are drawn with the selected palette
            if (inputFile != null && Images.isC64File(inputFile.toPath())) reloadC64Input();
            settingsChanged();
            updateCellPanel();
        });
        spaceBox.addActionListener(e -> settingsChanged());
        for (AbstractButton b : new AbstractButton[]{pairL2R, pairR2L, pairAvg, bothDirections, bitmapPalettize,
                fixClashes, serpentine, bleeding})
            b.addActionListener(e -> settingsChanged());
        for (AbstractButton b : new AbstractButton[]{pairL2R, pairR2L, pairAvg, bothDirections})
            b.addActionListener(e -> updateOutputPath());
        modeMc.addActionListener(e -> modeChanged());
        modeHires.addActionListener(e -> modeChanged());
        ditherBox.addActionListener(e -> settingsChanged());
        strength.addChangeListener(e -> { if (!strength.getValueIsAdjusting()) settingsChanged(); });
        backgroundBox.addActionListener(e -> settingsChanged());
        palAspect.addActionListener(e -> updateAspect());
        toC64Size.addActionListener(e -> {
            if (toC64Size.isSelected()) ensureFrame();
            refreshInput();
        });
    }

    private Tool tool() {
        for (Tool t : Tool.values()) if (toolButtons[t.ordinal()].isSelected()) return t;
        return Tool.PALETTIZE;
    }

    private C64Mode mode() { return modeHires.isSelected() ? C64Mode.HIRES : C64Mode.MULTICOLOR; }

    private Palette palette() { return (Palette) paletteBox.getSelectedItem(); }

    /** Enables what belongs to the current tool and settings. */
    private void updateEnabled() {
        Tool t = tool();
        boolean bitmap = t == Tool.BITMAP;
        setEnabledDeep(palettePanel, t != Tool.PIXELIZE);
        setEnabledDeep(pixelPanel, t == Tool.PIXELIZE || (bitmap && mode() == C64Mode.MULTICOLOR));
        bothDirections.setEnabled(t == Tool.PIXELIZE && !pairAvg.isSelected());
        boolean ditherOk = t == Tool.PALETTIZE || (bitmap && bitmapPalettize.isSelected());
        setEnabledDeep(ditherPanel, ditherOk);
        bleeding.setEnabled(ditherOk && bitmap);
        setEnabledDeep(bitmapPanel, bitmap);
        backgroundBox.setEnabled(bitmap && mode() == C64Mode.MULTICOLOR);
        fixClashes.setEnabled(bitmap && !ditherActive());
        toC64Size.setEnabled(!bitmap);
        toC64Size.setSelected(bitmap || toC64Size.isSelected());
        checkPanel.setVisible(bitmap);
        paintColour.setEnabled(toolPaint.isSelected());
        cropButton.setEnabled(source != null && usesFrame());
    }

    private boolean ditherActive() {
        return ditherBox.getSelectedItem() != Dither.NONE && strength.getValue() > 0;
    }

    private static void setEnabledDeep(Container c, boolean on) {
        c.setEnabled(on);
        for (Component child : c.getComponents()) {
            child.setEnabled(on);
            if (child instanceof Container cc && !(child instanceof JComboBox)) setEnabledDeep(cc, on);
        }
    }

    private boolean toolChangeRemembered;
    private boolean c64SizeBeforeBitmap = true;

    private void toolChanged() {
        Tool t = tool();
        // the bitmap tool always needs 320×200; remember the user's choice for the other tools
        if (t == Tool.BITMAP && !toolChangeRemembered) {
            c64SizeBeforeBitmap = toC64Size.isSelected();
            toolChangeRemembered = true;
        } else if (t != Tool.BITMAP && toolChangeRemembered) {
            toC64Size.setSelected(c64SizeBeforeBitmap);
            toolChangeRemembered = false;
        }
        rightPanel.setPixelListener(t == Tool.BITMAP ? this::resultClicked : null);
        rightPanel.setToolTipText(tr(t == Tool.BITMAP ? "preview.edit.tip" : "preview.tip"));
        if (usesFrame()) ensureFrame();
        result = null;
        updateEnabled();
        updateOutputPath();
        refreshInput();
    }

    private void modeChanged() {
        // pixel and cell coordinates differ between the modes
        cellEdits.clear();
        pixelEdits.clear();
        selectedCell = -1;
        updateEnabled();
        updateOutputPath();
        updateCellPanel();
        settingsChanged();
    }

    private void editToolChanged() {
        paintColour.setEnabled(toolPaint.isSelected());
    }

    private void updateTitles() {
        leftTitle.setText(tr(input() != null && input() != source ? "view.framed" : "view.original"));
        rightTitle.setText(tr("view.result." + tool().name()));
    }

    // ------------------------------------------------------------------ input picture & frame

    /** Whether the current tool works on the 320×200 version of the picture. */
    private boolean usesFrame() { return tool() == Tool.BITMAP || toC64Size.isSelected(); }

    /** Picture the current tool works on; null = a frame has to be chosen first. */
    private BufferedImage input() {
        if (source == null) return null;
        if (!usesFrame()) return source;
        if (fitSettings != null) return fitted;
        if (tool() == Tool.BITMAP && BitmapConverter.isValidSize(source)) return source;
        if (source.getWidth() == 320 && source.getHeight() == 200) return source;
        if (ImageFitter.isNativeSize(source)) return ImageFitter.fit(source, ImageFitter.Settings.defaults());
        return null;
    }

    /** Opens the crop & scale dialog if the picture needs a frame and none is chosen yet. */
    private void ensureFrame() {
        if (source != null && fitSettings == null && !ImageFitter.isNativeSize(source))
            SwingUtilities.invokeLater(() -> {
                if (fitSettings == null && usesFrame()) openCropDialog();
            });
    }

    private void refreshInput() {
        updateEnabled();
        updateTitles();
        updateLeft();
        updateRight();
        settingsChanged();
    }

    // ------------------------------------------------------------------ computation

    private void settingsChanged() {
        generation++;
        if (source != null) updateTimer.restart();
        updateEnabled();
    }

    private Pixelizer.Direction direction() {
        return pairR2L.isSelected() ? Pixelizer.Direction.R2L
                : pairAvg.isSelected() ? Pixelizer.Direction.AVERAGE : Pixelizer.Direction.L2R;
    }

    private Ditherer.Settings ditherSettings() {
        return new Ditherer.Settings((Dither) ditherBox.getSelectedItem(), strength.getValue(),
                serpentine.isSelected(), bleeding.isSelected());
    }

    private BitmapConverter.Settings bitmapSettings() {
        return new BitmapConverter.Settings(mode(), palette(), (ColorMatcher.Space) spaceBox.getSelectedItem(),
                direction(), bitmapPalettize.isSelected(), (Integer) backgroundBox.getSelectedItem(),
                fixClashes.isSelected(), ditherSettings());
    }

    private void recompute() {
        if (source == null) return;
        Tool t = tool();
        BufferedImage in = input();
        if (in == null) {
            result = null;
            updateRight();
            setStatus("status.needFrame", source.getWidth(), source.getHeight());
            setCheck("chk.none");
            return;
        }
        long gen = generation;
        Palette pal = palette();
        ColorMatcher.Space space = (ColorMatcher.Space) spaceBox.getSelectedItem();
        Pixelizer.Direction dir = direction();
        boolean both = bothDirections.isSelected() && dir != Pixelizer.Direction.AVERAGE;
        Ditherer.Settings ds = ditherSettings();
        BitmapConverter.Settings bs = bitmapSettings();
        BitmapConverter.Edits edits = new BitmapConverter.Edits(Map.copyOf(cellEdits), Map.copyOf(pixelEdits));
        long t0 = System.currentTimeMillis();
        if (worker != null) worker.cancel(false);
        progress.setVisible(true);
        worker = new SwingWorker<>() {
            @Override protected Computed doInBackground() {
                switch (t) {
                    case PALETTIZE:
                        return new Computed(t, Palettizer.palettize(in, pal, space, ds), null, null, null);
                    case PIXELIZE: {
                        // "both directions": L2R in the output file, R2L next to it (like the old c64pixelizer)
                        BufferedImage a = Pixelizer.pixelize(in, both ? Pixelizer.Direction.L2R : dir);
                        BufferedImage b = both ? Pixelizer.pixelize(in, Pixelizer.Direction.R2L) : null;
                        return new Computed(t, a, b, null, null);
                    }
                    default:
                        try {
                            BitmapConverter.Result r = BitmapConverter.convert(in, bs, edits);
                            return new Computed(t, r.render(pal), null, r, null);
                        } catch (BitmapConverter.ConversionException e) {
                            return new Computed(t, null, null, null, e.getMessage());
                        }
                }
            }

            @Override protected void done() {
                if (isCancelled() || gen != generation) return;
                progress.setVisible(false);
                try {
                    result = get();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    result = new Computed(t, null, null, null, String.valueOf(cause.getMessage()));
                }
                updateRight();
                updateCellPanel();
                reportResult(System.currentTimeMillis() - t0);
            }
        };
        worker.execute();
    }

    private void reportResult(long ms) {
        if (result == null) return;
        double secs = ms / 1000.0;
        if (result.error() != null) {
            setStatus("status.error", result.error());
            setCheck("chk.none");
            return;
        }
        BitmapConverter.Result r = result.bitmap();
        if (r == null) {
            setStatus("status.ready", secs);
            return;
        }
        String modeName = r.mode().toString();
        int edits = cellEdits.size() + pixelEdits.size();
        if (r.ok()) {
            if (r.mode().sharedBackground) setCheck("chk.okMc", modeName, r.background());
            else setCheck("chk.ok", modeName);
            setStatus(r.fixedCells().isEmpty() ? "status.bitmapOk" : "status.bitmapFixed",
                    secs, r.fixedCells().size(), edits);
        } else {
            setCheck("chk.bad", r.clashCells().size(), modeName, r.mode().coloursPerCell);
            setStatus("status.clashes", r.clashCells().size());
        }
    }

    // ------------------------------------------------------------------ previews

    private void updateLeft() {
        BufferedImage in = input();
        leftPanel.setImage(in != null ? in : source);
        updateAspect();
    }

    private void updateRight() {
        if (result == null || result.image() == null || result.tool() != tool()) {
            rightPanel.setImage(null);
            updateAspect();
            return;
        }
        BufferedImage img = result.image();
        BitmapConverter.Result r = result.bitmap();
        if (r != null && (markCells.isSelected() || selectedCell >= 0)) img = markCells(img, r);
        rightPanel.setImage(img);
        updateAspect();
    }

    /** Outlines cells on the 320×200 picture: red = breaks the rules, yellow = corrected, cyan = selected. */
    private BufferedImage markCells(BufferedImage src, BitmapConverter.Result r) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        if (markCells.isSelected()) {
            g.setColor(new Color(255, 220, 0));
            for (int cell : r.fixedCells()) outline(g, cell);
            for (int cell : cellEdits.keySet()) outline(g, cell);
            g.setColor(new Color(255, 40, 40));
            for (int cell : r.clashCells()) outline(g, cell);
        }
        if (selectedCell >= 0) {
            g.setColor(Color.CYAN);
            outline(g, selectedCell);
        }
        g.dispose();
        return out;
    }

    private static void outline(Graphics2D g, int cell) {
        g.drawRect((cell % C64Mode.COLS) * 8, (cell / C64Mode.COLS) * 8, 7, 7);
    }

    private void updateAspect() {
        leftPanel.setDisplayAspect(aspectOf(leftPanel.getImage()));
        rightPanel.setDisplayAspect(aspectOf(rightPanel.getImage()));
    }

    /** Square pixels, except C64-sized pictures with "PAL aspect" on. */
    private double aspectOf(BufferedImage img) {
        if (img == null) return 320 * PAL_PIXEL / 200;
        double a = (double) img.getWidth() / img.getHeight();
        return palAspect.isSelected() && BitmapConverter.isValidSize(img) ? 320 * PAL_PIXEL / 200 : a;
    }

    // ------------------------------------------------------------------ colour check & manual correction

    /** Left click / drag into the result of the bitmap tool. */
    private void resultClicked(int x, int y, boolean dragging) {
        if (result == null || result.bitmap() == null) return;
        C64Mode m = result.bitmap().mode();
        int mx = x / m.pixelWidth(), cell = m.cellOf(mx, y);
        if (toolPaint.isSelected()) {
            Integer colour = (Integer) paintColour.getSelectedItem();
            if (colour == null) return;
            Integer old = pixelEdits.put(y * m.width + mx, colour);
            if (Objects.equals(old, colour) && result.bitmap().indices()[y * m.width + mx] == colour) return;
            selectedCell = cell;
            settingsChanged();
        } else if (!dragging) {
            selectedCell = cell;
            updateCellPanel();
            updateRight();
        }
    }

    /** Shows the colours of the selected cell as toggle buttons (selected = used / allowed). */
    private void updateCellPanel() {
        BitmapConverter.Result r = result != null ? result.bitmap() : null;
        boolean has = r != null && selectedCell >= 0;
        int bg = r != null ? r.background() : -1;
        int[] count = has ? r.cellCounts(selectedCell) : new int[16];
        int[] own = has ? cellEdits.get(selectedCell) : null;
        Palette pal = palette();
        for (int i = 0; i < 16; i++) {
            JToggleButton b = swatches[i];
            b.setIcon(new ColourIcon(pal.rgb(i), 16));
            b.setText(i == bg ? tr("chk.bgShort") : count[i] > 0 ? String.valueOf(count[i]) : " ");
            b.setToolTipText(tr("chk.swatch.tip", i, tr("color." + i), count[i]));
            boolean selected = own != null ? contains(own, i) : count[i] > 0;
            b.setSelected(has && i != bg && selected);
            b.setEnabled(has && i != bg);
        }
        for (JButton b : new JButton[]{applyCell, autoCell, resetCell}) b.setEnabled(has);
        resetAll.setEnabled(!cellEdits.isEmpty() || !pixelEdits.isEmpty());
        if (has) {
            C64Mode m = r.mode();
            int used = 0;
            for (int i = 0; i < 16; i++) if (count[i] > 0 && i != bg) used++;
            cellLabel.setText(tr("chk.cell", selectedCell % C64Mode.COLS, selectedCell / C64Mode.COLS,
                    used, m.coloursPerCell));
            cellLabel.setForeground(used > m.coloursPerCell ? new Color(200, 30, 30) : UIManager.getColor("Label.foreground"));
        } else {
            cellLabel.setText(tr("chk.noCell"));
            cellLabel.setForeground(UIManager.getColor("Label.foreground"));
        }
        updateCellHint();
    }

    private void updateCellHint() {
        BitmapConverter.Result r = result != null ? result.bitmap() : null;
        if (r == null || selectedCell < 0) {
            cellHint.setText(tr(r == null ? " " : "chk.hint"));
            return;
        }
        int chosen = chosenColours().length, max = r.mode().coloursPerCell;
        cellHint.setText(chosen > max ? tr("chk.tooMany", chosen, max)
                : tr(r.mode().sharedBackground ? "chk.hintCellMc" : "chk.hintCell", max));
        applyCell.setEnabled(chosen <= max && (chosen > 0 || r.mode().sharedBackground));
    }

    private int[] chosenColours() {
        return Arrays.stream(swatches).filter(b -> b.isSelected() && b.isEnabled())
                .mapToInt(b -> Arrays.asList(swatches).indexOf(b)).toArray();
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    /** Manual: the selected cell may only use the toggled colours (pixels get the nearest of them). */
    private void applyCellColours() {
        if (selectedCell < 0) return;
        cellEdits.put(selectedCell, chosenColours());
        removePixelEditsInCell(selectedCell);
        settingsChanged();
    }

    /** Automatic for one cell: the colours that reproduce the cell best. */
    private void autoCellColours() {
        if (selectedCell < 0 || result == null || result.bitmap() == null || input() == null) return;
        cellEdits.put(selectedCell, BitmapConverter.suggestColours(input(), bitmapSettings(), selectedCell,
                result.bitmap().background()));
        removePixelEditsInCell(selectedCell);
        settingsChanged();
    }

    private void resetSelectedCell() {
        if (selectedCell < 0) return;
        cellEdits.remove(selectedCell);
        removePixelEditsInCell(selectedCell);
        settingsChanged();
    }

    private void removePixelEditsInCell(int cell) {
        C64Mode m = mode();
        pixelEdits.keySet().removeIf(p -> m.cellOf(p % m.width, p / m.width) == cell);
    }

    /** Selects the next / previous cell that breaks the rules and shows it zoomed in. */
    private void jumpToClash(int dirSign) {
        if (result == null || result.bitmap() == null || result.bitmap().clashCells().isEmpty()) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        List<Integer> cells = result.bitmap().clashCells();
        int target = dirSign > 0 ? cells.get(0) : cells.get(cells.size() - 1);
        for (int i = 0; i < cells.size(); i++) {
            int c = dirSign > 0 ? cells.get(i) : cells.get(cells.size() - 1 - i);
            if (dirSign > 0 ? c > selectedCell : c < selectedCell) { target = c; break; }
        }
        selectedCell = target;
        toolSelect.setSelected(true);
        editToolChanged();
        updateCellPanel();
        updateRight();
        rightPanel.showArea(new Rectangle((target % C64Mode.COLS) * 8, (target / C64Mode.COLS) * 8, 8, 8), 4);
    }

    // ------------------------------------------------------------------ files

    private void installDragAndDrop(JComponent c) {
        c.setTransferHandler(new TransferHandler() {
            @Override public boolean canImport(TransferSupport s) {
                return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport s) {
                try {
                    List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty()) SwingUtilities.invokeLater(() -> loadInput(files.get(0)));
                    return true;
                } catch (Exception ex) {
                    return false;
                }
            }
        });
    }

    private void chooseInput() {
        File dir = FileDialogs.lastDir("input");
        if (dir == null && inputFile != null) dir = inputFile.getAbsoluteFile().getParentFile();
        JFileChooser fc = FileDialogs.chooser("dlg.open.title", dir);
        FileDialogs.addImageFilters(fc);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) loadInput(fc.getSelectedFile());
    }

    private void chooseOutput() {
        File current = outputField.getText().isBlank() ? null : new File(outputField.getText());
        JFileChooser fc = FileDialogs.chooser("dlg.output.title", current != null ? current.getParentFile() : null);
        String ext = outputExtension();
        FileDialogs.addFilter(fc, ext.equals("kla") ? "filter.kla" : ext.equals("art") ? "filter.art" : "filter.png", ext);
        if (current != null) fc.setSelectedFile(current);
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            String p = fc.getSelectedFile().getPath();
            if (!p.toLowerCase(Locale.ROOT).endsWith("." + ext)) p += "." + ext;
            outputField.setText(p);
            outputDirChosen = new File(p).getAbsoluteFile().getParentFile();
            FileDialogs.rememberDir("output", outputDirChosen);
        }
    }

    private String outputExtension() { return tool() == Tool.BITMAP ? mode().extension : "png"; }

    /** Output = input name plus the suffix of the old tools, in the chosen output folder or next to the input. */
    private void updateOutputPath() {
        if (inputFile == null) return;
        String base = inputFile.getName().replaceFirst("\\.[^.]+$", "");
        String name = switch (tool()) {
            case PALETTIZE -> base + "_palettized.png";
            case PIXELIZE -> base + (bothDirections.isSelected() && !pairAvg.isSelected()
                    ? Pixelizer.Direction.L2R : direction()).fileSuffix() + ".png";
            case BITMAP -> base + "." + mode().extension;
        };
        File dir = outputDirChosen != null ? outputDirChosen : inputFile.getAbsoluteFile().getParentFile();
        File target = new File(dir, name);
        // never suggest overwriting the input file itself (e.g. a .kla opened in the bitmap tool)
        if (target.getAbsoluteFile().equals(inputFile.getAbsoluteFile()))
            target = new File(dir, base + "_new." + outputExtension());
        outputField.setText(target.getPath());
    }

    private void loadInput(File f) {
        BufferedImage img;
        try {
            img = Images.read(f.toPath(), palette());
        } catch (Exception ex) {
            error(tr("err.read", ex.getMessage()));
            return;
        }
        FileDialogs.rememberDir("input", f);
        if (leftPanel.isCropMode()) leftPanel.cancelCrop();
        ImageFitter.Settings fs = null;
        if (usesFrame() && !ImageFitter.isNativeSize(img)) {
            CropScaleDialog d = new CropScaleDialog(this, img, ImageFitter.Settings.suggested(img));
            d.setVisible(true);
            if (!d.isAccepted()) {
                setStatus("status.loadCancelled");
                return;
            }
            fs = d.getSettings();
        }
        inputFile = f;
        source = img;
        fitSettings = fs;
        fitted = fs != null ? ImageFitter.fit(img, fs) : null;
        cellEdits.clear();
        pixelEdits.clear();
        selectedCell = -1;
        inputField.setText(f.getPath());
        updateOutputPath();
        result = null;
        refreshInput();
        leftPanel.fit();
        rightPanel.fit();
        setStatus("status.loaded", f.getName(), img.getWidth(), img.getHeight());
    }

    /** A C64 file as input is redrawn when the palette changes. */
    private void reloadC64Input() {
        try {
            source = Images.read(inputFile.toPath(), palette());
            if (fitSettings != null) fitted = ImageFitter.fit(source, fitSettings);
            updateLeft();
        } catch (Exception ex) {
            error(tr("err.read", ex.getMessage()));
        }
    }

    /** Applies {@link #fitSettings}; a new frame discards the manual corrections. */
    private void applyFit() {
        if (source == null) return;
        fitted = fitSettings != null ? ImageFitter.fit(source, fitSettings) : null;
        cellEdits.clear();
        pixelEdits.clear();
        refreshInput();
    }

    private void openCropDialog() {
        if (source == null || !usesFrame()) return;
        if (leftPanel.isCropMode()) leftPanel.cancelCrop();
        CropScaleDialog d = new CropScaleDialog(this, source,
                fitSettings != null ? fitSettings : ImageFitter.Settings.suggested(source));
        d.setVisible(true);
        if (d.isAccepted()) {
            fitSettings = d.getSettings();
            applyFit();
        } else if (input() == null) {
            setStatus("status.needFrame", source.getWidth(), source.getHeight());
        }
    }

    /** Right click on the original: choose the frame directly in the preview. */
    private void startReframe() {
        if (source == null || !usesFrame() || leftPanel.isCropMode()) return;
        ImageFitter.Settings base = fitSettings != null ? fitSettings : ImageFitter.Settings.suggested(source);
        boolean fixed = base.mode() == ImageFitter.ScaleMode.NONE;
        if (fixed && (source.getWidth() < ImageFitter.W || source.getHeight() < ImageFitter.H)) {
            openCropDialog();   // a 1:1 frame larger than the picture is only possible in the dialog
            return;
        }
        double ratio = fixed ? 1.6 : base.aspect().ratio > 0 ? base.aspect().ratio : 1.6;
        reframeBackup = fitSettings;
        leftPanel.startCrop(source, base.crop(), ratio, fixed, new ImagePanel.CropListener() {
            @Override public void cropChanged(Rectangle crop) {
                fitSettings = new ImageFitter.Settings(crop, fixed ? ImageFitter.ScaleMode.NONE : ImageFitter.ScaleMode.FILL,
                        base.filter(), base.aspect(), base.fill());
                fitTimer.restart();
            }

            @Override public void cropFinished(boolean accepted) {
                fitTimer.stop();
                if (!accepted) fitSettings = reframeBackup;
                applyFit();
                setStatus(accepted ? "status.reframeApplied" : "status.reframeCancelled");
            }
        });
        setStatus("status.reframe");
    }

    // ------------------------------------------------------------------ run & save

    /** Writes the files of the current tool, like the old command line tools did. */
    private void run() {
        if (source == null) {
            error(tr("err.openFirst"));
            return;
        }
        if (result == null || result.tool() != tool() || updateTimer.isRunning() || (worker != null && !worker.isDone())) {
            setStatus("status.wait");
            return;
        }
        if (result.error() != null) {
            error(tr("err.convert", result.error()));
            return;
        }
        String out = outputField.getText().trim();
        if (out.isEmpty()) {
            chooseOutput();
            out = outputField.getText().trim();
            if (out.isEmpty()) return;
        }
        Path target = Path.of(out);
        List<String> written = new ArrayList<>();
        try {
            switch (tool()) {
                case PALETTIZE -> {
                    Images.writePng(result.image(), target);
                    written.add(target.getFileName().toString());
                }
                case PIXELIZE -> {
                    Images.writePng(result.image(), target);
                    written.add(target.getFileName().toString());
                    if (result.second() != null) {
                        Path second = r2lSibling(target);
                        Images.writePng(result.second(), second);
                        written.add(second.getFileName().toString());
                    }
                }
                case BITMAP -> {
                    BitmapConverter.Result r = result.bitmap();
                    if (!r.ok()) {
                        error(tr("err.clashes", r.clashCells().size(), r.mode().toString()));
                        return;
                    }
                    Files.write(target, r.fileBytes());
                    written.add(target.getFileName().toString());
                }
            }
            setStatus("status.saved", String.join(", ", written));
        } catch (Exception ex) {
            error(tr("err.write", ex.getMessage()));
        }
    }

    /** "x_pixelized_l2r.png" → "x_pixelized_r2l.png"; other names get "_r2l" appended. */
    static Path r2lSibling(Path p) {
        String name = p.getFileName().toString();
        String n = name.contains("_l2r") ? name.replace("_l2r", "_r2l") : name.replaceFirst("(\\.[^.]+)?$", "_r2l$1");
        return p.toAbsolutePath().resolveSibling(n);
    }

    private void saveResultPng() {
        if (result == null || result.image() == null) return;
        File dir = FileDialogs.lastDir("png");
        JFileChooser fc = FileDialogs.chooser("dlg.savePng.title", dir);
        FileDialogs.addFilter(fc, "filter.png", "png");
        String base = inputFile != null ? inputFile.getName().replaceFirst("\\.[^.]+$", "") : "result";
        fc.setSelectedFile(new File(dir, base + "_" + tool().name().toLowerCase(Locale.ROOT) + ".png"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        FileDialogs.rememberDir("png", fc.getSelectedFile());
        String s = fc.getSelectedFile().getPath();
        Path p = Path.of(s.toLowerCase(Locale.ROOT).endsWith(".png") ? s : s + ".png");
        try {
            Images.writePng(result.image(), p);   // without cell markers
            setStatus("status.saved", p.getFileName());
        } catch (Exception ex) {
            error(tr("err.write", ex.getMessage()));
        }
    }

    private void editCustomPalette() {
        PaletteEditorDialog d = new PaletteEditorDialog(this, palette());
        d.setVisible(true);
        if (!d.isAccepted()) return;
        Palette p = d.getPalette();
        paletteModel.addElement(p);
        paletteBox.setSelectedItem(p);
    }

    private void error(String msg) {
        JOptionPane.showMessageDialog(this, msg, App.NAME, JOptionPane.ERROR_MESSAGE);
    }
}
