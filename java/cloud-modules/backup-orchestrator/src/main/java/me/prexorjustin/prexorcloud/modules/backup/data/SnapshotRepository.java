package me.prexorjustin.prexorcloud.modules.backup.data;

import java.util.List;
import java.util.Optional;

import me.prexorjustin.prexorcloud.api.module.data.IndexSpec;
import me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore;
import me.prexorjustin.prexorcloud.api.module.data.Query;
import me.prexorjustin.prexorcloud.api.module.data.Sort;

/**
 * Mongo-backed store for {@link SnapshotMetadata} records.
 *
 * <p>One collection ({@code snapshots}) indexed by {@code instanceId} for the
 * common "show me the last N snapshots of instance X" query and by
 * {@code createdAt} for retention pruning.
 */
public final class SnapshotRepository {

    private static final String COLLECTION = "snapshots";

    private final ModuleDataStore store;

    public SnapshotRepository(ModuleDataStore store) {
        this.store = store;
        store.ensureCollection(COLLECTION);
        store.createIndex(COLLECTION, IndexSpec.asc("instanceId"));
        store.createIndex(COLLECTION, IndexSpec.asc("createdAt"));
    }

    public String save(SnapshotMetadata snapshot) {
        return store.insertOne(COLLECTION, snapshot);
    }

    public Optional<SnapshotMetadata> findById(String id) {
        return store.findOne(COLLECTION, Query.where("id").eq(id), SnapshotMetadata.class);
    }

    public List<SnapshotMetadata> findForInstance(String instanceId, int limit) {
        return store.find(
                COLLECTION,
                Query.where("instanceId").eq(instanceId),
                Sort.desc("createdAt"),
                Math.max(1, limit),
                SnapshotMetadata.class);
    }

    public List<SnapshotMetadata> findAll(int limit) {
        return store.find(COLLECTION, Query.all(), Sort.desc("createdAt"), Math.max(1, limit), SnapshotMetadata.class);
    }

    public boolean deleteById(String id) {
        return store.deleteOne(COLLECTION, Query.where("id").eq(id));
    }
}
