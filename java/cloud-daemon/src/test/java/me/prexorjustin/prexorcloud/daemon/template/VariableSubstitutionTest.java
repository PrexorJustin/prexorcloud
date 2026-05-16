package me.prexorjustin.prexorcloud.daemon.template;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("VariableSubstitution")
class VariableSubstitutionTest {

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("ProcessDirectory")
    class ProcessDirectory {

        @Test
        @DisplayName("Replaces %KEY% placeholders in text files")
        void replacesPlaceholders() throws IOException {
            Path file = tempDir.resolve("server.properties");
            Files.writeString(file, "server-port=%PORT%\nmax-players=%MAX_PLAYERS%");

            VariableSubstitution.processDirectory(tempDir, Map.of("PORT", "25565", "MAX_PLAYERS", "50"));

            String result = Files.readString(file);
            assertEquals("server-port=25565\nmax-players=50", result);
        }

        @Test
        @DisplayName("Leaves unknown placeholders intact")
        void leavesUnknownPlaceholders() throws IOException {
            Path file = tempDir.resolve("config.yml");
            Files.writeString(file, "host: %UNKNOWN_HOST%");

            VariableSubstitution.processDirectory(tempDir, Map.of("PORT", "25565"));

            assertEquals("host: %UNKNOWN_HOST%", Files.readString(file));
        }

        @Test
        @DisplayName("Does not modify files with no matching placeholders")
        void noModificationWhenNoMatch() throws IOException {
            Path file = tempDir.resolve("settings.cfg");
            String original = "debug=true\nlog-level=INFO";
            Files.writeString(file, original);

            VariableSubstitution.processDirectory(tempDir, Map.of("PORT", "25565"));

            assertEquals(original, Files.readString(file));
        }

        @Test
        @DisplayName("Processes multiple text files in a directory")
        void processesMultipleFiles() throws IOException {
            Files.writeString(tempDir.resolve("a.properties"), "port=%PORT%");
            Files.writeString(tempDir.resolve("b.yml"), "host: %HOST%");

            VariableSubstitution.processDirectory(tempDir, Map.of("PORT", "9000", "HOST", "localhost"));

            assertEquals("port=9000", Files.readString(tempDir.resolve("a.properties")));
            assertEquals("host: localhost", Files.readString(tempDir.resolve("b.yml")));
        }

        @Test
        @DisplayName("Processes files in subdirectories recursively")
        void processesSubdirectories() throws IOException {
            Path sub = tempDir.resolve("config");
            Files.createDirectories(sub);
            Path file = sub.resolve("server.yml");
            Files.writeString(file, "port: %PORT%");

            VariableSubstitution.processDirectory(tempDir, Map.of("PORT", "7777"));

            assertEquals("port: 7777", Files.readString(file));
        }

        @Test
        @DisplayName("Skips non-text file extensions")
        void skipsNonTextExtensions() throws IOException {
            // .jar, .png -- should not be touched (and won't fail either)
            Path bin = tempDir.resolve("server.jar");
            byte[] binaryContent = new byte[] {0x50, 0x4B, 0x03, 0x04}; // PK header
            Files.write(bin, binaryContent);

            // Should not throw, just skip
            assertDoesNotThrow(() -> VariableSubstitution.processDirectory(tempDir, Map.of("KEY", "value")));

            assertArrayEquals(binaryContent, Files.readAllBytes(bin));
        }

        @Test
        @DisplayName("All supported text extensions are processed")
        void allSupportedExtensionsProcessed() throws IOException {
            String[] extensions = {".properties", ".yml", ".yaml", ".toml", ".json", ".cfg", ".conf", ".txt"};
            for (String ext : extensions) {
                Files.writeString(tempDir.resolve("file" + ext), "key=%VALUE%");
            }

            VariableSubstitution.processDirectory(tempDir, Map.of("VALUE", "replaced"));

            for (String ext : extensions) {
                assertEquals(
                        "key=replaced",
                        Files.readString(tempDir.resolve("file" + ext)),
                        "Extension " + ext + " was not processed");
            }
        }

        @Test
        @DisplayName("Empty variable map leaves files unchanged")
        void emptyVariableMap() throws IOException {
            Path file = tempDir.resolve("app.properties");
            String content = "port=%PORT%\nhost=%HOST%";
            Files.writeString(file, content);

            VariableSubstitution.processDirectory(tempDir, Map.of());

            assertEquals(content, Files.readString(file));
        }

        @Test
        @DisplayName("Empty directory is processed without error")
        void emptyDirectory() {
            assertDoesNotThrow(() -> VariableSubstitution.processDirectory(tempDir, Map.of("K", "v")));
        }
    }
}
