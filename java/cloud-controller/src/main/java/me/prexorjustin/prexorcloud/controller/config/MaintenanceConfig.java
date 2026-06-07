package me.prexorjustin.prexorcloud.controller.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Global network-wide maintenance configuration. When enabled, overrides all
 * per-group maintenance settings.
 * <p>
 * Per-group bypass is configured via {@code GroupDto.maintenanceBypass} and
 * enforced by the proxy plugins — there is no separate global bypass concept.
 */
public record MaintenanceConfig(
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("message") String message) {

    public MaintenanceConfig {
        if (message == null) message = "The network is currently under maintenance.";
    }
}
