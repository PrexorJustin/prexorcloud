package me.prexorjustin.prexorcloud.daemon.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record HealthConfig(
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("bindAddress") String bindAddress,
        @JsonProperty("port") int port) {

    public HealthConfig {
        if (bindAddress == null || bindAddress.isBlank()) bindAddress = "127.0.0.1";
        if (port <= 0) port = 9091;
    }

    public HealthConfig() {
        this(true, "127.0.0.1", 9091);
    }
}
