package de.mulej;

import de.mulej.color.ColorMatcher;
import de.mulej.core.*;
import de.mulej.image.ImageFitter;
import de.mulej.palette.Palette;
import de.mulej.palette.Palettes;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command line interface. Exit codes as in the old tools: 0 = OK, 1 = wrong parameters, 2 = conversion error.
 */
public final class Cli {
    private Cli() {}

    static final String USAGE = """
            MuleJ - C64 graphics tools (palettize, pixelize 2x1, Koala)

            Usage: mulej <command> [options] <input> [output]
                   (no arguments starts the GUI)

            Commands:
              palettize   map all colours to the nearest palette colour      -> NAME_palettized.png
              pixelize    make 2x1 multicolor pixels                          -> NAME_pixelized_l2r.png
                                                                                 NAME_pixelized_r2l.png
              koala       320x200 (or 160x200) picture to Koala Painter file  -> NAME.kla
              topng       Koala file (.kla/.koa) to PNG                       -> NAME.png

            Input can also be given as -if FILE (like the old c64palettizer/pixelizer/koalaverter).
            Output: second argument or -o FILE (pixelize: only with a single --dir).

            Options:
              --palette NAME|FILE     colodore (default), pepto, deekay, palette, community, ptoing,
                                      c64hq, ccs64, c64s, vice, or a .vpl/.gpl/hex file
              --space euclid|rgb|yuv|hsl   colour matching (default: euclid = plain RGB distance)
              --dir both|l2r|r2l|avg  pixelize: which pixel of a pair survives (default: both = two files)
              --pair l2r|r2l|avg      koala: pixel pair used for each multicolor pixel (default: l2r)
              --no-palettize          koala: every pixel must already be a palette colour
              --bg auto|0..15         koala: background colour (default: most common colour)
              --fix-clashes           koala: reduce cells with too many colours instead of failing
              --fit stretch|fit|fill  koala: scale pictures that are not 320x200 (default: refuse)
              --preview FILE.png      koala: also write the result as PNG
              -h, --help              this help
            """;

