package me.prexorjustin.prexorcloud.modules.journey.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import me.prexorjustin.prexorcloud.api.domain.PlayerJourneyEntry;
import me.prexorjustin.prexorcloud.api.module.capability.PlayerJourneyTracker;
import me.prexorjustin.prexorcloud.modules.journey.data.JourneyRepository;

/**
 * {@link PlayerJourneyTracker} implementation backed by the module's Mongo
 * journey collection.
 */
public final class MongoPlayerJourneyTracker implements PlayerJourneyTracker {

    private final JourneyRepository repository;

    public MongoPlayerJourneyTracker(JourneyRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<PlayerJourneyEntry> recent(UUID playerUuid, int limit) {
        return repository.findRecent(playerUuid, limit);
    }

    @Override
    public List<PlayerJourneyEntry> since(UUID playerUuid, Instant since) {
        return repository.findSince(playerUuid, since);
    }
}
