package me.prexorjustin.prexorcloud.controller.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.cluster.mongo.MongoClusterReadView;
import me.prexorjustin.prexorcloud.controller.cluster.mongo.MongoClusterStore;
import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlPlane;
import me.prexorjustin.prexorcloud.controller.cluster.raft.RaftClusterReadView;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;

import org.junit.jupiter.api.Test;

/**
 * Pure delegation tests for the two {@link ClusterReadView} adapters — the backing
 * {@link ClusterControlPlane} / {@link MongoClusterStore} are mocked, so these run locally with no
 * Raft and no Mongo. They pin that each adapter forwards every read to its own store unchanged.
 */
final class ClusterReadViewTest {

    private static final Member RAFT_MEMBER = new Member(
            "raft-1", "10.0.0.1:9190", "10.0.0.1:8080", "10.0.0.1:50051", "raft", Instant.EPOCH, Instant.EPOCH);
    private static final Member MONGO_MEMBER = new Member(
            "mongo-1", "10.0.0.2:9190", "10.0.0.2:8080", "10.0.0.2:50051", "mongo", Instant.EPOCH, Instant.EPOCH);
    private static final ClusterMeta META = new ClusterMeta("cluster-1", "seed", Instant.EPOCH, 1);

    @Test
    void raftViewDelegatesToControlPlane() {
        ClusterControlPlane plane = mock(ClusterControlPlane.class);
        when(plane.listMembers()).thenReturn(List.of(RAFT_MEMBER));
        when(plane.getClusterMeta()).thenReturn(Optional.of(META));
        when(plane.getActiveConfigVersion()).thenReturn(7);

        ClusterReadView view = new RaftClusterReadView(plane);
        assertEquals(List.of(RAFT_MEMBER), view.listMembers());
        assertEquals(Optional.of(META), view.getClusterMeta());
        assertEquals(7, view.getActiveConfigVersion());
    }

    @Test
    void mongoViewDelegatesToStore() {
        MongoClusterStore store = mock(MongoClusterStore.class);
        when(store.listMembers()).thenReturn(List.of(MONGO_MEMBER));
        when(store.getClusterMeta()).thenReturn(Optional.of(META));
        when(store.getActiveConfigVersion()).thenReturn(3);

        ClusterReadView view = new MongoClusterReadView(store);
        assertEquals(List.of(MONGO_MEMBER), view.listMembers());
        assertEquals(Optional.of(META), view.getClusterMeta());
        assertEquals(3, view.getActiveConfigVersion());
    }
}
