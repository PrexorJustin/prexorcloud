package me.prexorjustin.prexorcloud.common.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class YamlConfigLoader {

    private static final Logger logger = LoggerFactory.getLogger(YamlConfigLoader.class);

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .registerModule(new ParameterNamesModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private YamlConfigLoader() {}

    /**
     * Load a YAML config file into the given record type. If the file does not
     * exist, copies the default from classpath and then loads it.
     *
     * @param configPath
     *            path to the YAML file on disk
     * @param type
     *            the record class to deserialize into
     * @param classpathDefault
     *            classpath resource path for the default config (e.g.
     *            "defaults/controller.yml")
     */
    public static <T> T load(Path configPath, Class<T> type, String classpathDefault) throws IOException {
        if (!Files.exists(configPath)) {
            copyDefault(configPath, classpathDefault);
        }
        logger.debug("Loading config from {}", configPath);
        return MAPPER.readValue(configPath.toFile(), type);
    }

    private static void copyDefault(Path target, String classpathResource) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream in = YamlConfigLoader.class.getClassLoader().getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IOException("Default config not found on classpath: " + classpathResource);
            }
            Files.copy(in, target);
            logger.info("Created default config at {}", target);
        }
    }

    /**
     * Returns the shared ObjectMapper for YAML serialization (e.g. for writing
     * configs back).
     */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
