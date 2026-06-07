// Paper 1.21 variant of protocol-tap.
//
// Pinned to paperApi121 via the prexorcloud.plugin-paper-1-21 convention
// plugin. Sibling of ../v1_20.
//
// Phase E upgrade (real NMS):
//   plugins {
//       id("io.papermc.paperweight.userdev") version "X.Y.Z"
//   }
//   dependencies { paperweight.paperDevBundle("1.21.4-R0.1-SNAPSHOT") }
plugins {
    id("prexorcloud.plugin-paper-1-21")
}

dependencies {
    implementation(project(":cloud-modules:protocol-tap:plugin:shared"))
}

tasks.shadowJar {
    archiveBaseName.set("protocol-tap-paper-v1_21")
    relocate("com.fasterxml.jackson", "me.prexorjustin.prexorcloud.modules.protocoltap.libs.jackson")
}
