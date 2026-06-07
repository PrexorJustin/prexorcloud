// NeoForge server integration (Minecraft 1.21.1). Built with ModDevGradle, which compiles against
// NeoForge using Mojang's official mappings — the same names the runtime uses, so (unlike Fabric's
// Loom) there is no remap step: the shaded jar is the distributable mod.
plugins {
    alias(libs.plugins.neoforge.moddev)
    alias(libs.plugins.shadow)
}

// server:shared + its transitive runtime closure (cloud-api, internal, Jackson, …) is shaded into
// the mod jar so it runs standalone inside NeoForge. slf4j-api is excluded — NeoForge provides it.
val bundled: Configuration by configurations.creating

configurations.implementation.get().extendsFrom(bundled)

repositories {
    mavenCentral()
}

dependencies {
    // NeoForge provides slf4j-api at runtime and strictly pins 2.0.9; drop our transitive 2.0.17 so
    // there is no version conflict and nothing competes with the host binding.
    bundled(project(":cloud-plugins:server:shared")) {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
}

neoForge {
    version = libs.versions.neoforge.get()

    mods {
        register("prexorcloud") { sourceSet(sourceSets.main.get()) }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// Shade only the bundled closure (NOT the NeoForge/Minecraft runtime classpath) into the mod jar.
tasks.shadowJar {
    configurations = listOf(bundled)
    archiveBaseName.set("PrexorCloudNeoForge")
    archiveVersion.set("")
    archiveClassifier.set("")
    // NeoForge provides slf4j-api + a logging binding. Bundling logback would drop a competing
    // SLF4JServiceProvider into the mod jar and hijack the host's logging — exclude the binding and
    // let the cloud code (slf4j-api only) log through NeoForge's.
    dependencies {
        exclude(dependency("org.slf4j:slf4j-api"))
        exclude(dependency("ch.qos.logback:logback-classic"))
        exclude(dependency("ch.qos.logback:logback-core"))
    }
}

tasks.named("assemble") { dependsOn(tasks.shadowJar) }
