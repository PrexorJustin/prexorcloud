// Folia variant of the tablist module.
//
// Same @ForVersion adapters as the Paper sibling — header/footer rendering
// is global-scheduler-safe so no Folia-specific threading concerns. The
// teaching point: tier-1 (@ForVersion) works identically on Folia and Paper;
// the convention plugin choice + cloud.platform flag are the only difference.
plugins {
    id("prexorcloud.plugin-folia")
}

dependencies {
    implementation(libs.jackson.databind)
    implementation(platform(libs.jackson.bom))
}

tasks.shadowJar {
    archiveBaseName.set("tablist-folia")

    relocate("com.fasterxml.jackson", "me.prexorjustin.prexorcloud.modules.tablist.libs.jackson")
}
