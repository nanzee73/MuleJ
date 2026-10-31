package de.mulej.gui;

import de.c64gfx.swing.UndoHistory;

import de.c64gfx.dither.Dither;
import de.c64gfx.dither.Ditherer;
import de.c64gfx.swing.FileDialogs;
import de.c64gfx.swing.Icons;
import de.c64gfx.swing.ImagePanel;
import de.c64gfx.swing.PaletteEditorDialog;
import de.c64gfx.swing.SliderControls;
import de.c64gfx.swing.WrapLayout;

import de.mulej.App;
import de.c64gfx.color.ColorMatcher;
import de.mulej.core.*;
import de.mulej.core.Pipeline.Step;
import de.mulej.core.Pipeline.Target;
import de.c64gfx.swing.I18n;
import de.c64gfx.swing.ImageAdjust;
import de.c64gfx.swing.ImageFitter;
import de.c64gfx.palette.Palette;
import de.c64gfx.palette.Palettes;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.function.Supplier;

import static de.c64gfx.swing.I18n.tr;

/**
 * Main window: one conversion chain instead of separate tools.
 * <pre>
 *  target (multicolor / hires / PETSCII) · open picture · start at (automatic or chosen)
 *  ① Original → ② Frame → ③ Prepare → ④ Pixelizer → ⑤ Colours → ⑥ Result      (④ only for multicolor)
 *  settings of the selected step | input of the step | output of the step
 *  back · next · save this step as PNG (1×–4×) · result: KLA/ART/Petmate, PRG
 * </pre>
 * Every change is computed automatically from the changed step on.
 */
public class MainWindow extends JFrame {

    private static final double PAL_PIXEL = 0.9365;
    private static final String CIRCLED = "①②③④⑤⑥";
    /** Marker for "start automatically" in the entry box. */
    private static final Object AUTO = new Object();

    // ---------------------------------------------------------------- top
    private final Map<Target, JRadioButton> targetButtons = new EnumMap<>(Target.class);
    private final JButton openButton = I18n.text(new JButton(), "top.open");
    private final JLabel fileLabel = new JLabel(" ");
    private final DefaultComboBoxModel<Object> entryModel = new DefaultComboBoxModel<>();
    private final JComboBox<Object> entryBox = new JComboBox<>(entryModel);
    private final JLabel detectionLabel = new JLabel(" ");

    // ---------------------------------------------------------------- steps
    private final Map<Step, JToggleButton> stepButtons = new EnumMap<>(Step.class);
    private final JPanel stepBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    private final CardLayout settingsCards = new CardLayout();
    /** Previews on top, settings of the current step below. */
    private JSplitPane center;
    private final JPanel settingsPanel = new WidthTrackingPanel(settingsCards);
    private final Map<Step, JPanel> stepPanels = new EnumMap<>(Step.class);
    private final Map<Step, JPanel> skippedPanels = new EnumMap<>(Step.class);

    // ① original
    private final JLabel originalInfo = new JLabel();

    // ② frame
    private final CropEditor cropEditor = new CropEditor();

    // ③ prepare (as in FlipperJ)
    private final JSlider contrast = slider(-100, 100, 0), brightness = slider(-100, 100, 0);
    private final JSlider gamma = slider(10, 300, 100), saturation = slider(0, 200, 100);

    // ④ pixelizer
    private final JRadioButton pairL2R = new JRadioButton("", true), pairR2L = new JRadioButton();

    // ⑤ colours
    private final DefaultComboBoxModel<Palette> paletteModel = new DefaultComboBoxModel<>();
    private final JComboBox<Palette> paletteBox = new JComboBox<>(paletteModel);
    private final JComboBox<ColorMatcher.Space> spaceBox = new JComboBox<>(ColorMatcher.Space.values());
    private final JComboBox<Dither> ditherBox = new JComboBox<>(Dither.values());
    private final JSlider strength = slider(0, 100, 80);
    private final JCheckBox serpentine = I18n.text(new JCheckBox(), "dit.serpentine");
    private final JCheckBox bleeding = I18n.tip(I18n.text(new JCheckBox("", true), "dit.bleeding"), "dit.bleeding.tip");
    private final JComboBox<Integer> backgroundBox = new JComboBox<>();
    private final JCheckBox fixClashes = I18n.tip(I18n.text(new JCheckBox(), "bmp.fix"), "bmp.fix.tip");
    private final JComboBox<Charset> charsetBox = new JComboBox<>(Charset.values());
    private final JCheckBox textChars = I18n.tip(I18n.text(new JCheckBox(), "pet.text"), "pet.text.tip");
    private final JComboBox<Integer> petBackgroundBox = new JComboBox<>();
    private final JSlider detail = slider(0, 100, 40), diffusion = slider(0, 100, 40), calm = slider(0, 100, 30);
    private JPanel bitmapColours, petsciiColours;

    // ⑥ result
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
    private final JButton fixAll = I18n.tip(I18n.text(new JButton(), "chk.fixAll"), "chk.fixAll.tip");
    private final JLabel petsciiInfo = new JLabel();
    private JPanel bitmapResult, petsciiResult;
    private final Map<Integer, int[]> cellEdits = new HashMap<>();
    private final Map<Integer, Integer> pixelEdits = new HashMap<>();
    private int selectedCell = -1;

    // ---------------------------------------------------------------- previews
    private final ImagePanel leftPanel = new ImagePanel(), rightPanel = new ImagePanel();
    private final CardLayout leftCards = new CardLayout();
    private final JPanel leftArea = new JPanel(leftCards);
    private final JLabel leftTitle = new JLabel(), rightTitle = new JLabel();
    private final JCheckBox linkViews = I18n.tip(I18n.text(new JCheckBox("", true), "view.link"), "view.link.tip");
    private final JCheckBox showGrid = I18n.tip(I18n.text(new JCheckBox("", true), "view.grid"), "view.grid.tip");
    private final JCheckBox palAspect = I18n.tip(I18n.text(new JCheckBox("", true), "view.pal"), "view.pal.tip");

    // ---------------------------------------------------------------- bottom
    private final JButton backButton = I18n.text(new JButton(), "nav.back");
    private final JButton nextButton = I18n.text(new JButton(), "nav.next");
    private final JButton savePngButton = I18n.tip(I18n.text(new JButton(), "save.png"), "save.png.tip");
    private final JComboBox<Integer> pngScale = new JComboBox<>(new Integer[]{1, 2, 3, 4});
    private final JButton saveDataButton = new JButton();
    private final JButton savePrgButton = I18n.tip(I18n.text(new JButton(), "save.prg"), "save.prg.tip");
    private final JProgressBar progress = new JProgressBar();
    private final JLabel status = new JLabel(" ");
    private Supplier<String> statusText = () -> " ";
    private Supplier<String> checkText = () -> " ";
    private Supplier<String> detectionText = () -> " ";

    // ---------------------------------------------------------------- state
    private File inputFile;
    private BufferedImage source;
    /** Frame & scaling; null = the picture is taken as it is (C64 size). */
    private ImageFitter.Settings fitSettings;
    /** Undo / redo of settings, frame and colour check edits (not of loading a picture). */
    private final UndoHistory history = new UndoHistory(this::captureState, this::restoreState);
    private Pipeline.Detection detection;
    /** Entry chosen by hand; null = automatic. */
    private Step manualEntry;
    private Step current = Step.ORIGINAL;
    private Pipeline.Outputs outputs;
    /** Outputs belong to these parameters (needed to reuse earlier steps). */
    private Pipeline.Params outputParams;
    private Step dirtyFrom = Step.FRAME;
    private long generation;
    private SwingWorker<Pipeline.Outputs, Void> worker;
    private final javax.swing.Timer updateTimer = new javax.swing.Timer(150, e -> recompute());
    /** True while widgets are set programmatically (no recomputation). */
    private boolean loading;
    private final List<Component> needPicture = new ArrayList<>();

