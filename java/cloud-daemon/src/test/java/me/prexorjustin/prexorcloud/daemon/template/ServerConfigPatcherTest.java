package me.prexorjustin.prexorcloud.daemon.template;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ServerConfigPatcher")
class ServerConfigPatcherTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("applies controller-resolved patches to server.properties")
    void patchesServerProperties() throws Exception {
        Files.writeString(tempDir.resolve("server.properties"), """
                online-mode=true
                server-port=25565
                motd=Default
                """);

        ServerConfigPatcher.patch(
                tempDir,
                "paper",
                List.of(
                        new ServerConfigPatcher.ConfigPatch("server.properties", "server-port", "30001"),
                        new ServerConfigPatcher.ConfigPatch("server.properties", "motd", "Lobby MOTD"),
                        new ServerConfigPatcher.ConfigPatch("server.properties", "max-players", "120")));

        String content = Files.readString(tempDir.resolve("server.properties"));
        assertTrue(content.contains("online-mode=false"));
        assertTrue(content.contains("server-port=30001"));
        assertTrue(content.contains("motd=Lobby MOTD"));
        assertTrue(content.contains("max-players=120"));
    }

    @Test
    @DisplayName("applies controller-resolved patches to velocity.toml")
    void patchesVelocityToml() throws Exception {
        Files.writeString(tempDir.resolve("velocity.toml"), """
                bind = "0.0.0.0:25565"
                motd = "<aqua>Default</aqua>"
                show-max-players = 100
                """);

        ServerConfigPatcher.patch(
                tempDir,
                "velocity",
                List.of(
                        new ServerConfigPatcher.ConfigPatch("velocity.toml", "bind", "0.0.0.0:30100"),
                        new ServerConfigPatcher.ConfigPatch("velocity.toml", "motd", "<green>Proxy</green>"),
                        new ServerConfigPatcher.ConfigPatch("velocity.toml", "show-max-players", "250")));

        String content = Files.readString(tempDir.resolve("velocity.toml"));
        assertTrue(content.contains("bind = \"0.0.0.0:30100\""));
        assertTrue(content.contains("motd = \"<green>Proxy</green>\""));
        assertTrue(content.contains("show-max-players = 250"));
    }

    @Test
    @DisplayName("applies controller-resolved patches to bungeecord config.yml")
    void patchesBungeecordConfig() throws Exception {
        Files.writeString(tempDir.resolve("config.yml"), """
                listeners:
                  - host: 0.0.0.0:25565
                    motd: "&bDefault"
                    max_players: 100
                online_mode: true
                ip_forward: false
                """);

        ServerConfigPatcher.patch(
                tempDir,
                "bungeecord",
                List.of(
                        new ServerConfigPatcher.ConfigPatch("config.yml", "host", "0.0.0.0:30100"),
                        new ServerConfigPatcher.ConfigPatch("config.yml", "motd", "<gold>Bungee</gold>"),
                        new ServerConfigPatcher.ConfigPatch("config.yml", "max_players", "250")));

        String content = Files.readString(tempDir.resolve("config.yml"));
        assertTrue(content.contains("host: \"0.0.0.0:30100\""));
        assertTrue(content.contains("motd: \"<gold>Bungee</gold>\""));
        assertTrue(content.contains("max_players: 250"));
        assertTrue(content.contains("online_mode: false"));
        assertTrue(content.contains("ip_forward: true"));
    }

    @Test
    @DisplayName("patches geyser remote.* without clobbering the like-named bedrock.port")
    void patchesGeyserConfigNestedKeys() throws Exception {
        Files.writeString(tempDir.resolve("config.yml"), """
                bedrock:
                  address: 0.0.0.0
                  port: 19132
                remote:
                  address: 127.0.0.1
                  port: 25565
                  auth-type: floodgate
                """);

        ServerConfigPatcher.patch(
                tempDir,
                "geyser",
                List.of(
                        new ServerConfigPatcher.ConfigPatch("config.yml", "remote.address", "10.0.0.5"),
                        new ServerConfigPatcher.ConfigPatch("config.yml", "remote.port", "30100")));

        List<String> lines = Files.readAllLines(tempDir.resolve("config.yml"));
        // bedrock.port is untouched; only the nested remote section changed.
        assertTrue(lines.contains("  port: 19132"), "bedrock.port must be preserved");
        assertTrue(lines.contains("  address: \"10.0.0.5\""), "remote.address must be patched");
        assertTrue(lines.contains("  port: 30100"), "remote.port must be patched");
        assertTrue(lines.contains("  auth-type: floodgate"), "untouched remote keys remain");
    }

    @Test
    @DisplayName("skips geyser keys that are not present rather than appending at the wrong nesting")
    void geyserSkipsMissingKeys() throws Exception {
        Files.writeString(tempDir.resolve("config.yml"), """
                bedrock:
                  address: 0.0.0.0
                  port: 19132
                """);

        ServerConfigPatcher.patch(
                tempDir,
                "geyser",
                List.of(new ServerConfigPatcher.ConfigPatch("config.yml", "remote.address", "10.0.0.5")));

        String content = Files.readString(tempDir.resolve("config.yml"));
        assertTrue(!content.contains("10.0.0.5"), "absent nested path must not be blindly appended");
    }
}
