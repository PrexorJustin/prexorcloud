package me.prexorjustin.prexorcloud.daemon.template;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Patches server configuration files using line-level text edits that preserve
 * the original file format exactly. This is critical because Paper uses a
 * {@code _version} key to detect config changes — reformatting the file (e.g.
 * with a YAML serializer) causes Paper to regenerate it with defaults.
 *
 * <p>
 * Runs after template unpacking and bootstrap cache application, right before
 * the process is launched. The bootstrap cache provides Paper's full generated
 * config with the correct {@code _version}, and this patcher ensures the
 * cloud-required values (velocity forwarding, secret, online-mode) are set.
 */
public final class ServerConfigPatcher {

    private static final Logger logger = LoggerFactory.getLogger(ServerConfigPatcher.class);

    public record ConfigPatch(String file, String key, String value) {}

    private ServerConfigPatcher() {}

    /**
     * Patches configuration files in the instance directory based on the config
     * format.
     */
    public static void patch(Path instanceDir, String configFormat) {
        patch(instanceDir, configFormat, List.of());
    }

    /**
     * Applies controller-resolved patches after the runtime-specific baseline
     * patching has completed.
     */
    public static void patch(Path instanceDir, String configFormat, List<ConfigPatch> configPatches) {
        if (configFormat == null || configFormat.isBlank()) return;

        switch (configFormat.toLowerCase()) {
            case "paper" -> {
                patchPaperGlobalConfig(instanceDir);
                patchServerProperties(instanceDir);
            }
            case "spigot" -> {
                patchSpigotConfig(instanceDir);
                patchServerProperties(instanceDir);
            }
            case "bungeecord" -> patchBungeecordConfig(instanceDir);
        }
        applyResolvedPatches(instanceDir, configPatches);
    }

    /**
     * Patches the velocity forwarding section in {@code config/paper-global.yml}.
     *
     * <p>
     * Sets three values in the {@code proxies.velocity} section:
     * <ul>
     * <li>{@code enabled: true} — activate Velocity modern forwarding</li>
     * <li>{@code online-mode: false} — proxy handles authentication</li>
     * <li>{@code secret: <value>} — the shared HMAC secret, read from
     * {@code forwarding.secret} in the instance root</li>
     * </ul>
     *
     * <p>
     * Uses a state machine to locate the {@code velocity:} section and patch only
     * the fields within it, preserving all other content byte-for-byte.
     */
    static void patchPaperGlobalConfig(Path instanceDir) {
        patchPaperGlobalConfig(instanceDir, false);
    }

