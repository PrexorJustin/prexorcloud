plugins {
    id("prexorcloud.java25-preview")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(platform(project(":cloud-platform")))
    implementation(project(":cloud-common"))
    implementation(project(":cloud-protocol"))
    implementation(project(":cloud-security"))
    implementation(project(":cloud-modules:runtime"))
    implementation(project(":cloud-api"))

    implementation(libs.javalin)
    // The community runtime plugins (javalin-openapi-plugin +
    // javalin-swagger-plugin 6.7.0-1) target Javalin 6's plugin SPI and
    // don't register routes under Javalin 7, so they're not used at runtime
    // — RestServer serves the spec manually from the AP-generated
    // classpath resource. We still need the @OpenApi annotation surface at
    // compile time, hence compileOnly on the plugin jar.
    compileOnly(libs.javalin.openapi.plugin)
    annotationProcessor(libs.javalin.openapi.annotation.processor)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.services)
    implementation(libs.protobuf.java)
    implementation(libs.mongodb.driver.sync)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)
    implementation(libs.logback.classic)
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.commons.compress)
    implementation(libs.lettuce.core)
    implementation(libs.jakarta.mail.api)
    implementation(libs.jakarta.activation.api)
    runtimeOnly(libs.angus.mail)

    // Apache Ratis — embedded Raft for the cluster control plane.
    // See docs/engineering/cluster-join-plan.md.
    implementation(libs.ratis.server.api)
    implementation(libs.ratis.client)
    implementation(libs.ratis.common)
    implementation(libs.ratis.proto)
    implementation(libs.ratis.server)
    implementation(libs.ratis.grpc)
    runtimeOnly(libs.ratis.metrics.default)

    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
    testImplementation(libs.grpc.testing)
    testImplementation(libs.bouncycastle.pkix)
    testImplementation(libs.bouncycastle.prov)
}

// Exclude long-running Ratis spike tests from the default test task. Invoke them
// explicitly via `./gradlew :cloud-controller:spikeTest`. See
// docs/engineering/ratis-spike.md for context.
tasks.test {
    useJUnitPlatform {
        excludeTags("spike")
    }
}

tasks.register<Test>("spikeTest") {
    description = "Runs Ratis multi-peer spike tests (slow, opt-in)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("spike")
    }
}

// --- Bundled plugin JARs (embedded as resources for base templates) ---

val bundledPlugins by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    bundledPlugins(project(":cloud-plugins:server:paper", configuration = "shadow"))
    bundledPlugins(project(":cloud-plugins:server:spigot", configuration = "shadow"))
    bundledPlugins(project(":cloud-plugins:server:folia", configuration = "shadow"))
    bundledPlugins(project(":cloud-plugins:proxy:velocity", configuration = "shadow"))
    bundledPlugins(project(":cloud-plugins:proxy:bungeecord", configuration = "shadow"))
}

val copyBundledPlugins by tasks.registering(Copy::class) {
    from(bundledPlugins)
    into(layout.buildDirectory.dir("resources/main/bundled-plugins"))
}

tasks.processResources { dependsOn(copyBundledPlugins) }

// --- OpenAPI spec sync ---
//
// The javalin-openapi annotation processor writes the live spec to
// build/classes/java/main/openapi-plugin/openapi-default.json. Copying that
// artefact to docs/openapi.json publishes it for the website (starlight-openapi
// + Scalar) and any external SDK generators without needing a separate build.
val syncOpenApi by tasks.registering(Copy::class) {
    description = "Publishes the generated OpenAPI spec to docs/openapi.json."
    group = "documentation"
    dependsOn(tasks.compileJava)
    from(layout.buildDirectory.file("classes/java/main/openapi-plugin/openapi-default.json"))
    into(layout.projectDirectory.dir("../../docs"))
    rename { "openapi.json" }
}

