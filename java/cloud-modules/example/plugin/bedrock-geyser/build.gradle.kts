// Bedrock/Geyser variant of the example-playtime module.
//
// Geyser loads extensions from its `extensions/` directory; the
// CloudPluginProcessor generates a bridge class implementing
// `org.geysermc.geyser.api.extension.Extension` plus an `extension.yml`
// descriptor — neither needs to be hand-maintained. The plugin class below
// is the user-facing impl that the bridge constructs and drives through
// CloudPluginBase's lifecycle hooks.
plugins {
    id("prexorcloud.plugin-bedrock-geyser")
}
