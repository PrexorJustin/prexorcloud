plugins {
    java
    jacoco
    id("com.diffplug.spotless")
}

group = "me.prexorjustin.prexorcloud"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://repo.opencollab.dev/main/") // Geyser & Floodgate
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

spotless {
    java {
        palantirJavaFormat("2.90.0")
        importOrder("java|javax", "me.prexorjustin", "")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        targetExclude("build/**")

        // palantir-java-format 2.90.0's bundled javac rejects `_` (unnamed
        // variables) which are stable in Java 22+ and used in our Java 25
        // preview modules. removeUnusedImports shares the same parser.
        //
        // Note on JEP 511 `import module X;` syntax: palantir 2.90.0 (latest
        // as of 2026-05) ALSO rejects this with FormatterException — and
        // suppressLintsFor catches lint output, not parser exceptions, so
        // `import module` cannot land here yet. Revisit when palantir or
        // google-java-format upstream adds support, or before then via
        // `targetExclude` for specific files that adopt the syntax.
        suppressLintsFor {
            step = "palantir-java-format"
            shortCode = "palantir-java-format"
        }
        suppressLintsFor {
            step = "removeUnusedImports"
            shortCode = "removeUnusedImports"
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.withType<JacocoReport> {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

val libs = versionCatalogs.named("libs")

dependencies {
    "implementation"(platform(libs.findLibrary("jackson-bom").get()))
    "implementation"(platform(libs.findLibrary("grpc-bom").get()))
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))

    "implementation"(libs.findLibrary("slf4j-api").get())

    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}
