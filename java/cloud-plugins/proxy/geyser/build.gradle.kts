plugins {
    id("prexorcloud.java21-api")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":cloud-plugins:proxy:shared"))
    compileOnly(libs.geyser.api)
}

tasks.shadowJar {
    archiveBaseName.set("PrexorCloudGeyserExtension")
    archiveVersion.set("")
    archiveClassifier.set("")
}
