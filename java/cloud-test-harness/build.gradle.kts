import java.time.Duration

plugins {
    `java-library`
    id("prexorcloud.java25-preview")
}

val testSourceSet = the<org.gradle.api.tasks.SourceSetContainer>()["test"]

dependencies {
    // ── main source set: ModuleTestHarness (consumed by module authors) ──
    // These have to be `api` because the harness's public methods return
    // PrexorController, ModuleDataStore, CapabilityRegistry, etc. Consumers
    // (module integration tests in :cloud-modules:*) need to see
    // those types on their compile classpath.
    api(project(":cloud-api"))
    api(project(":cloud-modules:runtime"))
    api(project(":cloud-controller"))
    implementation(project(":cloud-common"))
    implementation(project(":cloud-protocol"))
    implementation(project(":cloud-security"))
    implementation(libs.slf4j.api)
    implementation(libs.mongodb.driver.sync)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)

    // Testcontainers — boot ephemeral Mongo + Redis for fully hermetic
    // module integration tests. BOM is `api` so consumers (module
    // integration tests) inherit the pinned version on their compile
    // classpath; `implementation` would hide it from compile resolution.
    api(platform(libs.testcontainers.bom))
    api(libs.testcontainers.core)
    api(libs.testcontainers.mongodb)

    // ── test source set: TestCluster + the existing controller/daemon test suite ──
    testImplementation(project(":cloud-daemon"))

    // Controller transitive deps needed at test runtime
    testImplementation(libs.javalin)
    testImplementation(libs.grpc.netty.shaded)
    testImplementation(libs.grpc.protobuf)
    testImplementation(libs.grpc.stub)
    testImplementation(libs.grpc.services)
    testImplementation(libs.grpc.testing)
    testImplementation(libs.protobuf.java)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.jackson.dataformat.yaml)
    testImplementation(libs.jackson.datatype.jsr310)
    testImplementation(libs.jjwt.api)
    testImplementation(libs.mongodb.driver.sync)
    testRuntimeOnly(libs.jjwt.impl)
    testRuntimeOnly(libs.jjwt.jackson)
    testImplementation(libs.logback.classic)
    testImplementation(libs.micrometer.core)
    testImplementation(libs.micrometer.registry.prometheus)
    testImplementation(libs.commons.compress)
    testImplementation(libs.bouncycastle.pkix)
    testImplementation(libs.bouncycastle.prov)
    testImplementation(libs.argon2.jvm)

    // JUnit Platform launcher API (for TestReportListener)
    testImplementation("org.junit.platform:junit-platform-launcher")

    // Daemon transitive deps
    testImplementation(libs.jackson.dataformat.toml)
    testImplementation(libs.oshi.core.java25)
}

// Make the stats-aggregator + player-journey shadowJar artifacts available to
// integration tests so the end-to-end install harness can upload the real
// first-party modules against a live controller. The Shadow plugin task is
// registered during the consumer project's own evaluation, so we have to
// force evaluation order before resolving its task graph.
//
// player-journey is a hard prerequisite for stats-aggregator post-Layer-5: the
// controller no longer registers prexor.player.journey as a built-in
// capability, so stats-aggregator's "requires" only resolves once the journey
// module is installed.
evaluationDependsOn(":cloud-modules:stats-aggregator")
evaluationDependsOn(":cloud-modules:player-journey")
evaluationDependsOn(":test-fixtures:test-daemon-module")
val statsAggregatorShadowJar =
        project(":cloud-modules:stats-aggregator").tasks.named("shadowJar")
val playerJourneyShadowJar =
        project(":cloud-modules:player-journey").tasks.named("shadowJar")
val testDaemonModuleShadowJar =
        project(":test-fixtures:test-daemon-module").tasks.named("shadowJar")

