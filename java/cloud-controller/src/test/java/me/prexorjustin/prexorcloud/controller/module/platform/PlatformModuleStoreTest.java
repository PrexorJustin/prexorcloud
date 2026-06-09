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

@DisplayName("PlatformModuleStore")
class PlatformModuleStoreTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("commits modules under content-addressed artifact names")
    void commitsContentAddressedArtifacts() {
        PlatformModuleStore store = new PlatformModuleStore(tempDir.resolve("store"));
        Path sourceJar = createModuleJar(tempDir.resolve("chat.jar"), "chat", "1.0.0");

        PlatformModuleStore.PreparedModule prepared = store.prepare(sourceJar);
        PlatformModuleStore.StoredModule stored = store.commit(prepared);

        assertEquals("chat", stored.moduleId());
        assertEquals(stored.sha256() + ".jar", stored.jarPath().getFileName().toString());
        assertTrue(Files.isRegularFile(stored.jarPath()));
        assertEquals(1, store.list().size());
        assertEquals(stored.sha256(), store.find("chat").orElseThrow().sha256());
    }

    @Test
    @DisplayName("replacement updates index and prunes stale artifacts")
    void replacementPrunesStaleArtifacts() {
        PlatformModuleStore store = new PlatformModuleStore(tempDir.resolve("store"));

        PlatformModuleStore.StoredModule first =
                store.commit(store.prepare(createModuleJar(tempDir.resolve("chat-1.jar"), "chat", "1.0.0")));
        PlatformModuleStore.StoredModule second =
                store.commit(store.prepare(createModuleJar(tempDir.resolve("chat-2.jar"), "chat", "2.0.0")));

        assertEquals("2.0.0", store.find("chat").orElseThrow().version());
        assertNotEquals(first.sha256(), second.sha256());
        assertFalse(Files.exists(first.jarPath()));
        assertTrue(Files.exists(second.jarPath()));
    }

    private static Path createModuleJar(Path jarPath, String moduleId, String version) {
        String manifest = """
                manifestVersion: 1
                id: %s
                version: %s
                backend:
                  entrypoint: example.%sModule
                """.formatted(moduleId, version, capitalize(moduleId));

        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jarPath))) {
            out.putNextEntry(new JarEntry(PlatformModuleManifestParser.FILE_NAME));
            out.write(manifest.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            return jarPath;
        } catch (IOException e) {
            throw new IllegalStateException("failed to create test module jar", e);
        }
    }

    private static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
