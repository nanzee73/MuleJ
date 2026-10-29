plugins {
    application
}

group = "de.mulej"
version = "0.1.0"

repositories {
    mavenCentral()
}

val twelveMonkeys = "3.12.0"

dependencies {
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

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
}
