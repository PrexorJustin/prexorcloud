plugins {
    id("prexorcloud.java21-api")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":cloud-plugins:server:shared"))
    compileOnly(libs.paperApi120)
}

tasks.shadowJar {
    archiveBaseName.set("PrexorCloudPaperPlugin")
    archiveVersion.set("")
    archiveClassifier.set("")

    // Relocate shaded Jackson to avoid classpath conflicts with the server's own Jackson
    relocate("com.fasterxml.jackson", "me.prexorjustin.prexorcloud.libs.jackson")

    // Exclude duplicate META-INF files from dependencies and keep only this module's plugin.yml
    exclude("META-INF/LICENSE")
    exclude("META-INF/NOTICE")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
