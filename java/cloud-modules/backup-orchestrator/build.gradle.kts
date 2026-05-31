plugins {
    id("prexorcloud.module")
}

prexorcloudModule {
    archiveName.set("backup-orchestrator")
}

dependencies {
    // commons-compress writes the tar.gz archives the snapshot service
    // produces. Bundled into the module's shadowJar so the controller's
    // platform-module classloader resolves it without leaking the
    // dependency into the host classpath.
    implementation(libs.commons.compress)

    testImplementation(project(":cloud-api"))
    testImplementation(libs.mockito.junit.jupiter)
}
