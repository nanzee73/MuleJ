package de.mulej;

import de.mulej.color.ColorMatcher;
import de.mulej.core.*;
import de.mulej.image.ImageAdjust;
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
 * Command line interface. Exit codes as in the old tools: 0 = OK, 1 = wrong parameters, 2 = conversion error
 * (for {@code check}: 2 = the picture breaks the colour rules).
 */
public final class Cli {
    private Cli() {}

    static final String USAGE = """
            MuleJ - C64 graphics tools (palettize, pixelize 2x1, C64 bitmaps with colour check)

            Usage: mulej <command> [options] <input> [output]
                   (no arguments starts the GUI)

            Commands:
              palettize   map all colours to the nearest palette colour      -> NAME_palettized.png
              pixelize    make 2x1 multicolor pixels                          -> NAME_pixelized_l2r.png
                                                                                 NAME_pixelized_r2l.png
              koala       multicolor bitmap (bg + 3 colours per 4x8 cell)     -> NAME.kla  (Koala Painter)
              hires       hires bitmap (2 colours per 8x8 cell)               -> NAME.art  (Art Studio)
              petscii     text screen: characters + colours, for Petmate       -> NAME.petmate
              check       colour check: list the cells that break the rules of --mode (no file written)
              topng       Koala (.kla), Art Studio (.art) or Petmate file to PNG -> NAME.png

            Input can also be given as -if FILE (like the old c64palettizer/pixelizer/koalaverter).
            Output: second argument or -o FILE (pixelize: only with a single --dir).

            Options:
              --palette NAME|FILE     colodore (default), pepto, deekay, palette, community, ptoing,
                                      c64hq, ccs64, c64s, vice, or a .vpl/.gpl/hex file
              --space euclid|rgb|yuv|hsl   colour matching (default: euclid = plain RGB distance)
              --fit stretch|fit|fill|none  bring pictures that are not 320x200 to 320x200 first
                                      (none = 1:1 frame of 320x200 pixels, centered); required for
                                      koala/hires/check with other sizes, optional for the others
              --dither NAME           palettize/koala/hires: none (default), fs, atkinson, jarvis,
                                      stucki, sierra, bayer2, bayer4, bayer8
              --strength N            dither strength 0..100 (default: 80)
              --serpentine            serpentine scanning
              --no-bleeding           koala/hires: dither error never leaves its cell
              --dir both|l2r|r2l|avg  pixelize: which pixel of a pair survives (default: both = two files)
              --pair l2r|r2l|avg      koala: pixel pair used for each multicolor pixel (default: l2r)
              --mode mc|hires         check: which rules to check (default: mc)
              --no-palettize          koala/hires/check: every pixel must already be a palette colour
              --bg auto|0..15         koala: background colour (default: most common colour)
              --fix-clashes           koala/hires: correct cells with too many colours instead of failing
              --preview FILE.png      koala/hires/petscii: also write the result as PNG
              --prg FILE.prg          koala/hires/petscii: also write a runnable C64 program that shows it
              --contrast N --brightness N   prepare: -100..100 (default 0)
              --gamma N --saturation N      prepare: percent (default 100)
              --charset upper|lower   petscii: character set (default: upper = upper case + graphics)
              --text                  petscii: also use letters, digits and punctuation
              --detail N              petscii: 0..100, pixel exact shapes vs. soft tones (default 40)
              --diffusion N           petscii: 0..100, error passed on to neighbour cells (default 40)
              --calm N                petscii: 0..100, avoid busy characters (default 30)
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
        C64Mode mode = cmd.equals("hires") ? C64Mode.HIRES : C64Mode.MULTICOLOR;
        boolean palettize = true, fix = false, serpentine = false, bleeding = true;
        int bg = -1, strength = 80;
        Dither dither = Dither.NONE;
        ImageFitter.ScaleMode fit = null;
        Path in = null, out = null, preview = null, prg = null;
        int contrastV = 0, brightnessV = 0, gammaV = 100, saturationV = 100;
        PetsciiConverter.Settings pd = PetsciiConverter.Settings.defaults();
        Charset charset = pd.charset();
        boolean text = pd.textChars();
        int detailV = pd.detail(), diffusionV = pd.diffusion(), calmV = pd.calm();
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
                    case "--mode" -> mode = modeByName(next(args, ++i));
                    case "--no-palettize" -> palettize = false;
                    case "--bg" -> {
                        String v = next(args, ++i);
                        bg = v.equalsIgnoreCase("auto") ? -1 : Integer.parseInt(v);
                        if (bg < -1 || bg > 15) throw new IllegalArgumentException("--bg must be auto or 0..15");
                    }
                    case "--fix-clashes" -> fix = true;
                    case "--fit" -> fit = ImageFitter.ScaleMode.valueOf(next(args, ++i).toUpperCase(Locale.ROOT));
                    case "--dither" -> dither = ditherByName(next(args, ++i));
                    case "--strength" -> strength = Integer.parseInt(next(args, ++i));
                    case "--serpentine" -> serpentine = true;
                    case "--no-bleeding" -> bleeding = false;
                    case "--preview" -> preview = Path.of(next(args, ++i));
                    case "--prg" -> prg = Path.of(next(args, ++i));
                    case "--contrast" -> contrastV = Integer.parseInt(next(args, ++i));
                    case "--brightness" -> brightnessV = Integer.parseInt(next(args, ++i));
                    case "--gamma" -> gammaV = Integer.parseInt(next(args, ++i));
                    case "--saturation" -> saturationV = Integer.parseInt(next(args, ++i));
                    case "--charset" -> charset = Charset.byName(next(args, ++i));
                    case "--text" -> text = true;
                    case "--detail" -> detailV = Integer.parseInt(next(args, ++i));
                    case "--diffusion" -> diffusionV = Integer.parseInt(next(args, ++i));
                    case "--calm" -> calmV = Integer.parseInt(next(args, ++i));
                    default -> {
                        if (a.startsWith("-")) throw new IllegalArgumentException("Unknown option: " + a);
                        if (in == null) in = Path.of(a);
                        else if (out == null) out = Path.of(a);
                        else throw new IllegalArgumentException("Unexpected argument: " + a);
                    }
                }
            }
            if (in == null) throw new IllegalArgumentException("No input file given.");
            if (!List.of("palettize", "pixelize", "koala", "hires", "petscii", "check", "topng").contains(cmd))
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

        Ditherer.Settings ds = new Ditherer.Settings(dither, strength, serpentine, bleeding);
        ImageAdjust.Settings adjust = new ImageAdjust.Settings(brightnessV, contrastV, gammaV, saturationV);
        try {
            switch (cmd) {
                case "palettize" -> {
                    BufferedImage src = fitted(Images.read(in, pal), fit);
                    Path target = out != null ? out : Images.sibling(in, "_palettized.png");
                    Images.writePng(Palettizer.palettize(src, pal, space, ds), target);
                    System.out.println("Palettizing of " + in + " successful: " + target);
                }
                case "pixelize" -> {
                    BufferedImage src = fitted(Images.read(in, pal), fit);
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
                case "petscii" -> {
                    BufferedImage src = Images.read(in, pal);
                    if (!(src.getWidth() == 320 && src.getHeight() == 200)) {
                        if (ImageFitter.canTakeExactly(src)) src = ImageFitter.takeExactly(src);
                        else if (fit != null) src = fitted(src, fit);
                        else throw new IllegalStateException("Image resolution must be 320 x 200, not "
                                + src.getWidth() + " x " + src.getHeight() + " (use --fit).");
                    }
                    src = prepared(src, adjust);
                    PetsciiImage p = PetsciiConverter.convert(src, pal, space,
                            new PetsciiConverter.Settings(charset, text, bg, detailV, diffusionV, calmV));
                    Path target = out != null ? out : Images.sibling(in, ".petmate");
                    String name = in.getFileName().toString().replaceFirst("\\.[^.]+$", "");
                    Files.writeString(target, p.toPetmate(name), java.nio.charset.StandardCharsets.UTF_8);
                    if (preview != null) Images.writePng(p.render(pal), preview);
                    if (prg != null) {
                        Files.write(prg, ViewerPrg.petscii(p));
                        System.out.println("Wrote " + prg + " (LOAD\"NAME\",8 and RUN on the C64)");
                    }
                    System.out.println("Conversion of " + in + " successful: " + target + " (background "
                            + p.background + ", " + p.distinctCodes() + " different characters)");
                }
                case "koala", "hires", "check" -> {
                    BufferedImage src = Images.read(in, pal);
                    if (!BitmapConverter.isValidSize(src) && fit != null) src = fitted(src, fit);
                    if (BitmapConverter.isValidSize(src)) src = prepared(src, adjust);
                    BitmapConverter.Result r = BitmapConverter.convert(src, new BitmapConverter.Settings(
                            mode, pal, space, pair, palettize, bg, fix && !cmd.equals("check"), ds));
                    if (cmd.equals("check")) {
                        if (r.ok()) {
                            System.out.println("OK: " + in + " keeps the " + mode.name().toLowerCase(Locale.ROOT)
                                    + " colour rules" + (mode.sharedBackground ? " (background " + r.background() + ")." : "."));
                            return 0;
                        }
                        System.out.println(r.clashCells().size() + " cells break the " + mode.name().toLowerCase(Locale.ROOT)
                                + " colour rules (max. " + mode.coloursPerCell + " colours per cell"
                                + (mode.sharedBackground ? " + background " + r.background() : "") + "):");
                        for (int cell : r.clashCells()) System.out.println("  " + describeCell(r, cell));
                        return 2;
                    }
                    if (!r.ok()) {
                        System.out.println("Error: C64 " + mode.name().toLowerCase(Locale.ROOT)
                                + " colour restrictions violated in " + r.clashCells().size() + " cells, e.g. "
                                + describeCells(r.clashCells()) + ". Use --fix-clashes or --dither to correct them,"
                                + " or 'mulej check' for details.");
                        return 2;
                    }
                    Path target = out != null ? out : Images.sibling(in, "." + mode.extension);
                    Files.write(target, r.fileBytes());
                    if (!r.fixedCells().isEmpty())
                        System.out.println("Corrected colours in " + r.fixedCells().size() + " cells.");
                    if (preview != null) Images.writePng(r.render(pal), preview);
                    if (prg != null) {
                        Files.write(prg, ViewerPrg.build(r));
                        System.out.println("Wrote " + prg + " (LOAD\"NAME\",8 and RUN on the C64)");
                    }
                    System.out.println("Conversion of " + in + " successful: " + target
                            + (mode.sharedBackground ? " (background " + r.background() + ")" : ""));
                }
                case "topng" -> {
                    Path target = out != null ? out : Images.sibling(in, ".png");
                    String n = in.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    if (n.endsWith(".petmate")) {
                        PetsciiImage p = PetsciiImage.fromPetmate(Files.readString(in, java.nio.charset.StandardCharsets.UTF_8));
                        Images.writePng(p.render(pal), target);
                    } else {
                        if (!Images.isC64File(in)) throw new IllegalArgumentException("topng needs a .kla, .art or .petmate file.");
                        Images.writePng(Images.read(in, pal), target);
                    }
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

    /** Step "prepare"; pictures stay unchanged with neutral settings (results identical to the old tools). */
    private static BufferedImage prepared(BufferedImage src, ImageAdjust.Settings a) {
        if (ImageAdjust.Settings.NEUTRAL.equals(a)) return src;
        return ImageAdjust.toImage(ImageAdjust.toAdjustedRgb(src, a, true), src.getWidth(), src.getHeight());
    }

    private static BufferedImage fitted(BufferedImage src, ImageFitter.ScaleMode fit) {
        if (fit == null || (src.getWidth() == 320 && src.getHeight() == 200)) return src;
        ImageFitter.Settings fs = fit == ImageFitter.ScaleMode.NONE ? ImageFitter.Settings.oneToOne(src)
                : ImageFitter.Settings.suggested(src);
        if (fit != ImageFitter.ScaleMode.NONE)
            fs = new ImageFitter.Settings(fit == ImageFitter.ScaleMode.STRETCH ? null : fs.crop(), fit,
                    fs.filter(), fs.aspect(), fs.fill());
        return ImageFitter.fit(src, fs);
    }

    private static String next(String[] args, int i) {
        if (i >= args.length) throw new IllegalArgumentException("Missing value for " + args[i - 1]);
        return args[i];
    }

    /** "cell 12/5 (x 96-103, y 40-47): 5 colours 0 1 2 6 14" */
    static String describeCell(BitmapConverter.Result r, int cell) {
        C64Mode m = r.mode();
        int x0 = m.cellX(cell) * m.pixelWidth(), y0 = m.cellY(cell);
        int[] count = r.cellCounts(cell);
        StringBuilder cols = new StringBuilder();
        int n = 0;
        for (int i = 0; i < 16; i++) {
            if (count[i] == 0) continue;
            cols.append(' ').append(i);
            if (i != r.background()) n++;
        }
        return String.format(Locale.ROOT, "cell %d/%d (x %d-%d, y %d-%d): %d colours%s",
                cell % C64Mode.COLS, cell / C64Mode.COLS, x0, x0 + 7, y0, y0 + 7, n, cols);
    }

    /** "x/y" of the first few cells (cell coordinates, 0..39 / 0..24). */
    static String describeCells(List<Integer> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(5, cells.size()); i++) {
            int c = cells.get(i);
            sb.append(i == 0 ? "" : ", ").append("cell ").append(c % C64Mode.COLS).append('/').append(c / C64Mode.COLS);
        }
        if (cells.size() > 5) sb.append(", ...");
        return sb.toString();
    }

    static C64Mode modeByName(String n) {
        return switch (n.toLowerCase(Locale.ROOT)) {
            case "mc", "multicolor", "koala" -> C64Mode.MULTICOLOR;
            case "hires", "hi", "art" -> C64Mode.HIRES;
            default -> throw new IllegalArgumentException("Unknown mode: " + n + " (mc, hires)");
        };
    }

    static Pixelizer.Direction direction(String n) {
        return switch (n.toLowerCase(Locale.ROOT)) {
            case "l2r" -> Pixelizer.Direction.L2R;
            case "r2l" -> Pixelizer.Direction.R2L;
            case "avg", "average" -> Pixelizer.Direction.AVERAGE;
            default -> throw new IllegalArgumentException("Unknown direction: " + n + " (l2r, r2l, avg)");
        };
    }

    static Dither ditherByName(String n) {
        return switch (n.toLowerCase(Locale.ROOT)) {
            case "none" -> Dither.NONE;
            case "fs", "floyd", "floyd-steinberg" -> Dither.FLOYD_STEINBERG;
            case "atkinson" -> Dither.ATKINSON;
            case "jarvis" -> Dither.JARVIS;
            case "stucki" -> Dither.STUCKI;
            case "sierra" -> Dither.SIERRA_LITE;
            case "bayer2" -> Dither.BAYER2;
            case "bayer4" -> Dither.BAYER4;
            case "bayer8" -> Dither.BAYER8;
            default -> throw new IllegalArgumentException("Unknown dither: " + n);
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
