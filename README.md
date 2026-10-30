# MuleJ

Turns any picture into C64 graphics – **multicolor** (Koala Painter), **hires** (Art Studio) or **PETSCII** (Petmate) –
each also as runnable PRG. Successor of the command line tools **c64palettizer**, **c64pixelizer** and
**c64koalaverter** (plus c64imager), with a Swing GUI in the style of FlipperJ. The command line keeps working, and with
the default settings it produces the same results as the old tools.

## The chain

Choose the **target** first, then open a picture (or drop it onto the window). Every picture runs through the same steps;
each step has only its own settings, shows its input (left) and output (right), and its output can be saved as PNG.

| Step | Multicolor | Hires | PETSCII | What happens |
|---|---|---|---|---|
| ① Original | ✔ | ✔ | ✔ | the loaded picture next to the final result |
| ② Frame | ✔ | ✔ | ✔ | bring the picture to 320×200 (see below) |
| ③ Prepare | ✔ | ✔ | ✔ | contrast, brightness, gamma, saturation – as in FlipperJ |
| ④ Pixelizer | ✔ | – | – | two neighbouring pixels become one double-wide multicolor pixel (left→right or right→left) |
| ⑤ Colours | ✔ | ✔ | ✔ | palette, colour matching, dithering; for PETSCII the characters |
| ⑥ Result | ✔ | ✔ | ✔ | colour check, correction, saving as `.kla` / `.art` / `.petmate` and as PRG |

Every change is computed automatically from the changed step on. *Back* / *Next* or a click on a step moves between them.
Until a picture is opened, everything except the target, *Open picture* and the menus is locked.

### Starting later: edit intermediate results elsewhere

*This step as PNG…* saves the output of the shown step in the folder of the source picture – optionally enlarged
2×, 3× or 4× for comfortable editing on the PC or phone. When such a picture is loaded again, MuleJ finds out how far it
already is (**Start at: automatic**) and passes the earlier steps through unchanged:

* not 320×200 / 160×200 (or a whole-number multiple, e.g. a 2× export) → ② frame
* C64 size, free colours → ③ prepare
* only colours of a C64 palette (small deviations from JPEG or phone apps are tolerated; the palette is recognised too)
  → multicolor with unequal pixel pairs: ④ pixelizer, PETSCII: ⑤ characters, otherwise ⑥ colour check
  (colours are then only snapped to the palette – no dithering, no automatic correction)

The start can also be chosen by hand (*Start at*), and every skipped step offers *Use this step*. Koala and Art Studio files
can be opened as well.

### ② Frame

The step starts with the **largest centered frame in the C64 aspect ratio** (PAL, ≈1.50:1, as on a real monitor):

* **Larger pictures** are scaled down into that frame; drag it, resize it at the corner or draw a new one.
* **Smaller pictures** are scaled up along their shorter side, so the frame is filled completely (no border).
  Pictures that fit 320×200 or 160×200 by a whole-number factor (e.g. 160×100, 640×400) are taken completely and
  scaled exactly with nearest neighbour, so pixel art stays crisp.
* **320×200 frame (1:1)** – exactly 320×200 picture pixels without scaling; it can only be moved. Pictures smaller than
  320×200 get the border colour around them.
* Mouse wheel zooms around the pointer, right or middle drag pans, double click shows the whole picture.
* Scaling stretch / fit (letterbox) / fill (crop), aspect PAL / square pixels / NTSC / free, four filters, border colour.

### ⑤ Colours – multicolor and hires

Palettes Colodore (default, as before), Pepto, DeeKay, PALette, Community Colors, Ptoing, C64HQ, CCS64, C64S, VICE, or
custom ones (colour editor, `.vpl`/`.gpl`/hex files); matching in plain euclidean RGB (default, as before), weighted RGB,
YUV or HSL. Dithering: Floyd-Steinberg, Atkinson, Jarvis-Judice-Ninke, Stucki, Sierra Lite, Bayer 2×2/4×4/8×8 with
strength, serpentine and bleeding. With dithering every cell first gets the colours that reproduce it best, then the
picture is dithered with exactly those colours – the result always keeps the colour rules. Without dithering,
*Correct automatically* fixes cells with too many colours.

### ⑤ Colours – PETSCII

The text screen has 40×25 cells; every cell shows one character of the C64 character set in one colour on a background
colour shared by the whole screen (`$D021`). MuleJ finds the combination that looks most like the original:

* For every cell **all characters × all 15 foreground colours** are rated, for **all 16 background colours**
  (the background with the smallest total error wins, or a fixed one).
* Each candidate is rated twice and the two are mixed (*Detail*): **pixel by pixel** – characters follow edges and lines –
  and **blurred** (binomial filter), which rates brightness and colour the way the eye sees a cell from a distance, so
  checkerboards and half blocks stand in for in-between tones.
