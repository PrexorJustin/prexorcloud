plugins {
    id("prexorcloud.java21-api")
    `java-library`
}

dependencies {
    api(platform(project(":cloud-platform")))
    api(project(":cloud-api"))
    api(libs.jackson.databind)
    implementation(project(":cloud-common"))
    implementation(libs.slf4j.api)
    compileOnly(libs.paperApi120)
}
