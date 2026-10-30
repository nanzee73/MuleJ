package de.mulej.gui;

import de.c64gfx.swing.I18n;
import de.c64gfx.swing.ImageFitter;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

import static de.c64gfx.swing.I18n.tr;

/**
 * Frame & scaling to 320×200 (step "frame"): an interactive view of the source picture with the frame
 * ({@link #view()}) and the settings ({@link #controls()}), placed separately in the main window.
 * <p>
 * View: mouse wheel zooms, right/middle drag pans, double click shows the whole picture; drag inside the frame
 * to move it, the corner resizes it, dragging outside draws a new frame (1:1 frame: a click moves it there).
 */
public class CropEditor {
    private BufferedImage source;
    private Rectangle crop = new Rectangle(0, 0, 1, 1);
    private Runnable onChange = () -> {};

    private final JComboBox<ImageFitter.Aspect> aspectBox = new JComboBox<>(ImageFitter.Aspect.values());
    private final JComboBox<ImageFitter.ScaleMode> modeBox = new JComboBox<>(ImageFitter.ScaleMode.values());
    private final JComboBox<ImageFitter.Filter> filterBox = new JComboBox<>(ImageFitter.Filter.values());
    private final JCheckBox lockAspect = I18n.text(new JCheckBox("", true), "crop.lock");
    private final JSpinner sx = new JSpinner(), sy = new JSpinner(), sw = new JSpinner(), sh = new JSpinner();
    private final JButton fillButton = I18n.text(new JButton(), "crop.border");
    private Color fill = Color.BLACK;
    private final CropView view = new CropView();
    private final JPanel controls = new JPanel(new GridBagLayout());
    private boolean updating;
    private ImageFitter.ScaleMode lastMode;

    public CropEditor() {
        I18n.tip(view, "crop.help");
        view.setBackground(new Color(0x30, 0x30, 0x30));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0; c.anchor = GridBagConstraints.WEST; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 3, 2, 3); c.gridwidth = 2; c.weightx = 1;
        controls.add(I18n.label("crop.aspect"), c); c.gridy++;
        controls.add(aspectBox, c); c.gridy++;
        controls.add(I18n.label("crop.mode"), c); c.gridy++;
        controls.add(modeBox, c); c.gridy++;
        controls.add(I18n.label("crop.filter"), c); c.gridy++;
        controls.add(filterBox, c); c.gridy++;
        controls.add(fillButton, c); c.gridy++;
        controls.add(lockAspect, c); c.gridy++;
        c.gridwidth = 1;
        addSpinner(c, "crop.x", sx); addSpinner(c, "crop.y", sy);
        addSpinner(c, "crop.width", sw); addSpinner(c, "crop.height", sh);
        c.gridx = 0; c.gridwidth = 2;
        JButton whole = I18n.text(new JButton(), "crop.whole");
        JButton center = I18n.text(new JButton(), "crop.center");
        JButton oneToOne = I18n.tip(I18n.text(new JButton(), "crop.oneToOne"), "crop.oneToOne.tip");
        controls.add(whole, c); c.gridy++;
        controls.add(center, c); c.gridy++;
        controls.add(oneToOne, c); c.gridy++;
        JLabel help = new JLabel();
        I18n.bind(() -> help.setText(MainWindow.wrapped("<small>" + tr("crop.help") + "</small>")));
        controls.add(help, c); c.gridy++;

