package me.prexorjustin.prexorcloud.controller.cluster.mongo;

import java.util.List;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.cluster.ClusterReadView;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;

/**
 * {@link ClusterReadView} backed by the Mongo cluster store — the read cutover under
 * {@code clusterStore=mongo}. A thin delegate over {@link MongoClusterStore}; the store already
 * serves these reads with majority read concern, so a Mongo primary failover does not regress them.
 */
public final class MongoClusterReadView implements ClusterReadView {

    private final MongoClusterStore store;

    public MongoClusterReadView(MongoClusterStore store) {
        this.store = store;
    }

    @Override
    public List<Member> listMembers() {
        return store.listMembers();
    }

    @Override
    public Optional<ClusterMeta> getClusterMeta() {
        return store.getClusterMeta();
    }

    @Override
    public int getActiveConfigVersion() {
        return store.getActiveConfigVersion();
    }
}