tasks.withType<Test> {
    // Increase memory for stress tests
    maxHeapSize = "1g"

    dependsOn(statsAggregatorShadowJar, playerJourneyShadowJar, testDaemonModuleShadowJar)
    doFirst {
        val statsTask = statsAggregatorShadowJar.get() as org.gradle.api.tasks.bundling.AbstractArchiveTask
        val journeyTask = playerJourneyShadowJar.get() as org.gradle.api.tasks.bundling.AbstractArchiveTask
        val testDaemonTask = testDaemonModuleShadowJar.get() as org.gradle.api.tasks.bundling.AbstractArchiveTask
        systemProperty("prexor.test.statsAggregatorJar", statsTask.archiveFile.get().asFile.absolutePath)
        systemProperty("prexor.test.playerJourneyJar", journeyTask.archiveFile.get().asFile.absolutePath)
        systemProperty("prexor.test.testDaemonModuleJar", testDaemonTask.archiveFile.get().asFile.absolutePath)
    }

    // Tag-based filtering
    val includeTags: String? = System.getProperty("include.tags")
    val excludeTags: String? = System.getProperty("exclude.tags")
    val isPerfTask = name == "perfBaselines"
    val isDrTask = name == "drDrill"
    useJUnitPlatform {
        if (includeTags != null) includeTags(*includeTags.split(",").toTypedArray())
        if (excludeTags != null) {
            excludeTags(*excludeTags.split(",").toTypedArray())
        } else if (includeTags == null && !isPerfTask && !isDrTask) {
            // Default: keep the heavyweight perf + DR suites out of the regular test pass; the dedicated tasks opt in.
            excludeTags("perf", "dr")
        }
    }

    // Ensure tests run sequentially (shared ports)
    maxParallelForks = 1

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }

    // Pass the report output directory to the TestReportExtension
    systemProperty("test.report.dir", layout.buildDirectory.dir("reports/test-harness").get().asFile.absolutePath)
}

tasks.register<Test>("nightlyScenariosTest") {
    description = "Runs the curated nightly cloud-test-harness scenario suite."
    group = "verification"
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath

    useJUnitPlatform {
        excludeTags("stress", "perf")
    }

    filter {
        includeTestsMatching("me.prexorjustin.prexorcloud.tests.SystemTest")
        includeTestsMatching("me.prexorjustin.prexorcloud.tests.NodeTest")
        includeTestsMatching("me.prexorjustin.prexorcloud.tests.TemplateTest")
        includeTestsMatching("me.prexorjustin.prexorcloud.tests.SecurityTest")
        includeTestsMatching("me.prexorjustin.prexorcloud.tests.SseEventTest")
        includeTestsMatching("me.prexorjustin.prexorcloud.tests.CatalogTest")
    }
}

tasks.register<Test>("perfBaselines") {
    description = "Runs the cloud-test-harness performance-baseline suite (M2 / Phase 41)."
    group = "verification"
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath

    // perfBaselines explicitly opts in to the @Tag("perf") suite (excluded from default test).
    useJUnitPlatform {
        includeTags("perf")
    }

    val reportFile = layout.buildDirectory.file("reports/perf-baselines/baseline-report.json").get().asFile
    systemProperty("perf.report.file", reportFile.absolutePath)
    System.getProperty("perf.scheduler.groups")?.let { systemProperty("perf.scheduler.groups", it) }
    System.getProperty("perf.scheduler.samples")?.let { systemProperty("perf.scheduler.samples", it) }
    System.getProperty("perf.coordination.samples")?.let { systemProperty("perf.coordination.samples", it) }
    System.getProperty("perf.sse.samples")?.let { systemProperty("perf.sse.samples", it) }

    // Perf timer windows + the harness 2s scheduler interval × 8 samples push past the default 2-min timeout.
    timeout.set(Duration.ofMinutes(15))

    outputs.file(reportFile)
}

tasks.register<Test>("drDrill") {
    description = "Runs the cloud-test-harness DR drill (Phase 41 / gap #16) — backup, wipe, restore."
    group = "verification"
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath

    // drDrill explicitly opts in to the @Tag("dr") suite (excluded from default test).
    useJUnitPlatform {
        includeTags("dr")
    }

    // Backup + wipe + restart + restore comfortably exceeds the default 2-min cap.
    timeout.set(Duration.ofMinutes(10))
}