    /** Command line only (never starts the GUI), e.g. on headless machines. */
    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** Runs the command line; returns the exit code. */
    public static int run(String[] args) {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            System.out.println(USAGE);
            return args.length == 0 ? 1 : 0;
        }
        String cmd = args[0].toLowerCase(Locale.ROOT);
        Palette pal = Palettes.COLODORE;
        ColorMatcher.Space space = ColorMatcher.Space.EUCLID;
        String dir = "both";
        Pixelizer.Direction pair = Pixelizer.Direction.L2R;
        boolean palettize = true, fix = false;
        int bg = -1;
        ImageFitter.ScaleMode fit = null;
        Path in = null, out = null, preview = null;
        try {
            for (int i = 1; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "-h", "--help" -> { System.out.println(USAGE); return 0; }
                    case "-if", "--in" -> in = Path.of(next(args, ++i));
                    case "-o", "--out" -> out = Path.of(next(args, ++i));
                    case "--palette" -> pal = paletteByName(next(args, ++i));
                    case "--space" -> space = ColorMatcher.Space.valueOf(next(args, ++i).toUpperCase(Locale.ROOT));
                    case "--dir" -> dir = next(args, ++i).toLowerCase(Locale.ROOT);
                    case "--pair" -> pair = direction(next(args, ++i));
                    case "--no-palettize" -> palettize = false;
                    case "--bg" -> {
                        String v = next(args, ++i);
                        bg = v.equalsIgnoreCase("auto") ? -1 : Integer.parseInt(v);
                        if (bg < -1 || bg > 15) throw new IllegalArgumentException("--bg must be auto or 0..15");
                    }
                    case "--fix-clashes" -> fix = true;
                    case "--fit" -> fit = ImageFitter.ScaleMode.valueOf(next(args, ++i).toUpperCase(Locale.ROOT));
                    case "--preview" -> preview = Path.of(next(args, ++i));
                    default -> {
                        if (a.startsWith("-")) throw new IllegalArgumentException("Unknown option: " + a);
                        if (in == null) in = Path.of(a);
                        else if (out == null) out = Path.of(a);
                        else throw new IllegalArgumentException("Unexpected argument: " + a);
                    }
                }
            }
            if (in == null) throw new IllegalArgumentException("No input file given.");
            if (!List.of("palettize", "pixelize", "koala", "topng").contains(cmd))
                throw new IllegalArgumentException("Unknown command: " + args[0]);
            if (cmd.equals("pixelize") && !List.of("both", "l2r", "r2l", "avg").contains(dir))
                throw new IllegalArgumentException("--dir must be both, l2r, r2l or avg");
        } catch (IllegalArgumentException e) {
            System.out.println("Error: " + e.getMessage());
            System.out.println();
            System.out.println(USAGE);
            return 1;
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            return 1;
        }

        try {
            switch (cmd) {
                case "palettize" -> {
                    BufferedImage src = Images.read(in, pal);
                    Path target = out != null ? out : Images.sibling(in, "_palettized.png");
                    Images.writePng(Palettizer.palettize(src, pal, space), target);
                    System.out.println("Palettizing of " + in + " successful: " + target);
                }
                case "pixelize" -> {
                    BufferedImage src = Images.read(in, pal);
                    List<Pixelizer.Direction> dirs = new ArrayList<>();
                    if (dir.equals("both")) { dirs.add(Pixelizer.Direction.L2R); dirs.add(Pixelizer.Direction.R2L); }
                    else dirs.add(direction(dir));
                    if (out != null && dirs.size() > 1)
                        throw new IllegalArgumentException("An output name needs a single --dir (l2r, r2l or avg).");
                    for (Pixelizer.Direction d : dirs) {
                        Path target = out != null ? out : Images.sibling(in, d.fileSuffix() + ".png");
                        Images.writePng(Pixelizer.pixelize(src, d), target);
                        System.out.println("Wrote " + target);
                    }
                    System.out.println("Pixelizing of " + in + " successful.");
                }
                case "koala" -> {
                    BufferedImage src = Images.read(in, pal);
                    if (!KoalaConverter.isValidSize(src) && fit != null) {
                        ImageFitter.Settings fs = ImageFitter.Settings.defaults();
                        src = ImageFitter.fit(src, new ImageFitter.Settings(null, fit, fs.filter(), fs.aspect(), fs.fill()));
                    }
                    KoalaConverter.Result r = KoalaConverter.convert(src,
                            new KoalaConverter.Settings(pal, space, pair, palettize, bg, fix));
                    if (!r.ok()) {
                        System.out.println("Error: C64 multi color image restrictions violated in "
                                + r.clashCells().size() + " cells, e.g. " + describeCells(r.clashCells())
                                + ". Use --fix-clashes to reduce them.");
                        return 2;
                    }
                    Path target = out != null ? out : Images.sibling(in, ".kla");
                    Files.write(target, r.koala().toBytes());
                    if (!r.clashCells().isEmpty())
                        System.out.println("Reduced colours in " + r.clashCells().size() + " cells.");
                    if (preview != null) Images.writePng(r.koala().render(pal), preview);
                    System.out.println("Conversion of " + in + " successful: " + target
                            + " (background " + r.background() + ")");
                }
                case "topng" -> {
                    KoalaImage k = KoalaImage.read(Files.readAllBytes(in));
                    Path target = out != null ? out : Images.sibling(in, ".png");
                    Images.writePng(k.render(pal), target);
                    System.out.println("Wrote " + target);
                }
                default -> { return 1; }
            }
        } catch (IllegalArgumentException e) {
            System.out.println("Error: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            return 2;
        }
        return 0;
    }

    private static String next(String[] args, int i) {
        if (i >= args.length) throw new IllegalArgumentException("Missing value for " + args[i - 1]);
        return args[i];
    }

    /** "x,y" of the first few cells (cell coordinates, 0..39 / 0..24). */
    static String describeCells(List<Integer> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(5, cells.size()); i++) {
            int c = cells.get(i);
            sb.append(i == 0 ? "" : ", ").append("cell ").append(c % KoalaImage.COLS).append('/').append(c / KoalaImage.COLS);
        }
        if (cells.size() > 5) sb.append(", ...");
        return sb.toString();
    }

    static Pixelizer.Direction direction(String n) {
        return switch (n.toLowerCase(Locale.ROOT)) {
            case "l2r" -> Pixelizer.Direction.L2R;
            case "r2l" -> Pixelizer.Direction.R2L;
            case "avg", "average" -> Pixelizer.Direction.AVERAGE;
            default -> throw new IllegalArgumentException("Unknown direction: " + n + " (l2r, r2l, avg)");
        };
    }

    public static Palette paletteByName(String n) throws java.io.IOException {
        return switch (n.toLowerCase(Locale.ROOT)) {
            case "pepto" -> Palettes.PEPTO;
            case "colodore" -> Palettes.COLODORE;
            case "deekay" -> Palettes.DEEKAY;
            case "palette" -> Palettes.PALETTE_OFFENCE;
            case "community" -> Palettes.COMMUNITY;
            case "ptoing" -> Palettes.PTOING;
            case "c64hq" -> Palettes.C64HQ;
            case "ccs64" -> Palettes.CCS64;
            case "c64s" -> Palettes.C64S;
            case "vice" -> Palettes.VICE_OLD;
            default -> Palettes.load(Path.of(n));
        };
    }
}
