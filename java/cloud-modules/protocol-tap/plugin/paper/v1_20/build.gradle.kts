// Paper 1.20 variant of protocol-tap.
//
// Pinned to paperApi120 via the prexorcloud.plugin-paper convention plugin.
// See ../v1_21 for the sibling 1.21.4 build.
//
// Phase E upgrade (real NMS):
//   plugins {
//       id("io.papermc.paperweight.userdev") version "X.Y.Z"
//   }
//   dependencies { paperweight.paperDevBundle("1.20.4-R0.1-SNAPSHOT") }
// — at which point this subproject's source can import net.minecraft.* and
// hook ServerGamePacketListenerImpl directly.
plugins {
    id("prexorcloud.plugin-paper")
}

dependencies {
    implementation(project(":cloud-modules:protocol-tap:plugin:shared"))
}

tasks.shadowJar {
    archiveBaseName.set("protocol-tap-paper-v1_20")
    relocate("com.fasterxml.jackson", "me.prexorjustin.prexorcloud.modules.protocoltap.libs.jackson")
}
