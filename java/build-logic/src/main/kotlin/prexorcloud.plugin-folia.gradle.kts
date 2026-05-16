// Convention plugin for Folia server-side module plugins.
// Bundles: Paper API dep (Folia uses the same artifact), cloud-api, Java 21, platform flag.

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
    options.compilerArgs.add("-Acloud.platform=folia")
}
