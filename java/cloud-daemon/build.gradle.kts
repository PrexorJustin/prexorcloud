plugins {
    id("prexorcloud.java25-preview")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(platform(project(":cloud-platform")))
    implementation(project(":cloud-api"))
    implementation(project(":cloud-common"))
    implementation(project(":cloud-protocol"))
    implementation(project(":cloud-security"))
    implementation(project(":cloud-modules:runtime"))

    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.protobuf.java)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.jackson.dataformat.toml)
    implementation(libs.logback.classic)
    implementation(libs.commons.compress)
    implementation(libs.oshi.core.java25)

    testImplementation(project(":cloud-controller"))
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.shadowJar {
    archiveBaseName = "PrexorCloudDaemon"
    archiveVersion = ""
    archiveClassifier = ""
    manifest {
        attributes("Main-Class" to "me.prexorjustin.prexorcloud.daemon.PrexorDaemon")
    }
    mergeServiceFiles()
    exclude("**/module-info.class")
    exclude("META-INF/versions/*/module-info.class")
}
