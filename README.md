# MuleJ

C64 graphics tools under one roof – the former command line tools **c64palettizer**, **c64pixelizer** and
**c64koalaverter** (plus its predecessor c64imager) with a Swing GUI in the style of FlipperJ, extended by hires bitmaps,
dithering and a colour check with automatic and manual correction. The command line keeps working, and with the
default settings it produces the same results as the old tools.

## Tools

| Tool | What it does | Output |
|---|---|---|
| **Palettize** | Replaces every colour by the nearest colour of a C64 palette, optionally dithered. Any picture size, or brought to 320×200. | `NAME_palettized.png` |
| **Pixelize 2×1** | Makes every two neighbouring pixels equal (multicolor pixels): left→right or right→left. | `NAME_pixelized_l2r.png` or `NAME_pixelized_r2l.png` |
| **C64 bitmap – multicolor** | 160×200, per 4×8 cell 3 colours plus one background colour for the whole picture. | `NAME.kla` (Koala Painter, 10003 bytes, `$6000`) |
| **C64 bitmap – hires** | 320×200, per 8×8 cell 2 colours. | `NAME.art` (OCP Art Studio, 9009 bytes, `$2000`) |

### Bringing pictures to 320×200

*Bring to 320×200* (always on for C64 bitmaps) opens the crop & scale dialog for every picture that is not 320×200 or 160×200.
The dialog always starts with the **largest centered frame in the C64 aspect ratio** (PAL, ≈1.50:1, as on a real monitor):

* **Larger pictures** are scaled down into that frame; drag it, resize it at the corner or draw a new one.
* **320×200 frame (1:1)** – a frame of exactly 320×200 picture pixels without any scaling; it can only be moved
  (a click puts it where you clicked). Pictures smaller than 320×200 get the border colour around them.
* **Smaller pictures** are scaled up along their shorter side, so the C64 frame is filled completely (no border).
  Pictures that fit 320×200 or 160×200 by a whole-number factor (e.g. 160×100, 80×100, 640×400)
  are taken completely and scaled exactly with nearest neighbour, so pixel art stays crisp.
* **Mouse wheel** zooms into the picture (around the mouse pointer), right or middle drag pans, double click shows the whole picture.
* Scaling modes stretch / fit (letterbox) / fill (crop), aspect PAL / square pixels / NTSC / free, four filters, border colour.
* A right click into the original preview chooses the frame directly in the preview (the 1:1 frame keeps its size).

### Dithering

None, Floyd-Steinberg, Atkinson, Jarvis-Judice-Ninke, Stucki, Sierra Lite, Bayer 2×2/4×4/8×8 (same methods as FlipperJ),
with strength, serpentine scanning and – for C64 bitmaps – *bleeding* (off = the error never leaves its cell).
For C64 bitmaps every cell first gets the colours that reproduce it best, then the picture is dithered with exactly
those colours, so the result always keeps the colour rules.

### Colour check & correction (C64 bitmaps)

Every result is checked against the rules of the mode. Only a picture that passes is written as `.kla` / `.art`.

* **Check**: “✔ all 1000 cells keep the rules” or “✘ 37 cells break the multicolor rules”; offending cells are marked red,
  ◀ ▶ jumps to the previous/next conflict and zooms in.
* **Automatic**: *Correct automatically* gives every offending cell the colours (out of all combinations) that reproduce
  it best; dithering always produces valid pictures.
* **Manual**, by clicking into the result:
  * *Select cell* shows the colours of the cell with their pixel counts. Press the colours the cell may use and
    *Apply colours* – every pixel gets the nearest of them. *Automatic* does the same with the best colours for this cell.
  * *Paint* sets single pixels to a chosen colour (left click / drag).
  * *Reset cell* / *Discard all corrections* undo the manual changes. Corrected cells are marked yellow, the selected cell cyan.
* *Palettize first* off: the picture must already consist of palette colours – useful to only check and fix finished pixel art.
  Koala and Art Studio files can be opened too.

### More

* Every picture format Java ImageIO reads plus the TwelveMonkeys plugins (WebP, TIFF, PSD, TGA, …) – the old tools only took PNG.
* Palettes Colodore (default, as before), Pepto, DeeKay, PALette, Community Colors, Ptoing, C64HQ, CCS64, C64S, VICE;
  custom palettes via the colour editor or `.vpl`/`.gpl`/hex files.
* Colour matching in plain euclidean RGB (default, as before), luma-weighted RGB, YUV or HSL.
* Previews: zoom with the mouse wheel, pan with the right mouse button, linked previews, pixel and 8×8 cell grid.
* **Result as PNG…** saves the right preview, **Result as PRG…** a runnable C64 program that shows the picture
  (C64 bitmap tool, once the colour check has passed: `LOAD"NAME",8` and `RUN`, any key returns to BASIC).
  Both start in the folder of the source picture.
* Until a picture is opened, everything except opening a picture, the tool choice and the menus is locked.
* English and German user interface (*Language* menu, English by default, no restart needed).

## Command line

Without arguments the GUI starts, with arguments the command line runs (`mulej-cli` / `de.mulej.Cli` never starts the GUI).

```
mulej palettize image.png                       # -> image_palettized.png
mulej palettize photo.jpg --fit fill --dither fs
mulej pixelize image.png                        # -> image_pixelized_l2r.png + image_pixelized_r2l.png
mulej koala image.png                           # -> image.kla
mulej koala photo.jpg --fit fill --space yuv --dither fs --preview check.png --prg photo.prg
mulej hires image.png --fix-clashes             # -> image.art
mulej check pixelart.png --mode hires --no-palettize   # list every cell that breaks the rules
mulej topng image.kla                           # -> image.png
mulej palettize -if image.png                   # the old -if syntax still works
mulej --help
```

