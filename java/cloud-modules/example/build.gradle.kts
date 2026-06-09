// ---------------------------------------------------------------------------
// cloud-module-example — canonical ModuleSystem v2 reference.
//
// This build file is intentionally the entire build surface for a PrexorCloud
// module: applying the `prexorcloud.module` convention plugin wires up
// compile-only cloud-api, frontend staging, explicit platform-manifest
// generation, and workload-extension artifact packaging.
//
// STEP 0 — When copying this template, change `archiveName`, the backend
// entrypoint in `src/main/module/module.yaml`, and the extension artifact paths
// below to match your new module's shape.
// ---------------------------------------------------------------------------
plugins {
    id("prexorcloud.module")
}

dependencies {
    // STEP 0c — cloud-api is compileOnly on the main classpath (the convention
    // plugin wires it that way so the module jar doesn't re-bundle the API),
    // but tests exercise types from cloud-api directly (ModuleDataStore,
    // RouteRegistrar, ApiRequest, ApiResponse) so it has to be back on the
    // test classpath explicitly. Mirror this block in every module that
    // writes tests against the API surface.
    testImplementation(project(":cloud-api"))
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)

    // STEP 0d — cloud-test-harness powers the integration-test slice
    // (real Mongo + Redis via testcontainers, real in-process controller).
    // Skipped automatically when Docker isn't reachable.
    testImplementation(project(":cloud-test-harness"))
}

// STEP 0e — The integration test installs the module under test by pointing
// the harness at the freshly built shadow JAR. Force the test task to wait
// for shadowJar and surface its path via system property; the test reads it
// through `System.getProperty("example.shadowjar.path")`.
tasks.withType<Test>().configureEach {
    dependsOn(tasks.shadowJar)
    doFirst {
        val shadow = tasks.shadowJar.get().archiveFile.get().asFile
        systemProperty("example.shadowjar.path", shadow.absolutePath)
    }
}

prexorcloudModule {
    archiveName.set("example-playtime")

    // STEP 0a — `extensionArtifacts` maps manifest artifact paths to the
    // subprojects that build them. The convention plugin copies each shadow JAR
    // into the final module JAR and rewrites `module.yaml` with the computed
    // SHA-256 for every declared variant artifact.
    extensionArtifacts.set(
        mapOf(
            "extensions/server/folia/example-playtime-folia.jar" to ":cloud-modules:example:plugin:folia",
            "extensions/proxy/velocity/example-playtime-velocity.jar" to ":cloud-modules:example:plugin:velocity",
            "extensions/server/paper/example-playtime-paper.jar" to ":cloud-modules:example:plugin:paper",
            "extensions/server/bedrock-geyser/example-playtime-bedrock-geyser.jar"
                to ":cloud-modules:example:plugin:bedrock-geyser",
        ),
    )
}
