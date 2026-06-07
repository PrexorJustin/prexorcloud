// Folia variant of protocol-tap.
//
// Folia is a Paper fork that ships only on 1.20+, so we don't need the
// JAR-split that the Paper sibling uses for the 1.20-vs-1.21 chat-event
// drift — Folia just always uses the modern Adventure-flavoured
// AsyncChatEvent. This subproject is single-version on purpose: it's the
// "you don't always need tier-2 dispatch" counterpart to the Paper variant's
// JAR-split.
plugins {
    id("prexorcloud.plugin-folia")
}

dependencies {
    implementation(project(":cloud-modules:protocol-tap:plugin:shared"))
}

tasks.shadowJar {
    archiveBaseName.set("protocol-tap-folia")

    relocate("com.fasterxml.jackson", "me.prexorjustin.prexorcloud.modules.protocoltap.libs.jackson")
}
