package me.prexorjustin.prexorcloud.common.io;

import java.util.TimeZone;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Standard {@link ObjectMapper} configurations for PrexorCloud.
 *
 * <p>
 * One Jackson configuration across the codebase: {@code java.time} module
 * registered, ISO-8601 timestamps (not millis), UTC timezone, lenient on
 * unknown properties for the standard mapper, strict for the manifest
 * mapper.
 * </p>
 */
public final class ObjectMappers {

    private ObjectMappers() {}

    private static final ObjectMapper STANDARD = configure(new ObjectMapper(), false);
    private static final ObjectMapper STRICT = configure(new ObjectMapper(), true);
    private static final ObjectMapper YAML = configure(new ObjectMapper(new YAMLFactory()), true);

    /**
     * Lenient mapper for general-purpose use (REST request/response, event
     * payloads, dashboard traffic). Tolerates unknown properties so callers
     * survive forward-compatible field additions on the wire.
     */
    public static ObjectMapper standard() {
        return STANDARD;
    }

    /**
     * Strict mapper for trusted inbound from signed sources (manifests,
     * signed configs). Fails on unknown properties to surface schema drift
     * early.
     */
    public static ObjectMapper strict() {
        return STRICT;
    }

    /**
     * Strict YAML mapper. Same semantics as {@link #strict()} but parses
     * YAML; used by {@code YamlConfigLoader} and the module manifest
     * parser.
     */
    public static ObjectMapper yaml() {
        return YAML;
    }

    private static ObjectMapper configure(ObjectMapper mapper, boolean strict) {
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.setTimeZone(TimeZone.getTimeZone("UTC"));
        if (strict) {
            mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        } else {
            mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        }
        return mapper;
    }
}
