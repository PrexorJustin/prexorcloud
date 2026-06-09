plugins {
    `java-platform`
}

group = "me.prexorjustin.prexorcloud"
version = "1.0.0-SNAPSHOT"

javaPlatform {
    allowDependencies()
}

// Pin every cross-cutting third-party dependency that more than one PrexorCloud
// subproject pulls in. Subprojects depend on this platform so version drift
// between server-shared, proxy-shared, internal, controller, and modules is
// impossible: bumping a version in `libs.versions.toml` propagates everywhere.
dependencies {
    // Pull the upstream Jackson BOM so all jackson-* modules align on one version
    api(platform(libs.jackson.bom))

    constraints {
        // Jackson (jackson-bom pins versions; declaring constraints here so the
        // module set we actually use is documented in one place)
        api(libs.jackson.databind)
        api(libs.jackson.dataformat.yaml)
        api(libs.jackson.dataformat.toml)
        api(libs.jackson.datatype.jsr310)
        api(libs.jackson.module.parameter.names)

        // Logging
        api(libs.slf4j.api)
        api(libs.logback.classic)

        // Minecraft platform APIs
        api(libs.paperApi120)
        api(libs.paperApi121)
        api(libs.velocity.api)
        api(libs.bungeecord.api)
        api(libs.adventure.text.serializer.legacy)
    }
}