* Colours are compared in a linear colour space, so the error of a character can be written as `|A|² − 2·D·S + |D|²·Q`
  (`D` = foreground − background): per cell only a few numbers per character are needed, and the roughly 60 million
  combinations take well under a second.
* The remaining average error of a cell is passed on to its neighbours (*Diffusion*, Floyd-Steinberg on cell level), so
  areas and gradients keep their tone. *Calm* keeps busy characters away unless they clearly help, and by default only
  graphics characters are used (letters and digits can be switched on).
* Upper case + graphics or lower + upper case character set.

The character set data is the C64 character ROM as shipped with Petmate, so MuleJ and Petmate show the same glyphs.

### ⑥ Result

* **Colour check** (multicolor/hires): “✔ all 1000 cells keep the rules” or “✘ 37 cells break the multicolor rules”;
  offending cells are marked red, ◀ ▶ jumps to the previous/next conflict and zooms in.
* **Manual correction** by clicking into the result: *Select cell* shows its colours with pixel counts – press the
  colours the cell may use and *Apply colours*, or *Automatic* for the best colours of this cell; *Paint* sets single
  pixels; *Reset cell* / *Discard all* undo. Corrected cells are marked yellow, the selected cell cyan.
* PETSCII always keeps its rules (one character colour plus background per cell); to edit further, save as Petmate file.
* **Save Koala / Art Studio / Petmate…** and **Save PRG…** (only once the colour check has passed) start in the folder of
  the source picture. The PRG shows the picture after `LOAD"NAME",8` and `RUN`; any key returns to BASIC.

English and German user interface (*Language* menu, English by default, no restart needed).

## Command line

Without arguments the GUI starts, with arguments the command line runs (`mulej-cli` / `de.mulej.Cli` never starts the GUI).

```
mulej palettize image.png                       # -> image_palettized.png
mulej palettize photo.jpg --fit fill --dither fs
mulej pixelize image.png                        # -> image_pixelized_l2r.png + image_pixelized_r2l.png
mulej koala image.png                           # -> image.kla
mulej koala photo.jpg --fit fill --space yuv --dither fs --preview check.png --prg photo.prg
mulej hires image.png --fix-clashes             # -> image.art
mulej petscii photo.jpg --fit fill --contrast 20 --prg photo.prg   # -> photo.petmate + photo.prg
mulej check pixelart.png --mode hires --no-palettize   # list every cell that breaks the rules
mulej topng image.kla                           # -> image.png (any C64 picture or .petmate)
mulej palettize -if image.png                   # the old -if syntax still works
mulej --help
```

Exit codes as before: 0 = OK, 1 = wrong parameters, 2 = conversion error (`check`: 2 = rules broken).
`--contrast`, `--brightness`, `--gamma`, `--saturation` prepare the picture; PETSCII: `--charset upper|lower`, `--text`,
`--detail`, `--diffusion`, `--calm`, `--bg`. `mulej --help` lists everything.
The command line still offers `avg` (average of a pixel pair) for `--dir` and `--pair`; the GUI only offers left→right and right→left.

### PRG memory map

| Address | Content |
|---|---|
| `$0801` | BASIC line `10 SYS2061` |
| `$080D` | viewer code (sets VIC bank 0 and the mode, copies colour RAM, waits for a key, restores the text screen) |
| `$0C00–$0FE7` | screen RAM (bitmaps: the VIC reads it there directly; PETSCII: screen codes, copied to `$0400`) |
| `$1000–$13E7` | colour RAM data (multicolor, PETSCII), copied to `$D800` |
| `$13E8` / `$13E9` | background / border colour |
| `$2000–$3F3F` | bitmap |

The viewer was checked in a 6502 simulator (what the VIC would show is identical to the `.kla`/`.art`/`.petmate` data),
not yet in VICE or on real hardware.

## Using MuleJ (for users)

Download from the GitHub **Releases** page:

| System | File | Start |
|---|---|---|
| Windows | `MuleJ-x.y.z-windows-x64.msi` | Install, then *Start menu ▸ MuleJ*. Command line: `"C:\Program Files\MuleJ\mulej-cli.exe" --help` |
| Windows (no installation) | `MuleJ-x.y.z-windows-x64-portable.zip` | Unpack, run `MuleJ\MuleJ.exe` |
| Windows 32-bit | `MuleJ-x.y.z-windows-x86.msi` / `-windows-x86-portable.zip` | as above |
| macOS (Apple Silicon) | `MuleJ-x.y.z-macos-arm64.dmg` | Drag MuleJ into *Applications*. The app is not signed: the first time right click ▸ *Open*. Command line: `/Applications/MuleJ.app/Contents/MacOS/mulej-cli` |
| macOS (Intel) | `MuleJ-x.y.z-macos-x64.dmg` | as above |
| Linux (Debian/Ubuntu) | `MuleJ-x.y.z-linux-x64.deb` | `sudo apt install ./MuleJ-x.y.z-linux-x64.deb`, then the menu entry *MuleJ* or `/opt/mulej/bin/MuleJ`; command line `/opt/mulej/bin/mulej-cli` |
| Linux (others, no installation) | `MuleJ-x.y.z-linux-x64-portable.tar.gz` | Unpack, run `MuleJ/bin/MuleJ` |
| Any system with Java 17+ | `MuleJ-x.y.z-all-platforms-java17.zip` | Unpack, run `bin/mulej` (Linux, macOS) or `bin\mulej.bat` (Windows); with arguments it runs the command line |
| Any system with Java 17+ | `MuleJ-x.y.z-all-platforms-java17.jar` | `java -jar MuleJ-x.y.z-all-platforms-java17.jar` (GUI) or `… koala bild.png` (command line) |

