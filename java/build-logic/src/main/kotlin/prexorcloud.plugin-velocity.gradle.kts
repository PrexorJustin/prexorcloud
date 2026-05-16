// Convention plugin for Velocity proxy-side module plugins.
// Bundles: Velocity API (compile + annotation-processor), cloud-api, Java 21, platform flag.

plugins {
    id("prexorcloud.java21-compat")
    id("com.gradleup.shadow")
}

val libs = versionCatalogs.named("libs")

dependencies {
    "compileOnly"(libs.findLibrary("velocity-api").get())
    "annotationProcessor"(libs.findLibrary("velocity-api").get())
    "compileOnly"(project(":cloud-api"))
    "annotationProcessor"(project(":cloud-api"))
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Acloud.platform=velocity")
}
