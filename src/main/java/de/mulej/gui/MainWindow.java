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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import static de.mulej.i18n.I18n.tr;

/**
 * Main window, built like FlipperJ: tool and files on top, settings groups, previews (original / result) below.
 * The result is recomputed automatically after every change; "Run" writes the files.
 */
public class MainWindow extends JFrame {

    /** The three former command line tools. */
    enum Tool { PALETTIZE, PIXELIZE, KOALA }

    private static final double PAL_PIXEL = 0.9365;

    // tool
    private final JRadioButton[] toolButtons = new JRadioButton[Tool.values().length];

    // files
    private final JTextField inputField = new JTextField();
    private final JTextField outputField = new JTextField();
    /** Output folder chosen by the user; null = next to the input picture. */
    private File outputDirChosen;

    // palette & matching
    private final DefaultComboBoxModel<Palette> paletteModel = new DefaultComboBoxModel<>();
    private final JComboBox<Palette> paletteBox = new JComboBox<>(paletteModel);
    private final JComboBox<ColorMatcher.Space> spaceBox = new JComboBox<>(ColorMatcher.Space.values());

    // pixel pairs
    private final JRadioButton pairL2R = new JRadioButton("", true), pairR2L = new JRadioButton(), pairAvg = new JRadioButton();
    private final JCheckBox bothDirections = I18n.tip(I18n.text(new JCheckBox("", true), "pix.both"), "pix.both.tip");

    // koala
    private final JCheckBox koalaPalettize = I18n.tip(I18n.text(new JCheckBox("", true), "kla.palettize"), "kla.palettize.tip");
    private final JComboBox<Integer> backgroundBox = new JComboBox<>();
    private final JCheckBox fixClashes = I18n.tip(I18n.text(new JCheckBox(), "kla.fix"), "kla.fix.tip");
    private final JCheckBox markClashes = I18n.tip(I18n.text(new JCheckBox("", true), "kla.mark"), "kla.mark.tip");
    private final JCheckBox palAspect = I18n.text(new JCheckBox("", true), "view.palAspect");

    // previews
    private final ImagePanel leftPanel = new ImagePanel(), rightPanel = new ImagePanel();
    private final JLabel leftTitle = new JLabel(), rightTitle = new JLabel();
    private final JCheckBox linkViews = I18n.tip(I18n.text(new JCheckBox("", true), "view.link"), "view.link.tip");
    private final JCheckBox showGrid = I18n.tip(I18n.text(new JCheckBox("", true), "view.grid"), "view.grid.tip");

    private final JButton cropButton = I18n.text(new JButton(), "bottom.crop");
    private final JButton runButton = I18n.tip(I18n.text(new JButton(), "bottom.run"), "bottom.run.tip");
    private final JProgressBar progress = new JProgressBar();
    private final JLabel status = new JLabel(" ");
    /** Produces the current status text, so it can be re-translated after a language switch. */
    private Supplier<String> statusText = () -> " ";

    // state
    private File inputFile;
    private BufferedImage source;
    /** Crop & scale for the Koala tool; null = not chosen yet (only needed for pictures that are not C64-sized). */
    private ImageFitter.Settings fitSettings;
    private ImageFitter.Settings reframeBackup;
    private BufferedImage koalaInput;
    private Computed result;
    private long generation;
    private SwingWorker<Computed, Void> worker;
    private final Timer updateTimer = new Timer(150, e -> recompute());
    private final Timer fitTimer = new Timer(120, e -> applyFit());

    /** Outcome of one computation (always for one tool). */
    private record Computed(Tool tool, BufferedImage image, BufferedImage second,
                            KoalaConverter.Result koala, String error) {}

