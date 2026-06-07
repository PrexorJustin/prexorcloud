plugins {
    id("prexorcloud.module")
}

dependencies {
    testImplementation(project(":cloud-api"))
}

prexorcloudModule {
    archiveName.set("discord-bridge")
}
