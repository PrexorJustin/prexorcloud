package me.prexorjustin.prexorcloud.daemon.template;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deep-merges configuration files across template layers.
 *
 * <p>
 * When a higher-priority template layer contains a config file that already
 * exists from a lower layer, this merger applies only the keys defined in the
 * overlay — leaving all other keys from the base intact. This allows templates
 * to override specific settings (e.g. {@code misc.enable-nether: false})
 * without replacing the entire file.
 *
 * <p>
 * Supported formats:
 * <ul>
 * <li>YAML ({@code .yml}, {@code .yaml}) — recursive deep-merge of maps;
 * scalars and lists are replaced wholesale by the overlay</li>
 * <li>JSON ({@code .json}) — recursive deep-merge of objects; scalars and
 * arrays are replaced wholesale by the overlay</li>
 * <li>TOML ({@code .toml}) — recursive deep-merge of tables; scalars and arrays
 * are replaced wholesale by the overlay</li>
 * <li>Properties ({@code .properties}) — key-level merge; overlay keys
 * overwrite base keys</li>
 * <li>Text ({@code .txt}, {@code .cfg}, {@code .conf}, {@code .ini},
 * {@code .list}) — line-level merge; unique overlay lines are appended</li>
 * </ul>
 *
 * <p>
 * Binary files, JARs, and other non-config formats are not merged — they are
 * always overwritten by the higher layer (handled by {@link TemplateUnpacker}).
 */
public final class ConfigMerger {

    private static final Logger logger = LoggerFactory.getLogger(ConfigMerger.class);

    private static final Set<String> YAML_EXTENSIONS = Set.of(".yml", ".yaml");
    private static final Set<String> JSON_EXTENSIONS = Set.of(".json");
    private static final Set<String> TOML_EXTENSIONS = Set.of(".toml");
    private static final Set<String> PROPERTIES_EXTENSIONS = Set.of(".properties");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(".txt", ".cfg", ".conf", ".ini", ".list");

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static final TomlMapper TOML_MAPPER = new TomlMapper();

    private ConfigMerger() {}

    /**
     * Returns {@code true} if the file is a config format that supports merging.
     */
    public static boolean isMergeable(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return matchesAny(name, YAML_EXTENSIONS)
                || matchesAny(name, JSON_EXTENSIONS)
                || matchesAny(name, TOML_EXTENSIONS)
                || matchesAny(name, PROPERTIES_EXTENSIONS)
                || matchesAny(name, TEXT_EXTENSIONS);
    }

    /**
     * Merges the overlay file content into the existing base file. The base file is
     * read, the overlay is applied, and the result is written back to the base
     * path.
     *
     * @param basePath
     *            existing file from a lower template layer
     * @param overlayData
     *            raw bytes of the overlay file from the higher layer
     */
    public static void merge(Path basePath, byte[] overlayData) throws IOException {
        String name = basePath.getFileName().toString().toLowerCase();

        if (matchesAny(name, YAML_EXTENSIONS)) {
            mergeStructured(basePath, overlayData, YAML_MAPPER, "YAML");
        } else if (matchesAny(name, JSON_EXTENSIONS)) {
            mergeStructured(basePath, overlayData, JSON_MAPPER, "JSON");
        } else if (matchesAny(name, TOML_EXTENSIONS)) {
            mergeStructured(basePath, overlayData, TOML_MAPPER, "TOML");
        } else if (matchesAny(name, PROPERTIES_EXTENSIONS)) {
            mergeProperties(basePath, overlayData);
        } else if (matchesAny(name, TEXT_EXTENSIONS)) {
            mergeText(basePath, overlayData);
        }
    }

