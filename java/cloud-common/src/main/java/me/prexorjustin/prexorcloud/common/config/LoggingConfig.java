package me.prexorjustin.prexorcloud.common.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

public record LoggingConfig(
        @JsonProperty("level") String level,
        @JsonProperty("format") LogFormat format) {

    public enum LogFormat {
        HUMAN,
        JSON;

        @JsonCreator
        public static LogFormat fromString(String value) {
            if (value == null) return HUMAN;
            try {
                return valueOf(value.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                return HUMAN;
            }
        }

        @JsonValue
        public String toJson() {
            return name();
        }
    }

    public LoggingConfig {
        if (level == null || level.isBlank()) level = "INFO";
        if (format == null) format = LogFormat.HUMAN;
    }

    public LoggingConfig() {
        this("INFO", LogFormat.HUMAN);
    }
}
