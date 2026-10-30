import org.gradle.internal.os.OperatingSystem

plugins {
    application
    // single runnable jar with all dependencies (merges the ImageIO plugin registrations)
    id("com.gradleup.shadow") version "8.3.5"
}

group = "de.mulej"
version = "0.1.0"

repositories {
    mavenCentral()
    // C64GfxLib (shared code with FlipperJ), built by JitPack from the public repository – no login needed.
    // To work on the library at the same time, see settings.gradle.kts (-Pc64gfxLocal).
    maven("https://jitpack.io") { content { includeGroupByRegex("com\\.github\\.nanzee73.*") } }
}

val c64gfxVersion = "0.1.0"

val twelveMonkeys = "3.12.0"

dependencies {
    implementation("com.github.nanzee73.C64GfxLib:c64gfx-swing:$c64gfxVersion")   // includes c64gfx-core

    // Extra ImageIO readers (registered automatically via SPI): WebP, TIFF, PSD, TGA, PCX, PNM, IFF/ILBM,
    // SGI, PICT, ICNS and an improved BMP/JPEG reader. Only needed at runtime.
    listOf("webp", "tiff", "psd", "tga", "pcx", "pnm", "iff", "sgi", "pict", "icns", "bmp", "jpeg").forEach {
        runtimeOnly("com.twelvemonkeys.imageio:imageio-$it:$twelveMonkeys")
    }

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}

application {
    mainClass.set("de.mulej.App")
    applicationName = "mulej"
}

tasks.jar {
    manifest { attributes("Main-Class" to "de.mulej.App", "Implementation-Version" to project.version) }
}

// build/libs/mulej-<version>-all.jar – start with: java -jar mulej-<version>-all.jar
tasks.shadowJar {
    archiveBaseName.set("mulej")
    archiveClassifier.set("all")
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
}

// ---------------------------------------------------------------------------------------------------------------
// Native installers with jpackage (JDK 17+), always for the operating system Gradle runs on.
// The installer contains its own Java runtime, so users do not need Java.
//
//   ./gradlew jpackage                              Windows: .msi (needs WiX), macOS: .dmg, Linux: .deb
//   ./gradlew jpackage -PinstallerType=app-image    portable folder without installation
//   ./gradlew jpackage -PinstallerType=rpm          other jpackage types: exe, pkg, rpm
//   -PappVersion=1.2.3                              version shown by the installer (default: project version)
//
// Two launchers are installed: "MuleJ" (GUI; with arguments it runs the command line) and "mulej-cli"
// (command line only, with a console window on Windows).
// ---------------------------------------------------------------------------------------------------------------
val jpackage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds a native installer for this operating system (includes a Java runtime)."
    dependsOn(tasks.installDist)

    val os = OperatingSystem.current()
    val type = providers.gradleProperty("installerType").orNull
        ?: when { os.isWindows -> "msi"; os.isMacOsX -> "dmg"; else -> "deb" }
    var appVersion = providers.gradleProperty("appVersion").orNull ?: project.version.toString()
    // jpackage on macOS refuses versions starting with 0
    if (os.isMacOsX && appVersion.startsWith("0")) appVersion = "1" + appVersion.substring(1)
    val icon = when {
        os.isWindows -> "packaging/mulej.ico"
        os.isMacOsX -> "build/mulej.icns"            // made by the macosIcon task
        else -> "packaging/mulej.png"
    }
    val dest = layout.buildDirectory.dir("installer").get().asFile
    val lib = layout.buildDirectory.dir("install/mulej/lib").get().asFile

    if (os.isMacOsX) dependsOn("macosIcon")
    doFirst { dest.mkdirs() }

    val args = mutableListOf(
        "jpackage", "--type", type, "--name", "MuleJ", "--app-version", appVersion,
        "--vendor", "nanzee73", "--description", "C64 graphics tools: palettize, pixelize, Koala/Art Studio with colour check",
        "--copyright", "Copyright (c) 2024-2026 nanzee73, MIT License",
        "--input", lib.path, "--main-jar", "mulej-${project.version}.jar", "--main-class", "de.mulej.App",
        "--dest", dest.path, "--icon", icon,
        "--add-launcher", "mulej-cli=packaging/cli-launcher.properties",
        "--java-options", "-Xmx1g",
        // a small runtime: only the modules MuleJ needs
        "--add-modules", "java.desktop,java.prefs,java.xml,java.logging,jdk.unsupported",
        "--jlink-options", "--strip-debug --no-header-files --no-man-pages --strip-native-commands",
    )
    if (type != "app-image") args += listOf("--license-file", "LICENSE")
    when {
        os.isWindows -> if (type != "app-image") args += listOf(
            "--win-menu", "--win-menu-group", "MuleJ", "--win-shortcut", "--win-dir-chooser",
            "--win-upgrade-uuid", "0d6b8c4e-7a43-4c1f-9d47-3b8a2f6e5c11")
        os.isMacOsX -> args += listOf("--mac-package-name", "MuleJ")
        else -> if (type != "app-image") args += listOf(
            "--linux-package-name", "mulej", "--linux-shortcut", "--linux-menu-group", "Graphics",
            "--linux-app-category", "graphics")
    }
    commandLine(args)
}

// macOS needs an .icns icon; iconutil only exists on macOS
tasks.register<Exec>("macosIcon") {
    val set = layout.buildDirectory.dir("mulej.iconset").get().asFile
    val icons = "src/main/resources/de/mulej/icons"
    doFirst {
        set.mkdirs()
        listOf(16 to "16x16", 32 to "16x16@2x", 32 to "32x32", 64 to "32x32@2x", 128 to "128x128",
            256 to "128x128@2x", 256 to "256x256", 512 to "256x256@2x", 512 to "512x512").forEach { (size, name) ->
            file("$icons/mulej-$size.png").copyTo(File(set, "icon_$name.png"), overwrite = true)
        }
    }
    commandLine("iconutil", "-c", "icns", set.path, "-o", layout.buildDirectory.file("mulej.icns").get().asFile.path)
}