    public MainWindow() {
        I18n.bind(() -> setTitle(tr("app.title", App.VERSION)));
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setIconImages(Icons.appIcons());
        for (Palette p : Palettes.builtIns()) paletteModel.addElement(p);
        paletteBox.setSelectedItem(Palettes.COLODORE);
        spaceBox.setRenderer(translatedRenderer(v -> tr("space." + ((ColorMatcher.Space) v).name())));
        for (int i = -1; i < 16; i++) backgroundBox.addItem(i);
        backgroundBox.setRenderer(translatedRenderer(v -> {
            int i = (Integer) v;
            return i < 0 ? tr("kla.bgAuto") : i + " " + tr("color." + i);
        }));
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
        root.add(north, BorderLayout.NORTH);
        root.add(buildPreviewPanel(), BorderLayout.CENTER);
        root.add(buildBottomPanel(), BorderLayout.SOUTH);
        setContentPane(root);
        installDragAndDrop(root);
        wireListeners();
        I18n.bind(() -> status.setText(statusText.get()));
        I18n.bind(() -> leftPanel.setPlaceholder(tr("preview.placeholder.left")));
        I18n.bind(() -> rightPanel.setPlaceholder(tr("preview.placeholder.right")));
        I18n.bind(this::updateTitles);
        I18n.tip(leftPanel, "preview.left.tip");
        I18n.tip(rightPanel, "preview.tip");
        leftPanel.setRightClickAction(this::startReframe);
        toolChanged();
        pack();
        setMinimumSize(new Dimension(980, 720));
        setLocationRelativeTo(null);
    }

