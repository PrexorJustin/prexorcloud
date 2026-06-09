package me.prexorjustin.prexorcloud.daemon.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record InstancesConfig(
        @JsonProperty("directory") String directory,
        @JsonProperty("shutdownTimeoutSeconds") int shutdownTimeoutSeconds,
        @JsonProperty("killTimeoutSeconds") int killTimeoutSeconds,
        @JsonProperty("logRingBufferLines") int logRingBufferLines,
        @JsonProperty("maxConsoleOutputLinesPerSecond") int maxConsoleOutputLinesPerSecond) {

    public InstancesConfig {
        if (directory == null) directory = "instances";
        if (shutdownTimeoutSeconds <= 0) shutdownTimeoutSeconds = 30;
        if (killTimeoutSeconds <= 0) killTimeoutSeconds = 10;
        if (logRingBufferLines <= 0) logRingBufferLines = 500;
        if (maxConsoleOutputLinesPerSecond <= 0) maxConsoleOutputLinesPerSecond = 200;
    }

    public InstancesConfig() {
        this("instances", 30, 10, 500, 200);
    }
}
