package me.prexorjustin.prexorcloud.modules.example.data;

import java.time.Instant;
import java.util.UUID;

/**
 * Aggregated playtime totals per player — what the leaderboard reads.
 *
 * <p>Rebuilt from the {@code sessions} collection so /top reads don't have to
 * scan every session.
 */
public record TopEntry(UUID playerId, long totalMs, int sessionCount, Instant lastSeen) {}
