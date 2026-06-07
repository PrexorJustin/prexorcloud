package me.prexorjustin.prexorcloud.daemon.template;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("TemplateUnpacker")
class TemplateUnpackerTest {

    @TempDir
    Path tempDir;

    /** Build an in-memory tar.gz with the given entries (name → content). */
    private static byte[] buildTarGz(java.util.Map<String, String> entries) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var gzip = new GzipCompressorOutputStream(baos);
                var tar = new TarArchiveOutputStream(gzip)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (var e : entries.entrySet()) {
                byte[] data = e.getValue().getBytes(StandardCharsets.UTF_8);
                var entry = new TarArchiveEntry(e.getKey());
                entry.setSize(data.length);
                tar.putArchiveEntry(entry);
                tar.write(data);
                tar.closeArchiveEntry();
            }
            tar.finish();
        }
        return baos.toByteArray();
    }

    @Nested
    @DisplayName("Unpack")
    class Unpack {

        @Test
        @DisplayName("Extracts files to target directory")
        void extractsFiles() throws IOException {
            byte[] tarGz =
                    buildTarGz(java.util.Map.of("server.properties", "port=25565", "plugins/info.txt", "hello world"));

            TemplateUnpacker.unpack(tarGz, tempDir);

            assertEquals("port=25565", Files.readString(tempDir.resolve("server.properties")));
            assertEquals("hello world", Files.readString(tempDir.resolve("plugins/info.txt")));
        }

        @Test
        @DisplayName("Creates target directory if it does not exist")
        void createsTargetDirectory() throws IOException {
            Path target = tempDir.resolve("new-instance");
            byte[] tarGz = buildTarGz(java.util.Map.of("file.txt", "data"));

            TemplateUnpacker.unpack(tarGz, target);

            assertTrue(Files.exists(target.resolve("file.txt")));
        }

        @Test
        @DisplayName("Rejects tar entry that escapes target directory (zip-slip)")
        void rejectsZipSlip() throws IOException {
            // Build a tar with a malicious path-traversal entry
            var baos = new ByteArrayOutputStream();
            try (var gzip = new GzipCompressorOutputStream(baos);
                    var tar = new TarArchiveOutputStream(gzip)) {
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                byte[] data = "evil".getBytes(StandardCharsets.UTF_8);
                var entry = new TarArchiveEntry("../escape.txt");
                entry.setSize(data.length);
                tar.putArchiveEntry(entry);
                tar.write(data);
                tar.closeArchiveEntry();
                tar.finish();
            }
            byte[] tarGz = baos.toByteArray();

            assertThrows(IOException.class, () -> TemplateUnpacker.unpack(tarGz, tempDir));
        }

        @Test
        @DisplayName("Handles empty archive without error")
        void handlesEmptyArchive() throws IOException {
            byte[] tarGz = buildTarGz(java.util.Map.of());
            assertDoesNotThrow(() -> TemplateUnpacker.unpack(tarGz, tempDir));
        }
    }

    @Nested
    @DisplayName("UnpackWithProtectedPaths")
    class UnpackWithProtectedPaths {

        @Test
        @DisplayName("Does not overwrite existing protected files")
        void doesNotOverwriteProtected() throws IOException {
            // Create an existing protected file
            Path existing = tempDir.resolve("config.yml");
            Files.writeString(existing, "original");

            byte[] tarGz = buildTarGz(java.util.Map.of("config.yml", "overwritten"));

            TemplateUnpacker.unpackWithProtectedPaths(tarGz, tempDir, Set.of("config.yml"));

            assertEquals("original", Files.readString(existing));
        }

        @Test
        @DisplayName("Overwrites non-protected files")
        void overwritesNonProtected() throws IOException {
            Path existing = tempDir.resolve("server.jar");
            Files.writeString(existing, "old");

            byte[] tarGz = buildTarGz(java.util.Map.of("server.jar", "new"));

            TemplateUnpacker.unpackWithProtectedPaths(tarGz, tempDir, Set.of("config.yml"));

            assertEquals("new", Files.readString(existing));
        }

        @Test
        @DisplayName("Writes protected files when they do not yet exist")
        void writesProtectedFileIfAbsent() throws IOException {
            byte[] tarGz = buildTarGz(java.util.Map.of("config.yml", "fresh"));

            TemplateUnpacker.unpackWithProtectedPaths(tarGz, tempDir, Set.of("config.yml"));

            assertEquals("fresh", Files.readString(tempDir.resolve("config.yml")));
        }

        @Test
        @DisplayName("Protected path prefix protects entire subtree")
        void protectedPathCoversSubtree() throws IOException {
            Path existing = tempDir.resolve("protected/nested/data.txt");
            Files.createDirectories(existing.getParent());
            Files.writeString(existing, "safe");

            byte[] tarGz = buildTarGz(java.util.Map.of("protected/nested/data.txt", "overwritten"));

            TemplateUnpacker.unpackWithProtectedPaths(tarGz, tempDir, Set.of("protected"));

            assertEquals("safe", Files.readString(existing));
        }
    }
}
