plugins {
    id("prexorcloud.java21-api")
    `java-library`
}

dependencies {
    api(platform(project(":cloud-platform")))
    api(project(":cloud-plugins:cloud-plugins-internal"))
    compileOnly(libs.paperApi120)
}
