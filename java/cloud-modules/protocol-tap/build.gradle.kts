// ---------------------------------------------------------------------------
// cloud-module-protocol-tap — JAR-split (tier-2) multi-version reference.
//
// Teaches: per-MC-version Gradle subprojects, separate JARs at
// META-INF/plugins/server/paper/{1.20,1.21}/, mcVersionRange-based runtime
// selection by ModulePluginManager. Each subproject pins its own paper-api
// version (paperApi120 vs paperApi121) so API drift surfaces at compile time
// in the right place.
//
// Why tier-2 instead of @ForVersion?
//   When the version drift involves classes/methods that don't even exist on
//   the other version's classpath, intra-jar dispatch can't compile cleanly —
//   the "other" class trips a NoSuchMethodError at load time on a cold class.
//   Each subproject having its own world avoids that entirely. The canonical
//   trigger: NMS access via paperweight-userdev + Mojang mappings, where
//   class names like ServerGamePacketListenerImpl differ between MC versions.
//
// This reference module demonstrates the **JAR-split structure** with stable
// paper-api on each side. The paperweight-userdev step (real NMS) lands as
// Phase E of the master plan: each subproject's build.gradle.kts gets the
// `id("io.papermc.paperweight.userdev")` plugin, the convention plugin
// gains `paperweight.paperApi(...)`, and the source code can import
// `net.minecraft.*` mappings.
// ---------------------------------------------------------------------------
plugins {
    id("prexorcloud.module")
}

dependencies {
    testImplementation(project(":cloud-api"))
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
}

prexorcloudModule {
    archiveName.set("protocol-tap")

    extensionArtifacts.set(
        mapOf(
            "extensions/server/paper/1.20/protocol-tap-paper-v1_20.jar" to ":cloud-modules:protocol-tap:plugin:paper:v1_20",
            "extensions/server/paper/1.21/protocol-tap-paper-v1_21.jar" to ":cloud-modules:protocol-tap:plugin:paper:v1_21",
            "extensions/server/folia/protocol-tap-folia.jar" to ":cloud-modules:protocol-tap:plugin:folia",
        ),
    )
}
