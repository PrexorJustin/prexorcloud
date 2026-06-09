plugins {
    id("prexorcloud.java21-api")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":cloud-plugins:server:shared"))
    compileOnly(libs.paperApi120)
}

tasks.shadowJar {
    archiveBaseName.set("PrexorCloudSpigotPlugin")
    archiveVersion.set("")
    archiveClassifier.set("")

    // Relocate shaded Jackson to avoid classpath conflicts with server's own Jackson
    relocate("com.fasterxml.jackson", "me.prexorjustin.prexorcloud.libs.jackson")
}
