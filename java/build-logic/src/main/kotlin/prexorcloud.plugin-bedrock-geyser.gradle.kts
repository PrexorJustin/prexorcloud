plugins {
    id("prexorcloud.java21-compat")
    id("com.gradleup.shadow")
}

val libs = versionCatalogs.named("libs")

dependencies {
    "compileOnly"(libs.findLibrary("geyser-api").get())
    "annotationProcessor"(libs.findLibrary("geyser-api").get())
    "compileOnly"(project(":cloud-api"))
    "annotationProcessor"(project(":cloud-api"))
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Acloud.platform=bedrock-geyser")
}