    public MainWindow() {
        I18n.bind(() -> setTitle(tr("app.title", App.VERSION)));
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setIconImages(Icons.appIcons());
        for (Palette p : Palettes.builtIns()) paletteModel.addElement(p);
        paletteBox.setSelectedItem(Palettes.COLODORE);
        spaceBox.setRenderer(renderer(v -> tr("space." + ((ColorMatcher.Space) v).name()), null));
        ditherBox.setRenderer(I18n.enumRenderer("dither"));
        charsetBox.setRenderer(renderer(Object::toString, null));
        for (JComboBox<Integer> b : List.of(backgroundBox, petBackgroundBox)) {
            for (int i = -1; i < 16; i++) b.addItem(i);
            b.setRenderer(renderer(v -> (Integer) v < 0 ? tr("bmp.bgAuto") : v + " " + tr("color." + v), v -> (Integer) v));
        }
        for (int i = 0; i < 16; i++) paintColour.addItem(i);
        paintColour.setRenderer(renderer(v -> v + " " + tr("color." + v), v -> (Integer) v));
        paintColour.setSelectedIndex(1);
        pngScale.setRenderer(renderer(v -> v + "×", null));
        ditherBox.setSelectedItem(Dither.NONE);
        entryBox.setRenderer(renderer(this::entryText, null));
        updateTimer.setRepeats(false);

        setJMenuBar(buildMenu());
        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.add(buildTopPanel());
        north.add(buildStepBar());
        root.add(north, BorderLayout.NORTH);
        JComponent settings = buildSettings();
        shrinkable(settings);
        JScrollPane settingsScroll = new JScrollPane(settings, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        settingsScroll.setBorder(BorderFactory.createEmptyBorder());
        settingsScroll.setPreferredSize(new Dimension(900, 250));
        settingsScroll.setMinimumSize(new Dimension(100, 80));
        settingsScroll.getVerticalScrollBar().setUnitIncrement(16);
        // the previews get the full width, the settings of the step sit below them; the divider can be moved
        JComponent previews = buildPreviews();
        previews.setMinimumSize(new Dimension(100, 150));
        center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, previews, settingsScroll);
        center.setResizeWeight(1.0);                 // extra height goes to the pictures
        center.setContinuousLayout(true);
        center.setBorder(BorderFactory.createEmptyBorder());
        root.add(center, BorderLayout.CENTER);
        root.add(buildBottom(), BorderLayout.SOUTH);
        setContentPane(root);
        installDragAndDrop(root);
        wireListeners();

        I18n.bind(() -> status.setText(statusText.get()));
        I18n.bind(() -> checkLabel.setText(checkText.get()));
        I18n.bind(() -> detectionLabel.setText(detectionText.get()));
        I18n.bind(() -> leftPanel.setPlaceholder(tr("preview.placeholder.left")));
        I18n.bind(() -> rightPanel.setPlaceholder(tr("preview.placeholder.right")));
        I18n.bind(this::updateStepBar);
        I18n.bind(this::updateViews);
        I18n.bind(this::updateOriginalInfo);
        I18n.bind(this::updateEnabled);
        updateEntryBox();
        showStep(Step.ORIGINAL);
        pack();
        setMinimumSize(new Dimension(1150, 780));
        setLocationRelativeTo(null);
    }

    // ================================================================ building the window

