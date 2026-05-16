// Convention plugin for BungeeCord/Waterfall proxy-side module plugins.
// Bundles: BungeeCord API dep, cloud-api, Java 21, platform flag.

plugins {
    id("prexorcloud.java21-compat")
    id("com.gradleup.shadow")
}

val libs = versionCatalogs.named("libs")

dependencies {
    "compileOnly"(libs.findLibrary("bungeecord-api").get())
    "compileOnly"(project(":cloud-api"))
    "annotationProcessor"(project(":cloud-api"))
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Acloud.platform=bungeecord")
}
