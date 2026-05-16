plugins {
    id("prexorcloud.java-common")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--enable-preview"))
}

tasks.withType<Test> {
    jvmArgs(
        "--enable-preview",
        "-Dnet.bytebuddy.experimental=true",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
    )
}

// JaCoCo report generation does not yet fully support Java 25 preview
// bytecode. The agent still collects execution data (.exec files) during
// tests. We configure classDirectories to exclude preview-generated
// classes that cause parse errors, and let the report cover everything else.
tasks.withType<JacocoReport> {
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("classes/java/main")) {
            // Exclude generated/synthetic classes that trip up JaCoCo on Java 25
            exclude("**/module-info.class")
        }
    )
}
