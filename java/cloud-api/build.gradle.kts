plugins {
    id("prexorcloud.java21-api")
}

dependencies {
    api(platform(project(":cloud-platform")))
    // The ModuleContext interface returns Logger / ObjectMapper / HttpClient,
    // so consumers transitively need slf4j + jackson + java.net.http (JDK).
    api(libs.jackson.databind)
    api(libs.slf4j.api)

    // JavaPoet is used by CloudPluginProcessor at annotation-processing time only
    implementation(libs.javapoet)
}

// Plugin API + Module API. Java 21 target. Exposes Logger / ObjectMapper /
// HttpClient as symmetric primitives shared by plugin and module SDKs.
