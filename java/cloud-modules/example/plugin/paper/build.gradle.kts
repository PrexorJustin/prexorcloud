// Paper variant of the example-playtime module.
//
// Single-version reference plugin compiled against `paperApi120` (Paper 1.20.4).
// Shows the simplest plugin shape: one Gradle subproject, one jar packaged at
// META-INF/plugins/server/paper/ inside the module JAR. ModulePluginManager
// extracts it onto any Paper instance the module is attached to, regardless of
// MC version (the manifest extension entry uses mcVersionRange: "*").
//
// For real multi-version stories, see:
//   - cloud-module-example/plugin/folia: @ForVersion intra-jar dispatch
//   - (planned) cloud-module-tablist:    @ForVersion across MC 1.18 → 1.21
//   - (planned) cloud-module-protocol-tap: JAR-split + paperweight-userdev/NMS
//
// Choosing JAR-split inside example-playtime would have been theatre — the
// plugin doesn't touch any API that drifts between Paper versions.
plugins {
    id("prexorcloud.plugin-paper")
}

tasks.shadowJar {
    archiveBaseName.set("example-playtime-paper")
}
