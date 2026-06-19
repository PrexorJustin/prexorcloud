package me.prexorjustin.prexorcloud.controller.cluster.mongo;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterConfigVersion;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterEntry;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.cluster.state.JoinToken;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the dual-write bridge — the {@link MongoClusterStore} is mocked, so these run
 * locally with no Mongo. They pin the {@link ClusterEntry} → store mapping (and, crucially, the
 * entries that must NOT be mirrored).
 */
final class MongoClusterShadowTest {

    private static final Member MEMBER =
            new Member("ctrl-2", "10.0.0.6:9190", "10.0.0.6:8080", "10.0.0.6:50051", "ctrl-2", Instant.EPOCH, Instant.EPOCH);

    @Test
    void mirrorsClusterMeta() {
        MongoClusterStore store = mock(MongoClusterStore.class);
        ClusterMeta meta = new ClusterMeta("cluster-1", "seed", Instant.EPOCH, 1);
        new MongoClusterShadow(store).accept(new ClusterEntry.SetClusterMeta(meta));
        verify(store).putClusterMeta(meta);
    }

    @Test
    void rotateSeedRewritesIdentitySeedFromShadowedMeta() {
        MongoClusterStore store = mock(MongoClusterStore.class);
        when(store.getClusterMeta()).thenReturn(Optional.of(new ClusterMeta("cluster-1", "old", Instant.EPOCH, 1)));
        new MongoClusterShadow(store).accept(new ClusterEntry.RotateSeed("new", "admin", Instant.EPOCH));
        verify(store).putClusterMeta(new ClusterMeta("cluster-1", "new", Instant.EPOCH, 1));
    }

    @Test
    void mirrorsMemberAddAndRemove() {
        MongoClusterStore store = mock(MongoClusterStore.class);
        MongoClusterShadow shadow = new MongoClusterShadow(store);
        shadow.accept(new ClusterEntry.AddMember(MEMBER));
        verify(store).putMember(MEMBER);
        shadow.accept(new ClusterEntry.RemoveMember("ctrl-2", "left", Instant.EPOCH));
        verify(store).removeMember("ctrl-2");
    }

    @Test
    void mirrorsJoinTokenLifecycle() {
        MongoClusterStore store = mock(MongoClusterStore.class);
        MongoClusterShadow shadow = new MongoClusterShadow(store);
        JoinToken token = new JoinToken(
                "jti-1", "hmac", "edge", "admin", Instant.EPOCH, Instant.EPOCH.plusSeconds(60), null, null, null,
                false, null, null);
        shadow.accept(new ClusterEntry.WriteJoinToken(token));
        verify(store).putJoinToken(token);
        shadow.accept(new ClusterEntry.RedeemJoinToken("jti-1", Instant.EPOCH, "10.0.0.0/24", "ctrl-2"));
        verify(store).redeemJoinToken("jti-1", Instant.EPOCH, "10.0.0.0/24", "ctrl-2");
        shadow.accept(new ClusterEntry.RevokeJoinToken("jti-1", "admin", Instant.EPOCH));
        verify(store).revokeJoinToken("jti-1", "admin", Instant.EPOCH);
    }

    @Test
    void mirrorsClusterFileWriteAndDelete() {
        MongoClusterStore store = mock(MongoClusterStore.class);
        MongoClusterShadow shadow = new MongoClusterShadow(store);
        ClusterFile file = new ClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT, "sha", "pem".getBytes());
        shadow.accept(new ClusterEntry.WriteClusterFile(file));
        verify(store).putClusterFile(file);
        shadow.accept(new ClusterEntry.DeleteClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT));
        verify(store).removeClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT);
    }

    @Test
    void doesNotMirrorConfigVersionsLeasesOrHeartbeats() {
        MongoClusterStore store = mock(MongoClusterStore.class);
        MongoClusterShadow shadow = new MongoClusterShadow(store);

        shadow.accept(new ClusterEntry.WriteConfigVersion(
                new ClusterConfigVersion(1, 0, "admin", Instant.EPOCH, Map.of(), "init")));
        shadow.accept(new ClusterEntry.SetActiveConfigVersion(1, "admin", Instant.EPOCH));
        shadow.accept(new ClusterEntry.TouchMember("ctrl-2", Instant.EPOCH));
        shadow.accept(new ClusterEntry.GrantLease("scheduler", "ctrl-1", Instant.EPOCH, 15_000));
        shadow.accept(new ClusterEntry.RenewLease("scheduler", "ctrl-1", Instant.EPOCH));
        shadow.accept(new ClusterEntry.ReleaseLease("scheduler", "ctrl-1"));

        verifyNoInteractions(store);
    }
}
