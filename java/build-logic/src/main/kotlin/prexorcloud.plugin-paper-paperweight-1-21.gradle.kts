// Convention plugin for Paper 1.21 module plugins that need real NMS access
// (Mojang-mapped net.minecraft.* imports) via paperweight-userdev.
//
// Usage: id("prexorcloud.plugin-paper-paperweight-1-21") in a module plugin's
// build.gradle.kts. Sibling of prexorcloud.plugin-paper-paperweight-1-20.

plugins {
    id("prexorcloud.java21-compat")
    id("com.gradleup.shadow")
    id("io.papermc.paperweight.userdev")
}

val libs = versionCatalogs.named("libs")

dependencies {
    "compileOnly"(project(":cloud-api"))
    "annotationProcessor"(project(":cloud-api"))
    "paperweightDevBundle"("io.papermc.paper:dev-bundle:1.21.4-R0.1-SNAPSHOT")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Acloud.platform=paper")
}
