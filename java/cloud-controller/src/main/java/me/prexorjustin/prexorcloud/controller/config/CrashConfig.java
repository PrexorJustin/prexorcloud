package me.prexorjustin.prexorcloud.controller.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CrashConfig(
        @JsonProperty("ringBufferSize") int ringBufferSize,
        @JsonProperty("crashLoopThreshold") int crashLoopThreshold,
        @JsonProperty("crashLoopWindowSeconds") int crashLoopWindowSeconds) {

    public CrashConfig {
        if (ringBufferSize <= 0) ringBufferSize = 500;
        if (crashLoopThreshold <= 0) crashLoopThreshold = 3;
        if (crashLoopWindowSeconds <= 0) crashLoopWindowSeconds = 300;
    }

    public CrashConfig() {
        this(500, 3, 300);
    }
}
