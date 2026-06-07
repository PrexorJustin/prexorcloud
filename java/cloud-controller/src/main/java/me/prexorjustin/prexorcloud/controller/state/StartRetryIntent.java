package me.prexorjustin.prexorcloud.controller.state;

import java.time.Instant;

public record StartRetryIntent(
        String instanceId,
        String groupName,
        String nodeId,
        String reason,
        String planHash,
        int attempt,
        Instant retryAt,
        Instant createdAt) {}
