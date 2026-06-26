package me.prexorjustin.prexorcloud.daemon.template;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Platform-agnostic config-rule applier (Group/Template v2, Phase 3). The daemon holds no per-platform
 * knowledge: each {@link ConfigPatch} fully describes one edit -- a target file, a selector, an
 * operation, and a value -- and this applier dispatches purely on the file's extension (its syntax) and
 * the op. Line-level edits preserve the original formatting exactly, which matters because Paper keys a
 * {@code _version} field off the file and regenerates it from defaults if it is reserialised.
 *
 * <p>Runs after template unpacking, {@code %VAR%} substitution, and bootstrap-cache application, right
 * before launch. The per-platform forwarding / online-mode defaults that used to be hardcoded here are
 * now data: the controller resolves them from {@code PlatformConfigDefaults}, and the bootstrap cache
 * bakes Paper's velocity {@code enabled}/{@code online-mode}. The one daemon-local value is the
 * forwarding secret, substituted into a rule's value from the instance's {@code forwarding.secret} so it
 * never travels on the wire or through {@code planHash}.
 */
public final class ServerConfigPatcher {

    private static final Logger logger = LoggerFactory.getLogger(ServerConfigPatcher.class);

    /** Daemon-local placeholder, resolved from {@code forwarding.secret} at apply time. */
    private static final String FORWARDING_SECRET_PLACEHOLDER = "%FORWARDING_SECRET%";

    /**
     * One resolved config edit. {@code path} is a dotted/flat key for {@link Op#SET}/{@link Op#REPLACE}
     * (a YAML dotted path addresses a nested section, e.g. {@code proxies.velocity.secret}) or a regular
     * expression for {@link Op#REGEX}.
     */
    public record ConfigPatch(String file, Op op, String path, String value) {

        public enum Op {
            SET,
            REPLACE,
            REGEX
        }

        public ConfigPatch {
            if (op == null) op = Op.SET;
        }
    }

    private ServerConfigPatcher() {}

