plugins {
    id("prexorcloud.java21-api")
}

dependencies {
    implementation(platform(project(":cloud-platform")))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.jackson.module.parameter.names)
    implementation(libs.logback.classic)
}

val generateVersionProperties by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated-resources/version")
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        val propsFile = dir.resolve("version.properties")
        val gitCommit = try {
            providers.exec {
                commandLine("git", "rev-parse", "--short", "HEAD")
                isIgnoreExitValue = true
            }.standardOutput.asText.get().trim()
        } catch (_: Exception) {
            "unknown"
        }
        propsFile.writeText(
            "version=${project.version}\ngit.commit=$gitCommit\n"
        )
    }
}

tasks.processResources {
    from(generateVersionProperties)
}
