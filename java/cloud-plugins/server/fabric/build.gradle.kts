import net.fabricmc.loom.task.RemapJarTask

plugins {
    alias(libs.plugins.fabric.loom)
    alias(libs.plugins.shadow)
}

// Fabric server integration. Unlike the Bukkit-family plugins (compileOnly against a server API
// jar), Fabric mods are built with Loom, which remaps Minecraft and produces a remapped jar.
val minecraftVersion = "1.21.1"
val yarnMappings = "1.21.1+build.3"
val loaderVersion = "0.16.10"
val fabricApiVersion = "0.116.12+1.21.1"

repositories {
    mavenCentral()
}

// server:shared + its transitive runtime closure (cloud-api, internal, Jackson, …) is shaded into
// the mod jar so it runs standalone inside Fabric. slf4j-api is excluded — fabric-loader provides it.
val bundled: Configuration by configurations.creating

configurations.implementation.get().extendsFrom(bundled)

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    bundled(project(":cloud-plugins:server:shared"))
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.shadowJar {
    configurations = listOf(bundled)
    archiveClassifier.set("dev-shadow")
    // fabric-loader provides slf4j-api + a logging binding on the mod classpath. Bundling logback
    // would drop a competing SLF4JServiceProvider into the mod jar and hijack the host's logging —
    // exclude the binding and let the cloud code (slf4j-api only) log through the loader's.
    dependencies {
        exclude(dependency("org.slf4j:slf4j-api"))
        exclude(dependency("ch.qos.logback:logback-classic"))
        exclude(dependency("ch.qos.logback:logback-core"))
    }
}

// Remap the shaded jar (not the plain one) so the published mod carries its bundled dependencies.
tasks.named<RemapJarTask>("remapJar") {
    inputFile.set(tasks.shadowJar.flatMap { it.archiveFile })
    dependsOn(tasks.shadowJar)
    archiveClassifier.set("")
}