    static void patchPaperGlobalConfig(Path instanceDir, boolean skipSecret) {
        Path configFile = instanceDir.resolve("config").resolve("paper-global.yml");
        if (!Files.exists(configFile)) {
            logger.warn("paper-global.yml not found at {} -- cannot patch velocity forwarding", configFile);
            return;
        }

        // Read the forwarding secret from the template-provided file
        String secret = "";
        if (!skipSecret) {
            Path secretFile = instanceDir.resolve("forwarding.secret");
            if (Files.exists(secretFile)) {
                try {
                    secret = Files.readString(secretFile).trim();
                } catch (IOException e) {
                    logger.warn("Failed to read forwarding.secret: {}", e.getMessage());
                }
            } else {
                logger.warn("forwarding.secret not found in {} -- velocity forwarding will fail", instanceDir);
            }
        }

        try {
            var lines = new ArrayList<>(Files.readAllLines(configFile));
            boolean changed = false;
            boolean inVelocitySection = false;
            int velocityIndent = -1;

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.stripLeading();
                int indent = line.length() - trimmed.length();

                // Detect entering the velocity: section
                if (trimmed.startsWith("velocity:") && !inVelocitySection) {
                    inVelocitySection = true;
                    velocityIndent = indent;
                    continue;
                }

                // If we're in the velocity section, check if we've left it
                // (a line with same or less indentation that isn't a velocity field)
                if (inVelocitySection && !trimmed.isEmpty() && indent <= velocityIndent) {
                    inVelocitySection = false;
                    continue;
                }

                if (!inVelocitySection) continue;

                // Patch fields within the velocity section
                if (trimmed.startsWith("enabled:") && !trimmed.equals("enabled: true")) {
                    String prefix = line.substring(0, indent);
                    lines.set(i, prefix + "enabled: true");
                    changed = true;
                } else if (trimmed.startsWith("online-mode:") && !trimmed.equals("online-mode: false")) {
                    String prefix = line.substring(0, indent);
                    lines.set(i, prefix + "online-mode: false");
                    changed = true;
                } else if (trimmed.startsWith("secret:") && !secret.isEmpty()) {
                    String expected = "secret: '" + secret + "'";
                    if (!trimmed.equals(expected)) {
                        String prefix = line.substring(0, indent);
                        lines.set(i, prefix + expected);
                        changed = true;
                    }
                }
            }

            if (changed) {
                Files.write(configFile, lines);
                logger.debug("Patched paper-global.yml: velocity forwarding enabled, secret set, online-mode=false");
            }
        } catch (IOException e) {
            logger.warn("Failed to patch paper-global.yml: {}", e.getMessage());
        }
    }

    /**
     * Enables BungeeCord forwarding in {@code spigot.yml}.
     */
    static void patchSpigotConfig(Path instanceDir) {
        Path configFile = instanceDir.resolve("spigot.yml");
        if (!Files.exists(configFile)) return;

        try {
            var lines = new ArrayList<>(Files.readAllLines(configFile));
            boolean changed = false;
            boolean inSettings = false;

            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).stripLeading();

                if (trimmed.startsWith("settings:")) {
                    inSettings = true;
                    continue;
                }

                if (inSettings && trimmed.startsWith("bungeecord:") && trimmed.contains("false")) {
                    lines.set(i, lines.get(i).replace("false", "true"));
                    changed = true;
                    break;
                }
            }

            if (changed) {
                Files.write(configFile, lines);
                logger.debug("Patched spigot.yml: bungeecord forwarding enabled");
            }
        } catch (IOException e) {
            logger.warn("Failed to patch spigot.yml: {}", e.getMessage());
        }
    }

    /**
     * Ensures {@code online-mode=false} in {@code server.properties}.
     */
    private static void patchServerProperties(Path instanceDir) {
        Path propsFile = instanceDir.resolve("server.properties");
        if (!Files.exists(propsFile)) return;

        try {
            var lines = new ArrayList<>(Files.readAllLines(propsFile));
            boolean changed = false;

            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().startsWith("online-mode=")
                        && !lines.get(i).trim().equals("online-mode=false")) {
                    lines.set(i, "online-mode=false");
                    changed = true;
                    break;
                }
            }

            if (changed) {
                Files.write(propsFile, lines);
                logger.debug("Patched server.properties: online-mode=false");
            }
        } catch (IOException e) {
            logger.warn("Failed to patch server.properties: {}", e.getMessage());
        }
    }

    static void patchBungeecordConfig(Path instanceDir) {
        Path configFile = instanceDir.resolve("config.yml");
        if (!Files.exists(configFile)) return;

        try {
            var lines = new ArrayList<>(Files.readAllLines(configFile));
            boolean changed = false;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).stripLeading();
                if (trimmed.startsWith("online_mode:") && !trimmed.equals("online_mode: false")) {
                    String prefix = lines.get(i).substring(0, lines.get(i).length() - trimmed.length());
                    lines.set(i, prefix + "online_mode: false");
                    changed = true;
                } else if (trimmed.startsWith("ip_forward:") && !trimmed.equals("ip_forward: true")) {
                    String prefix = lines.get(i).substring(0, lines.get(i).length() - trimmed.length());
                    lines.set(i, prefix + "ip_forward: true");
                    changed = true;
                }
            }
            if (changed) {
                Files.write(configFile, lines);
                logger.debug("Patched bungeecord config.yml: online_mode=false, ip_forward=true");
            }
        } catch (IOException e) {
            logger.warn("Failed to patch bungeecord config.yml: {}", e.getMessage());
        }
    }

    private static void applyResolvedPatches(Path instanceDir, List<ConfigPatch> configPatches) {
        for (ConfigPatch configPatch : configPatches) {
            if (configPatch.file() == null || configPatch.file().isBlank()) {
                continue;
            }
            Path target = instanceDir.resolve(configPatch.file()).normalize();
            if (!target.startsWith(instanceDir.normalize())) {
                throw new SecurityException("Config patch escapes instance dir: " + configPatch.file());
            }
            if (!Files.exists(target)) {
                logger.warn("Config patch target does not exist: {}", configPatch.file());
                continue;
            }

            String lowerName = target.getFileName().toString().toLowerCase();
            try {
                if (lowerName.endsWith(".properties")) {
                    applyPropertiesPatch(target, configPatch.key(), configPatch.value());
                } else if (lowerName.endsWith(".toml")) {
                    applyTomlPatch(target, configPatch.key(), configPatch.value());
                } else if (lowerName.endsWith(".yml") || lowerName.endsWith(".yaml")) {
                    applyYamlPatch(target, configPatch.key(), configPatch.value());
                } else {
                    logger.warn("Unsupported config patch target: {}", configPatch.file());
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to patch config file " + configPatch.file(), e);
            }
        }
    }

    private static void applyPropertiesPatch(Path file, String key, String value) throws IOException {
        var lines = new ArrayList<>(Files.readAllLines(file));
        boolean changed = false;
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith(key + "=")) {
                lines.set(i, key + "=" + value);
                found = true;
                changed = true;
                break;
            }
        }
        if (!found) {
            lines.add(key + "=" + value);
            changed = true;
        }
        if (changed) {
            Files.write(file, lines);
        }
    }

    private static void applyTomlPatch(Path file, String key, String value) throws IOException {
        var lines = new ArrayList<>(Files.readAllLines(file));
        boolean changed = false;
        boolean found = false;
        String renderedValue = renderTomlValue(value);
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith(key + " =")) {
                lines.set(i, key + " = " + renderedValue);
                found = true;
                changed = true;
                break;
            }
        }
        if (!found) {
            lines.add(key + " = " + renderedValue);
            changed = true;
        }
        if (changed) {
            Files.write(file, lines);
        }
    }

    private static void applyYamlPatch(Path file, String key, String value) throws IOException {
        var lines = new ArrayList<>(Files.readAllLines(file));
        boolean changed = false;
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.stripLeading();
            if (trimmed.startsWith(key + ":")) {
                String prefix = line.substring(0, line.length() - trimmed.length());
                lines.set(i, prefix + key + ": " + renderYamlValue(value));
                found = true;
                changed = true;
                break;
            }
        }
        if (!found) {
            lines.add(key + ": " + renderYamlValue(value));
            changed = true;
        }
        if (changed) {
            Files.write(file, lines);
        }
    }

    private static String renderTomlValue(String value) {
        if (value == null) {
            return "\"\"";
        }
        if (value.matches("-?\\d+(\\.\\d+)?") || "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String renderYamlValue(String value) {
        if (value == null) {
            return "\"\"";
        }
        if (value.matches("-?\\d+(\\.\\d+)?") || "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
