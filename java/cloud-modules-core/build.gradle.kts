plugins {
    id("prexorcloud.java21-api")
}

dependencies {
    implementation(platform(project(":cloud-platform")))
    implementation(project(":cloud-api"))
    implementation(project(":cloud-common"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)

    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
}