    private JMenuBar buildMenu() {
        JMenuBar bar = new JMenuBar();
        JMenu file = I18n.text(new JMenu(), "menu.file");
        JMenuItem open = I18n.text(new JMenuItem(), "menu.file.open");
        open.addActionListener(e -> chooseInput());
        JMenuItem exit = I18n.text(new JMenuItem(), "menu.file.exit");
        exit.addActionListener(e -> dispose());
        file.add(open);
        file.addSeparator();
        file.add(exit);

        JMenu language = I18n.text(new JMenu(), "menu.language");
        ButtonGroup lg = new ButtonGroup();
        JRadioButtonMenuItem system = new JRadioButtonMenuItem();
        I18n.bind(() -> system.setText(tr("opt.languageSystem", I18n.nativeName(I18n.systemLocale()))));
        system.addActionListener(e -> {
            I18n.useSystemLocale();                     // English if the system language is not available
            refreshLanguage();
        });
        lg.add(system);
        language.add(system);
        language.addSeparator();
        java.util.Map<Locale, JRadioButtonMenuItem> items = new java.util.HashMap<>();
        for (Locale l : I18n.supportedLocales()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(I18n.nativeName(l));
            item.addActionListener(e -> {
                I18n.setLocale(l);                      // remembered for the next start
                refreshLanguage();
            });
            lg.add(item);
            language.add(item);
            items.put(l, item);
        }
        I18n.bind(() -> (I18n.followsSystem() ? system : items.get(I18n.locale())).setSelected(true));

        JMenu help = I18n.text(new JMenu(), "menu.help");
        JMenuItem guide = I18n.text(new JMenuItem(), "menu.help.guide");
        guide.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F1, 0));
        guide.addActionListener(e -> de.c64gfx.swing.HelpDialog.show(this,
                App.NAME + " – " + tr("menu.help.guide"), "/de/mulej/help/help"));
        help.add(guide);
        help.addSeparator();
        JMenuItem about = I18n.text(new JMenuItem(), "menu.help.about");
        about.addActionListener(e -> JOptionPane.showMessageDialog(this, tr("about.text", App.VERSION),
                tr("about.title"), JOptionPane.INFORMATION_MESSAGE, aboutIcon()));
        help.add(about);
        bar.add(file);
        bar.add(history.menu());
        bar.add(language);
        bar.add(help);
        return bar;
    }

    private JPanel buildTopPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEtchedBorder());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.anchor = GridBagConstraints.WEST;
        JPanel targets = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        targets.add(I18n.label("top.target"));
        ButtonGroup g = new ButtonGroup();
        for (Target t : Target.values()) {
            JRadioButton b = I18n.tip(I18n.text(new JRadioButton("", t == Target.MULTICOLOR), "target." + t.name()),
                    "target." + t.name() + ".tip");
            targetButtons.put(t, b);
            g.add(b);
            targets.add(b);
        }
        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        p.add(targets, c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        fileLabel.setForeground(new Color(0x606060));
        p.add(fileLabel, c);
        c.gridx = 2; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        p.add(openButton, c);

        JPanel entry = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        entry.add(I18n.label("top.entry"));
        I18n.tip(entryBox, "top.entry.tip");
        entry.add(entryBox);
        entry.add(detectionLabel);
        c.gridx = 0; c.gridy = 1; c.gridwidth = 3; c.fill = GridBagConstraints.HORIZONTAL;
        p.add(entry, c);
        needPicture.add(entry);
        return p;
    }

    private JPanel buildStepBar() {
        stepBar.setBorder(BorderFactory.createEtchedBorder());
        ButtonGroup g = new ButtonGroup();
        for (Step s : Step.values()) {
            JToggleButton b = new JToggleButton();
            b.setFocusable(false);
            b.setMargin(new Insets(3, 10, 3, 10));
            b.addActionListener(e -> showStep(s));
            stepButtons.put(s, b);
            g.add(b);
        }
        return stepBar;
    }

    /** Re-arranges the step bar for the current target (④ only for multicolor) and numbers the steps. */
    private void updateStepBar() {
        stepBar.removeAll();
        List<Step> steps = Pipeline.steps(target());
        Pipeline.Params p = params();
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (i > 0) {
                JLabel arrow = new JLabel("→");
                arrow.setForeground(Color.GRAY);
                stepBar.add(arrow);
            }
            JToggleButton b = stepButtons.get(s);
            boolean skipped = source != null && p.skipped(s);
            b.setText(CIRCLED.charAt(i) + " " + s + (skipped ? " " + tr("step.skippedShort") : ""));
            b.setForeground(skipped ? Color.GRAY : UIManager.getColor("ToggleButton.foreground"));
            b.setToolTipText(tr("step." + s.name() + ".tip"));
            b.setEnabled(source != null || s == Step.ORIGINAL);
            stepBar.add(b);
        }
        stepBar.revalidate();
        stepBar.repaint();
    }

    private JComponent buildSettings() {
        // ① original
        JPanel original = group("step.ORIGINAL");
        GridBagConstraints c = gbc();
        originalInfo.setVerticalAlignment(SwingConstants.TOP);
        c.gridy = 0; c.weightx = 1; original.add(originalInfo, c);
        addGlue(original);
        stepPanels.put(Step.ORIGINAL, original);

        // ② frame
        JPanel frame = group("step.FRAME");
        c = gbc();
        c.gridy = 0; c.weightx = 1; frame.add(cropEditor.controls(), c);
        addGlue(frame);
        stepPanels.put(Step.FRAME, frame);

        // ③ prepare
        JPanel prepare = group("step.PREPARE");
        c = gbc();
        addSlider(prepare, c, 0, "prep.contrast", contrast);
        addSlider(prepare, c, 1, "prep.brightness", brightness);
        addSlider(prepare, c, 2, "prep.gamma", gamma);
        addSlider(prepare, c, 3, "prep.saturation", saturation);
        addDefaultButton(prepare, () -> {
            contrast.setValue(0); brightness.setValue(0); gamma.setValue(100); saturation.setValue(100);
        });
        stepPanels.put(Step.PREPARE, prepare);

        // ④ pixelizer
        JPanel pix = group("step.PIXELIZER");
        c = gbc();
        ButtonGroup pg = new ButtonGroup();
        pg.add(pairL2R); pg.add(pairR2L);
        I18n.tip(I18n.text(pairL2R, "pixel.L2R"), "pixel.L2R.tip");
        I18n.tip(I18n.text(pairR2L, "pixel.R2L"), "pixel.R2L.tip");
        c.gridy = 0; pix.add(pairL2R, c);
        c.gridy = 1; pix.add(pairR2L, c);
        JLabel pixHint = new JLabel();
        I18n.bind(() -> pixHint.setText(wrapped("<small>" + tr("pixel.hint") + "</small>")));
        c.gridy = 2; c.weightx = 1; pix.add(pixHint, c);
        addDefaultButton(pix, () -> pairL2R.doClick());
        stepPanels.put(Step.PIXELIZER, pix);

        // ⑤ colours
        JPanel colours = new JPanel(new GridBagLayout());
        c = gbc();
        c.gridy = 0; c.weightx = 1; c.gridx = 0; colours.add(buildPaletteGroup(), c);   // side by side: less height
        c.gridx = 1; colours.add(bitmapColours = buildBitmapColours(), c);
        c.gridx = 2; colours.add(petsciiColours = buildPetsciiColours(), c);
        addGlue(colours);
        stepPanels.put(Step.COLOURS, colours);

        // ⑥ result
        JPanel result = new JPanel(new GridBagLayout());
        c = gbc();
        c.gridy = 0; c.weightx = 1; result.add(bitmapResult = buildBitmapResult(), c);
        c.gridy = 1; result.add(petsciiResult = buildPetsciiResult(), c);
        addGlue(result);
        stepPanels.put(Step.RESULT, result);

        for (Step s : Step.values()) {
            settingsPanel.add(stepPanels.get(s), s.name());
            JPanel skipped = buildSkippedPanel(s);
            skippedPanels.put(s, skipped);
            settingsPanel.add(skipped, "skipped-" + s.name());
        }
        return settingsPanel;
    }

    /** Shown instead of the settings of a step that is passed through. */
    private JPanel buildSkippedPanel(Step s) {
        JPanel p = group("step." + s.name());
        GridBagConstraints c = gbc();
        JLabel text = new JLabel();
        I18n.bind(() -> text.setText(wrapped(tr("step.skipped", s.toString()))));
        c.gridy = 0; c.weightx = 1; p.add(text, c);
        JButton use = I18n.tip(I18n.text(new JButton(), "step.useAnyway"), "step.useAnyway.tip");
        use.addActionListener(e -> setManualEntry(s));
        c.gridy = 1; c.fill = GridBagConstraints.NONE; p.add(use, c);
        addGlue(p);
        return p;
    }

    private JPanel buildPaletteGroup() {
        JPanel p = group("pal.group");
        GridBagConstraints c = gbc();
        JButton custom = I18n.text(new JButton(), "pal.customButton");
        custom.addActionListener(e -> editCustomPalette());
        c.gridy = 0; c.gridx = 0; c.gridwidth = 2; c.weightx = 1; p.add(paletteBox, c);
        c.gridy = 1; c.gridwidth = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE; p.add(custom, c);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridy = 2; c.gridx = 0; p.add(fixedLabel("pal.matchIn"), c);
        c.gridx = 1; c.weightx = 1; p.add(spaceBox, c);
        addDefaultButton(p, () -> {
            paletteBox.setSelectedItem(Palettes.COLODORE);
            spaceBox.setSelectedItem(ColorMatcher.Space.EUCLID);
        });
        return p;
    }

    private JPanel buildBitmapColours() {
        JPanel p = group("dit.group");
        GridBagConstraints c = gbc();
        c.gridy = 0; c.gridx = 0; c.gridwidth = 3; c.weightx = 1; p.add(ditherBox, c);
        c.gridwidth = 1; c.weightx = 0;
        addSlider(p, c, 1, "dit.strength", strength);
        c.gridx = 0; c.gridwidth = 3;
        c.gridy = 2; p.add(serpentine, c);
        c.gridy = 3; p.add(bleeding, c);
        c.gridwidth = 1;
        c.gridy = 4; c.gridx = 0; p.add(fixedLabel("bmp.background"), c);
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1; p.add(backgroundBox, c);
        c.gridx = 0; c.gridwidth = 3;
        c.gridy = 5; p.add(fixClashes, c);
        addDefaultButton(p, () -> {
            ditherBox.setSelectedItem(Dither.NONE);
            strength.setValue(80);
            serpentine.setSelected(false);
            bleeding.setSelected(true);
            backgroundBox.setSelectedIndex(0);
            fixClashes.setSelected(false);
            changed(Step.COLOURS);
        });
        return p;
    }

    private JPanel buildPetsciiColours() {
        JPanel p = group("pet.group");
        GridBagConstraints c = gbc();
        c.gridy = 0; c.gridx = 0; p.add(fixedLabel("pet.charset"), c);
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1; p.add(charsetBox, c);
        c.gridwidth = 1; c.weightx = 0;
        c.gridy = 1; c.gridx = 0; p.add(fixedLabel("bmp.background"), c);
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1; p.add(petBackgroundBox, c);
        c.gridx = 0; c.gridwidth = 3;
        c.gridy = 2; p.add(textChars, c);
        c.gridwidth = 1; c.weightx = 0;
        addSlider(p, c, 3, "pet.detail", detail);
        addSlider(p, c, 4, "pet.diffusion", diffusion);
        addSlider(p, c, 5, "pet.calm", calm);
        I18n.tip(detail, "pet.detail.tip");
        I18n.tip(diffusion, "pet.diffusion.tip");
        I18n.tip(calm, "pet.calm.tip");
        JLabel hint = new JLabel();
        I18n.bind(() -> hint.setText(wrapped("<small>" + tr("pet.hint") + "</small>")));
        c.gridx = 0; c.gridwidth = 3; c.gridy = 6; c.weightx = 1; p.add(hint, c);
        addDefaultButton(p, () -> {
            PetsciiConverter.Settings d = PetsciiConverter.Settings.defaults();
            charsetBox.setSelectedItem(d.charset());
            petBackgroundBox.setSelectedIndex(0);
            textChars.setSelected(d.textChars());
            detail.setValue(d.detail());
            diffusion.setValue(d.diffusion());
            calm.setValue(d.calm());
            changed(Step.COLOURS);
        });
        return p;
    }

    /** Colour check and manual correction (multicolor / hires). */
    private JPanel buildBitmapResult() {
        JPanel p = group("chk.group");
        checkLabel.setFont(checkLabel.getFont().deriveFont(Font.BOLD));
        JButton prev = I18n.tip(new JButton("◀"), "chk.prev.tip"), next = I18n.tip(new JButton("▶"), "chk.next.tip");
        prev.setMargin(new Insets(1, 6, 1, 6));
        next.setMargin(new Insets(1, 6, 1, 6));
        prev.addActionListener(e -> jumpToClash(-1));
        next.addActionListener(e -> jumpToClash(1));
        ButtonGroup g = new ButtonGroup();
        g.add(toolSelect); g.add(toolPaint);

        // three columns, so the group needs little height below the previews:
        // check status and tools | selected cell and its colours | cell buttons
        JPanel status = new JPanel(new GridBagLayout());
        GridBagConstraints c = gbc();
        c.gridy = 0; c.weightx = 1; status.add(checkLabel, c);
        c.gridy = 1; status.add(row(I18n.label("chk.conflict"), prev, next, markCells), c);
        c.gridy = 2; status.add(row(fixAll), c);
        c.gridy = 3; status.add(row(I18n.label("chk.click"), toolSelect), c);
        c.gridy = 4; status.add(row(Box.createHorizontalStrut(12), toolPaint, paintColour), c);

        JPanel cell = new JPanel(new GridBagLayout());
        c = gbc();
        c.gridy = 0; c.weightx = 1; cell.add(cellLabel, c);
        JPanel sw = new JPanel(new GridLayout(2, 8, 2, 2));
        for (int i = 0; i < 16; i++) {
            JToggleButton b = new JToggleButton();
            b.setMargin(new Insets(1, 1, 1, 1));
            b.setFocusable(false);
            b.setHorizontalTextPosition(SwingConstants.CENTER);
            b.setVerticalTextPosition(SwingConstants.BOTTOM);
            b.setFont(b.getFont().deriveFont(9f));
            b.addActionListener(e -> updateCellHint());
            swatches[i] = b;
            sw.add(b);
        }
        c.gridy = 1; cell.add(sw, c);
        c.gridy = 2; cell.add(cellHint, c);

        JPanel buttons = new JPanel(new GridLayout(4, 1, 3, 3));
        buttons.add(applyCell);
        buttons.add(autoCell);
        buttons.add(resetCell);
        buttons.add(resetAll);

        c = gbc();
        c.gridy = 0; c.gridx = 0; c.weightx = 0.4; p.add(status, c);
        c.gridx = 1; c.weightx = 0.6; p.add(cell, c);
        c.gridx = 2; c.weightx = 0; p.add(buttons, c);
        applyCell.addActionListener(e -> applyCellColours());
        autoCell.addActionListener(e -> autoCellColours());
        resetCell.addActionListener(e -> resetSelectedCell());
        fixAll.addActionListener(e -> fixAllCells());
        resetAll.addActionListener(e -> {
            int answer = JOptionPane.showConfirmDialog(this, tr("chk.resetAll.confirm"), App.NAME,
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) return;
            cellEdits.clear();
            pixelEdits.clear();
            changed(Step.COLOURS);
        });
        markCells.addActionListener(e -> updateViews());
        toolSelect.addActionListener(e -> updateEnabled());
        toolPaint.addActionListener(e -> updateEnabled());
        return p;
    }

    private JPanel buildPetsciiResult() {
        JPanel p = group("chk.group");
        GridBagConstraints c = gbc();
        petsciiInfo.setVerticalAlignment(SwingConstants.TOP);
        c.gridy = 0; c.weightx = 1; p.add(petsciiInfo, c);
        return p;
    }

    private JPanel buildPreviews() {
        JPanel p = new JPanel(new GridLayout(1, 2, 6, 0));
        leftArea.add(leftPanel, "image");
        leftArea.add(cropEditor.view(), "crop");
        p.add(previewBox(leftArea, leftPanel, leftTitle));
        p.add(previewBox(rightPanel, rightPanel, rightTitle));
        leftPanel.addViewListener(src -> { if (linkViews.isSelected() && sameSize()) rightPanel.copyViewFrom(src); });
        rightPanel.addViewListener(src -> { if (linkViews.isSelected() && sameSize()) leftPanel.copyViewFrom(src); });
        linkViews.addActionListener(e -> { if (linkViews.isSelected() && sameSize()) rightPanel.copyViewFrom(leftPanel); });
        showGrid.addActionListener(e -> {
            leftPanel.setShowGrid(showGrid.isSelected());
            rightPanel.setShowGrid(showGrid.isSelected());
        });
        palAspect.addActionListener(e -> updateAspect());
        return p;
    }

    private boolean sameSize() {
        BufferedImage a = leftPanel.getImage(), b = rightPanel.getImage();
        return a == null || b == null || (a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight());
    }

    private JPanel previewBox(JComponent content, ImagePanel panel, JLabel title) {
        JPanel box = new JPanel(new BorderLayout());
        box.setBorder(BorderFactory.createEtchedBorder());
        JPanel header = new JPanel(new BorderLayout());
        title.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        header.add(title, BorderLayout.WEST);
        JPanel tools = new JPanel(new WrapLayout(FlowLayout.RIGHT, 2, 0));
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
            tools.add(palAspect);
        }
        needPicture.add(tools);
        header.add(tools, BorderLayout.CENTER);
        box.add(header, BorderLayout.NORTH);
        box.add(content, BorderLayout.CENTER);
        return box;
    }

    private JPanel buildBottom() {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        backButton.addActionListener(e -> stepBy(-1));
        nextButton.addActionListener(e -> stepBy(1));
        left.add(backButton);
        left.add(nextButton);
        progress.setIndeterminate(true);
        progress.setPreferredSize(new Dimension(70, progress.getPreferredSize().height));
        progress.setVisible(false);
        left.add(progress);
        left.add(status);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        I18n.tip(pngScale, "save.scale.tip");
        right.add(savePngButton);
        right.add(pngScale);
        right.add(Box.createHorizontalStrut(10));
        right.add(saveDataButton);
        right.add(savePrgButton);
        savePngButton.addActionListener(e -> savePng());
        saveDataButton.addActionListener(e -> saveData());
        savePrgButton.addActionListener(e -> savePrg());
        p.add(left, BorderLayout.CENTER);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    // ---------------------------------------------------------------- small UI helpers

    /** Combo boxes and spinners may get narrower than their longest entry, so the settings column never overflows. */
    static void shrinkable(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof JComboBox<?> || child instanceof JSpinner) {
                Dimension d = child.getPreferredSize();
                child.setPreferredSize(new Dimension(Math.min(d.width, 150), d.height));
                child.setMinimumSize(new Dimension(40, d.height));
            } else if (child instanceof Container cc) {
                shrinkable(cc);
            }
        }
    }

    /** HTML text that wraps inside the settings column. */
    static String wrapped(String html) {
        return "<html><body style='width: 270px'>" + html + "</body></html>";
    }

    /** A panel inside a scroll pane that always takes the width of the viewport (only scrolls vertically). */
    private static final class WidthTrackingPanel extends JPanel implements Scrollable {
        WidthTrackingPanel(LayoutManager l) { super(l); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height - 32; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        /** Only the step shown counts (a CardLayout would take the height of the tallest step). */
        @Override public Dimension getPreferredSize() {
            for (Component c : getComponents())
                if (c.isVisible()) {
                    Dimension d = c.getPreferredSize();
                    Insets in = getInsets();
                    return new Dimension(d.width + in.left + in.right, d.height + in.top + in.bottom);
                }
            return super.getPreferredSize();
        }
        @Override public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport v && v.getHeight() > getPreferredSize().height;
        }
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

    private static JSlider slider(int min, int max, int value) {
        JSlider s = new JSlider(min, max, value);
        s.setPreferredSize(new Dimension(110, s.getPreferredSize().height));
        return s;
    }

    /** Label | ◀ slider ▶ | value, as in FlipperJ (default mark, snapping, double click resets). */
    private static void addSlider(JPanel p, GridBagConstraints c, int y, String key, JSlider s) {
        JLabel value = new JLabel(String.valueOf(s.getValue()));
        value.setHorizontalAlignment(SwingConstants.RIGHT);
        FontMetrics fm = value.getFontMetrics(value.getFont());
        int w = Math.max(fm.stringWidth(String.valueOf(s.getMinimum())), fm.stringWidth(String.valueOf(s.getMaximum()))) + 6;
        Dimension d = new Dimension(w, value.getPreferredSize().height);
        value.setPreferredSize(d);
        value.setMinimumSize(d);
        s.addChangeListener(e -> value.setText(String.valueOf(s.getValue())));
        int gw = c.gridwidth;
        double wx = c.weightx;
        c.gridwidth = 1;
        c.gridy = y;
        c.gridx = 0; c.weightx = 0; p.add(fixedLabel(key), c);
        c.gridx = 1; c.weightx = 1; p.add(SliderControls.wrap(s), c);
        c.gridx = 2; c.weightx = 0; p.add(value, c);
        c.gridwidth = gw;
        c.weightx = wx;
    }

    private static JPanel group(String titleKey) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(I18n.titled(p, titleKey));
        return p;
    }

    private static GridBagConstraints gbc() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 3, 2, 3);
        c.anchor = GridBagConstraints.NORTHWEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        return c;
    }

    private static void addGlue(JPanel p) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 98; c.weighty = 1;
        p.add(Box.createVerticalGlue(), c);
    }

    private static JPanel row(Component... comps) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        for (Component comp : comps) r.add(comp);
        return r;
    }

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

    private static JButton toolButton(String text, String tipKey, Runnable action) {
        JButton b = new JButton(text);
        I18n.tip(b, tipKey);
        b.setMargin(new Insets(1, 5, 1, 5));
        b.setFocusable(false);
        b.addActionListener(e -> action.run());
        return b;
    }

    private static Icon aboutIcon() {
        for (Image img : Icons.appIcons())
            if (img.getWidth(null) == 128) return new ImageIcon(img);
        return null;
    }

    private static void setEnabledDeep(Component c, boolean on) {
        c.setEnabled(on);
        if (c instanceof JComboBox || c instanceof JMenuItem || !(c instanceof Container cc)) return;
        for (Component child : cc.getComponents()) setEnabledDeep(child, on);
    }

    private void setStatus(String key, Object... args) {
        statusText = () -> tr(key, args);
        status.setText(statusText.get());
    }

    private void setCheck(String key, Object... args) {
        checkText = () -> tr(key, args);
        checkLabel.setText(checkText.get());
    }

    // ================================================================ listeners

    private void wireListeners() {
        openButton.addActionListener(e -> chooseInput());
        for (Target t : Target.values()) targetButtons.get(t).addActionListener(e -> targetChanged());
        entryBox.addActionListener(e -> {
            if (loading) return;
            Object sel = entryBox.getSelectedItem();
            setManualEntry(sel instanceof Step s ? s : null);
        });
        cropEditor.setOnChange(() -> {
            if (loading || source == null) return;
            fitSettings = cropEditor.getSettings();
            changed(Step.FRAME);
        });
        for (JSlider s : List.of(contrast, brightness, gamma, saturation)) s.addChangeListener(e -> changed(Step.PREPARE));
        pairL2R.addActionListener(e -> changed(Step.PIXELIZER));
        pairR2L.addActionListener(e -> changed(Step.PIXELIZER));
        paletteBox.addActionListener(e -> {
            if (loading) return;
            if (inputFile != null && Images.isC64File(inputFile.toPath())) reloadC64Input();
            changed(Step.COLOURS);
            paintColour.repaint();
        });
        spaceBox.addActionListener(e -> changed(Step.COLOURS));
        for (JComboBox<?> b : List.of(ditherBox, backgroundBox, charsetBox, petBackgroundBox))
            b.addActionListener(e -> changed(Step.COLOURS));
        for (AbstractButton b : List.of(serpentine, bleeding, fixClashes, textChars))
            b.addActionListener(e -> changed(Step.COLOURS));
        for (JSlider s : List.of(strength, detail, diffusion, calm))
            s.addChangeListener(e -> { if (!s.getValueIsAdjusting()) changed(Step.COLOURS); });
        rightPanel.setPixelListener(this::resultClicked);
    }

    // ================================================================ state & parameters

    private Target target() {
        for (Target t : Target.values()) if (targetButtons.get(t).isSelected()) return t;
        return Target.MULTICOLOR;
    }

    private Palette palette() { return (Palette) paletteBox.getSelectedItem(); }

    private Step entry() {
        if (source == null) return Step.FRAME;
        Step e = manualEntry != null ? manualEntry : detection != null ? detection.entry() : Step.FRAME;
        return Pipeline.entries(target(), source).contains(e) ? e : Step.FRAME;
    }

    // ---------------------------------------------------------------- undo

    /** All settings and edits, keyed by the translation key of their label (shown as "Undo: …"). */
    private Map<String, Object> captureState() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("top.target", target());
        m.put("top.entry", Optional.ofNullable(manualEntry));
        m.put("step.FRAME", Optional.ofNullable(fitSettings));
        m.put("prep.contrast", contrast.getValue());
        m.put("prep.brightness", brightness.getValue());
        m.put("prep.gamma", gamma.getValue());
        m.put("prep.saturation", saturation.getValue());
        m.put("step.PIXELIZER", pairR2L.isSelected());
        m.put("pal.group", paletteBox.getSelectedItem());
        m.put("pal.matchIn", spaceBox.getSelectedItem());
        m.put("dit.group", ditherBox.getSelectedItem());
        m.put("dit.strength", strength.getValue());
        m.put("dit.serpentine", serpentine.isSelected());
        m.put("dit.bleeding", bleeding.isSelected());
        m.put("bmp.background", backgroundBox.getSelectedIndex());
        m.put("bmp.fix", fixClashes.isSelected());
        m.put("pet.charset", charsetBox.getSelectedItem());
        m.put("pet.text", textChars.isSelected());
        m.put("pet.group", petBackgroundBox.getSelectedIndex());
        m.put("pet.detail", detail.getValue());
        m.put("pet.diffusion", diffusion.getValue());
        m.put("pet.calm", calm.getValue());
        Map<Integer, List<Integer>> cells = new TreeMap<>();
        cellEdits.forEach((k, v) -> cells.put(k, Arrays.stream(v).boxed().toList()));
        m.put("chk.group", List.of(cells, new TreeMap<>(pixelEdits)));
        return m;
    }

    @SuppressWarnings("unchecked")
    private void restoreState(Map<String, Object> m) {
        loading = true;                                       // set everything, then recompute once
        try {
            Target t = (Target) m.get("top.target");
            if (t != target()) {
                targetButtons.get(t).setSelected(true);
                targetChanged();
            }
            manualEntry = ((Optional<Step>) m.get("top.entry")).orElse(null);
            fitSettings = ((Optional<ImageFitter.Settings>) m.get("step.FRAME")).orElse(null);
            if (source != null)
                cropEditor.setSource(source, fitSettings != null ? fitSettings : ImageFitter.Settings.suggested(source));
            contrast.setValue((Integer) m.get("prep.contrast"));
            brightness.setValue((Integer) m.get("prep.brightness"));
            gamma.setValue((Integer) m.get("prep.gamma"));
            saturation.setValue((Integer) m.get("prep.saturation"));
            ((Boolean) m.get("step.PIXELIZER") ? pairR2L : pairL2R).setSelected(true);
            paletteBox.setSelectedItem(m.get("pal.group"));
            spaceBox.setSelectedItem(m.get("pal.matchIn"));
            ditherBox.setSelectedItem(m.get("dit.group"));
            strength.setValue((Integer) m.get("dit.strength"));
            serpentine.setSelected((Boolean) m.get("dit.serpentine"));
            bleeding.setSelected((Boolean) m.get("dit.bleeding"));
            backgroundBox.setSelectedIndex((Integer) m.get("bmp.background"));
            fixClashes.setSelected((Boolean) m.get("bmp.fix"));
            charsetBox.setSelectedItem(m.get("pet.charset"));
            textChars.setSelected((Boolean) m.get("pet.text"));
            petBackgroundBox.setSelectedIndex((Integer) m.get("pet.group"));
            detail.setValue((Integer) m.get("pet.detail"));
            diffusion.setValue((Integer) m.get("pet.diffusion"));
            calm.setValue((Integer) m.get("pet.calm"));
            List<Object> edits = (List<Object>) m.get("chk.group");
            cellEdits.clear();
            ((Map<Integer, List<Integer>>) edits.get(0)).forEach((k, v) ->
                    cellEdits.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
            pixelEdits.clear();
            pixelEdits.putAll((Map<Integer, Integer>) edits.get(1));
        } finally {
            loading = false;
        }
        updateEntryBox();
        updateStepBar();
        showStep(current);
        dirtyFrom = Step.FRAME;
        changed(Step.FRAME);
    }

    private Pipeline.Params params() {
        PetsciiConverter.Settings ps = new PetsciiConverter.Settings((Charset) charsetBox.getSelectedItem(),
                textChars.isSelected(), (Integer) petBackgroundBox.getSelectedItem(), detail.getValue(),
                diffusion.getValue(), calm.getValue());
        return new Pipeline.Params(target(), entry(), fitSettings,
                new ImageAdjust.Settings(brightness.getValue(), contrast.getValue(), gamma.getValue(), saturation.getValue()),
                pairR2L.isSelected() ? Pixelizer.Direction.R2L : Pixelizer.Direction.L2R,
                palette(), (ColorMatcher.Space) spaceBox.getSelectedItem(),
                new Ditherer.Settings((Dither) ditherBox.getSelectedItem(), strength.getValue(), serpentine.isSelected(),
                        bleeding.isSelected()),
                (Integer) backgroundBox.getSelectedItem(), fixClashes.isSelected(), ps,
                new BitmapConverter.Edits(Map.copyOf(cellEdits), Map.copyOf(pixelEdits)));
    }

    /** A setting of a step changed: recompute from that step on (debounced). */
    private void changed(Step from) {
        if (loading || source == null) return;
        history.changed();
        if (from.ordinal() < dirtyFrom.ordinal()) dirtyFrom = from;
        generation++;
        updateTimer.restart();
        updateEnabled();
    }

    private void targetChanged() {
        // pixel and cell coordinates differ between the targets
        cellEdits.clear();
        pixelEdits.clear();
        selectedCell = -1;
        if (source != null && manualEntry == null) detect();
        if (!Pipeline.steps(target()).contains(current)) current = Step.COLOURS;
        updateEntryBox();
        updateStepBar();
        showStep(current);
        changed(Step.FRAME);
    }

    private void setManualEntry(Step s) {
        manualEntry = s;
        updateEntryBox();
        updateStepBar();
        showStep(current);
        changed(Step.FRAME);
    }

    private String entryText(Object v) {
        if (v == AUTO) {
            Step e = detection != null ? detection.entry() : Step.FRAME;
            return tr("entry.auto", stepLabel(e));
        }
        return stepLabel((Step) v);
    }

    /** "③ Prepare" with the number the step has for the current target. */
    private String stepLabel(Step s) {
        int i = Pipeline.steps(target()).indexOf(s);
        return (i >= 0 ? CIRCLED.charAt(i) + " " : "") + s;
    }

    private void updateEntryBox() {
        loading = true;
        entryModel.removeAllElements();
        entryModel.addElement(AUTO);
        for (Step s : Pipeline.entries(target(), source)) entryModel.addElement(s);
        entryBox.setSelectedItem(manualEntry != null && entryModel.getIndexOf(manualEntry) >= 0 ? manualEntry : AUTO);
        if (manualEntry != null && entryModel.getIndexOf(manualEntry) < 0) manualEntry = null;
        loading = false;
        detectionText = () -> detection == null ? " " : tr(detection.reasonKey());
        detectionLabel.setText(detectionText.get());
    }

    private void detect() {
        detection = Pipeline.detect(source, target(), palette());
        if (!detection.palette().equals(palette())) {
            loading = true;
            paletteBox.setSelectedItem(detection.palette());
            loading = false;
        }
    }

    // ================================================================ computation

    private void recompute() {
        if (source == null) return;
        Pipeline.Params p = params();
        // earlier results can only be reused if they were made with the same picture and earlier settings
        Step from = outputs == null || outputParams == null || outputParams.target() != p.target()
                || outputParams.entry() != p.entry() ? Step.FRAME : dirtyFrom;
        Pipeline.Outputs prev = outputs;
        long gen = generation;
        BufferedImage src = source;
        if (worker != null) worker.cancel(false);
        progress.setVisible(true);
        long t0 = System.currentTimeMillis();
        worker = new SwingWorker<>() {
            @Override protected Pipeline.Outputs doInBackground() {
                return Pipeline.run(src, p, prev, from);
            }

            @Override protected void done() {
                if (isCancelled()) return;
                try {
                    Pipeline.Outputs o = get();
                    // settings changed meanwhile: the next run is already scheduled and starts early enough
                    if (gen != generation) return;
                    outputs = o;
                    outputParams = p;
                    dirtyFrom = Step.RESULT;
                    progress.setVisible(false);
                    report(System.currentTimeMillis() - t0);
                    updateViews();
                    updateEnabled();
                    updateCellPanel();
                } catch (Exception ex) {
                    progress.setVisible(false);
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    setStatus("status.error", String.valueOf(cause.getMessage()));
                }
            }
        };
        worker.execute();
    }

    private void report(long ms) {
        Pipeline.Outputs o = outputs;
        double secs = ms / 1000.0;
        if (o.error() != null) {
            setStatus("status.error", o.error());
            setCheck("chk.none");
            return;
        }
        if (o.petscii() != null) {
            PetsciiImage pet = o.petscii();
            setStatus("status.petscii", secs, pet.distinctCodes(), pet.background);
            petsciiInfo.setText(wrapped(tr("pet.result", pet.distinctCodes(), pet.background, tr("color." + pet.background))
                    + "<br><br>" + tr("pet.result.edit")));
            return;
        }
        BitmapConverter.Result r = o.bitmap();
        String modeName = r.mode().toString();
        if (r.ok()) {
            if (r.mode().sharedBackground) setCheck("chk.okMc", modeName, r.background());
            else setCheck("chk.ok", modeName);
            setStatus(r.fixedCells().isEmpty() ? "status.bitmapOk" : "status.bitmapFixed", secs, r.fixedCells().size());
        } else {
            setCheck("chk.bad", r.clashCells().size(), modeName, r.mode().coloursPerCell);
            setStatus("status.clashes", r.clashCells().size());
        }
    }

    // ================================================================ steps & views

    private void showStep(Step s) {
        current = s;
        JToggleButton b = stepButtons.get(s);
        if (b != null) b.setSelected(true);
        boolean skipped = source != null && params().skipped(s);
        settingsCards.show(settingsPanel, (skipped ? "skipped-" : "") + s.name());
        SwingUtilities.invokeLater(this::fitSettings);
        updateViews();
        updateEnabled();
    }

    private void stepBy(int d) {
        List<Step> steps = Pipeline.steps(target());
        int i = steps.indexOf(current) + d;
        if (i >= 0 && i < steps.size()) showStep(steps.get(i));
    }

    /** Fills both previews for the current step. */
    private void updateViews() {
        if (stepButtons.isEmpty()) return;
        Pipeline.Outputs o = outputs;
        Step s = current;
        boolean crop = s == Step.FRAME && source != null && !params().skipped(Step.FRAME);
        leftCards.show(leftArea, crop ? "crop" : "image");
        BufferedImage left = null, right = null;
        String lt = "view.in", rt = "view.out";
        if (source != null) {
            switch (s) {
                case ORIGINAL -> { left = source; right = o != null ? o.colours() : null; lt = "view.original"; rt = "view.final"; }
                case FRAME -> { left = source; right = o != null ? o.framed() : null; }
                case PREPARE -> { left = o != null ? o.framed() : null; right = o != null ? o.prepared() : null; }
                case PIXELIZER -> { left = o != null ? o.prepared() : null; right = o != null ? o.pixelized() : null; }
                case COLOURS -> { left = o != null ? o.pixelized() : null; right = o != null ? o.colours() : null; }
                case RESULT -> { left = o != null ? o.pixelized() : null; right = o != null ? resultImage(o) : null; lt = "view.template"; }
            }
        }
        leftPanel.setImage(left);
        rightPanel.setImage(right);
        leftTitle.setText(tr(lt, stepLabel(s)));
        rightTitle.setText(tr(rt, stepLabel(s)) + (source != null && params().skipped(s) ? " " + tr("step.skippedShort") : ""));
        updateAspect();
        if (linkViews.isSelected() && sameSize() && !crop) rightPanel.copyViewFrom(leftPanel);
    }

    /** The result with the cell markers of the colour check (bitmaps). */
    private BufferedImage resultImage(Pipeline.Outputs o) {
        BufferedImage img = o.colours();
        BitmapConverter.Result r = o.bitmap();
        if (img == null || r == null || (!markCells.isSelected() && selectedCell < 0)) return img;
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, 0, 0, null);
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

    /** C64-sized pictures with PAL pixels (if switched on), everything else with square pixels. */
    private double aspectOf(BufferedImage img) {
        if (img == null) return 320 * PAL_PIXEL / 200;
        boolean c64 = img.getWidth() == 320 && img.getHeight() == 200;
        return palAspect.isSelected() && c64 ? 320 * PAL_PIXEL / 200 : (double) img.getWidth() / img.getHeight();
    }

    private void updateOriginalInfo() {
        if (source == null) {
            originalInfo.setText(wrapped(tr("orig.none")));
            return;
        }
        originalInfo.setText(wrapped(tr("orig.info", inputFile.getName(), source.getWidth(), source.getHeight())
                + "<br><br>" + (detection != null ? tr(detection.reasonKey()) : "")));
    }

    /** Locks what needs a picture, shows the right groups for the target, enables the save buttons. */
    private void updateEnabled() {
        boolean has = source != null;
        Target t = target();
        for (Component c : needPicture) setEnabledDeep(c, has);
        for (Step s : Step.values()) setEnabledDeep(stepPanels.get(s), has);
        for (JPanel p : skippedPanels.values()) setEnabledDeep(p, has);
        cropEditor.enabledChanged();
        boolean pet = t == Target.PETSCII;
        bitmapColours.setVisible(!pet);
        petsciiColours.setVisible(pet);
        bitmapResult.setVisible(!pet);
        petsciiResult.setVisible(pet);
        if (has && !pet) {
            boolean dithering = ditherBox.getSelectedItem() != Dither.NONE && strength.getValue() > 0;
            fixClashes.setEnabled(!dithering);
            backgroundBox.setEnabled(t == Target.MULTICOLOR);
            bleeding.setEnabled(dithering);
            serpentine.setEnabled(dithering);
            strength.setEnabled(ditherBox.getSelectedItem() != Dither.NONE);
            paintColour.setEnabled(toolPaint.isSelected());
        }
        List<Step> steps = Pipeline.steps(t);
        int i = steps.indexOf(current);
        backButton.setEnabled(has && i > 0);
        nextButton.setEnabled(has && i < steps.size() - 1);
        Pipeline.Outputs o = outputs;
        boolean image = has && o != null && previewImage() != null;
        savePngButton.setEnabled(image);
        pngScale.setEnabled(image);
        boolean result = current == Step.RESULT;
        saveDataButton.setVisible(result);
        savePrgButton.setVisible(result);
        saveDataButton.setText(tr(pet ? "save.petmate" : t == Target.HIRES ? "save.art" : "save.kla"));
        saveDataButton.setToolTipText(tr(pet ? "save.petmate.tip" : "save.data.tip"));
        boolean ok = has && o != null && o.ok() && outputParams != null && outputParams.target() == t;
        saveDataButton.setEnabled(ok);
        savePrgButton.setEnabled(ok);
        rightPanel.setPixelListener(has && current == Step.RESULT && !pet ? this::resultClicked : null);
        rightPanel.setToolTipText(tr(current == Step.RESULT && !pet ? "preview.edit.tip" : "preview.tip"));
    }

    // ================================================================ manual correction (bitmaps)

    private void resultClicked(int x, int y, boolean dragging) {
        Pipeline.Outputs o = outputs;
        if (o == null || o.bitmap() == null || current != Step.RESULT) return;
        C64Mode m = o.bitmap().mode();
        int mx = x / m.pixelWidth(), cell = m.cellOf(mx, y);
        if (toolPaint.isSelected()) {
            Integer colour = (Integer) paintColour.getSelectedItem();
            if (colour == null) return;
            Integer old = pixelEdits.put(y * m.width + mx, colour);
            if (Objects.equals(old, colour) && o.bitmap().indices()[y * m.width + mx] == colour) return;
            selectedCell = cell;
            changed(Step.COLOURS);
        } else if (!dragging) {
            selectedCell = cell;
            updateCellPanel();
            updateViews();
        }
    }

    private void updateCellPanel() {
        Pipeline.Outputs o = outputs;
        BitmapConverter.Result r = o != null ? o.bitmap() : null;
        boolean has = r != null && selectedCell >= 0;
        int bg = r != null ? r.background() : -1;
        int[] count = has ? r.cellCounts(selectedCell) : new int[16];
        int[] own = has ? cellEdits.get(selectedCell) : null;
        Palette pal = palette();
        for (int i = 0; i < 16; i++) {
            JToggleButton b = swatches[i];
            b.setIcon(new ColourIcon(pal.rgb(i), 14));
            b.setText(i == bg ? tr("chk.bgShort") : count[i] > 0 ? String.valueOf(count[i]) : " ");
            b.setToolTipText(tr("chk.swatch.tip", i, tr("color." + i), count[i]));
            boolean selected = own != null ? contains(own, i) : count[i] > 0;
            b.setSelected(has && i != bg && selected);
            b.setEnabled(has && i != bg);
        }
        for (JButton b : new JButton[]{applyCell, autoCell, resetCell}) b.setEnabled(has);
        resetAll.setEnabled(!cellEdits.isEmpty() || !pixelEdits.isEmpty());
        fixAll.setEnabled(r != null && !r.clashCells().isEmpty());
        if (has) {
            C64Mode m = r.mode();
            int used = 0;
            for (int i = 0; i < 16; i++) if (count[i] > 0 && i != bg) used++;
            cellLabel.setText(tr("chk.cell", selectedCell % C64Mode.COLS, selectedCell / C64Mode.COLS, used, m.coloursPerCell));
            cellLabel.setForeground(used > m.coloursPerCell ? new Color(200, 30, 30) : UIManager.getColor("Label.foreground"));
        } else {
            cellLabel.setText(tr("chk.noCell"));
            cellLabel.setForeground(UIManager.getColor("Label.foreground"));
        }
        updateCellHint();
    }

    private void updateCellHint() {
        Pipeline.Outputs o = outputs;
        BitmapConverter.Result r = o != null ? o.bitmap() : null;
        if (r == null || selectedCell < 0) {
            cellHint.setText(wrapped("<small>" + (r == null ? "" : tr("chk.hint")) + "</small>"));
            return;
        }
        int chosen = chosenColours().length, max = r.mode().coloursPerCell;
        cellHint.setText(wrapped("<small>" + (chosen > max ? tr("chk.tooMany", chosen, max)
                : tr(r.mode().sharedBackground ? "chk.hintCellMc" : "chk.hintCell", max)) + "</small>"));
        applyCell.setEnabled(chosen <= max && (chosen > 0 || r.mode().sharedBackground));
    }

    private int[] chosenColours() {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < 16; i++) if (swatches[i].isSelected() && swatches[i].isEnabled()) l.add(i);
        return l.stream().mapToInt(Integer::intValue).toArray();
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    private void applyCellColours() {
        if (selectedCell < 0) return;
        cellEdits.put(selectedCell, chosenColours());
        removePixelEditsInCell(selectedCell);
        changed(Step.COLOURS);
    }

    private void autoCellColours() {
        Pipeline.Outputs o = outputs;
        if (selectedCell < 0 || o == null || o.bitmap() == null) return;
        cellEdits.put(selectedCell, BitmapConverter.suggestColours(o.pixelized(), Pipeline.bitmapSettings(params()),
                selectedCell, o.bitmap().background()));
        removePixelEditsInCell(selectedCell);
        changed(Step.COLOURS);
    }

    /** Every cell with too many colours gets the colours that reproduce it best, like "Automatic" for one cell. */
    private void fixAllCells() {
        Pipeline.Outputs o = outputs;
        if (o == null || o.bitmap() == null || o.bitmap().clashCells().isEmpty()) return;
        var settings = Pipeline.bitmapSettings(params());
        for (int cell : o.bitmap().clashCells())
            cellEdits.put(cell, BitmapConverter.suggestColours(o.pixelized(), settings, cell, o.bitmap().background()));
        changed(Step.COLOURS);
    }

    private void resetSelectedCell() {
        if (selectedCell < 0) return;
        cellEdits.remove(selectedCell);
        removePixelEditsInCell(selectedCell);
        changed(Step.COLOURS);
    }

    private void removePixelEditsInCell(int cell) {
        C64Mode m = target().mode;
        if (m == null) return;
        pixelEdits.keySet().removeIf(p -> m.cellOf(p % m.width, p / m.width) == cell);
    }

    private void jumpToClash(int dirSign) {
        Pipeline.Outputs o = outputs;
        if (o == null || o.bitmap() == null || o.bitmap().clashCells().isEmpty()) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        List<Integer> cells = o.bitmap().clashCells();
        int target = dirSign > 0 ? cells.get(0) : cells.get(cells.size() - 1);
        for (int i = 0; i < cells.size(); i++) {
            int c = dirSign > 0 ? cells.get(i) : cells.get(cells.size() - 1 - i);
            if (dirSign > 0 ? c > selectedCell : c < selectedCell) { target = c; break; }
        }
        selectedCell = target;
        toolSelect.setSelected(true);
        updateCellPanel();
        updateViews();
        rightPanel.showArea(new Rectangle((target % C64Mode.COLS) * 8, (target / C64Mode.COLS) * 8, 8, 8), 4);
    }

    // ================================================================ files

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

    /** Moves the divider so the settings of the shown step fit (at most half of the height). */
    private void fitSettings() {
        if (center == null || center.getHeight() <= 0) return;
        settingsPanel.revalidate();
        int want = settingsPanel.getPreferredSize().height + 4;
        int max = center.getHeight() / 2;
        center.setDividerLocation(center.getHeight() - center.getDividerSize() - Math.min(want, max));
    }

    private void refreshLanguage() {
        SwingUtilities.updateComponentTreeUI(this);
        revalidate();
        repaint();
    }

    private void chooseInput() {
        File dir = FileDialogs.lastDir("input");
        if (dir == null && inputFile != null) dir = inputFile.getAbsoluteFile().getParentFile();
        File f = FileDialogs.openImage(this, dir);     // native dialog on macOS, image filters elsewhere
        if (f != null) loadInput(f);
    }

    /** Loads a picture, finds out where the chain starts and shows that step. */
    void loadInput(File f) {
        BufferedImage img;
        try {
            img = Images.read(f.toPath(), palette());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, tr("err.read", ex.getMessage()), App.NAME, JOptionPane.ERROR_MESSAGE);
            return;
        }
        FileDialogs.rememberDir("input", f);
        loading = true;
        Path p = f.toPath();
        if (Images.isC64File(p))
            targetButtons.get(Images.isHiresC64File(p) ? Target.HIRES : Target.MULTICOLOR).setSelected(true);
        inputFile = f;
        source = img;
        manualEntry = null;
        cellEdits.clear();
        pixelEdits.clear();
        selectedCell = -1;
        outputs = null;
        outputParams = null;
        fitSettings = ImageFitter.canTakeExactly(img) ? null : ImageFitter.Settings.suggested(img);
        cropEditor.setSource(img, fitSettings != null ? fitSettings : ImageFitter.Settings.suggested(img));
        loading = false;
        detect();
        fileLabel.setText(f.getName() + "  ·  " + img.getWidth() + "×" + img.getHeight());
        updateEntryBox();
        updateStepBar();
        updateOriginalInfo();
        setStatus("status.loaded", f.getName(), img.getWidth(), img.getHeight());
        showStep(entry());
        leftPanel.fit();
        rightPanel.fit();
        dirtyFrom = Step.FRAME;
        changed(Step.FRAME);
        history.reset();                                      // a new picture starts a new history
    }

    /** A C64 file as input is redrawn when the palette changes. */
    private void reloadC64Input() {
        try {
            source = Images.read(inputFile.toPath(), palette());
            changed(Step.FRAME);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, tr("err.read", ex.getMessage()), App.NAME, JOptionPane.ERROR_MESSAGE);
        }
    }

    private String sourceBase() {
        return inputFile != null ? inputFile.getName().replaceFirst("\\.[^.]+$", "") : "result";
    }

    /** Save dialog in the folder of the source picture; returns the path with extension, or null. */
    private Path chooseFile(String titleKey, String filterKey, String ext, String defaultName) {
        File dir = inputFile != null ? inputFile.getAbsoluteFile().getParentFile() : null;
        JFileChooser fc = FileDialogs.chooser(titleKey, dir);
        FileDialogs.addFilter(fc, filterKey, ext);
        fc.setSelectedFile(new File(dir, defaultName));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return null;
        String s = fc.getSelectedFile().getPath();
        Path p = Path.of(s.toLowerCase(Locale.ROOT).endsWith("." + ext) ? s : s + "." + ext);
        if (Files.exists(p) && JOptionPane.showConfirmDialog(this, tr("dlg.overwrite", p.getFileName()), App.NAME,
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return null;
        return p;
    }

    /** The picture of the current step without markers (what "save as PNG" writes). */
    private BufferedImage previewImage() {
        Pipeline.Outputs o = outputs;
        if (o == null) return null;
        return current == Step.ORIGINAL ? o.colours() : o.image(current);
    }

    private static final Map<Step, String> PNG_SUFFIX = Map.of(Step.ORIGINAL, "result", Step.FRAME, "320x200",
            Step.PREPARE, "prepared", Step.PIXELIZER, "pixelized", Step.COLOURS, "c64colours", Step.RESULT, "c64");

    /** Saves the output of the current step, enlarged by the chosen factor (MuleJ takes it back exactly). */
    private void savePng() {
        BufferedImage img = previewImage();
        if (img == null) return;
        int f = (Integer) pngScale.getSelectedItem();
        String suffix = current == Step.RESULT && target() == Target.PETSCII ? "petscii" : PNG_SUFFIX.get(current);
        Path p = chooseFile("dlg.savePng.title", "filter.png", "png",
                sourceBase() + "_" + suffix + (f > 1 ? "_x" + f : "") + ".png");
        if (p == null) return;
        try {
            BufferedImage out = f == 1 ? img : ImageFitter.scale(img, img.getWidth() * f, img.getHeight() * f,
                    ImageFitter.Filter.NEAREST);
            Images.writePng(out, p);
            setStatus("status.saved", p.getFileName());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, tr("err.write", ex.getMessage()), App.NAME, JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Koala / Art Studio / Petmate file of the result. */
    private void saveData() {
        Pipeline.Outputs o = outputs;
        if (o == null || !o.ok()) return;
        try {
            if (o.petscii() != null) {
                Path p = chooseFile("dlg.saveData.title", "filter.petmate", "petmate", sourceBase() + ".petmate");
                if (p == null) return;
                Files.writeString(p, o.petscii().toPetmate(sourceBase()), StandardCharsets.UTF_8);
                setStatus("status.saved", p.getFileName());
            } else {
                C64Mode m = o.bitmap().mode();
                Path p = chooseFile("dlg.saveData.title", m == C64Mode.HIRES ? "filter.art" : "filter.kla", m.extension,
                        sourceBase() + "." + m.extension);
                if (p == null) return;
                if (inputFile != null && p.toAbsolutePath().equals(inputFile.toPath().toAbsolutePath())
                        && JOptionPane.showConfirmDialog(this, tr("dlg.overwriteInput"), App.NAME,
                        JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
                Files.write(p, o.bitmap().fileBytes());
                setStatus("status.saved", p.getFileName());
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, tr("err.write", ex.getMessage()), App.NAME, JOptionPane.ERROR_MESSAGE);
        }
    }

    private void savePrg() {
        Pipeline.Outputs o = outputs;
        if (o == null || !o.ok()) return;
        Path p = chooseFile("dlg.savePrg.title", "filter.prg", "prg", sourceBase() + ".prg");
        if (p == null) return;
        try {
            Files.write(p, o.petscii() != null ? ViewerPrg.petscii(o.petscii()) : ViewerPrg.build(o.bitmap()));
            setStatus("status.savedPrg", p.getFileName());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, tr("err.write", ex.getMessage()), App.NAME, JOptionPane.ERROR_MESSAGE);
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
}
