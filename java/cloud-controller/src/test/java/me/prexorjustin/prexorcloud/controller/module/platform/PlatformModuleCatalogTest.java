package me.prexorjustin.prexorcloud.controller.module.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import me.prexorjustin.prexorcloud.modules.runtime.PlatformModuleManifestParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("PlatformModuleCatalog")
class PlatformModuleCatalogTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("discovers platform module manifests from module jars")
    void discoversPlatformModuleManifests() throws IOException {
        writeJar(tempDir.resolve("matchmaking.jar"), PlatformModuleManifestParser.FILE_NAME, """
                id: matchmaking
                version: 1.0.0
                backend:
                  entrypoint: com.example.MatchmakingModule
                extensions:
                  - id: matchmaking-paper
                    target: server/paper
                    activation: explicit-group-attach
                    variants:
                      - id: paper-1-20
                        mcVersionRange: "[1.20,1.21)"
                        runtimeApiVersion: 1
                        artifact: extensions/paper/matchmaking-paper-1.20.jar
                        sha256: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
                        installPath: plugins/
                """);
        writeJar(tempDir.resolve("legacy.jar"), "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n");

        PlatformModuleCatalog.ScanResult scanResult = new PlatformModuleCatalog().scan(tempDir);

        assertEquals(1, scanResult.modules().size());
        assertEquals("matchmaking", scanResult.modules().getFirst().manifest().id());
        assertTrue(scanResult.failures().isEmpty());
    }

    @Test
    @DisplayName("records invalid manifests as discovery failures")
    void recordsInvalidManifestFailures() throws IOException {
        writeJar(tempDir.resolve("broken.jar"), PlatformModuleManifestParser.FILE_NAME, """
                id: broken
                backend:
                  entrypoint: com.example.BrokenModule
                """);

        PlatformModuleCatalog.ScanResult scanResult = new PlatformModuleCatalog().scan(tempDir);

        assertTrue(scanResult.modules().isEmpty());
        assertEquals(1, scanResult.failures().size());
        assertEquals("broken.jar", scanResult.failures().getFirst().jarFileName());
        assertTrue(scanResult.failures().getFirst().message().contains("version"));
    }

    private static void writeJar(Path jarPath, String entryName, String content) throws IOException {
        Files.createDirectories(jarPath.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jarPath))) {
            out.putNextEntry(new JarEntry(entryName));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }
}
