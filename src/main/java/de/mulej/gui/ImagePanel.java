package de.mulej.gui;

import de.mulej.i18n.I18n;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows a picture with nearest-neighbour filtering, zoom and pan.
 * <ul>
 *   <li>Mouse wheel while hovering: zoom around the mouse position</li>
 *   <li>Right mouse button drag (also middle button): pan</li>
 *   <li>Right click without dragging: notifies {@link #setRightClickAction}</li>
 *   <li>Double click: fit to panel</li>
 * </ul>
 * The view state is zoom (relative to "fit") plus the picture point shown at the panel centre,
 * so two panels can be linked and show exactly the same detail.
 * <p>
 * <b>Crop mode</b> ({@link #startCrop}): the panel shows a full source picture with a crop frame on top.
 * Mouse wheel resizes the frame, left drag moves it, right click or Enter applies, Esc cancels;
 * Ctrl + wheel still zooms the view.
 */
public class ImagePanel extends JPanel {
    public static final double MIN_ZOOM = 1.0, MAX_ZOOM = 32.0;
    private static final int CLICK_SLOP = 4;

    /** Notified whenever zoom or pan changes. */
    public interface ViewListener { void viewChanged(ImagePanel source); }

    /** Left click / left drag on a picture pixel (outside the crop mode). */
    public interface PixelListener { void pixel(int x, int y, boolean dragging); }

    /** Callbacks of the crop mode. */
    public interface CropListener {
        void cropChanged(Rectangle crop);
        void cropFinished(boolean accepted);
    }

    private BufferedImage image;
    private String placeholder = "";
    private double aspect = 1.6;
    /** Zoom relative to "fit" (1 = whole picture visible). */
    private double zoom = 1.0;
    /** Picture coordinate (0..1, 0..1) shown at the panel centre. */
    private double centerX = 0.5, centerY = 0.5;
    private final List<ViewListener> listeners = new ArrayList<>();
    private boolean showGrid = true;
    private Runnable rightClickAction;
    private PixelListener pixelListener;

    // crop mode
    private BufferedImage cropSource;
    private Rectangle2D.Double crop;
    private double cropAspect;
    /** 1:1 frame: fixed size, the mouse wheel zooms instead of resizing. */
    private boolean cropFixed;
    private CropListener cropListener;
    private BufferedImage savedImage;
    private double savedAspect;

    public ImagePanel() {
        setBackground(new Color(0x30, 0x30, 0x30));
        setPreferredSize(new Dimension(400, 250));
        MouseAdapter ma = new MouseAdapter() {
            private Point last, pressedAt;
            private boolean panning, movingCrop;

            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                if (image == null) return;
                if (isCropMode() && !e.isControlDown() && !cropFixed) {
                    resizeCrop(Math.pow(1.08, e.getPreciseWheelRotation()), e.getPoint());
                    return;
                }
                double factor = Math.pow(1.25, -e.getPreciseWheelRotation());
                zoomAt(zoom * factor, e.getPoint());
            }

            @Override public void mousePressed(MouseEvent e) {
                last = e.getPoint();
                pressedAt = e.getPoint();
                if (SwingUtilities.isRightMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) {
                    panning = true;
                } else if (SwingUtilities.isLeftMouseButton(e) && isCropMode()) {
                    movingCrop = true;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    firePixel(e.getPoint(), false);
                }
            }

            @Override public void mouseDragged(MouseEvent e) {
                if (last == null) return;
                Rectangle r = imageRect();
                if (r.width > 0) {
                    double dx = (e.getX() - last.x) / (double) r.width, dy = (e.getY() - last.y) / (double) r.height;
                    if (panning) {
                        if (pressedAt.distance(e.getPoint()) > CLICK_SLOP) setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                        centerX -= dx;
                        centerY -= dy;
                        clampCenter();
                        fire();
                    } else if (movingCrop) {
                        moveCrop(dx * cropSource.getWidth(), dy * cropSource.getHeight());
                    } else if (SwingUtilities.isLeftMouseButton(e) && !isCropMode()) {
                        firePixel(e.getPoint(), true);
                    }
                }
                last = e.getPoint();
            }

            @Override public void mouseReleased(MouseEvent e) {
                boolean click = pressedAt != null && pressedAt.distance(e.getPoint()) <= CLICK_SLOP;
                if (SwingUtilities.isRightMouseButton(e) && click) {
                    if (isCropMode()) endCrop(true);
                    else if (rightClickAction != null) rightClickAction.run();
                }
                last = null;
                pressedAt = null;
                panning = false;
                movingCrop = false;
                setCursor(pixelListener != null && !isCropMode() ? Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
                        : Cursor.getDefaultCursor());
            }

            @Override public void mouseClicked(MouseEvent e) {
                // while editing, left clicks belong to the editor
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2 && pixelListener == null) fit();
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);

        InputMap im = getInputMap(WHEN_IN_FOCUSED_WINDOW);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "cropApply");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cropCancel");
        getActionMap().put("cropApply", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { if (isCropMode()) endCrop(true); }
            @Override public boolean isEnabled() { return isCropMode(); }
        });
        getActionMap().put("cropCancel", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { if (isCropMode()) endCrop(false); }
            @Override public boolean isEnabled() { return isCropMode(); }
        });
    }

    public void addViewListener(ViewListener l) { listeners.add(l); }

    /** Left clicks on the picture report the pixel (crosshair cursor); null switches it off. */
    public void setPixelListener(PixelListener l) {
        pixelListener = l;
        setCursor(l != null ? Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR) : Cursor.getDefaultCursor());
    }

    private void firePixel(Point p, boolean dragging) {
        if (pixelListener == null || image == null) return;
        Point2D f = toPicture(p);
        int x = (int) Math.floor(f.getX() * image.getWidth()), y = (int) Math.floor(f.getY() * image.getHeight());
        if (x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight()) pixelListener.pixel(x, y, dragging);
    }

    /** Shows the given picture area (in pixels) centered, zoomed in at least to {@code minZoom}. */
    public void showArea(Rectangle area, double minZoom) {
        if (image == null || isCropMode()) return;
        zoom = Math.max(zoom, Math.min(MAX_ZOOM, minZoom));
        centerX = area.getCenterX() / image.getWidth();
        centerY = area.getCenterY() / image.getHeight();
        clampCenter();
        fire();
    }

    /** Action for a right click without dragging (outside of crop mode). */
    public void setRightClickAction(Runnable r) { this.rightClickAction = r; }

    private void fire() {
        repaint();
        if (isCropMode()) return; // the crop view is not shared with linked panels
        for (ViewListener l : listeners) l.viewChanged(this);
    }

    public void setImage(BufferedImage img) {
        if (isCropMode()) {
            savedImage = img;   // shown again when the crop mode ends
            return;
        }
        this.image = img;
        repaint();
    }

    public BufferedImage getImage() { return isCropMode() ? savedImage : image; }

    public void setPlaceholder(String text) {
        this.placeholder = text;
        repaint();
    }

    /** Display aspect ratio of the picture (1.6 = square pixels, ≈1.5 = PAL). */
    public void setDisplayAspect(double aspect) {
        if (isCropMode()) {
            savedAspect = aspect;
            return;
        }
        this.aspect = aspect;
        repaint();
    }

    /** Draws a pixel grid when zoomed in far enough. */
    public void setShowGrid(boolean showGrid) {
        this.showGrid = showGrid;
        repaint();
    }

    public double getZoom() { return zoom; }

    /** Copies zoom and pan from another panel without notifying listeners (used for linking). */
    public void copyViewFrom(ImagePanel other) {
        if (isCropMode() || other.isCropMode()) return;
        zoom = other.zoom;
        centerX = other.centerX;
        centerY = other.centerY;
        repaint();
    }

    public void zoomIn() { zoomAt(zoom * 1.5, null); }
    public void zoomOut() { zoomAt(zoom / 1.5, null); }

    public void fit() {
        zoom = 1.0;
        centerX = 0.5;
        centerY = 0.5;
        fire();
    }

    /** Zoom so one picture pixel is n screen pixels wide. */
    public void setPixelScale(double n) {
        double fitW = fitSize().getWidth();
        if (fitW > 0 && image != null) zoomAt(n * image.getWidth() / fitW, null);
    }

    /** Pans by a fraction of the visible area (dx, dy in -1..1). */
    public void panBy(double dx, double dy) {
        centerX += dx / zoom;
        centerY += dy / zoom;
        clampCenter();
        fire();
    }

    // ------------------------------------------------------------------ crop mode

    public boolean isCropMode() { return cropSource != null; }

    /**
     * Shows the full source picture with a crop frame.
     * @param initial    initial frame (source pixels), null = largest centered frame
     * @param frameAspect width/height of the frame in source pixels
     */
    public void startCrop(BufferedImage full, Rectangle initial, double frameAspect, CropListener l) {
        startCrop(full, initial, frameAspect, false, l);
    }

    /**
     * @param fixedSize keep the size of {@code initial} (1:1 frame); it must fit into the picture
     */
    public void startCrop(BufferedImage full, Rectangle initial, double frameAspect, boolean fixedSize, CropListener l) {
        if (isCropMode()) return;
        cropFixed = fixedSize && initial != null;
        savedImage = image;
        savedAspect = aspect;
        cropSource = full;
        cropAspect = frameAspect;
        cropListener = l;
        image = full;
        aspect = (double) full.getWidth() / full.getHeight();
        zoom = 1.0;
        centerX = centerY = 0.5;
        double w, h;
        if (initial != null) {
            // keep the centre and the width of the existing frame, enforce the aspect
            w = initial.width;
            h = w / frameAspect;
            crop = new Rectangle2D.Double(initial.getCenterX() - w / 2, initial.getCenterY() - h / 2, w, h);
        } else {
            w = full.getWidth();
            h = w / frameAspect;
            if (h > full.getHeight()) { h = full.getHeight(); w = h * frameAspect; }
            crop = new Rectangle2D.Double((full.getWidth() - w) / 2, (full.getHeight() - h) / 2, w, h);
        }
        clampCrop();
        requestFocusInWindow();
        repaint();
    }

    /** Leaves the crop mode without applying the frame. */
    public void cancelCrop() {
        if (isCropMode()) endCrop(false);
    }

    private void endCrop(boolean accepted) {
        CropListener l = cropListener;
        cropSource = null;
        cropListener = null;
        image = savedImage;
        aspect = savedAspect;
        savedImage = null;
        zoom = 1.0;
        centerX = centerY = 0.5;
        fire();
        if (l != null) l.cropFinished(accepted);
    }

    private void moveCrop(double dx, double dy) {
        crop.x += dx;
        crop.y += dy;
        clampCrop();
        cropUpdated();
    }

    /** Scales the frame around the picture point under the mouse. */
    private void resizeCrop(double factor, Point mouse) {
        Point2D p = toPicture(mouse);
        double px = p.getX() * cropSource.getWidth(), py = p.getY() * cropSource.getHeight();
        double maxW = Math.min(cropSource.getWidth(), cropSource.getHeight() * cropAspect);
        double w = Math.max(16, Math.min(maxW, crop.width * factor));
        double h = w / cropAspect;
        // keep the anchor at the same relative position inside the frame
        double rx = crop.width > 0 ? (px - crop.x) / crop.width : 0.5;
        double ry = crop.height > 0 ? (py - crop.y) / crop.height : 0.5;
        rx = Math.max(0, Math.min(1, rx));
        ry = Math.max(0, Math.min(1, ry));
        crop = new Rectangle2D.Double(px - rx * w, py - ry * h, w, h);
        clampCrop();
        cropUpdated();
    }

    private void clampCrop() {
        double maxW = Math.min(cropSource.getWidth(), cropSource.getHeight() * cropAspect);
        if (crop.width > maxW) {
            double cx = crop.getCenterX(), cy = crop.getCenterY();
            crop.width = maxW;
            crop.height = maxW / cropAspect;
            crop.x = cx - crop.width / 2;
            crop.y = cy - crop.height / 2;
        }
        crop.x = Math.max(0, Math.min(cropSource.getWidth() - crop.width, crop.x));
        crop.y = Math.max(0, Math.min(cropSource.getHeight() - crop.height, crop.y));
    }

    private void cropUpdated() {
        repaint();
        if (cropListener != null) cropListener.cropChanged(currentCrop());
    }

    /** Current frame in whole source pixels. */
    public Rectangle currentCrop() {
        int x = (int) Math.round(crop.x), y = (int) Math.round(crop.y);
        int w = (int) Math.round(crop.width), h = (int) Math.round(crop.height);
        w = Math.min(w, cropSource.getWidth() - x);
        h = Math.min(h, cropSource.getHeight() - y);
        return new Rectangle(x, y, Math.max(1, w), Math.max(1, h));
    }

    // ------------------------------------------------------------------ geometry

    private void zoomAt(double newZoom, Point anchor) {
        newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, newZoom));
        if (anchor != null) {
            // keep the picture point under the mouse in place
            Point2D before = toPicture(anchor);
            zoom = newZoom;
            Point2D after = toPicture(anchor);
            centerX += before.getX() - after.getX();
            centerY += before.getY() - after.getY();
        } else {
            zoom = newZoom;
        }
        clampCenter();
        fire();
    }

    private void clampCenter() {
        double half = 0.5 / zoom;
        centerX = Math.max(half, Math.min(1 - half, centerX));
        centerY = Math.max(half, Math.min(1 - half, centerY));
    }

    /** Size of the picture at zoom 1 (fitted into the panel). */
    private Dimension fitSize() {
        double w = getWidth(), h = getHeight();
        double dw = w, dh = w / aspect;
        if (dh > h) { dh = h; dw = h * aspect; }
        return new Dimension((int) dw, (int) dh);
    }

    /** Screen rectangle of the whole picture at the current zoom and pan. */
    private Rectangle imageRect() {
        Dimension f = fitSize();
        double w = f.width * zoom, h = f.height * zoom;
        double x = getWidth() / 2.0 - centerX * w, y = getHeight() / 2.0 - centerY * h;
        return new Rectangle((int) Math.round(x), (int) Math.round(y), (int) Math.round(w), (int) Math.round(h));
    }

    private Point2D toPicture(Point p) {
        Rectangle r = imageRect();
        return new Point2D.Double((p.x - r.x) / (double) r.width, (p.y - r.y) / (double) r.height);
    }

    // ------------------------------------------------------------------ painting

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        if (image == null) {
            g2.setColor(Color.LIGHT_GRAY);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(placeholder, (getWidth() - fm.stringWidth(placeholder)) / 2, getHeight() / 2);
            g2.dispose();
            return;
        }
        Rectangle r = imageRect();
        boolean cropMode = isCropMode();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, cropMode
                ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.drawImage(image, r.x, r.y, r.width, r.height, null);

        if (cropMode) {
            paintCropOverlay(g2, r);
        } else {
            double px = r.width / (double) image.getWidth(), py = r.height / (double) image.getHeight();
            if (showGrid && px >= 8) paintGrid(g2, r, px, py);
        }
        if (zoom > 1.001) {
            String label = String.format("%.0f%%", zoom * 100);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 11f));
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(new Color(0, 0, 0, 150));
            g2.fillRoundRect(4, 4, fm.stringWidth(label) + 10, fm.getHeight() + 4, 8, 8);
            g2.setColor(Color.WHITE);
            g2.drawString(label, 9, 6 + fm.getAscent());
        }
        g2.dispose();
    }

    private void paintCropOverlay(Graphics2D g2, Rectangle r) {
        double sx = r.width / (double) cropSource.getWidth(), sy = r.height / (double) cropSource.getHeight();
        int fx = r.x + (int) Math.round(crop.x * sx), fy = r.y + (int) Math.round(crop.y * sy);
        int fw = (int) Math.round(crop.width * sx), fh = (int) Math.round(crop.height * sy);
        g2.setColor(new Color(0, 0, 0, 150));
        g2.fillRect(r.x, r.y, r.width, Math.max(0, fy - r.y));
        g2.fillRect(r.x, fy + fh, r.width, Math.max(0, r.y + r.height - fy - fh));
        g2.fillRect(r.x, fy, Math.max(0, fx - r.x), fh);
        g2.fillRect(fx + fw, fy, Math.max(0, r.x + r.width - fx - fw), fh);
        g2.setStroke(new BasicStroke(2f));
        g2.setColor(Color.YELLOW);
        g2.drawRect(fx, fy, fw, fh);
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(new Color(255, 255, 0, 110));
        g2.drawLine(fx + fw / 2, fy + fh / 2 - 6, fx + fw / 2, fy + fh / 2 + 6);
        g2.drawLine(fx + fw / 2 - 6, fy + fh / 2, fx + fw / 2 + 6, fy + fh / 2);

        // hint box at the bottom; split into two lines when the panel is too narrow
        String hint = I18n.tr(cropFixed ? "preview.reframeHintFixed" : "preview.reframeHint");
        g2.setFont(g2.getFont().deriveFont(11f));
        FontMetrics fm = g2.getFontMetrics();
        List<String> lines = new ArrayList<>(List.of(hint));
        if (fm.stringWidth(hint) > getWidth() - 20) {
            String[] parts = hint.split(" · ");
            int half = (parts.length + 1) / 2;
            lines = List.of(String.join(" · ", java.util.Arrays.copyOfRange(parts, 0, half)),
                    String.join(" · ", java.util.Arrays.copyOfRange(parts, half, parts.length)));
        }
        int tw = 0;
        for (String l : lines) tw = Math.max(tw, fm.stringWidth(l));
        int lh = fm.getHeight();
        int bh = lh * lines.size() + 6;
        int bx = Math.max(4, (getWidth() - tw) / 2 - 6), by = getHeight() - bh - 6;
        g2.setColor(new Color(0, 0, 0, 170));
        g2.fillRoundRect(bx, by, Math.min(getWidth() - 8, tw + 12), bh, 8, 8);
        g2.setColor(Color.WHITE);
        for (int i = 0; i < lines.size(); i++) g2.drawString(lines.get(i), bx + 6, by + 3 + i * lh + fm.getAscent());
    }

    private void paintGrid(Graphics2D g2, Rectangle r, double px, double py) {
        // pixel grid, plus stronger 8×8 cell grid
        Rectangle clip = g2.getClipBounds() != null ? g2.getClipBounds() : new Rectangle(getSize());
        int x0 = Math.max(0, (int) ((clip.x - r.x) / px)), x1 = Math.min(image.getWidth(), (int) ((clip.x + clip.width - r.x) / px) + 1);
        int y0 = Math.max(0, (int) ((clip.y - r.y) / py)), y1 = Math.min(image.getHeight(), (int) ((clip.y + clip.height - r.y) / py) + 1);
        for (int x = x0; x <= x1; x++) {
            g2.setColor(x % 8 == 0 ? new Color(255, 255, 255, 90) : new Color(0, 0, 0, 40));
            int sx = r.x + (int) Math.round(x * px);
            g2.drawLine(sx, r.y + (int) (y0 * py), sx, r.y + (int) (y1 * py));
        }
        for (int y = y0; y <= y1; y++) {
            g2.setColor(y % 8 == 0 ? new Color(255, 255, 255, 90) : new Color(0, 0, 0, 40));
            int sy = r.y + (int) Math.round(y * py);
            g2.drawLine(r.x + (int) (x0 * px), sy, r.x + (int) (x1 * px), sy);
        }
    }
}
