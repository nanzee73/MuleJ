package de.mulej.gui;

import de.mulej.i18n.I18n;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Hashtable;

/**
 * Slider extras:
 * <ul>
 *   <li>step buttons (◀ / ▶) left and right that move the thumb by one; holding a button repeats</li>
 *   <li>a tick mark at the default value</li>
 *   <li>snapping ("detent"): while dragging, the thumb locks onto the default value when it gets close</li>
 *   <li>double click on the slider resets it to the default value</li>
 * </ul>
 * The default value is the slider's value at the time {@link #wrap(JSlider)} is called.
 */
public final class SliderControls {
    /** Snap zone as fraction of the slider range. */
    private static final double SNAP_FRACTION = 0.04;
    private static final String DEFAULT_KEY = "mulej.default";

    private SliderControls() {}

    public static int defaultValue(JSlider s) {
        Object v = s.getClientProperty(DEFAULT_KEY);
        return v instanceof Integer i ? i : s.getValue();
    }

    /** Returns a panel [◀][slider][▶]; the slider itself stays the component that holds the value. */
    public static JPanel wrap(JSlider s) {
        int def = s.getValue();
        s.putClientProperty(DEFAULT_KEY, def);
        installDefaultMark(s, def);
        installSnapping(s, def);
        s.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) s.setValue(def);
            }
        });
        I18n.tip(s, "slider.default.tip", def);
        // let the slider track shrink before any text gets cut off
        s.setMinimumSize(new Dimension(50, s.getPreferredSize().height));

        JPanel p = new JPanel(new BorderLayout(1, 0));
        p.setOpaque(false);
        p.add(stepButton("◀", "slider.dec.tip", s, -1), BorderLayout.WEST);
        p.add(s, BorderLayout.CENTER);
        p.add(stepButton("▶", "slider.inc.tip", s, +1), BorderLayout.EAST);
        return p;
    }

    /** A single small label at the default value, positioned by the look and feel itself. */
    private static void installDefaultMark(JSlider s, int def) {
        JLabel mark = new JLabel("▲");
        mark.setFont(mark.getFont().deriveFont(8f));
        mark.setForeground(new Color(0x6C5EB5));
        Hashtable<Integer, JComponent> labels = new Hashtable<>();
        labels.put(def, mark);
        s.setLabelTable(labels);
        s.setPaintLabels(true);
    }

    private static void installSnapping(JSlider s, int def) {
        int zone = Math.max(1, (int) Math.round((s.getMaximum() - s.getMinimum()) * SNAP_FRACTION));
        boolean[] busy = {false};
        s.addChangeListener(e -> {
            if (busy[0] || !s.getValueIsAdjusting()) return;   // only while dragging with the mouse
            int v = s.getValue();
            if (v != def && Math.abs(v - def) <= zone) {
                busy[0] = true;
                s.setValue(def);
                busy[0] = false;
            }
        });
    }

    private static JButton stepButton(String text, String tipKey, JSlider s, int delta) {
        JButton b = new JButton(text);
        I18n.tip(b, tipKey);
        b.setFocusable(false);
        b.setMargin(new Insets(0, 2, 0, 2));
        b.setFont(b.getFont().deriveFont(9f));
        // step once on press, then repeat while held
        Timer repeat = new Timer(60, e -> s.setValue(s.getValue() + delta));
        repeat.setInitialDelay(400);
        b.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || !b.isEnabled()) return;
                s.setValue(s.getValue() + delta);
                repeat.restart();
            }

            @Override public void mouseReleased(MouseEvent e) { repeat.stop(); }

            @Override public void mouseExited(MouseEvent e) { repeat.stop(); }
        });
        return b;
    }
}
