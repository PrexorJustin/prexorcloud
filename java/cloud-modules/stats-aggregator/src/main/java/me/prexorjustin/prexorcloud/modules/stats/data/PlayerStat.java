package me.prexorjustin.prexorcloud.modules.stats.data;

import java.time.Instant;
import java.util.UUID;

public record PlayerStat(
        UUID playerId, String playerName, long totalMs, int sessionCount, Instant firstSeen, Instant lastSeen) {}
