package me.prexorjustin.prexorcloud.modules.stats.data;

import java.time.Instant;
import java.util.UUID;

public record SessionRecord(
        UUID playerId,
        String playerName,
        UUID sessionId,
        String group,
        String instanceId,
        Instant joinAt,
        Instant quitAt,
        long durationMs) {}
