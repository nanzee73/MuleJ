rootProject.name = "MuleJ"

// Work on C64GfxLib and MuleJ at the same time: put both repositories next to each other and build with
// -Pc64gfxLocal (or c64gfxLocal=true in gradle.properties). Gradle then uses ../C64GfxLib instead of the package.
if (providers.gradleProperty("c64gfxLocal").isPresent && file("../C64GfxLib").isDirectory) {
    includeBuild("../C64GfxLib")
}
