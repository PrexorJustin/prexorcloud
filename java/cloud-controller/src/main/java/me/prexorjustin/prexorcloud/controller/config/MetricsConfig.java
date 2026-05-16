package me.prexorjustin.prexorcloud.controller.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MetricsConfig(
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("retentionHours") int retentionHours,
        @JsonProperty("collectionIntervalSeconds") int collectionIntervalSeconds) {

    public MetricsConfig {
        if (retentionHours <= 0) retentionHours = 168;
        if (collectionIntervalSeconds <= 0) collectionIntervalSeconds = 30;
    }

    public MetricsConfig() {
        this(true, 168, 30);
    }
}