    private static DefaultListCellRenderer translatedRenderer(java.util.function.Function<Object, String> f) {
        return new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                    boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list, value == null ? "" : f.apply(value), index, selected, focus);
            }
        };
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
        JLabel hint = I18n.label("file.hint");
        hint.setHorizontalAlignment(SwingConstants.CENTER);
        p.add(hint, c);
        c.gridy = 2; c.gridx = 0; p.add(fixedLabel("file.output"), c);
        c.gridx = 1; c.weightx = 1; p.add(outputField, c);
        c.gridx = 2; c.weightx = 0; p.add(selOut, c);
        return p;
    }

    private JPanel buildSettingsPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        c.weighty = 1;
        c.gridx = 0;
        p.add(buildPalettePanel(), c);
        c.gridx = 1;
        p.add(buildPixelPanel(), c);
        c.gridx = 2;
        p.add(buildKoalaPanel(), c);
        return p;
    }

    private JPanel palettePanel, pixelPanel, koalaPanel;

    private JPanel buildPalettePanel() {
        JPanel p = palettePanel = group("pal.group");
        GridBagConstraints c = gbc();
        JButton custom = I18n.text(new JButton(), "pal.customButton");
        custom.addActionListener(e -> editCustomPalette());
        c.gridy = 0; c.gridx = 0; c.gridwidth = 2; p.add(paletteBox, c);
        c.gridx = 2; c.gridwidth = 1; p.add(custom, c);
        c.gridy = 1; c.gridx = 0; p.add(fixedLabel("pal.matchIn"), c);
        c.gridx = 1; c.gridwidth = 2; p.add(spaceBox, c);
        c.gridwidth = 1;
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
        });
        return p;
    }

    private JPanel buildKoalaPanel() {
        JPanel p = koalaPanel = group("kla.group");
        GridBagConstraints c = gbc();
        c.gridx = 0; c.gridwidth = 2;
        c.gridy = 0; p.add(koalaPalettize, c);
        c.gridwidth = 1;
        c.gridy = 1; p.add(fixedLabel("kla.background"), c);
        c.gridx = 1; p.add(backgroundBox, c);
        c.gridx = 0; c.gridwidth = 2;
        c.gridy = 2; p.add(fixClashes, c);
        c.gridy = 3; p.add(markClashes, c);
        c.gridy = 4; p.add(palAspect, c);
        addDefaultButton(p, () -> {
            koalaPalettize.setSelected(true);
            backgroundBox.setSelectedIndex(0);
            fixClashes.setSelected(false);
            markClashes.setSelected(true);
            palAspect.setSelected(true);
            settingsChanged();
        });
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
        linkViews.addActionListener(e -> { if (linkViews.isSelected()) rightPanel.copyViewFrom(leftPanel); });
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

    private void wireListeners() {
        for (JRadioButton b : toolButtons) b.addActionListener(e -> toolChanged());
        paletteBox.addActionListener(e -> {
            // a Koala file as input is drawn with the selected palette
            if (inputFile != null && Images.isKoala(inputFile.toPath())) reloadKoalaInput();
            settingsChanged();
        });
        spaceBox.addActionListener(e -> settingsChanged());
        for (AbstractButton b : new AbstractButton[]{pairL2R, pairR2L, pairAvg, bothDirections, koalaPalettize,
                fixClashes, markClashes})
            b.addActionListener(e -> settingsChanged());
        bothDirections.addActionListener(e -> updateOutputPath());
        pairL2R.addActionListener(e -> updateOutputPath());
        pairR2L.addActionListener(e -> updateOutputPath());
        pairAvg.addActionListener(e -> updateOutputPath());
        backgroundBox.addActionListener(e -> settingsChanged());
        palAspect.addActionListener(e -> updateAspect());
    }

    private Tool tool() {
        for (Tool t : Tool.values()) if (toolButtons[t.ordinal()].isSelected()) return t;
        return Tool.PALETTIZE;
    }

    private void toolChanged() {
        Tool t = tool();
        setEnabledDeep(palettePanel, t != Tool.PIXELIZE);
        setEnabledDeep(pixelPanel, t != Tool.PALETTIZE);
        bothDirections.setEnabled(t == Tool.PIXELIZE && !pairAvg.isSelected());
        setEnabledDeep(koalaPanel, t == Tool.KOALA);
        cropButton.setEnabled(t == Tool.KOALA);
        if (t == Tool.KOALA && source != null && fitSettings == null && !KoalaConverter.isValidSize(source)) {
            // pictures that are not C64-sized need a frame first
            SwingUtilities.invokeLater(this::openCropDialog);
        }
        result = null;
        updateOutputPath();
        updateTitles();
        updateLeft();
        updateRight();
        settingsChanged();
    }

    private static void setEnabledDeep(Container c, boolean on) {
        c.setEnabled(on);
        for (Component child : c.getComponents()) {
            child.setEnabled(on);
            if (child instanceof Container cc && !(child instanceof JComboBox)) setEnabledDeep(cc, on);
        }
    }

    private void updateTitles() {
        Tool t = tool();
        leftTitle.setText(tr(t == Tool.KOALA && koalaInput != null && koalaInput != source ? "view.framed" : "view.original"));
        rightTitle.setText(tr("view.result." + t.name()));
    }

    // ------------------------------------------------------------------ computation

    private void settingsChanged() {
        generation++;
        if (source != null) updateTimer.restart();
        bothDirections.setEnabled(tool() == Tool.PIXELIZE && !pairAvg.isSelected());
    }

    private Pixelizer.Direction direction() {
        return pairR2L.isSelected() ? Pixelizer.Direction.R2L
                : pairAvg.isSelected() ? Pixelizer.Direction.AVERAGE : Pixelizer.Direction.L2R;
    }

    private void recompute() {
        if (source == null) return;
        Tool t = tool();
        BufferedImage in = t == Tool.KOALA ? koalaInput : source;
        if (in == null) {
            result = null;
            updateRight();
            setStatus("status.needFrame", source.getWidth(), source.getHeight());
            return;
        }
        long gen = generation;
        Palette pal = (Palette) paletteBox.getSelectedItem();
        ColorMatcher.Space space = (ColorMatcher.Space) spaceBox.getSelectedItem();
        Pixelizer.Direction dir = direction();
        boolean both = bothDirections.isSelected() && dir != Pixelizer.Direction.AVERAGE;
        KoalaConverter.Settings ks = new KoalaConverter.Settings(pal, space, dir, koalaPalettize.isSelected(),
                (Integer) backgroundBox.getSelectedItem(), fixClashes.isSelected());
        long t0 = System.currentTimeMillis();
        if (worker != null) worker.cancel(false);
        progress.setVisible(true);
        worker = new SwingWorker<>() {
            @Override protected Computed doInBackground() {
                switch (t) {
                    case PALETTIZE:
                        return new Computed(t, Palettizer.palettize(in, pal, space), null, null, null);
                    case PIXELIZE: {
                        // "both directions": L2R in the output file, R2L next to it (like the old c64pixelizer)
                        BufferedImage a = Pixelizer.pixelize(in, both ? Pixelizer.Direction.L2R : dir);
                        BufferedImage b = both ? Pixelizer.pixelize(in, Pixelizer.Direction.R2L) : null;
                        return new Computed(t, a, b, null, null);
                    }
                    default:
                        try {
                            KoalaConverter.Result r = KoalaConverter.convert(in, ks);
                            BufferedImage img = r.ok() ? r.koala().render(pal)
                                    : Palettizer.render(doubleWidth(r.indices()), 320, 200, pal);
                            return new Computed(t, img, null, r, null);
                        } catch (KoalaConverter.ConversionException e) {
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
                reportResult(System.currentTimeMillis() - t0);
            }
        };
        worker.execute();
    }

    private static int[] doubleWidth(int[] idx160) {
        int[] out = new int[320 * 200];
        for (int i = 0; i < idx160.length; i++) out[2 * i] = out[2 * i + 1] = idx160[i];
        return out;
    }

    private void reportResult(long ms) {
        if (result == null) return;
        double secs = ms / 1000.0;
        if (result.error() != null) {
            setStatus("status.error", result.error());
            return;
        }
        KoalaConverter.Result k = result.koala();
        if (k == null) {
            setStatus("status.ready", secs);
        } else if (!k.ok()) {
            setStatus("status.clashes", k.clashCells().size());
        } else if (!k.clashCells().isEmpty()) {
            setStatus("status.koalaFixed", secs, k.background(), k.clashCells().size());
        } else {
            setStatus("status.koalaOk", secs, k.background());
        }
    }

    // ------------------------------------------------------------------ previews

    private void updateLeft() {
        BufferedImage img = tool() == Tool.KOALA && koalaInput != null ? koalaInput : source;
        leftPanel.setImage(img);
        updateAspect();
    }

    private void updateRight() {
        if (result == null || result.image() == null || result.tool() != tool()) {
            rightPanel.setImage(null);
            updateAspect();
            return;
        }
        BufferedImage img = result.image();
        KoalaConverter.Result k = result.koala();
        if (k != null && markClashes.isSelected() && !k.clashCells().isEmpty()) img = markCells(img, k.clashCells(), !k.ok());
        rightPanel.setImage(img);
        updateAspect();
        if (linkViews.isSelected() && sameSize()) rightPanel.copyViewFrom(leftPanel);
    }

    /** Outlines cells (8×8 on the 320×200 picture): red = too many colours, yellow = repaired. */
    private static BufferedImage markCells(BufferedImage src, List<Integer> cells, boolean errors) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.setColor(errors ? new Color(255, 40, 40) : new Color(255, 220, 0));
        for (int cell : cells) {
            int x = (cell % KoalaImage.COLS) * 8, y = (cell / KoalaImage.COLS) * 8;
            g.drawRect(x, y, 7, 7);
        }
        g.dispose();
        return out;
    }

    private void updateAspect() {
        leftPanel.setDisplayAspect(aspectOf(leftPanel.getImage()));
        rightPanel.setDisplayAspect(aspectOf(rightPanel.getImage()));
    }

    /** Square pixels, except C64-sized pictures with "PAL aspect" on. */
    private double aspectOf(BufferedImage img) {
        if (img == null) return 320 * PAL_PIXEL / 200;
        double a = (double) img.getWidth() / img.getHeight();
        return palAspect.isSelected() && KoalaConverter.isValidSize(img) ? 320 * PAL_PIXEL / 200 : a;
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
        FileDialogs.addFilter(fc, ext.equals("kla") ? "filter.kla" : "filter.png", ext);
        if (current != null) fc.setSelectedFile(current);
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            String p = fc.getSelectedFile().getPath();
            if (!p.toLowerCase(Locale.ROOT).endsWith("." + ext)) p += "." + ext;
            outputField.setText(p);
            outputDirChosen = new File(p).getAbsoluteFile().getParentFile();
            FileDialogs.rememberDir("output", outputDirChosen);
        }
    }

    private String outputExtension() { return tool() == Tool.KOALA ? "kla" : "png"; }

    /** Output = input name plus the suffix of the old tools, in the chosen output folder or next to the input. */
    private void updateOutputPath() {
        if (inputFile == null) return;
        String base = inputFile.getName().replaceFirst("\\.[^.]+$", "");
        String name = switch (tool()) {
            case PALETTIZE -> base + "_palettized.png";
            case PIXELIZE -> base + (bothDirections.isSelected() && !pairAvg.isSelected()
                    ? Pixelizer.Direction.L2R : direction()).fileSuffix() + ".png";
            case KOALA -> base + ".kla";
        };
        File dir = outputDirChosen != null ? outputDirChosen : inputFile.getAbsoluteFile().getParentFile();
        outputField.setText(new File(dir, name).getPath());
    }

    private void loadInput(File f) {
        BufferedImage img;
        try {
            img = Images.read(f.toPath(), (Palette) paletteBox.getSelectedItem());
        } catch (Exception ex) {
            error(tr("err.read", ex.getMessage()));
            return;
        }
        FileDialogs.rememberDir("input", f);
        if (leftPanel.isCropMode()) leftPanel.cancelCrop();
        ImageFitter.Settings fs = null;
        if (tool() == Tool.KOALA && !KoalaConverter.isValidSize(img)) {
            CropScaleDialog d = new CropScaleDialog(this, img, ImageFitter.Settings.defaults());
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
        inputField.setText(f.getPath());
        updateOutputPath();
        result = null;
        applyFit();
        leftPanel.fit();
        rightPanel.fit();
        setStatus("status.loaded", f.getName(), img.getWidth(), img.getHeight());
    }

    /** A Koala file as input is redrawn when the palette changes. */
    private void reloadKoalaInput() {
        try {
            source = Images.read(inputFile.toPath(), (Palette) paletteBox.getSelectedItem());
            applyFit();
        } catch (Exception ex) {
            error(tr("err.read", ex.getMessage()));
        }
    }

    /** Builds the 320×200 input of the Koala tool. */
    private void applyFit() {
        if (source == null) return;
        if (KoalaConverter.isValidSize(source) && fitSettings == null) koalaInput = source;
        else if (fitSettings != null) koalaInput = ImageFitter.fit(source, fitSettings);
        else koalaInput = null;
        updateTitles();
        updateLeft();
        settingsChanged();
    }

    private void openCropDialog() {
        if (source == null || tool() != Tool.KOALA) return;
        if (leftPanel.isCropMode()) leftPanel.cancelCrop();
        CropScaleDialog d = new CropScaleDialog(this, source,
                fitSettings != null ? fitSettings : ImageFitter.Settings.defaults());
        d.setVisible(true);
        if (d.isAccepted()) {
            fitSettings = d.getSettings();
            applyFit();
        } else if (koalaInput == null) {
            setStatus("status.needFrame", source.getWidth(), source.getHeight());
        }
    }

    /** Right click on the original (Koala tool): choose the frame directly in the preview. */
    private void startReframe() {
        if (source == null || tool() != Tool.KOALA || leftPanel.isCropMode()) return;
        ImageFitter.Settings base = fitSettings != null ? fitSettings : ImageFitter.Settings.defaults();
        double ratio = base.aspect().ratio > 0 ? base.aspect().ratio : 1.6;
        reframeBackup = fitSettings;
        leftPanel.startCrop(source, base.crop(), ratio, new ImagePanel.CropListener() {
            @Override public void cropChanged(Rectangle crop) {
                fitSettings = new ImageFitter.Settings(crop, ImageFitter.ScaleMode.FILL,
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
        if (result == null || result.tool() != tool() || (updateTimer.isRunning() || (worker != null && !worker.isDone()))) {
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
                case KOALA -> {
                    KoalaConverter.Result k = result.koala();
                    if (!k.ok()) {
                        error(tr("err.clashes", k.clashCells().size()));
                        return;
                    }
                    Files.write(target, k.koala().toBytes());
                    written.add(target.getFileName().toString());
                }
            }
            String list = String.join(", ", written);
            setStatus("status.saved", list);
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
            Images.writePng(result.image(), p);   // without clash markers
            setStatus("status.saved", p.getFileName());
        } catch (Exception ex) {
            error(tr("err.write", ex.getMessage()));
        }
    }

    private void editCustomPalette() {
        PaletteEditorDialog d = new PaletteEditorDialog(this, (Palette) paletteBox.getSelectedItem());
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
