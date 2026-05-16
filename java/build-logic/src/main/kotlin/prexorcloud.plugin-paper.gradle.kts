// Convention plugin for Paper 1.20 server-side module plugins.
// Bundles: Paper API 1.20 dep, cloud-api compile+annotation-processor, Java 21, platform flag.
// Usage: id("prexorcloud.plugin-paper") in a module plugin build.gradle.kts.
// For Paper 1.21, use id("prexorcloud.plugin-paper-1-21") instead.

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
    options.compilerArgs.add("-Acloud.platform=paper")
}
