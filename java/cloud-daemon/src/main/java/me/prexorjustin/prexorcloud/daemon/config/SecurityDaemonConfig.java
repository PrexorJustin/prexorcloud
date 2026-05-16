package me.prexorjustin.prexorcloud.daemon.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SecurityDaemonConfig(
        @JsonProperty("certificateDir") String certificateDir,
        @JsonProperty("joinToken") String joinToken) {

    public SecurityDaemonConfig {
        if (certificateDir == null) certificateDir = "config/security";
        if (joinToken == null) joinToken = "";
    }

    public SecurityDaemonConfig() {
        this("config/security", "");
    }
}