Exit codes as before: 0 = OK, 1 = wrong parameters, 2 = conversion error (`check`: 2 = rules broken).
The command line still offers `avg` (average of a pixel pair) for `--dir` and `--pair`; the GUI only offers left→right and right→left.

### PRG memory map

| Address | Content |
|---|---|
| `$0801` | BASIC line `10 SYS2061` |
| `$080D` | viewer code (sets VIC bank 0, bitmap mode, copies colour RAM, waits for a key, restores the text screen) |
| `$0C00–$0FE7` | screen RAM (the VIC reads it there directly) |
| `$1000–$13E7` | colour RAM data (multicolor), copied to `$D800` |
| `$13E8` / `$13E9` | background / border colour |
| `$2000–$3F3F` | bitmap |

The viewer was checked in a 6502 simulator (the picture the VIC would show is identical to the `.kla`/`.art` data),
not yet in VICE or on real hardware.

## Using MuleJ (for users)

Download from the GitHub **Releases** page:

| System | File | Start |
|---|---|---|
| Windows | `MuleJ-x.y.z.msi` | Install, then *Start menu ▸ MuleJ*. Command line: `"C:\Program Files\MuleJ\mulej-cli.exe" --help` |
| Windows (no installation) | `MuleJ-…-windows-portable.zip` | Unpack, run `MuleJ\MuleJ.exe` |
| macOS (Apple Silicon) | `MuleJ-…-macos-arm64.dmg` | Drag MuleJ into *Applications*. The app is not signed: the first time right click ▸ *Open*. Command line: `/Applications/MuleJ.app/Contents/MacOS/mulej-cli` |
| Linux (Debian/Ubuntu) | `mulej_x.y.z-1_amd64.deb` | `sudo apt install ./mulej_….deb`, then the menu entry *MuleJ* or `/opt/mulej/bin/MuleJ`; command line `/opt/mulej/bin/mulej-cli` |
| Linux (others, no installation) | `MuleJ-…-linux-portable.tar.gz` | Unpack, run `MuleJ/bin/MuleJ` |
| Any system with Java 17+ | `mulej-x.y.z-all.jar` | `java -jar mulej-x.y.z-all.jar` (GUI) or `java -jar mulej-x.y.z-all.jar koala bild.png` |

The installers and portable versions contain their own Java runtime – nothing else needs to be installed.

## Releasing a new version (for the developer)

1. Set `version` in `build.gradle.kts` and `VERSION` in `App.java`.
2. `git tag v1.0.0 && git push origin v1.0.0`
3. GitHub Actions (`.github/workflows/release.yml`) builds and tests on Windows, macOS and Linux and publishes a release
   with all files above. *Actions ▸ Build installers ▸ Run workflow* builds the same files without a release.

Note: jpackage on macOS does not accept versions starting with 0, so `0.x.y` becomes `1.x.y` in the `.dmg` only.
Use tags from `v1.0.0` on to keep the versions identical everywhere.

## Build

Requires a JDK 17 or newer. IntelliJ IDEA: *File ▸ Open…*, select the folder with `build.gradle.kts`, run `de.mulej.App`.

```
./gradlew run                                 # start the GUI
./gradlew test                                # run the unit tests
./gradlew installDist                         # build/install/mulej/bin/mulej(.bat) – needs Java on the target
./gradlew shadowJar                           # build/libs/mulej-<version>-all.jar – java -jar …
./gradlew jpackage                            # installer for this system in build/installer (Windows needs WiX 3.14)
./gradlew jpackage -PinstallerType=app-image  # portable folder for this system
```

## File formats

**Koala Painter** (`.kla`): `$6000` load address, 8000 bytes bitmap (cell by cell), 1000 bytes screen RAM,
1000 bytes colour RAM, 1 byte background. Bit pairs: `00` background, `01` screen RAM high nibble, `10` screen RAM low
nibble, `11` colour RAM. Cell colours are assigned in ascending colour index order, exactly like c64koalaverter –
the old tools and MuleJ were compared on all pictures in their `ressources` folders: all 125 outputs are identical.

**OCP Art Studio** (`.art`): `$2000` load address, 8000 bytes bitmap, 1000 bytes screen RAM (high nibble = set bits,
low nibble = cleared bits), 7 bytes filler (the first is used as border colour by some programs) – 9009 bytes.

## License

MIT License (see `LICENSE`): you may use, copy, change, sell and pass on MuleJ in any way, as long as the copyright
notice and the license text stay with every copy and every program based on it – that names the original.
The old tools c64palettizer, c64pixelizer, c64koalaverter and c64helpers are under the same license.

## Project layout

```
src/main/java/de/mulej/
  App.java, Cli.java          entry point, command line
  core/                       Palettizer, Pixelizer, Ditherer, BitmapConverter (check & correction),
                              KoalaImage, HiresImage, C64Mode, Images
  palette/                    palettes, .vpl/.gpl/hex loading
  color/ColorMatcher.java     colour distance in euclidean RGB / weighted RGB / YUV / HSL
  image/ImageFitter.java      crop & scale to 320×200, 1:1 frame
  gui/                        Swing UI (previews, crop dialog, palette editor – shared with FlipperJ)
  i18n/                       English/German texts
src/test/java/...             JUnit 5 tests
packaging/                    icon (grey mule, C64 light grey on C64 blue, like the FlipperJ dolphin) as SVG, PNG and .ico;
                              launcher settings for mulej-cli
.github/workflows/            installers for Windows, macOS and Linux
```
