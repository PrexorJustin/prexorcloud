import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    id("prexorcloud.java25-preview")
    id("com.gradleup.shadow")
}

interface PrexorCloudModuleExtension {
    val archiveName: Property<String>
    val moduleManifest: RegularFileProperty
    val extensionArtifacts: MapProperty<String, String>
}

val extension = extensions.create<PrexorCloudModuleExtension>("prexorcloudModule").apply {
    moduleManifest.convention(layout.projectDirectory.file("src/main/module/module.yaml"))
    extensionArtifacts.convention(emptyMap())
}

dependencies {
    compileOnly(project(":cloud-api"))
    "annotationProcessor"(project(":cloud-api"))
}

val yamlMapper = ObjectMapper(YAMLFactory())

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}

fun packagingTask(projectPath: String): AbstractArchiveTask {
    val targetProject = project(projectPath)
    return (targetProject.tasks.findByName("shadowJar")
            ?: targetProject.tasks.findByName("jar")) as? AbstractArchiveTask
            ?: throw GradleException("No archive task found for module extension artifact project $projectPath")
}

fun resolveExtensionArtifactFiles(): Map<String, File> {
    return extension.extensionArtifacts.getOrElse(emptyMap()).mapValues { (_, projectPath) ->
        packagingTask(projectPath).archiveFile.get().asFile
    }
}

// Frontend build — skipped automatically when no frontend/ directory exists.
val hasFrontend = file("frontend/package.json").exists()
val pnpmCommand = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "pnpm.cmd" else "pnpm"

val pnpmInstall by tasks.registering(Exec::class) {
    workingDir = file("frontend")
    commandLine(pnpmCommand, "install")
    environment("CI", "true")
    onlyIf { hasFrontend }
    inputs.files(project.files("frontend/package.json", "frontend/pnpm-lock.yaml").filter { it.exists() })
    outputs.dir("frontend/node_modules")
}

val buildFrontend by tasks.registering(Exec::class) {
    dependsOn(pnpmInstall)
    workingDir = file("frontend")
    commandLine(pnpmCommand, "build")
    environment("CI", "true")
    onlyIf { hasFrontend }
    inputs.dir("frontend/src")
    inputs.files(project.files("frontend/package.json", "frontend/vite.config.ts", "frontend/tsconfig.json").filter {
        it.exists()
    })
    outputs.dir("frontend/dist")
}

val preparePlatformManifest by tasks.registering {
    val outputFile = layout.buildDirectory.file("generated/prexor/module.yaml")

    inputs.file(extension.moduleManifest)
    inputs.property("extensionArtifacts", extension.extensionArtifacts)
    outputs.file(outputFile)

    doLast {
        val manifestFile = extension.moduleManifest.asFile.get()
        if (!manifestFile.isFile) {
            throw GradleException("Platform module manifest template not found: $manifestFile")
        }

        val artifactFiles = resolveExtensionArtifactFiles()
        val root = yamlMapper.readValue(manifestFile, MutableMap::class.java) as MutableMap<String, Any?>
        val extensions = root["extensions"] as? List<*> ?: emptyList<Any?>()
        val referencedArtifacts = linkedSetOf<String>()

        extensions.forEachIndexed { extensionIndex, extensionNode ->
            val extensionMap = extensionNode as? MutableMap<*, *>
                    ?: throw GradleException("extensions[$extensionIndex] must be an object")
            val target = extensionMap["target"]?.toString()?.trim().orEmpty()
            if (!target.matches(Regex("^(server|proxy)/[a-z0-9-]+$"))) {
                throw GradleException("extensions[$extensionIndex].target must match <server|proxy>/<platform>: $target")
            }

            val variants = extensionMap["variants"] as? List<*>
                    ?: throw GradleException("extensions[$extensionIndex].variants must be a list")
            variants.forEachIndexed { variantIndex, variantNode ->
                val variantMap = variantNode as? MutableMap<*, *>
                        ?: throw GradleException("extensions[$extensionIndex].variants[$variantIndex] must be an object")
                val artifactPath = variantMap["artifact"]?.toString()?.trim().orEmpty()
                if (artifactPath.isBlank()) {
                    throw GradleException(
                            "extensions[$extensionIndex].variants[$variantIndex].artifact must not be blank")
                }
                if (!artifactPath.endsWith(".jar")) {
                    throw GradleException(
                            "extensions[$extensionIndex].variants[$variantIndex].artifact must point to a .jar: $artifactPath")
                }
                val installPath = variantMap["installPath"]?.toString()?.trim().orEmpty()
                if (installPath.isBlank()) {
                    throw GradleException(
                            "extensions[$extensionIndex].variants[$variantIndex].installPath must not be blank")
                }

                val artifactFile = artifactFiles[artifactPath]
                        ?: throw GradleException(
                                "Manifest references '$artifactPath' but prexorcloudModule.extensionArtifacts does not declare it")
                referencedArtifacts += artifactPath
                @Suppress("UNCHECKED_CAST")
                (variantMap as MutableMap<String, Any?>)["sha256"] = sha256(artifactFile)
            }
        }

        val unusedArtifacts = artifactFiles.keys - referencedArtifacts
        if (unusedArtifacts.isNotEmpty()) {
            throw GradleException(
                    "Declared extensionArtifacts are not referenced by the platform manifest: $unusedArtifacts")
        }

        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        yamlMapper.writeValue(output, root)
    }
}

tasks.processResources {
    if (hasFrontend) {
        dependsOn(buildFrontend)
        from("frontend/dist") {
            into("META-INF/frontend")
        }
    }

    dependsOn(preparePlatformManifest)
    from(preparePlatformManifest) {
        into("META-INF/prexor")
    }
}

gradle.projectsEvaluated {
    extension.extensionArtifacts.getOrElse(emptyMap()).forEach { (artifactPath, projectPath) ->
        val archiveTask = packagingTask(projectPath)
        preparePlatformManifest.configure {
            dependsOn(archiveTask)
        }
        tasks.processResources {
            dependsOn(archiveTask)
            from(archiveTask) {
                val normalizedPath = artifactPath.replace("\\", "/")
                val slashIndex = normalizedPath.lastIndexOf('/')
                val directory = if (slashIndex >= 0) normalizedPath.substring(0, slashIndex) else ""
                val fileName = if (slashIndex >= 0) normalizedPath.substring(slashIndex + 1) else normalizedPath
                if (directory.isNotEmpty()) {
                    into(directory)
                }
                rename { fileName }
            }
        }
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.shadowJar {
    archiveVersion = ""
    archiveClassifier = ""
    mergeServiceFiles()
    exclude("**/module-info.class")
    exclude("META-INF/versions/*/module-info.class")
    exclude("org/slf4j/**")
}

afterEvaluate {
    val name = extension.archiveName.orNull
    if (name != null) {
        tasks.named<ShadowJar>("shadowJar") {
            archiveBaseName = name
        }
    }
}
