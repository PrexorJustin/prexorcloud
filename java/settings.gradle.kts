pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        // Fabric Loom (cloud-plugins:server:fabric) is published to the FabricMC maven, not the portal.
        maven("https://maven.fabricmc.net/")
    }
    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "prexorcloud"

include(
    "cloud-common",
    "cloud-platform",
    "cloud-protocol",
    "cloud-security",
    "cloud-controller",
    "cloud-daemon",
    "cloud-test-harness",

    // Unified public API (Java 21) — single dependency for plugin and module developers
    "cloud-api",

    // ---- MODULES ---- //
    "cloud-modules:runtime",
    "cloud-modules:example",
    "cloud-modules:stats-aggregator",
    "cloud-modules:player-journey",
    "cloud-modules:webhook-alerts",
    "cloud-modules:discord-bridge",
    "cloud-modules:backup-orchestrator",
    "cloud-modules:tablist",
    "cloud-modules:protocol-tap",
    "cloud-modules:example:plugin:paper",
    "cloud-modules:example:plugin:folia",
    "cloud-modules:example:plugin:velocity",
    "cloud-modules:example:plugin:bedrock-geyser",
    "cloud-modules:tablist:plugin:paper",
    "cloud-modules:tablist:plugin:folia",
    "cloud-modules:protocol-tap:plugin:shared",
    "cloud-modules:protocol-tap:plugin:paper:v1_20",
    "cloud-modules:protocol-tap:plugin:paper:v1_21",
    "cloud-modules:protocol-tap:plugin:folia",

    // ---- PLATFORM PLUGINS ---- //
    "cloud-plugins:internal",
    "cloud-plugins:proxy:shared",
    "cloud-plugins:proxy:velocity",
    "cloud-plugins:proxy:bungeecord",
    "cloud-plugins:proxy:geyser",
    "cloud-plugins:server:shared",
    "cloud-plugins:server:spigot",
    "cloud-plugins:server:paper",
    "cloud-plugins:server:folia",
    "cloud-plugins:server:fabric",
    "cloud-plugins:server:neoforge",

    // ---- TEST FIXTURES ---- //
    "test-fixtures:test-daemon-module",
)
