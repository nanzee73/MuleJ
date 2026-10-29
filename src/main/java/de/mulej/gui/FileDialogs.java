package de.mulej.gui;

import de.mulej.i18n.I18n;

import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.prefs.Preferences;

/** File chooser helpers: filters, remembered folders and no file operations inside the dialog. */
final class FileDialogs {
    private FileDialogs() {}

    private static Preferences prefs() { return Preferences.userRoot().node("de/mulej"); }

    /** Last folder used for a given purpose ("input", "output", "png", "palette"). */
    static File lastDir(String purpose) {
        try {
            String s = prefs().get("dir." + purpose, null);
            if (s != null && new File(s).isDirectory()) return new File(s);
        } catch (Exception ignored) {
            // preferences not available
        }
        return null;
    }

    static void rememberDir(String purpose, File fileOrDir) {
        if (fileOrDir == null) return;
        File d = fileOrDir.isDirectory() ? fileOrDir : fileOrDir.getAbsoluteFile().getParentFile();
        try {
            if (d != null) prefs().put("dir." + purpose, d.getPath());
        } catch (Exception ignored) {
            // preferences not available
        }
    }

    /** A file chooser with translated title and without delete/rename buttons (GTK look and feel). */
    static JFileChooser chooser(String titleKey, File dir) {
        JFileChooser fc = new JFileChooser(dir) {
            @Override public void updateUI() {
                super.updateUI();
                hideFileOperations(this);
            }
        };
        fc.setDialogTitle(I18n.tr(titleKey));
        hideFileOperations(fc);
        return fc;
    }

    /**
     * The GTK file chooser (Linux system look) offers "Delete file" and "Rename file" buttons.
     * They are found via Swing's own text keys, so this works in every language.
     */
    static void hideFileOperations(Container root) {
        Locale l = root.getLocale();
        Set<String> texts = new HashSet<>();
        for (String key : new String[]{"FileChooser.deleteFileButtonText", "FileChooser.renameFileButtonText"}) {
            String t = UIManager.getString(key, l);
            if (t != null) texts.add(normalize(t));
        }
        if (texts.isEmpty()) return;
        hide(root, texts);
    }

    private static void hide(Container c, Set<String> texts) {
        for (Component child : c.getComponents()) {
            if (child instanceof AbstractButton b && b.getText() != null && texts.contains(normalize(b.getText()))) {
                b.setVisible(false);
            }
            if (child instanceof Container cc) hide(cc, texts);
        }
    }

    private static String normalize(String s) {
        return s.replace("&", "").replace("_", "").trim().toLowerCase(Locale.ROOT);
    }

    /** "All image formats" (selected), one filter per format, "All files" last. */
    static void addImageFilters(JFileChooser fc) {
        Map<String, Set<String>> byFormat = new TreeMap<>();
        Set<String> all = new TreeSet<>();
        Iterator<ImageReaderSpi> it = IIORegistry.getDefaultInstance().getServiceProviders(ImageReaderSpi.class, true);
        while (it.hasNext()) {
            ImageReaderSpi spi = it.next();
            String[] suffixes = spi.getFileSuffixes();
            String[] names = spi.getFormatNames();
            if (suffixes == null || names == null || names.length == 0) continue;
            String name = names[0].toUpperCase(Locale.ROOT);
            for (String suf : suffixes) {
                if (suf == null || suf.isBlank()) continue;
                String s = suf.toLowerCase(Locale.ROOT).replace(".", "");
                all.add(s);
                byFormat.computeIfAbsent(name, k -> new TreeSet<>()).add(s);
            }
        }
        if (all.isEmpty()) all.addAll(Arrays.asList(ImageIO.getReaderFileSuffixes()));
        fc.setAcceptAllFileFilterUsed(false);
        FileFilter allImages = filter(I18n.tr("filter.allImages"), all);
        fc.addChoosableFileFilter(allImages);
        for (Map.Entry<String, Set<String>> e : byFormat.entrySet()) fc.addChoosableFileFilter(filter(e.getKey(), e.getValue()));
        fc.addChoosableFileFilter(fc.getAcceptAllFileFilter());
        fc.setFileFilter(allImages);
    }

    /** A single filter (selected) plus "All files". */
    static void addFilter(JFileChooser fc, String descriptionKey, String... extensions) {
        FileFilter f = filter(I18n.tr(descriptionKey), new TreeSet<>(Arrays.asList(extensions)));
        fc.addChoosableFileFilter(f);
        fc.setFileFilter(f);
    }

    private static FileFilter filter(String description, Collection<String> ext) {
        List<String> list = new ArrayList<>(ext);
        StringBuilder sb = new StringBuilder(description).append(" (");
        int shown = Math.min(list.size(), 8);  // keep the filter combo box narrow
        for (int i = 0; i < shown; i++) sb.append(i == 0 ? "" : ", ").append("*.").append(list.get(i));
        if (shown < list.size()) sb.append(", …");
        sb.append(')');
        return new FileNameExtensionFilter(sb.toString(), list.toArray(new String[0]));
    }
}
