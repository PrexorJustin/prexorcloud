package me.prexorjustin.prexorcloud.common.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("YamlConfigLoader")
class YamlConfigLoaderTest {

    public record SimpleConfig(String name, int port) {

        public SimpleConfig() {
            this("default", 8080);
        }
    }

    @Nested
    @DisplayName("load")
    class Load {

        @Test
        @DisplayName("Loads existing YAML file into record")
        void loadsExistingFile(@TempDir Path tempDir) throws IOException {
            Path config = tempDir.resolve("test.yml");
            Files.writeString(config, "name: myserver\nport: 25565\n");

            var result = YamlConfigLoader.load(config, SimpleConfig.class, "nonexistent");
            assertEquals("myserver", result.name());
            assertEquals(25565, result.port());
        }

        @Test
        @DisplayName("Ignores unknown properties")
        void ignoresUnknownProperties(@TempDir Path tempDir) throws IOException {
            Path config = tempDir.resolve("test.yml");
            Files.writeString(config, "name: server\nport: 80\nunknownField: value\n");

            var result = YamlConfigLoader.load(config, SimpleConfig.class, "nonexistent");
            assertEquals("server", result.name());
            assertEquals(80, result.port());
        }

        @Test
        @DisplayName("Throws when file does not exist and no classpath default")
        void throwsWhenNoDefault(@TempDir Path tempDir) {
            Path config = tempDir.resolve("missing.yml");
            assertThrows(
                    IOException.class, () -> YamlConfigLoader.load(config, SimpleConfig.class, "does-not-exist.yml"));
        }
    }

    @Nested
    @DisplayName("mapper")
    class Mapper {

        @Test
        @DisplayName("Returns non-null shared mapper")
        void returnsSharedMapper() {
            assertNotNull(YamlConfigLoader.mapper());
            assertSame(YamlConfigLoader.mapper(), YamlConfigLoader.mapper());
        }
    }
}
