plugins {
    id("prexorcloud.java21-api")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":cloud-plugins:cloud-plugins-proxy:cloud-plugins-proxy-shared"))
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)
}

tasks.shadowJar {
    archiveBaseName.set("PrexorCloudVelocityPlugin")
    archiveVersion.set("")
    archiveClassifier.set("")

    // Velocity provides javax.inject and com.google.inject via its own classloader.
    // Bundling them would produce a different class identity, causing Guice to fail
    // to recognise @Inject on our constructor with "No injectable constructor".
    exclude("javax/inject/**")
    exclude("jakarta/inject/**")
    exclude("com/google/inject/**")
    exclude("aopalliance/**")
}
