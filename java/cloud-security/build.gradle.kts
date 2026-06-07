plugins {
    id("prexorcloud.java25-preview")
}

dependencies {
    implementation(project(":cloud-common"))
    implementation(libs.bouncycastle.pkix)
    implementation(libs.bouncycastle.prov)
    implementation(libs.jjwt.api)
    implementation(libs.argon2.jvm) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation("net.java.dev.jna:jna-jpms:5.18.1")
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.grpc.netty.shaded)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)
}