    /**
     * Apply every resolved config rule to the instance directory. Each rule is sandboxed to the instance
     * dir, skipped (with a warning) if its target file is absent, and dispatched on the file extension
     * plus op. A {@code %FORWARDING_SECRET%} placeholder in a rule's value is resolved from the instance's
     * {@code forwarding.secret}; if that file is missing the rule is skipped rather than writing a broken
     * secret.
     */
    public static void patch(Path instanceDir, List<ConfigPatch> patches) {
        if (patches == null || patches.isEmpty()) return;
        Path root = instanceDir.normalize();
        String secret = null; // lazily loaded; "" once we know there is none

        for (ConfigPatch patch : patches) {
            if (patch.file() == null
                    || patch.file().isBlank()
                    || patch.path() == null
                    || patch.path().isBlank()) {
                continue;
            }

            String value = patch.value() == null ? "" : patch.value();
            if (value.contains(FORWARDING_SECRET_PLACEHOLDER)) {
                if (secret == null) secret = readForwardingSecret(root);
                if (secret.isEmpty()) {
                    logger.warn(
                            "forwarding.secret missing in {} -- skipping secret patch {} ({}); forwarding will fail",
                            root,
                            patch.path(),
                            patch.file());
                    continue;
                }
                value = value.replace(FORWARDING_SECRET_PLACEHOLDER, secret);
            }

            Path target = root.resolve(patch.file()).normalize();
            if (!target.startsWith(root)) {
                throw new SecurityException("Config patch escapes instance dir: " + patch.file());
            }
            if (!Files.exists(target)) {
                logger.warn("Config patch target does not exist: {}", patch.file());
                continue;
            }

            try {
                if (patch.op() == ConfigPatch.Op.REGEX) {
                    applyRegexPatch(target, patch.path(), value);
                } else {
                    applyStructuredPatch(target, patch, value);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to patch config file " + patch.file(), e);
            }
        }
    }

    private static String readForwardingSecret(Path instanceDir) {
        Path secretFile = instanceDir.resolve("forwarding.secret");
        if (!Files.exists(secretFile)) return "";
        try {
            return Files.readString(secretFile).trim();
        } catch (IOException e) {
            logger.warn("Failed to read forwarding.secret: {}", e.getMessage());
            return "";
        }
    }

    /**
     * {@code SET}/{@code REPLACE}: set a structured key by file syntax. A YAML dotted path addresses a
     * nested section via {@link #setNestedYamlKey} (replace-only -- an absent nested key is left as-is
     * rather than synthesised at a guessed nesting); a flat key (properties / toml / top-level yaml) is
     * created when absent for {@code SET} and only rewritten (never created) for {@code REPLACE}.
     */
    private static void applyStructuredPatch(Path target, ConfigPatch patch, String value) throws IOException {
        boolean createIfAbsent = patch.op() == ConfigPatch.Op.SET;
        String name = target.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".properties")) {
            applyPropertiesPatch(target, patch.path(), value, createIfAbsent);
        } else if (name.endsWith(".toml")) {
            applyTomlPatch(target, patch.path(), value, createIfAbsent);
        } else if (name.endsWith(".yml") || name.endsWith(".yaml")) {
            if (patch.path().contains(".")) {
                applyNestedYamlPatch(target, patch.path(), value);
            } else {
                applyYamlPatch(target, patch.path(), value, createIfAbsent);
            }
        } else {
            logger.warn("Unsupported structured config patch target: {}", patch.file());
        }
    }

    /**
     * {@code REGEX}: substitute every per-line match of {@code pattern} with {@code replacement}
     * (Pterodactyl-style find/replace). Format-agnostic; the replacement may reference capture groups
     * ({@code $1}). An uncompilable pattern is logged and skipped.
     */
    private static void applyRegexPatch(Path file, String pattern, String replacement) throws IOException {
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            logger.warn("Invalid REGEX config patch pattern '{}': {} -- skipping", pattern, e.getMessage());
            return;
        }
        var lines = new ArrayList<>(Files.readAllLines(file));
        boolean changed = false;
        for (int i = 0; i < lines.size(); i++) {
            String replaced = compiled.matcher(lines.get(i)).replaceAll(replacement);
            if (!replaced.equals(lines.get(i))) {
                lines.set(i, replaced);
                changed = true;
            }
        }
        if (changed) {
            Files.write(file, lines);
        }
    }

    private static void applyPropertiesPatch(Path file, String key, String value, boolean createIfAbsent)
            throws IOException {
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
        if (!found && createIfAbsent) {
            lines.add(key + "=" + value);
            changed = true;
        }
        if (changed) {
            Files.write(file, lines);
        }
    }

    private static void applyTomlPatch(Path file, String key, String value, boolean createIfAbsent) throws IOException {
        var lines = new ArrayList<>(Files.readAllLines(file));
        boolean changed = false;
        boolean found = false;
        String renderedValue = renderTomlValue(value);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith(key + " =")) {
                lines.set(i, key + " = " + renderedValue);
                found = true;
                changed = true;
                break;
            }
        }
        if (!found && createIfAbsent) {
            lines.add(key + " = " + renderedValue);
            changed = true;
        }
        if (changed) {
            Files.write(file, lines);
        }
    }

    private static void applyYamlPatch(Path file, String key, String value, boolean createIfAbsent) throws IOException {
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
        if (!found && createIfAbsent) {
            lines.add(key + ": " + renderYamlValue(value));
            changed = true;
        }
        if (changed) {
            Files.write(file, lines);
        }
    }

    private static void applyNestedYamlPatch(Path file, String dottedKey, String value) throws IOException {
        var lines = new ArrayList<>(Files.readAllLines(file));
        if (setNestedYamlKey(lines, dottedKey, value)) {
            Files.write(file, lines);
        } else {
            logger.warn("Nested config key not found, skipping: {} in {}", dottedKey, file.getFileName());
        }
    }

    /**
     * Sets a dotted-path key (e.g. {@code remote.address}) in an indentation-structured YAML document,
     * descending one section at a time and bounding the search to each parent's block so duplicate leaf
     * names under different sections are disambiguated. Returns {@code false} if the path is absent.
     */
    static boolean setNestedYamlKey(List<String> lines, String dottedKey, String value) {
        if (dottedKey == null || dottedKey.isBlank()) return false;
        String[] segments = dottedKey.split("\\.");
        int searchStart = 0;
        int searchEnd = lines.size();
        int parentIndent = -1;

        for (int s = 0; s < segments.length; s++) {
            String segment = segments[s];
            int foundLine = -1;
            int foundIndent = -1;
            for (int i = searchStart; i < searchEnd; i++) {
                String line = lines.get(i);
                String trimmed = line.stripLeading();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int indent = line.length() - trimmed.length();
                if (indent <= parentIndent) break; // left the parent's block
                if ((trimmed.equals(segment + ":") || trimmed.startsWith(segment + ": "))) {
                    foundLine = i;
                    foundIndent = indent;
                    break;
                }
            }
            if (foundLine < 0) return false;

            if (s == segments.length - 1) {
                String prefix = lines.get(foundLine).substring(0, foundIndent);
                lines.set(foundLine, prefix + segment + ": " + renderYamlValue(value));
                return true;
            }

            // Descend: bound the next search to this section's child block.
            parentIndent = foundIndent;
            searchStart = foundLine + 1;
            searchEnd = lines.size();
            for (int i = foundLine + 1; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.stripLeading();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                if (line.length() - trimmed.length() <= foundIndent) {
                    searchEnd = i;
                    break;
                }
            }
        }
        return false;
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
