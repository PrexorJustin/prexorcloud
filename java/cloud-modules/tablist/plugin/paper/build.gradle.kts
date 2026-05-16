// Paper variant of the tablist module.
//
// Single subproject with @ForVersion adapters covering MC 1.18 → 1.21. The
// plugin compiles against paperApi120 (the convention plugin's default); the
// adapters claim narrower runtime ranges via @ForVersion and dispatch at
// onEnable time via VersionDispatcher.
//
// Jackson is bundled (and relocated) so the plugin can parse the controller's
// JSON response. Real-world tablist modules typically need this — adding
// Jackson + relocation is the canonical pattern for any module plugin that
// makes HTTP calls back to its own backend.
plugins {
    id("prexorcloud.plugin-paper")
}

dependencies {
    implementation(libs.jackson.databind)
    implementation(platform(libs.jackson.bom))
}

tasks.shadowJar {
    archiveBaseName.set("tablist-paper")

    // Paper bundles its own Jackson; relocate to avoid loader conflicts.
    relocate("com.fasterxml.jackson", "me.prexorjustin.prexorcloud.modules.tablist.libs.jackson")
}
