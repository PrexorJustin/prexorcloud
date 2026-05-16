// Folia-side Minecraft plugin for the example-playtime module.
//
// Folia uses region-based threading: scheduler calls go through
// GlobalRegionScheduler via CloudPluginContext.scheduler(), which is already
// Folia-safe. @ForVersion nested classes handle 1.20 vs 1.21 API drift.
plugins {
    id("prexorcloud.plugin-folia")
}
