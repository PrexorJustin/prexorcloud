// Convention plugin for vanilla-Spigot module plugins.
// Bundles: Paper API dep (Paper is API-compatible with Spigot), cloud-api,
// Java 21, platform flag.
// Usage: id("prexorcloud.plugin-spigot") in a module plugin's build.gradle.kts.
//
// Why paperApi120 and not a separate spigot-api?
//   The spigot-api jar is a strict subset of paper-api at the same MC version.
//   Compiling against paperApi120 gives module authors the same API surface
//   they'd get on a real Paper server, but the runtime plug is a Spigot
//   server. That works as long as the plugin avoids Paper-only methods at
//   call time. Module authors who need stricter compile-time enforcement
//   can swap this for `compileOnly("org.spigotmc:spigot-api:...")` in their
//   own build.gradle.kts; the convention plugin defaults to the broadest
//   API to keep new-module scaffolding simple.
//
// Sibling: prexorcloud.plugin-paper (same dep, different platform flag).

plugins {
    id("prexorcloud.java21-compat")
    id("com.gradleup.shadow")
}

val libs = versionCatalogs.named("libs")

dependencies {
    "compileOnly"(libs.findLibrary("paperApi120").get())
    "compileOnly"(project(":cloud-api"))
    "annotationProcessor"(project(":cloud-api"))
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Acloud.platform=spigot")
}
