package me.prexorjustin.prexorcloud.daemon.template;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import me.prexorjustin.prexorcloud.daemon.template.ServerConfigPatcher.ConfigPatch;
import me.prexorjustin.prexorcloud.daemon.template.ServerConfigPatcher.ConfigPatch.Op;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ServerConfigPatcher (rule engine)")
class ServerConfigPatcherTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("SET rewrites an existing flat properties key and appends an absent one")
    void setProperties() throws Exception {
        Files.writeString(tempDir.resolve("server.properties"), """
                online-mode=true
                server-port=25565
                """);

        ServerConfigPatcher.patch(
                tempDir,
                List.of(
                        new ConfigPatch("server.properties", Op.SET, "server-port", "30001"),
                        new ConfigPatch("server.properties", Op.SET, "max-players", "120")));

        String content = Files.readString(tempDir.resolve("server.properties"));
        assertTrue(content.contains("server-port=30001"), "existing key rewritten");
        assertTrue(content.contains("max-players=120"), "absent SET key appended");
        assertTrue(content.contains("online-mode=true"), "untouched keys remain");
    }

    @Test
    @DisplayName("REPLACE rewrites only an existing key and never creates one")
    void replaceOnlyIfPresent() throws Exception {
        Files.writeString(tempDir.resolve("server.properties"), "online-mode=true\n");

        ServerConfigPatcher.patch(
                tempDir,
                List.of(
                        new ConfigPatch("server.properties", Op.REPLACE, "online-mode", "false"),
                        new ConfigPatch("server.properties", Op.REPLACE, "max-players", "120")));

        String content = Files.readString(tempDir.resolve("server.properties"));
        assertTrue(content.contains("online-mode=false"));
        assertFalse(content.contains("max-players"), "absent REPLACE key is not created");
    }

    @Test
    @DisplayName("SET on a toml file renders and replaces a top-level key")
    void setToml() throws Exception {
        Files.writeString(tempDir.resolve("velocity.toml"), """
                bind = "0.0.0.0:25565"
                show-max-players = 100
                """);

        ServerConfigPatcher.patch(
                tempDir,
                List.of(
                        new ConfigPatch("velocity.toml", Op.SET, "show-max-players", "250"),
                        new ConfigPatch("velocity.toml", Op.SET, "motd", "<green>Proxy</green>")));

        String content = Files.readString(tempDir.resolve("velocity.toml"));
        assertTrue(content.contains("show-max-players = 250"), "numeric value left unquoted");
        assertTrue(content.contains("motd = \"<green>Proxy</green>\""), "absent string key appended and quoted");
    }

    @Test
    @DisplayName("dotted YAML path patches the nested key without clobbering a like-named sibling")
    void nestedYaml() throws Exception {
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
                List.of(
                        new ConfigPatch("config.yml", Op.REPLACE, "remote.address", "10.0.0.5"),
                        new ConfigPatch("config.yml", Op.REPLACE, "remote.port", "30100")));

        List<String> lines = Files.readAllLines(tempDir.resolve("config.yml"));
        assertTrue(lines.contains("  port: 19132"), "bedrock.port preserved");
        assertTrue(lines.contains("  address: \"10.0.0.5\""), "remote.address patched");
        assertTrue(lines.contains("  port: 30100"), "remote.port patched");
        assertTrue(lines.contains("  auth-type: floodgate"), "untouched remote keys remain");
    }

    @Test
    @DisplayName("absent dotted YAML path is skipped, not appended at the wrong nesting")
    void nestedYamlSkipsMissing() throws Exception {
        Files.writeString(tempDir.resolve("config.yml"), """
                bedrock:
                  address: 0.0.0.0
                  port: 19132
                """);

        ServerConfigPatcher.patch(
                tempDir, List.of(new ConfigPatch("config.yml", Op.REPLACE, "remote.address", "10.0.0.5")));

        assertFalse(
                Files.readString(tempDir.resolve("config.yml")).contains("10.0.0.5"),
                "absent nested path must not be blindly appended");
    }

    @Test
    @DisplayName("REGEX substitutes every per-line match with the replacement")
    void regex() throws Exception {
        Files.writeString(tempDir.resolve("bukkit.yml"), """
                settings:
                  allow-end: false
                  warn-on-overload: false
                """);

        ServerConfigPatcher.patch(tempDir, List.of(new ConfigPatch("bukkit.yml", Op.REGEX, ": false", ": true")));

        String content = Files.readString(tempDir.resolve("bukkit.yml"));
        assertTrue(content.contains("allow-end: true"));
        assertTrue(content.contains("warn-on-overload: true"));
    }

    @Test
    @DisplayName("%FORWARDING_SECRET% resolves from forwarding.secret at apply time")
    void forwardingSecretSubstituted() throws Exception {
        Files.writeString(tempDir.resolve("forwarding.secret"), "s3cr3t-hmac\n");
        Path config = tempDir.resolve("config");
        Files.createDirectories(config);
        Files.writeString(config.resolve("paper-global.yml"), """
                proxies:
                  velocity:
                    enabled: true
                    online-mode: false
                    secret: ''
                """);

        ServerConfigPatcher.patch(
                tempDir,
                List.of(new ConfigPatch(
                        "config/paper-global.yml", Op.REPLACE, "proxies.velocity.secret", "%FORWARDING_SECRET%")));

        String content = Files.readString(config.resolve("paper-global.yml"));
        assertTrue(content.contains("secret: \"s3cr3t-hmac\""), "secret resolved from forwarding.secret");
        assertFalse(content.contains("FORWARDING_SECRET"), "placeholder never written literally");
    }

    @Test
    @DisplayName("a %FORWARDING_SECRET% rule is skipped when forwarding.secret is absent")
    void forwardingSecretMissing() throws Exception {
        Path config = tempDir.resolve("config");
        Files.createDirectories(config);
        Files.writeString(config.resolve("paper-global.yml"), """
                proxies:
                  velocity:
                    secret: ''
                """);

        ServerConfigPatcher.patch(
                tempDir,
                List.of(new ConfigPatch(
                        "config/paper-global.yml", Op.REPLACE, "proxies.velocity.secret", "%FORWARDING_SECRET%")));

        String content = Files.readString(config.resolve("paper-global.yml"));
        assertFalse(content.contains("FORWARDING_SECRET"), "placeholder never written literally");
        assertTrue(content.contains("secret: ''"), "secret left at its shipped default rather than broken");
    }

    @Test
    @DisplayName("rejects a patch whose file escapes the instance dir")
    void rejectsEscape() {
        assertThrows(
                SecurityException.class,
                () -> ServerConfigPatcher.patch(
                        tempDir, List.of(new ConfigPatch("../evil.properties", Op.SET, "x", "y"))));
    }
}
