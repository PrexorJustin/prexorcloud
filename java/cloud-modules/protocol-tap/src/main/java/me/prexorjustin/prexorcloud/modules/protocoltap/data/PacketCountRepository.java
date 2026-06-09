package me.prexorjustin.prexorcloud.modules.protocoltap.data;

import java.util.List;

import me.prexorjustin.prexorcloud.api.module.data.IndexSpec;
import me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore;
import me.prexorjustin.prexorcloud.api.module.data.Query;
import me.prexorjustin.prexorcloud.api.module.data.Sort;

/**
 * Mongo-backed sink for packet observations posted by the in-game plugin.
 *
 * <p>Each row is one snapshot from one instance — the plugin posts every few
 * seconds with the delta. The {@link #recent} reader is the Prometheus
 * exporter's input.
 */
public final class PacketCountRepository {

    public static final String OBSERVATIONS = "observations";

    private final ModuleDataStore store;

    public PacketCountRepository(ModuleDataStore store) {
        this.store = store;
        store.ensureCollection(OBSERVATIONS);
        store.createIndex(OBSERVATIONS, IndexSpec.asc("group"));
        store.createIndex(OBSERVATIONS, IndexSpec.desc("observedAt"));
    }

    public void record(PacketCount count) {
        store.insertOne(OBSERVATIONS, count);
    }

    public List<PacketCount> recent(String group, int limit) {
        Query filter = group == null || group.isBlank()
                ? Query.all()
                : Query.where("group").eq(group);
        return store.find(OBSERVATIONS, filter, Sort.desc("observedAt"), limit, PacketCount.class);
    }
}
