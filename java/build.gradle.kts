import java.security.MessageDigest

plugins {
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.protobuf) apply false
    id("nl.littlerobots.version-catalog-update") version "1.1.0"
    id("com.github.ben-manes.versions") version "0.53.0"
}

subprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("prexor.repo.root", rootProject.projectDir.parentFile.absolutePath)
    }
}

// Keep the heavy harness test execution out of aggregate root builds unless it
// was requested explicitly. This preserves the fast `build` lane while still
// allowing dedicated CI lanes such as `:cloud-test-harness:nightlyScenariosTest`.
//
// Important: this only gates `Test`-type tasks. The harness's main artifact is
// consumed by module integration tests (e.g. `:cloud-modules:example`
// via `testImplementation(project(":cloud-test-harness"))`), so the compile +
// jar chain must always run so downstream `compileTestJava` can resolve
// `ModuleTestHarness`.
val harnessExplicitlyRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName == ":cloud-test-harness:test"
            || taskName.startsWith(":cloud-test-harness:")
            || taskName == "cloud-test-harness:test"
            || taskName.startsWith("cloud-test-harness:")
}

project(":cloud-test-harness").tasks.withType<Test>().configureEach {
    enabled = harnessExplicitlyRequested
}

// ── Cloud release publishing ─────────────────────────────────────────────────
//
// CUTOVER: until prexorcloud is public on GitHub, controller + daemon JARs are
// mirrored at https://get.scharbau.me/cloud/<version>/ (with a 'latest' symlink),
// matching the CLI installer flow in cli/Makefile. After cutover, replace this
// with a CI workflow that uploads to GitHub releases.
//
// Usage:
//   ./gradlew publishCloud -PpublishHost=get.scharbau.me -PpublishVersion=0.1.0
//   PUBLISH_HOST=get.scharbau.me PUBLISH_VERSION=0.1.0 ./gradlew publishCloud
//
// Properties / env vars (property wins; env is fallback):
//   publishHost    / PUBLISH_HOST     — required, e.g. get.scharbau.me
//   publishUser    / PUBLISH_USER     — default: deploy
//   publishPath    / PUBLISH_PATH     — default: /var/www/prexorcloud
//   publishVersion / PUBLISH_VERSION  — default: dev

fun cloudPublishProp(name: String, env: String, default: String): String =
    findProperty(name) as String? ?: System.getenv(env) ?: default

val publishHost    = cloudPublishProp("publishHost",    "PUBLISH_HOST",    "")
val publishUser    = cloudPublishProp("publishUser",    "PUBLISH_USER",    "deploy")
val publishPath    = cloudPublishProp("publishPath",    "PUBLISH_PATH",    "/var/www/prexorcloud")
val publishVersion = cloudPublishProp("publishVersion", "PUBLISH_VERSION", "dev")

val cloudReleaseDir = layout.buildDirectory.dir("release/$publishVersion")

val stageCloudRelease by tasks.registering {
    group = "distribution"
    description = "Stages controller + daemon shadow JARs and checksums.txt into build/release/<version>"

    val controllerShadow = project(":cloud-controller").tasks.named("shadowJar")
    val daemonShadow = project(":cloud-daemon").tasks.named("shadowJar")
    dependsOn(controllerShadow, daemonShadow)
    inputs.files(controllerShadow.map { it.outputs.files }, daemonShadow.map { it.outputs.files })
    outputs.dir(cloudReleaseDir)

    doLast {
        val out = cloudReleaseDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()

        val jars = listOf(
            controllerShadow.get().outputs.files.singleFile,
            daemonShadow.get().outputs.files.singleFile,
        )
        for (jar in jars) jar.copyTo(out.resolve(jar.name), overwrite = true)

        val checksums = jars.sortedBy { it.name }.joinToString("\n") { jar ->
            val md = MessageDigest.getInstance("SHA-256")
            jar.inputStream().use { stream ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            "${md.digest().joinToString("") { "%02x".format(it) }}  ${jar.name}"
        }
        out.resolve("checksums.txt").writeText(checksums + "\n")

        logger.lifecycle("Staged release at ${out.absolutePath}")
    }
}

val publishCloudValidate by tasks.registering {
    doFirst {
        check(publishHost.isNotBlank()) {
            "publishHost is required (e.g. -PpublishHost=get.scharbau.me or PUBLISH_HOST=get.scharbau.me)"
        }
        logger.lifecycle("Publishing $publishVersion to $publishUser@$publishHost:$publishPath")
    }
}

val publishCloudMkdir by tasks.registering(Exec::class) {
    dependsOn(publishCloudValidate, stageCloudRelease)
    commandLine("ssh", "$publishUser@$publishHost", "mkdir -p $publishPath/$publishVersion")
}

val publishCloudUpload by tasks.registering(Exec::class) {
    dependsOn(publishCloudMkdir)
    commandLine(
        "rsync", "-av", "--checksum",
        "${cloudReleaseDir.get().asFile.absolutePath}/",
        "$publishUser@$publishHost:$publishPath/$publishVersion/",
    )
}

val publishCloudSymlink by tasks.registering(Exec::class) {
    dependsOn(publishCloudUpload)
    commandLine("ssh", "$publishUser@$publishHost", "ln -sfn $publishVersion $publishPath/latest")
}

tasks.register("publishCloud") {
    group = "distribution"
    description = "Publishes controller + daemon JARs to <user>@<host>:<path>/<version>/ and updates 'latest' symlink"
    dependsOn(publishCloudSymlink)
}
