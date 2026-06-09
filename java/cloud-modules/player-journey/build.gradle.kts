plugins {
    id("prexorcloud.module")
}

dependencies {
    testImplementation(project(":cloud-api"))
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
}

prexorcloudModule {
    archiveName.set("player-journey")
}
