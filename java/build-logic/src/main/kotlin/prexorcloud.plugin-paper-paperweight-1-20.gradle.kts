// Convention plugin for Paper 1.20 module plugins that need real NMS access
// (Mojang-mapped net.minecraft.* imports) via paperweight-userdev.
//
// Usage: id("prexorcloud.plugin-paper-paperweight-1-20") in a module plugin's
// build.gradle.kts. Applies the paperweight-userdev gradle plugin and pins
// the 1.20.4 paper dev bundle.
//
// First-time setup: paperweight-userdev downloads Paper internals on the
// first build. Allow it network access (https://maven.papermc.io/, etc).
//
// Sibling: prexorcloud.plugin-paper-paperweight-1-21.

plugins {
    id("prexorcloud.java21-compat")
    id("com.gradleup.shadow")
    id("io.papermc.paperweight.userdev")
}

val libs = versionCatalogs.named("libs")

dependencies {
    "compileOnly"(project(":cloud-api"))
    "annotationProcessor"(project(":cloud-api"))
    // 1.20.4 dev bundle — provides Mojang-mapped net.minecraft.* on the
    // compile classpath, and reobf at JAR-time for runtime dispatch.
    "paperweightDevBundle"("io.papermc.paper:dev-bundle:1.20.4-R0.1-SNAPSHOT")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Acloud.platform=paper")
}
