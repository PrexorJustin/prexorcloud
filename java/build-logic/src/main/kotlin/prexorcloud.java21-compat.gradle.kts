// Java 21 base for plugin-developer-facing modules — Paper, Folia, Velocity,
// BungeeCord/Waterfall convention plugins. Modern versions of all four platforms
// support Java 21, so this is the floor for our public plugin SDK surface.
//
// Internal services (controller, daemon, modules) use prexorcloud.java25-preview.

plugins {
    id("prexorcloud.java-common")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile> {
    options.release.set(21)
}
