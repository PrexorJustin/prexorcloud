package me.prexorjustin.prexorcloud.modules.journey.data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import me.prexorjustin.prexorcloud.api.domain.PlayerJourneyEntry;
import me.prexorjustin.prexorcloud.api.module.data.IndexSpec;
import me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore;
import me.prexorjustin.prexorcloud.api.module.data.Query;
import me.prexorjustin.prexorcloud.api.module.data.Sort;

/**
 * Append-only journey log over the module's Mongo storage. One document per
 * observation (PLAYER_CONNECTED / PLAYER_TRANSFER / PLAYER_DISCONNECTED).
 *
 * <p>Compound index on (playerUuid, timestamp DESC) supports the two read
 * paths — {@code findRecent} (latest N for a player) and {@code findSince}
 * (window-bounded query for back-fills). Re-creates the index on each load;
 * Mongo is idempotent on identical specs so this is cheap.
 */
public final class JourneyRepository {

    public static final String COLLECTION = "journey";

    private final ModuleDataStore store;

    public JourneyRepository(ModuleDataStore store) {
        this.store = store;
        store.ensureCollection(COLLECTION);
        store.createIndex(
                COLLECTION,
                IndexSpec.compound(java.util.Map.of("playerUuid", 1, "timestamp", -1))
                        .withName("playerUuid_1_timestamp_-1"));
    }

    public void save(PlayerJourneyEntry entry) {
        store.insertOne(COLLECTION, JourneyDoc.from(entry));
    }

    public List<PlayerJourneyEntry> findRecent(UUID playerUuid, int limit) {
        int capped = Math.max(1, Math.min(limit, 1000));
        return store
                .find(
                        COLLECTION,
                        Query.where("playerUuid").eq(playerUuid.toString()),
                        Sort.desc("timestamp"),
                        capped,
                        JourneyDoc.class)
                .stream()
                .map(JourneyDoc::toEntry)
                .toList();
    }

    public List<PlayerJourneyEntry> findSince(UUID playerUuid, Instant since) {
        return store
                .find(
                        COLLECTION,
                        Query.where("playerUuid").eq(playerUuid.toString()).and("timestamp").gte(since),
                        Sort.desc("timestamp"),
                        1000,
                        JourneyDoc.class)
                .stream()
                .map(JourneyDoc::toEntry)
                .toList();
    }
}
