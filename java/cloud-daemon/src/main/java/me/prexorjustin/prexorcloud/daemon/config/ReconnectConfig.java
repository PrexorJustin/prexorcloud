package me.prexorjustin.prexorcloud.daemon.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ReconnectConfig(
        @JsonProperty("initialDelayMs") long initialDelayMs,
        @JsonProperty("maxDelayMs") long maxDelayMs,
        @JsonProperty("multiplier") double multiplier) {

    public ReconnectConfig {
        if (initialDelayMs <= 0) initialDelayMs = 1000;
        if (maxDelayMs <= 0) maxDelayMs = 60000;
        if (multiplier <= 0) multiplier = 2.0;
    }

    public ReconnectConfig() {
        this(1000, 60000, 2.0);
    }
}
