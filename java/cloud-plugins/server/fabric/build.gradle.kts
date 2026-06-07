plugins {
    alias(libs.plugins.fabric.loom)
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

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // Platform-agnostic controller client + metrics payload, shared with the Bukkit plugins.
    implementation(project(":cloud-plugins:server:shared"))
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