The installers and portable versions contain their own Java runtime – nothing else needs to be installed.

## Releasing a new version (for the developer)

1. `git tag v1.0.0 && git push origin v1.0.0` – the tag sets the version everywhere (file names, installers,
   title bar); `build.gradle.kts` only holds the version for local builds.
2. GitHub Actions (`.github/workflows/release.yml`) builds and tests on Windows, macOS and Linux and publishes a release
   with all files above. *Actions ▸ Build & Release ▸ Run workflow* builds the same files without a release.

**Development build:** every push to `main` that changes code or build files replaces the pre-release
*Development build* (tag `latest-dev`) on the Releases page with fresh packages (`…-dev-…` in the file names).
Changes to the README or other documents build nothing.

Note: jpackage on macOS does not accept versions starting with 0, so `0.x.y` becomes `1.x.y` in the `.dmg` only.
Use tags from `v1.0.0` on to keep the versions identical everywhere.

## Shared library

Palettes, colour matching, dithering, Koala/Art Studio files, the 6502 assembler, the image panel, file dialogs,
crop & scale, translations of shared texts and more come from [C64GfxLib](https://github.com/nanzee73/C64GfxLib)
(also used by FlipperJ), via [JitPack](https://jitpack.io/#nanzee73/C64GfxLib) – no account or token needed.
To work on the library at the same time, check it out next to MuleJ and build with `-Pc64gfxLocal`.

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

**Input:** every picture Java ImageIO reads (PNG, JPEG, GIF, BMP, WebP, TIFF, …) and C64 pictures from C64GfxLib:
Koala Painter (`.kla .koa .gig`), Art Studio (`.art .aas .hpi`), Advanced Art Studio (`.ocp .mpic`),
Drazpaint (`.drz .drp`), Drazlace (`.drl .dlp`), FLI / FLI Designer / FLI Graph / FLI Editor (`.fli .fd2 .bml .flg .fed`),
AFLI (`.afl`), Gunpaint (`.gun .ifl`), Funpaint (`.fun .fp2`) and FlipperJ's raw files – also compressed.
They are drawn with the selected palette; interlace pictures are mixed like on screen.

**Koala Painter** (`.kla`): `$6000` load address, 8000 bytes bitmap (cell by cell), 1000 bytes screen RAM,
1000 bytes colour RAM, 1 byte background. Bit pairs: `00` background, `01` screen RAM high nibble, `10` screen RAM low
nibble, `11` colour RAM. Cell colours are assigned in ascending colour index order, exactly like c64koalaverter –
the old tools and MuleJ were compared on all pictures in their `ressources` folders: all 125 outputs are identical.

**OCP Art Studio** (`.art`): `$2000` load address, 8000 bytes bitmap, 1000 bytes screen RAM (high nibble = set bits,
low nibble = cleared bits), 7 bytes filler (the first is used as border colour by some programs) – 9009 bytes.

**Petmate** (`.petmate`): Petmate workspace, version 2, one 40×25 screen with `code` (screen code) and `color` per cell,
`backgroundColor`, `borderColor` and `charset` (`upper` / `lower`).

## License

MuleJ may be used, copied, changed and passed on for **noncommercial purposes only**, under the
[PolyForm Noncommercial License 1.0.0](LICENSE). For commercial use ask nanzee73 for permission.
Parts by others keep their own licenses, see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
The old tools c64palettizer, c64pixelizer, c64koalaverter and c64helpers stay under the MIT License.

## Project layout

```
src/main/java/de/mulej/
  App.java, Cli.java          entry point, command line
  core/                       Pipeline (the chain, automatic start), Palettizer, Pixelizer,
                              BitmapConverter (check & correction), PetsciiConverter, PetsciiImage (Petmate),
                              Charset, C64Mode, ViewerPrg (6502 viewer), Images (reading/writing files)
  gui/                        Swing UI: MainWindow (target, steps, previews), CropEditor
src/main/resources/de/mulej/i18n/   English/German texts (shared texts come from C64GfxLib)
src/test/java/...             JUnit 5 tests
packaging/                    icon (grey mule, C64 light grey on C64 blue, like the FlipperJ dolphin) as SVG, PNG and .ico;
                              launcher settings for mulej-cli
.github/workflows/            installers for Windows, macOS and Linux
```
