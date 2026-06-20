package me.prexorjustin.prexorcloud.controller.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.cluster.mongo.MongoClusterPlane;
import me.prexorjustin.prexorcloud.controller.cluster.mongo.MongoClusterStore;
import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlPlane;
import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterWriteConflict;
import me.prexorjustin.prexorcloud.controller.cluster.raft.RaftClusterPlane;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterConfigVersion;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;

import org.junit.jupiter.api.Test;

/**
 * Tests for the two {@link ClusterPlane} adapters. The Raft adapter is a pure delegate (mocked
 * {@link ClusterControlPlane}); the Mongo adapter additionally ports three logic-bearing writes
 * (config-version proposal, join-token mint, seed rotation) over a mocked {@link MongoClusterStore},
 * so those are exercised for real. All run locally — no Raft, no Mongo.
 */
final class ClusterPlaneTest {

    private static final Member MEMBER = new Member(
            "ctrl-2", "10.0.0.6:9190", "10.0.0.6:8080", "10.0.0.6:50051", "ctrl-2", Instant.EPOCH, Instant.EPOCH);
    private static final ClusterMeta META =
            new ClusterMeta("cluster-1", Base64.getEncoder().encodeToString(new byte[32]), Instant.EPOCH, 1);

    // --- Raft adapter: pure delegation ----------------------------------------------------------

    @Test
    void raftPlaneDelegatesReadsAndWrites() throws Exception {
        ClusterControlPlane plane = mock(ClusterControlPlane.class);
        when(plane.listMembers()).thenReturn(List.of(MEMBER));
        when(plane.getActiveConfigVersion()).thenReturn(7);

        RaftClusterPlane rp = new RaftClusterPlane(plane);
        assertEquals(List.of(MEMBER), rp.listMembers());
        assertEquals(7, rp.getActiveConfigVersion());

        rp.addMember(MEMBER);
        verify(plane).addMember(MEMBER);
        rp.removeMember("ctrl-2", "ejected");
        verify(plane).removeMember("ctrl-2", "ejected");
        rp.proposeConfigPatch(3, "alice", Map.of("k", "v"), "r");
        verify(plane).proposeConfigPatch(3, "alice", Map.of("k", "v"), "r");
    }

    // --- Mongo adapter: read delegation + ported write logic -------------------------------------

    @Test
    void mongoPlaneDelegatesMemberWrites() {
        MongoClusterStore store = mock(MongoClusterStore.class);
        MongoClusterPlane mp = new MongoClusterPlane(store);
        mp.addMember(MEMBER);
        verify(store).putMember(MEMBER);
        mp.removeMember("ctrl-2", "ejected"); // reason is dropped on the Mongo backing
        verify(store).removeMember("ctrl-2");
        assertTrue(mp.getLeases().isEmpty(), "Mongo backing reports no Raft leases");
    }

    @Test
    void mongoPlaneProposesNextConfigVersionAndReturnsIt() throws Exception {
        MongoClusterStore store = mock(MongoClusterStore.class);
        when(store.listConfigVersions())
                .thenReturn(List.of(new ClusterConfigVersion(1, 0, "boot", Instant.EPOCH, Map.of(), "v1")));
        when(store.writeConfigVersion(any())).thenReturn(true);

        int version = new MongoClusterPlane(store).proposeConfigPatch(1, "alice", Map.of("k", "v"), "patch");
        assertEquals(2, version, "next ordinal after version 1");
    }

    @Test
    void mongoPlaneSurfacesConfigConflictAsClusterWriteConflict() {
        MongoClusterStore store = mock(MongoClusterStore.class);
        when(store.listConfigVersions()).thenReturn(List.of());
        when(store.writeConfigVersion(any())).thenReturn(false); // guard lost the race / stale parent

        ClusterWriteConflict ex = assertThrows(
                ClusterWriteConflict.class,
                () -> new MongoClusterPlane(store).proposeConfigPatch(0, "alice", Map.of(), "patch"));
        assertEquals("VERSION_NOT_NEXT", ex.code());
    }

    @Test
    void mongoPlaneSurfacesRedeemFailureAsClusterWriteConflict() {
        MongoClusterStore store = mock(MongoClusterStore.class);
        when(store.redeemJoinToken(eq("jti-1"), any(), any(), any())).thenReturn(false);

        ClusterWriteConflict ex = assertThrows(
                ClusterWriteConflict.class,
                () -> new MongoClusterPlane(store).redeemJoinToken("jti-1", Instant.EPOCH, "10.0.0.6", "ctrl-2"));
        assertEquals("TOKEN_NOT_REDEEMABLE", ex.code());
    }

    @Test
    void mongoPlaneMintsJoinTokenFromIdentitySeed() throws Exception {
        MongoClusterStore store = mock(MongoClusterStore.class);
        when(store.getClusterMeta()).thenReturn(Optional.of(META));

        IssuedJoinToken issued = new MongoClusterPlane(store)
                .issueJoinToken(List.of("10.0.0.3:9190"), java.time.Duration.ofHours(1), "label", "admin");

        assertTrue(issued.token() != null && !issued.token().isBlank(), "wire token minted");
        assertTrue(issued.jti() != null && !issued.jti().isBlank(), "jti assigned");
        verify(store).putJoinToken(any());
        verify(store, never()).putMember(any());
    }
}
