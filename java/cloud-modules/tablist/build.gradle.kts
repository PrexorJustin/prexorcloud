// ---------------------------------------------------------------------------
// cloud-module-tablist — @ForVersion intra-jar dispatch reference module.
//
// Teaches: tier-1 multi-version (one jar, runtime adapter selection) across
// MC 1.18 → 1.21 with real Adventure/Component API drift in the renderer.
//
// Pair-read with cloud-module-example (the kitchen-sink module) and the
// planned cloud-module-protocol-tap (NMS + JAR-split, the tier-2 demo).
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
    archiveName.set("tablist")

    extensionArtifacts.set(
        mapOf(
            "extensions/server/paper/tablist-paper.jar" to ":cloud-modules:tablist:plugin:paper",
            "extensions/server/folia/tablist-folia.jar" to ":cloud-modules:tablist:plugin:folia",
        ),
    )
}