    /**
     * Deep-merges a structured config format (YAML, JSON, or TOML) using the given
     * mapper. Overlay keys override base keys. Maps/objects are merged recursively;
     * all other node types (scalars, arrays) are replaced entirely.
     */
    private static void mergeStructured(Path basePath, byte[] overlayData, ObjectMapper mapper, String format)
            throws IOException {
        JsonNode baseNode;
        JsonNode overlayNode;

        // If either file contains unparseable content (e.g. %VARIABLE% placeholders
        // before substitution), fall back to overwriting instead of failing the
        // instance start.
        try {
            baseNode = mapper.readTree(basePath.toFile());
        } catch (Exception e) {
            logger.debug(
                    "Cannot parse base {} file {} for merge ({}), overlay wins",
                    format,
                    basePath.getFileName(),
                    e.getMessage());
            Files.write(basePath, overlayData);
            return;
        }

        try {
            overlayNode = mapper.readTree(overlayData);
        } catch (Exception e) {
            logger.debug(
                    "Cannot parse overlay {} for {} ({}), keeping base",
                    format,
                    basePath.getFileName(),
                    e.getMessage());
            return;
        }

        if (baseNode == null || baseNode.isMissingNode() || !baseNode.isObject()) {
            Files.write(basePath, overlayData);
            return;
        }
        if (overlayNode == null || overlayNode.isMissingNode() || !overlayNode.isObject()) {
            Files.write(basePath, overlayData);
            return;
        }

        ObjectNode merged = deepMerge((ObjectNode) baseNode, (ObjectNode) overlayNode);
        String result = mapper.writeValueAsString(merged);
        Files.writeString(basePath, result, StandardCharsets.UTF_8);

        logger.debug("Deep-merged {} config: {}", format, basePath.getFileName());
    }

    /**
     * Recursively merge overlay into base. Overlay wins for scalars and arrays. For
     * nested objects, recurse.
     */
    static ObjectNode deepMerge(ObjectNode base, ObjectNode overlay) {
        ObjectNode result = base.deepCopy();

        Iterator<String> fieldNames = overlay.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            JsonNode overlayValue = overlay.get(field);
            JsonNode baseValue = result.get(field);

            if (baseValue != null && baseValue.isObject() && overlayValue.isObject()) {
                // Both are maps — recurse
                result.set(field, deepMerge((ObjectNode) baseValue, (ObjectNode) overlayValue));
            } else {
                // Scalar, array, or type mismatch — overlay wins
                result.set(field, overlayValue.deepCopy());
            }
        }

        return result;
    }

    /**
     * Merges properties files at the key level. Overlay keys overwrite base keys;
     * base-only keys are preserved.
     */
    private static void mergeProperties(Path basePath, byte[] overlayData) throws IOException {
        var base = new Properties();
        try (var reader = Files.newBufferedReader(basePath, StandardCharsets.UTF_8)) {
            base.load(reader);
        }

        var overlay = new Properties();
        overlay.load(new java.io.ByteArrayInputStream(overlayData));

        // Overlay wins
        base.putAll(overlay);

        try (var writer = Files.newBufferedWriter(basePath, StandardCharsets.UTF_8)) {
            base.store(writer, null);
        }

        logger.debug("Deep-merged properties config: {}", basePath.getFileName());
    }

    /**
     * Merges plain text config files by appending unique overlay lines. Lines
     * already present in the base are not duplicated. Blank lines in the overlay
     * are always appended (to preserve formatting).
     */
    private static void mergeText(Path basePath, byte[] overlayData) throws IOException {
        List<String> baseLines = Files.readAllLines(basePath, StandardCharsets.UTF_8);
        Set<String> baseLineSet = new LinkedHashSet<>(baseLines);

        List<String> overlayLines =
                new String(overlayData, StandardCharsets.UTF_8).lines().toList();

        var merged = new ArrayList<>(baseLines);
        for (String line : overlayLines) {
            if (line.isBlank() || !baseLineSet.contains(line)) {
                merged.add(line);
                baseLineSet.add(line);
            }
        }

        Files.write(basePath, merged, StandardCharsets.UTF_8);

        logger.debug("Appended unique lines to text config: {}", basePath.getFileName());
    }

    private static boolean matchesAny(String name, Set<String> extensions) {
        return extensions.stream().anyMatch(name::endsWith);
    }
}