tasks.compileJava { finalizedBy(syncOpenApi) }

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.shadowJar {
    archiveBaseName = "PrexorCloudController"
    archiveVersion = ""
    archiveClassifier = ""
    manifest {
        attributes("Main-Class" to "me.prexorjustin.prexorcloud.controller.PrexorCloudBootstrap")
    }
    mergeServiceFiles()
    exclude("**/module-info.class")
    exclude("META-INF/versions/*/module-info.class")
}

// --- Bundled JRE distribution via jlink ---

val jlinkModules = listOf(
    "java.base",
    "java.management",
    "java.naming",
    "java.logging",
    "java.net.http",
    "jdk.unsupported",
    "jdk.crypto.ec",
    "jdk.zipfs",
)

val bundleDir = layout.buildDirectory.dir("bundle")
val osName = System.getProperty("os.name").lowercase().let {
    when {
        "windows" in it -> "windows"
        "mac" in it || "darwin" in it -> "macos"
        else -> "linux"
    }
}
val archName = System.getProperty("os.arch").let {
    when (it) {
        "amd64", "x86_64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> it
    }
}

val javaLauncher = javaToolchains.launcherFor(java.toolchain)

tasks.register<Exec>("jlinkJre") {
    description = "Creates a minimal JRE via jlink for distribution"
    group = "distribution"

    val outputDir = bundleDir.get().asFile.resolve("jre")

    inputs.property("modules", jlinkModules.joinToString(","))
    outputs.dir(outputDir)

    doFirst {
        outputDir.deleteRecursively()
        val javaHome = javaLauncher.get().metadata.installationPath.asFile
        executable = javaHome.resolve("bin/jlink").absolutePath
        args(
            "--module-path", javaHome.resolve("jmods").absolutePath,
            "--add-modules", jlinkModules.joinToString(","),
            "--output", outputDir.absolutePath,
            "--strip-debug",
            "--no-man-pages",
            "--no-header-files",
            "--compress", "zip-6",
        )
    }
}

tasks.register("bundleDist") {
    description = "Creates a self-contained distribution with JRE + shadow JAR + launcher"
    group = "distribution"
    dependsOn(tasks.shadowJar, "jlinkJre")

    val distDir = bundleDir.get().asFile.resolve("prexorcloud-controller-$osName-$archName")

    outputs.dir(distDir)

    doLast {
        distDir.deleteRecursively()
        distDir.mkdirs()

        // Copy JRE
        bundleDir.get().asFile.resolve("jre").copyRecursively(distDir.resolve("jre"))

        // Copy shadow JAR
        val jar = tasks.shadowJar.get().archiveFile.get().asFile
        jar.copyTo(distDir.resolve("PrexorCloudController.jar"), overwrite = true)

        // Create launcher scripts
        val bashLauncher = distDir.resolve("bin/prexorcloud-controller")
        bashLauncher.parentFile.mkdirs()
        bashLauncher.writeText(buildString {
            appendLine("#!/bin/sh")
            appendLine("""SCRIPT_DIR="${'$'}(cd "${'$'}(dirname "${'$'}0")/.." && pwd)"""")
            appendLine("""exec "${'$'}SCRIPT_DIR/jre/bin/java" \""")
            appendLine("  --enable-preview \\")
            appendLine("  --enable-native-access=ALL-UNNAMED \\")
            appendLine("  --sun-misc-unsafe-memory-access=allow \\")
            appendLine("  -Dio.netty.noUnsafe=true \\")
            appendLine("""  -jar "${'$'}SCRIPT_DIR/PrexorCloudController.jar" \""")
            appendLine("""  "${'$'}@"""")
        })
        bashLauncher.setExecutable(true)

        val batLauncher = distDir.resolve("bin/prexorcloud-controller.bat")
        batLauncher.writeText(buildString {
            appendLine("@echo off")
            appendLine("""set "SCRIPT_DIR=%~dp0.."""")
            appendLine(""""%SCRIPT_DIR%\jre\bin\java.exe" --enable-preview --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -Dio.netty.noUnsafe=true -jar "%SCRIPT_DIR%\PrexorCloudController.jar" %*""")
        })

        println("Distribution created at: ${distDir.absolutePath}")
    }
}
