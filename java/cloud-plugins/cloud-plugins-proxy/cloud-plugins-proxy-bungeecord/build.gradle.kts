plugins {
    id("prexorcloud.java21-api")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":cloud-plugins:cloud-plugins-proxy:cloud-plugins-proxy-shared"))
    implementation(libs.adventure.text.serializer.legacy)
    compileOnly(libs.bungeecord.api)
}

tasks.shadowJar {
    archiveBaseName.set("PrexorCloudBungeecordPlugin")
    archiveVersion.set("")
    archiveClassifier.set("")
}
