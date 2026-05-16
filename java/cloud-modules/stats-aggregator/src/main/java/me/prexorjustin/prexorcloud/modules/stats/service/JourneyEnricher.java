package me.prexorjustin.prexorcloud.modules.stats.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import me.prexorjustin.prexorcloud.api.domain.PlayerJourneyEntry;
import me.prexorjustin.prexorcloud.api.module.capability.PlayerJourneyTracker;

/**
 * Wraps the controller-provided {@link PlayerJourneyTracker} capability so the
 * REST routes can render per-player timelines. The capability handle is
 * resolved lazily because module tests run without the controller and the
 * capability is null in that path.
 */
public final class JourneyEnricher {

    private final PlayerJourneyTracker tracker;

    public JourneyEnricher(PlayerJourneyTracker tracker) {
        this.tracker = tracker;
    }

    public boolean isAvailable() {
        return tracker != null;
    }

    public Optional<List<PlayerJourneyEntry>> recent(UUID playerId, int limit) {
        if (tracker == null) return Optional.empty();
        return Optional.of(tracker.recent(playerId, Math.max(1, limit)));
    }

    public Optional<List<PlayerJourneyEntry>> since(UUID playerId, Instant since) {
        if (tracker == null) return Optional.empty();
        return Optional.of(tracker.since(playerId, since));
    }
}
