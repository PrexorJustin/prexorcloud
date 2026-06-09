plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// ── Pull versions from the root version catalog (libs.versions.toml) ──
// Gradle's built-in version catalog isn't available to a build-logic
// build script's `dependencies { }` block (the catalog only resolves
// inside the projects that consume it). To stay DRY we parse the TOML
// directly and read each `[versions]` entry. This means a bump in
// `gradle/libs.versions.toml` propagates here without manual edits.
//
// If the catalog format changes drastically, the regex below will fail
// loudly — that's preferable to silent drift between consumers and
// producers of these tool versions.
val catalogFile = rootDir.resolve("../gradle/libs.versions.toml")
val versions: Map<String, String> = run {
    require(catalogFile.exists()) { "expected version catalog at $catalogFile" }
    val versionLine = Regex("""^([\w-]+)\s*=\s*"([^"]+)"\s*$""")
    var inVersionsBlock = false
    val map = mutableMapOf<String, String>()
    catalogFile.forEachLine { raw ->
        val line = raw.trim()
        if (line.startsWith("#") || line.isEmpty()) return@forEachLine
        if (line.startsWith("[")) {
            inVersionsBlock = (line == "[versions]")
            return@forEachLine
        }
        if (!inVersionsBlock) return@forEachLine
        versionLine.matchEntire(line)?.let { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            map[key] = value
        }
    }
    map
}

fun catalogVersion(key: String): String =
    versions[key] ?: error("version catalog missing key '$key' (looked in $catalogFile)")

dependencies {
    val jackson = catalogVersion("jackson")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jackson")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jackson")

    // Spotless and Shadow are Gradle plugins consumed via plugin coords.
    // Both are sourced from the version catalog so a single bump in
    // libs.versions.toml propagates to both consumers + this build-logic.
    implementation("com.diffplug.spotless:spotless-plugin-gradle:${catalogVersion("spotless")}")
    implementation("com.gradleup.shadow:shadow-gradle-plugin:${catalogVersion("shadow")}")

    // paperweight-userdev — used by the prexorcloud.plugin-paper-paperweight-*
    // convention plugins. Only modules that opt in pay the cost of
    // paperweight's first-build dev-bundle download. Pinned here because
    // the catalog doesn't yet ship a `paperweight` key — add one when the
    // version is bumped.
    implementation("io.papermc.paperweight.userdev:io.papermc.paperweight.userdev.gradle.plugin:2.0.0-beta.18")
}
