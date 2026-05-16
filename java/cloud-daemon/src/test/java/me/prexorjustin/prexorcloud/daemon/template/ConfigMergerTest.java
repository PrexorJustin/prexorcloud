package me.prexorjustin.prexorcloud.daemon.template;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigMergerTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    @Nested
    @DisplayName("YAML deep-merge")
    class YamlMerge {

        @Test
        @DisplayName("overlay overrides single nested key, base keys preserved")
        void overlayOverridesSingleKey(@TempDir Path tempDir) throws Exception {
            // Base: base-hub template's paper-global.yml
            Path base = tempDir.resolve("paper-global.yml");
            Files.writeString(base, """
                    misc:
                      enable-nether: true
                      max-joins-per-tick: 5
                    proxies:
                      velocity:
                        enabled: true
                    """);

            // Overlay: proxy template only changes enable-nether
            byte[] overlay = """
                    misc:
                      enable-nether: false
                    """.getBytes(StandardCharsets.UTF_8);

            ConfigMerger.merge(base, overlay);

            var result = YAML.readTree(base.toFile());

            // Overlay key applied
            assertFalse(result.at("/misc/enable-nether").asBoolean());
            // Base-only keys preserved
            assertEquals(5, result.at("/misc/max-joins-per-tick").asInt());
            assertTrue(result.at("/proxies/velocity/enabled").asBoolean());
        }

        @Test
        @DisplayName("overlay adds new keys without affecting existing")
        void overlayAddsNewKeys(@TempDir Path tempDir) throws Exception {
            Path base = tempDir.resolve("config.yml");
            Files.writeString(base, """
                    server:
                      port: 25565
                    """);

            byte[] overlay = """
                    server:
                      motd: "Hello"
                    logging:
                      level: DEBUG
                    """.getBytes(StandardCharsets.UTF_8);

            ConfigMerger.merge(base, overlay);

            var result = YAML.readTree(base.toFile());
            assertEquals(25565, result.at("/server/port").asInt());
            assertEquals("Hello", result.at("/server/motd").asText());
            assertEquals("DEBUG", result.at("/logging/level").asText());
        }

        @Test
        @DisplayName("overlay replaces arrays entirely")
        void overlayReplacesArrays(@TempDir Path tempDir) throws Exception {
            Path base = tempDir.resolve("config.yml");
            Files.writeString(base, """
                    worlds:
                      - world
                      - world_nether
                    """);

            byte[] overlay = """
                    worlds:
                      - lobby
                    """.getBytes(StandardCharsets.UTF_8);

            ConfigMerger.merge(base, overlay);

            var result = YAML.readTree(base.toFile());
            assertEquals(1, result.at("/worlds").size());
            assertEquals("lobby", result.at("/worlds/0").asText());
        }

        @Test
        @DisplayName("three-level deep merge")
        void deepNestedMerge(@TempDir Path tempDir) throws Exception {
            Path base = tempDir.resolve("paper-global.yml");
            Files.writeString(base, """
                    proxies:
                      velocity:
                        enabled: true
                        online-mode: true
                        secret: base-secret
                    misc:
                      enable-nether: true
                    """);

            byte[] overlay = """
                    proxies:
                      velocity:
                        online-mode: false
                    """.getBytes(StandardCharsets.UTF_8);

            ConfigMerger.merge(base, overlay);

            var result = YAML.readTree(base.toFile());
            // Changed
            assertFalse(result.at("/proxies/velocity/online-mode").asBoolean());
            // Preserved
            assertTrue(result.at("/proxies/velocity/enabled").asBoolean());
            assertEquals("base-secret", result.at("/proxies/velocity/secret").asText());
            assertTrue(result.at("/misc/enable-nether").asBoolean());
        }
    }

    @Nested
    @DisplayName("JSON deep-merge")
    class JsonMerge {

        @Test
        @DisplayName("overlay overrides nested key, base keys preserved")
        void overlayOverridesKey(@TempDir Path tempDir) throws Exception {
            Path base = tempDir.resolve("config.json");
            Files.writeString(base, """
                    {
                      "server": {
                        "port": 25565,
                        "motd": "Welcome"
                      },
                      "debug": false
                    }
                    """);

            byte[] overlay = """
                    {
                      "server": {
                        "motd": "Custom Server"
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);

            ConfigMerger.merge(base, overlay);

            var result = JSON.readTree(base.toFile());
            assertEquals(25565, result.at("/server/port").asInt());
            assertEquals("Custom Server", result.at("/server/motd").asText());
            assertFalse(result.at("/debug").asBoolean());
        }

        @Test
        @DisplayName("deep nested JSON merge")
        void deepNestedMerge(@TempDir Path tempDir) throws Exception {
            Path base = tempDir.resolve("data.json");
            Files.writeString(base, """
                    {
                      "a": {
                        "b": {
                          "c": 1,
                          "d": 2
                        },
                        "e": 3
                      }
                    }
                    """);

            byte[] overlay = """
                    {
                      "a": {
                        "b": {
                          "c": 99
                        }
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);

            ConfigMerger.merge(base, overlay);

            var result = JSON.readTree(base.toFile());
            assertEquals(99, result.at("/a/b/c").asInt());
            assertEquals(2, result.at("/a/b/d").asInt());
            assertEquals(3, result.at("/a/e").asInt());
        }

        @Test
        @DisplayName("JSON arrays are replaced entirely")
        void arraysReplaced(@TempDir Path tempDir) throws Exception {
            Path base = tempDir.resolve("config.json");
            Files.writeString(base, """
                    {"items": [1, 2, 3], "keep": true}
                    """);

            byte[] overlay = """
                    {"items": [42]}
                    """.getBytes(StandardCharsets.UTF_8);

            ConfigMerger.merge(base, overlay);

            var result = JSON.readTree(base.toFile());
            assertEquals(1, result.at("/items").size());
            assertEquals(42, result.at("/items/0").asInt());
            assertTrue(result.at("/keep").asBoolean());
        }
    }

    @Nested
    @DisplayName("TOML deep-merge")
    class TomlMerge {

        @Test
        @DisplayName("overlay overrides TOML key, base keys preserved")
        void overlayOverridesKey(@TempDir Path tempDir) throws Exception {
            Path base = tempDir.resolve("config.toml");
            Files.writeString(base, """
                    [server]
                    port = 25565
                    motd = "Welcome"
                    """);

            byte[] overlay = """
                    [server]
                    motd = "Custom"
                    """.getBytes(StandardCharsets.UTF_8);

            ConfigMerger.merge(base, overlay);

            var toml = new com.fasterxml.jackson.dataformat.toml.TomlMapper();
            var result = toml.readTree(base.toFile());
            assertEquals(25565, result.at("/server/port").asInt());
            assertEquals("Custom", result.at("/server/motd").asText());
        }

        @Test
        @DisplayName("deep nested TOML merge")
        void deepNestedMerge(@TempDir Path tempDir) throws Exception {
            var toml = new com.fasterxml.jackson.dataformat.toml.TomlMapper();

            Path base = tempDir.resolve("settings.toml");
            Files.writeString(base, """
                    [database]
                    host = "localhost"
                    port = 5432

                    [database.pool]
                    min = 5
                    max = 20
                    """);

            byte[] overlay = """
                    [database.pool]
                    max = 50
                    """.getBytes(StandardCharsets.UTF_8);

            ConfigMerger.merge(base, overlay);

            var result = toml.readTree(base.toFile());
            assertEquals("localhost", result.at("/database/host").asText());
            assertEquals(5432, result.at("/database/port").asInt());
            assertEquals(5, result.at("/database/pool/min").asInt());
            assertEquals(50, result.at("/database/pool/max").asInt());
        }
    }

    @Nested
    @DisplayName("Text line-merge")
    class TextMerge {

        @Test
        @DisplayName("unique overlay lines are appended")
        void appendsUniqueLines(@TempDir Path tempDir) throws Exception {
            Path base = tempDir.resolve("whitelist.txt");
            Files.writeString(base, """
                    player1
                    player2
                    """);

            byte[] overlay = """
                    player2
                    player3
                    """.getBytes(StandardCharsets.UTF_8);

            ConfigMerger.merge(base, overlay);

            List<String> lines = Files.readAllLines(base);
            assertEquals(List.of("player1", "player2", "player3"), lines);
        }

        @Test
        @DisplayName("works with .cfg files")
        void cfgFiles(@TempDir Path tempDir) throws Exception {
            Path base = tempDir.resolve("options.cfg");
            Files.writeString(base, """
                    option1=true
                    option2=false
                    """);

            byte[] overlay = """
                    option3=yes
                    """.getBytes(StandardCharsets.UTF_8);

            ConfigMerger.merge(base, overlay);

            List<String> lines = Files.readAllLines(base);
            assertTrue(lines.contains("option1=true"));
            assertTrue(lines.contains("option2=false"));
            assertTrue(lines.contains("option3=yes"));
        }

        @Test
        @DisplayName("works with .conf files")
        void confFiles(@TempDir Path tempDir) throws Exception {
            Path base = tempDir.resolve("server.conf");
            Files.writeString(base, "line1\nline2\n");

            byte[] overlay = "line2\nline3\n".getBytes(StandardCharsets.UTF_8);

            ConfigMerger.merge(base, overlay);

            List<String> lines = Files.readAllLines(base);
            assertEquals(List.of("line1", "line2", "line3"), lines);
        }

        @Test
        @DisplayName("works with .ini files")
        void iniFiles(@TempDir Path tempDir) throws Exception {
            Path base = tempDir.resolve("config.ini");
            Files.writeString(base, "[section]\nkey=val\n");

            byte[] overlay = "[section]\nnewkey=newval\n".getBytes(StandardCharsets.UTF_8);

            ConfigMerger.merge(base, overlay);

            List<String> lines = Files.readAllLines(base);
            assertTrue(lines.contains("key=val"));
            assertTrue(lines.contains("newkey=newval"));
        }

        @Test
        @DisplayName("works with .list files")
        void listFiles(@TempDir Path tempDir) throws Exception {
            Path base = tempDir.resolve("banned.list");
            Files.writeString(base, "badplayer1\n");

            byte[] overlay = "badplayer2\n".getBytes(StandardCharsets.UTF_8);

            ConfigMerger.merge(base, overlay);

            List<String> lines = Files.readAllLines(base);
            assertEquals(List.of("badplayer1", "badplayer2"), lines);
        }
    }

    @Nested
    @DisplayName("deepMerge node operation")
    class DeepMergeNode {

        @Test
        @DisplayName("does not mutate the base node")
        void doesNotMutateBase() throws Exception {
            ObjectNode base = (ObjectNode) YAML.readTree("{a: 1, b: 2}");
            ObjectNode overlay = (ObjectNode) YAML.readTree("{b: 99}");

            ObjectNode result = ConfigMerger.deepMerge(base, overlay);

            assertEquals(2, base.get("b").asInt(), "base should not be mutated");
            assertEquals(99, result.get("b").asInt());
            assertEquals(1, result.get("a").asInt());
        }
    }

    @Nested
    @DisplayName("Properties merge")
    class PropertiesMerge {

        @Test
        @DisplayName("overlay keys override base, base-only keys preserved")
        void propertiesMerge(@TempDir Path tempDir) throws Exception {
            Path base = tempDir.resolve("server.properties");
            Files.writeString(base, """
                    online-mode=true
                    server-port=25565
                    motd=Welcome
                    """);

            byte[] overlay = """
                    online-mode=false
                    max-players=50
                    """.getBytes(StandardCharsets.UTF_8);

            ConfigMerger.merge(base, overlay);

            var props = new java.util.Properties();
            try (var reader = Files.newBufferedReader(base)) {
                props.load(reader);
            }

            assertEquals("false", props.getProperty("online-mode"));
            assertEquals("25565", props.getProperty("server-port"));
            assertEquals("50", props.getProperty("max-players"));
        }
    }

    @Nested
    @DisplayName("isMergeable")
    class Mergeable {

        @Test
        void yamlFilesAreMergeable() {
            assertTrue(ConfigMerger.isMergeable(Path.of("config/paper-global.yml")));
            assertTrue(ConfigMerger.isMergeable(Path.of("spigot.yaml")));
        }

        @Test
        void jsonFilesAreMergeable() {
            assertTrue(ConfigMerger.isMergeable(Path.of("data.json")));
            assertTrue(ConfigMerger.isMergeable(Path.of("config/permissions.json")));
        }

        @Test
        void tomlFilesAreMergeable() {
            assertTrue(ConfigMerger.isMergeable(Path.of("config.toml")));
        }

        @Test
        void propertiesFilesAreMergeable() {
            assertTrue(ConfigMerger.isMergeable(Path.of("server.properties")));
        }

        @Test
        void textFilesAreMergeable() {
            assertTrue(ConfigMerger.isMergeable(Path.of("whitelist.txt")));
            assertTrue(ConfigMerger.isMergeable(Path.of("options.cfg")));
            assertTrue(ConfigMerger.isMergeable(Path.of("server.conf")));
            assertTrue(ConfigMerger.isMergeable(Path.of("settings.ini")));
            assertTrue(ConfigMerger.isMergeable(Path.of("banned.list")));
        }

        @Test
        void binaryFilesAreNotMergeable() {
            assertFalse(ConfigMerger.isMergeable(Path.of("plugins/Plugin.jar")));
            assertFalse(ConfigMerger.isMergeable(Path.of("server.jar")));
            assertFalse(ConfigMerger.isMergeable(Path.of("world/level.dat")));
            assertFalse(ConfigMerger.isMergeable(Path.of("icon.png")));
        }
    }
}
