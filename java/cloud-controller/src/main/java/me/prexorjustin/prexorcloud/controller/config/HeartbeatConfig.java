package me.prexorjustin.prexorcloud.controller.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record HeartbeatConfig(
        @JsonProperty("intervalMs") long intervalMs,
        @JsonProperty("missedThreshold") int missedThreshold) {

    public HeartbeatConfig {
        if (intervalMs <= 0) intervalMs = 30000;
        if (missedThreshold <= 0) missedThreshold = 3;
    }

    public HeartbeatConfig() {
        this(30000, 3);
    }
}
