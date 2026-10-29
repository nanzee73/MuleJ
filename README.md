# MuleJ

C64 graphics tools under one roof – the former command line tools **c64palettizer**, **c64pixelizer** and
**c64koalaverter** (plus its predecessor c64imager) with a Swing GUI in the style of FlipperJ.
The command line keeps working, and with the default settings it produces the same results as the old tools.

## Tools

| Tool | What it does | Output |
|---|---|---|
| **Palettize** | Replaces every colour by the nearest colour of a C64 palette. Any picture size. | `NAME_palettized.png` |
| **Pixelize 2×1** | Makes every two neighbouring pixels equal (multicolor pixels): left→right, right→left or average. Any picture size. | `NAME_pixelized_l2r.png`, `NAME_pixelized_r2l.png` (or `_avg`) |
| **Koala** | 320×200 (or 160×200) picture → Koala Painter file: pixel pairs → palette → background = most common colour → at most 3 colours per 4×8 cell besides the background. | `NAME.kla` (10003 bytes, load address `$6000`) |

New compared to the old tools:

* **Every picture format** Java ImageIO reads, plus the TwelveMonkeys plugins (WebP, TIFF, PSD, TGA, …) – the old tools only took PNG.
  Koala files (`.kla`/`.koa`) can be opened too; they are drawn with the selected palette.
* **Palettes**: Colodore (default, as before), Pepto, DeeKay, PALette, Community Colors, Ptoing, C64HQ, CCS64, C64S, VICE;
  custom palettes via the colour editor or `.vpl`/`.gpl`/hex files.
* **Colour matching**: plain euclidean RGB (default, as before), luma-weighted RGB, YUV or HSL.
* **Koala colour clashes**: instead of only “restrictions violated”, every offending cell is reported (red in the preview,
  cell coordinates on the command line). *Reduce colour clashes* keeps the 3 most common colours of such a cell and maps
  the other pixels to the nearest allowed colour.
* **Koala input of any size**: crop & scale dialog (GUI) or `--fit` (command line); fixed background colour;
  *Palettize first* can be switched off for finished pixel art (then every pixel must already be a palette colour).
* **Koala → PNG** (`topng`).

## GUI

```
./gradlew run
```

Choose the tool at the top, open a picture (or drop it onto the window). The result on the right updates automatically
after every change; **Save** writes the file(s) of the chosen tool, named like the old tools (next to the picture, or in
the folder you picked for the output). *Result as PNG…* saves the right preview without the clash markers.
Previews: mouse wheel zooms, right drag pans, double click fits, both previews can be linked, pixel and 8×8 cell grid when
zoomed in. In the Koala tool a right click into the original picks a new frame directly in the preview.
English and German user interface (*Language* menu, no restart needed).

## Command line

Without arguments the GUI starts, with arguments the command line runs (`java -cp … de.mulej.Cli` never starts the GUI).

```
mulej palettize image.png                    # -> image_palettized.png
mulej pixelize image.png                     # -> image_pixelized_l2r.png + image_pixelized_r2l.png
mulej koala image.png                        # -> image.kla
mulej koala photo.jpg --fit fill --pair avg --space yuv --fix-clashes --preview check.png
mulej topng image.kla                        # -> image.png
mulej palettize -if image.png                # the old -if syntax still works
mulej --help
```

Options: `--palette NAME|FILE`, `--space euclid|rgb|yuv|hsl`, `--dir both|l2r|r2l|avg` (pixelize),
`--pair l2r|r2l|avg`, `--no-palettize`, `--bg auto|0..15`, `--fix-clashes`, `--fit stretch|fit|fill`,
`--preview FILE.png` (koala), `-o FILE`. Exit codes as before: 0 = OK, 1 = wrong parameters, 2 = conversion error.

## Build

Requires a JDK 17 or newer. IntelliJ IDEA: *File ▸ Open…*, select the folder with `build.gradle.kts`, run `de.mulej.App`.

```
./gradlew run                 # start the GUI
./gradlew test                # run the unit tests
./gradlew installDist         # build/install/mulej/bin/mulej(.bat)
```

## Koala format and compatibility

`$6000` load address, 8000 bytes bitmap (cell by cell), 1000 bytes screen RAM, 1000 bytes colour RAM, 1 byte background.
Bit pairs: `00` background, `01` screen RAM high nibble, `10` screen RAM low nibble, `11` colour RAM.
The colours of a cell are assigned in ascending colour index order (low nibble, high nibble, colour RAM), exactly like
c64koalaverter – the old tools and MuleJ were compared on all pictures in their `ressources` folders: all 125 outputs
(palettized, pixelized and Koala files) are identical.

## Project layout

```
src/main/java/de/mulej/
  App.java, Cli.java          entry point, command line
  core/                       Palettizer, Pixelizer, KoalaConverter, KoalaImage, Images
  palette/                    palettes, .vpl/.gpl/hex loading
  color/ColorMatcher.java     colour distance in euclidean RGB / weighted RGB / YUV / HSL
  image/ImageFitter.java      crop & scale to 320×200
  gui/                        Swing UI (preview, crop dialog, palette editor – shared with FlipperJ)
  i18n/                       English/German texts
src/test/java/...             JUnit 5 tests
packaging/                    icon (mule head, C64 yellow on C64 blue) as SVG, PNG and .ico
```