        whole.addActionListener(e -> {
            if (source == null) return;
            if (fixed()) modeBox.setSelectedItem(ImageFitter.ScaleMode.FIT);
            crop = new Rectangle(0, 0, source.getWidth(), source.getHeight());
            refresh();
        });
        center.addActionListener(e -> {
            if (source == null) return;
            if (fixed()) modeBox.setSelectedItem(ImageFitter.ScaleMode.FILL);
            crop = maxCentered();
            refresh();
        });
        oneToOne.addActionListener(e -> modeBox.setSelectedItem(ImageFitter.ScaleMode.NONE));
        fillButton.addActionListener(e -> {
            Color chosen = JColorChooser.showDialog(view, tr("crop.border").replace("…", ""), fill);
            if (chosen != null) { fill = chosen; refresh(); }
        });
        aspectBox.addActionListener(e -> { if (!updating) refresh(); });
        modeBox.addActionListener(e -> { if (!updating) modeChanged(); });
        filterBox.addActionListener(e -> { if (!updating) refresh(); });
        for (JSpinner s : new JSpinner[]{sx, sy, sw, sh}) s.addChangeListener(e -> fromSpinners());
    }

    public JComponent view() { return view; }

    public JPanel controls() { return controls; }

    /** Called whenever the frame or a setting changes. */
    public void setOnChange(Runnable r) { onChange = r; }

    /** Shows a new picture with the given settings (null crop = whole picture). */
    public void setSource(BufferedImage img, ImageFitter.Settings s) {
        source = img;
        updating = true;
        crop = s.crop() != null ? new Rectangle(s.crop()) : new Rectangle(0, 0, img.getWidth(), img.getHeight());
        fill = s.fill();
        lastMode = s.mode();
        aspectBox.setSelectedItem(s.aspect());
        modeBox.setSelectedItem(s.mode());
        filterBox.setSelectedItem(s.filter());
        updating = false;
        view.zoom = 1;
        refreshControls();
        view.repaint();
    }

    private void addSpinner(GridBagConstraints c, String key, JSpinner s) {
        c.gridx = 0; c.weightx = 0; controls.add(I18n.label(key), c);
        c.gridx = 1; c.weightx = 1; controls.add(s, c);
        c.gridy++;
    }

    /** 1:1 mode: the frame is exactly 320×200 source pixels and can only be moved. */
    private boolean fixed() { return modeBox.getSelectedItem() == ImageFitter.ScaleMode.NONE; }

    private void modeChanged() {
        if (source == null) return;
        boolean nowFixed = fixed(), wasFixed = lastMode == ImageFitter.ScaleMode.NONE;
        if (nowFixed && !wasFixed) {
            crop = clampRect(new Rectangle((int) Math.round(crop.getCenterX()) - ImageFitter.W / 2,
                    (int) Math.round(crop.getCenterY()) - ImageFitter.H / 2, ImageFitter.W, ImageFitter.H));
        } else if (!nowFixed && wasFixed) {
            crop = maxCentered();
        }
        lastMode = (ImageFitter.ScaleMode) modeBox.getSelectedItem();
        refresh();
    }

    public ImageFitter.Settings getSettings() {
        Rectangle r = source == null || crop.equals(new Rectangle(0, 0, source.getWidth(), source.getHeight()))
                ? null : new Rectangle(crop);
        return new ImageFitter.Settings(r, (ImageFitter.ScaleMode) modeBox.getSelectedItem(),
                (ImageFitter.Filter) filterBox.getSelectedItem(), (ImageFitter.Aspect) aspectBox.getSelectedItem(), fill);
    }

    private double targetAspect() {
        ImageFitter.Aspect a = (ImageFitter.Aspect) aspectBox.getSelectedItem();
        return a == null || a.ratio <= 0 ? 0 : a.ratio;
    }

    private Rectangle maxCentered() {
        double a = targetAspect();
        int w = source.getWidth(), h = source.getHeight();
        if (a > 0) {
            if ((double) w / h > a) w = (int) Math.round(h * a);
            else h = (int) Math.round(w / a);
        }
        return new Rectangle((source.getWidth() - w) / 2, (source.getHeight() - h) / 2, w, h);
    }

    private void fromSpinners() {
        if (updating || source == null) return;
        crop = clampRect(new Rectangle((Integer) sx.getValue(), (Integer) sy.getValue(),
                (Integer) sw.getValue(), (Integer) sh.getValue()));
        refresh();
    }

    private Rectangle clampRect(Rectangle r) {
        if (fixed()) {
            // the frame may be larger than the picture (then the rest gets the border colour)
            int w = ImageFitter.W, h = ImageFitter.H, dw = source.getWidth() - w, dh = source.getHeight() - h;
            int x = Math.max(Math.min(0, dw), Math.min(Math.max(0, dw), r.x));
            int y = Math.max(Math.min(0, dh), Math.min(Math.max(0, dh), r.y));
            return new Rectangle(x, y, w, h);
        }
        int w = Math.max(1, Math.min(r.width, source.getWidth()));
        int h = Math.max(1, Math.min(r.height, source.getHeight()));
        int x = Math.max(0, Math.min(r.x, source.getWidth() - w));
        int y = Math.max(0, Math.min(r.y, source.getHeight() - h));
        return new Rectangle(x, y, w, h);
    }

    private void refresh() {
        refreshControls();
        view.repaint();
        onChange.run();
    }

    private void refreshControls() {
        if (source == null) return;
        updating = true;
        boolean f = fixed();
        int minX = f ? Math.min(0, source.getWidth() - ImageFitter.W) : 0;
        int minY = f ? Math.min(0, source.getHeight() - ImageFitter.H) : 0;
        int maxX = f ? Math.max(0, source.getWidth() - ImageFitter.W) : source.getWidth() - 1;
        int maxY = f ? Math.max(0, source.getHeight() - ImageFitter.H) : source.getHeight() - 1;
        sx.setModel(new SpinnerNumberModel(Math.max(minX, Math.min(maxX, crop.x)), minX, maxX, 1));
        sy.setModel(new SpinnerNumberModel(Math.max(minY, Math.min(maxY, crop.y)), minY, maxY, 1));
        sw.setModel(new SpinnerNumberModel(crop.width, 1, Math.max(crop.width, source.getWidth()), 1));
        sh.setModel(new SpinnerNumberModel(crop.height, 1, Math.max(crop.height, source.getHeight()), 1));
        sw.setEnabled(controls.isEnabled() && !f);
        sh.setEnabled(controls.isEnabled() && !f);
        aspectBox.setEnabled(controls.isEnabled() && !f);
        filterBox.setEnabled(controls.isEnabled() && !f);
        lockAspect.setEnabled(controls.isEnabled() && !f);
        updating = false;
    }

    /** After the main window enabled/disabled the controls: keep the 1:1 restrictions. */
    public void enabledChanged() { refreshControls(); }

    /** Source image with an editable crop frame. */
    private class CropView extends JComponent {
        private Point dragStart;
        private Rectangle dragOrig;
        private int dragMode; // 0 none, 1 move, 2 resize, 3 new
        /** Zoom relative to "whole picture visible" (1 .. 32). */
        private double zoom = 1;
        /** Picture point (pixels) in the middle of the view while zoomed in. */
        private double centerX, centerY;
        private Point panLast;

        CropView() {
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                    if (source == null) return;
                    zoomAt(zoom * Math.pow(1.25, -e.getPreciseWheelRotation()), e.getPoint());
                }

                @Override public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                        zoom = 1;
                        repaint();
                    }
                }

                @Override public void mousePressed(MouseEvent e) {
                    if (source == null) return;
                    if (!SwingUtilities.isLeftMouseButton(e)) {          // right / middle button: pan the view
                        panLast = e.getPoint();
                        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                        return;
                    }
                    Point p = toImage(e.getPoint());
                    dragStart = p;
                    dragOrig = new Rectangle(crop);
                    Point corner = new Point(crop.x + crop.width, crop.y + crop.height);
                    double handle = 8 / scale();
                    if (fixed()) {
                        if (!crop.contains(p)) {
                            crop = clampRect(new Rectangle(p.x - crop.width / 2, p.y - crop.height / 2, crop.width, crop.height));
                            dragOrig = new Rectangle(crop);
                            refresh();
                        }
                        dragMode = 1;
                    } else if (p.distance(corner) < handle) dragMode = 2;
                    else if (crop.contains(p)) dragMode = 1;
                    else dragMode = 3;
                }

                @Override public void mouseDragged(MouseEvent e) {
                    if (panLast != null) {
                        double sc = scale();
                        centerX -= (e.getX() - panLast.x) / sc;
                        centerY -= (e.getY() - panLast.y) / sc;
                        panLast = e.getPoint();
                        clampCenter();
                        repaint();
                        return;
                    }
                    if (dragStart == null) return;
                    Point p = toImage(e.getPoint());
                    int dx = p.x - dragStart.x, dy = p.y - dragStart.y;
                    Rectangle r;
                    switch (dragMode) {
                        case 1 -> r = new Rectangle(dragOrig.x + dx, dragOrig.y + dy, dragOrig.width, dragOrig.height);
                        case 2 -> r = sized(dragOrig.x, dragOrig.y, dragOrig.width + dx, dragOrig.height + dy);
                        default -> r = sized(Math.min(dragStart.x, p.x), Math.min(dragStart.y, p.y),
                                Math.abs(dx), Math.abs(dy));
                    }
                    crop = clampRect(r);
                    refresh();
                }

                @Override public void mouseReleased(MouseEvent e) {
                    dragStart = null;
                    dragMode = 0;
                    panLast = null;
                    setCursor(Cursor.getDefaultCursor());
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            addMouseWheelListener(ma);
        }

        /** Zooms so the picture point under the mouse stays where it is. */
        private void zoomAt(double newZoom, Point mouse) {
            newZoom = Math.max(1, Math.min(32, newZoom));
            if (newZoom == zoom) return;
            double px = toImageX(mouse.x), py = toImageY(mouse.y);
            if (zoom == 1) {
                Rectangle b = shownArea();
                centerX = b.getCenterX();
                centerY = b.getCenterY();
            }
            zoom = newZoom;
            double sc = scale();
            centerX = px - (mouse.x - getWidth() / 2.0) / sc;
            centerY = py - (mouse.y - getHeight() / 2.0) / sc;
            clampCenter();
            repaint();
        }

        private void clampCenter() {
            Rectangle b = shownArea();
            double sc = scale(), halfW = getWidth() / 2.0 / sc, halfH = getHeight() / 2.0 / sc;
            centerX = b.width <= 2 * halfW ? b.getCenterX() : Math.max(b.x + halfW, Math.min(b.x + b.width - halfW, centerX));
            centerY = b.height <= 2 * halfH ? b.getCenterY() : Math.max(b.y + halfH, Math.min(b.y + b.height - halfH, centerY));
        }

        private Rectangle sized(int x, int y, int w, int h) {
            w = Math.max(4, w);
            h = Math.max(4, h);
            double a = targetAspect();
            if (lockAspect.isSelected() && a > 0) h = (int) Math.round(w / a);
            return new Rectangle(x, y, w, h);
        }

        /** Area shown: the picture, plus a 1:1 frame that sticks out of a small picture. */
        private Rectangle shownArea() {
            Rectangle b = new Rectangle(0, 0, source.getWidth(), source.getHeight());
            return fixed() ? b.union(crop) : b;
        }

        private double scale() {
            Rectangle b = shownArea();
            return zoom * Math.min((double) getWidth() / b.width, (double) getHeight() / b.height);
        }

        /** Screen position of picture pixel (0,0). */
        private Point offset() {
            double s = scale();
            Rectangle b = shownArea();
            double cx = zoom == 1 ? b.getCenterX() : centerX, cy = zoom == 1 ? b.getCenterY() : centerY;
            return new Point((int) Math.round(getWidth() / 2.0 - cx * s), (int) Math.round(getHeight() / 2.0 - cy * s));
        }

        private double toImageX(int x) { return (x - offset().x) / scale(); }

        private double toImageY(int y) { return (y - offset().y) / scale(); }

        private Point toImage(Point p) {
            double s = scale();
            Point o = offset();
            return new Point((int) Math.floor((p.x - o.x) / s), (int) Math.floor((p.y - o.y) / s));
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            if (source == null) {
                g2.setColor(new Color(0x30, 0x30, 0x30));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
                return;
            }
            g2.setColor(Color.DARK_GRAY);
            g2.fillRect(0, 0, getWidth(), getHeight());
            double s = scale();
            Point o = offset();
            // zoomed in far: show crisp pixels
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, s >= 2 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                    : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            if (fixed()) {
                // border colour where the 1:1 frame is larger than the picture
                g2.setColor(fill);
                g2.fillRect(o.x + (int) (crop.x * s), o.y + (int) (crop.y * s), (int) (crop.width * s), (int) (crop.height * s));
            }
            g2.drawImage(source, o.x, o.y, (int) (source.getWidth() * s), (int) (source.getHeight() * s), null);
            int rx = o.x + (int) (crop.x * s), ry = o.y + (int) (crop.y * s);
            int rw = (int) (crop.width * s), rh = (int) (crop.height * s);
            // darken outside the crop frame
            Area outside = new Area(o.x, o.y, (int) (source.getWidth() * s), (int) (source.getHeight() * s), rx, ry, rw, rh);
            g2.setColor(new Color(0, 0, 0, 140));
            outside.fill(g2);
            g2.setColor(Color.YELLOW);
            g2.drawRect(rx, ry, rw, rh);
            if (!fixed()) g2.fillRect(rx + rw - 4, ry + rh - 4, 8, 8);
            g2.dispose();
        }
    }

    /** Fills the area of an outer rectangle that lies outside an inner one. */
    private record Area(int ox, int oy, int ow, int oh, int ix, int iy, int iw, int ih) {
        void fill(Graphics2D g) {
            g.fillRect(ox, oy, ow, Math.max(0, iy - oy));
            g.fillRect(ox, iy + ih, ow, Math.max(0, oy + oh - iy - ih));
            g.fillRect(ox, iy, Math.max(0, ix - ox), ih);
            g.fillRect(ix + iw, iy, Math.max(0, ox + ow - ix - iw), ih);
        }
    }
}
