plugins {
    id("prexorcloud.java21-api")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":cloud-plugins:cloud-plugins-server:cloud-plugins-server-shared"))
    // Folia uses the Paper API — AsyncScheduler etc. are part of paper >= 1.20
    compileOnly(libs.paperApi120)
}

tasks.shadowJar {
    archiveBaseName.set("PrexorCloudFoliaPlugin")
    archiveVersion.set("")
    archiveClassifier.set("")
}
