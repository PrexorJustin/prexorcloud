package me.prexorjustin.prexorcloud.modules.stats.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import me.prexorjustin.prexorcloud.modules.stats.data.SessionRecord;
import me.prexorjustin.prexorcloud.modules.stats.data.StatsRepository;

/**
 * Owns the lifecycle of a single session: open on join, close on quit/transfer,
 * and recompute aggregates when sessions close. Pure: does not schedule itself
 * — the calling thread (REST handler or scheduled flush) drives it.
 */
public final class SessionAggregator {

    private final StatsRepository repo;

    public SessionAggregator(StatsRepository repo) {
        this.repo = Objects.requireNonNull(repo, "repo");
    }

    public void onJoin(
            UUID playerId, String playerName, UUID sessionId, String group, String instanceId, Instant joinAt) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(joinAt, "joinAt");
        repo.openSession(new SessionRecord(
                playerId,
                playerName == null ? "" : playerName,
                sessionId,
                group == null ? "" : group,
                instanceId == null ? "" : instanceId,
                joinAt,
                null,
                0L));
    }

    /**
     * Close a session by id. {@code durationMs} is computed from the recorded
     * {@code joinAt} when not provided by the caller.
     */
    public CloseOutcome onLeave(UUID sessionId, Instant quitAt, Long providedDurationMs) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(quitAt, "quitAt");
        var existing = repo.findSession(sessionId);
        if (existing.isEmpty()) {
            return CloseOutcome.NOT_FOUND;
        }
        long durationMs = providedDurationMs != null
                ? Math.max(0L, providedDurationMs)
                : Math.max(0L, Duration.between(existing.get().joinAt(), quitAt).toMillis());
        repo.closeSession(sessionId, quitAt, durationMs);
        return CloseOutcome.CLOSED;
    }

    /** Recompute leaderboard aggregates from the raw sessions log. */
    public StatsRepository.RebuildResult rebuild(Instant now) {
        return repo.rebuildAggregates(now);
    }

    public enum CloseOutcome {
        CLOSED,
        NOT_FOUND
    }
}
