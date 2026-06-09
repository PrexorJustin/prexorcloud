package me.prexorjustin.prexorcloud.controller.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SchedulerConfig(
        @JsonProperty("evaluationIntervalSeconds") int evaluationIntervalSeconds,
        @JsonProperty("scalingCooldownSeconds") int scalingCooldownSeconds,
        @JsonProperty("nodeTimeoutSeconds") int nodeTimeoutSeconds,
        @JsonProperty("auditRetentionDays") int auditRetentionDays) {

    public SchedulerConfig {
        if (evaluationIntervalSeconds <= 0) evaluationIntervalSeconds = 15;
        if (scalingCooldownSeconds <= 0) scalingCooldownSeconds = 60;
        if (nodeTimeoutSeconds <= 0) nodeTimeoutSeconds = 90;
        if (auditRetentionDays <= 0) auditRetentionDays = 90;
    }

    public SchedulerConfig() {
        this(15, 60, 90, 90);
    }
}
