package me.prexorjustin.prexorcloud.controller.cluster.mongo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;

import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.cluster.state.JoinToken;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;

import org.junit.jupiter.api.Test;

/**
 * Pure BSON round-trip tests for the {@link MongoClusterStore} codecs — no Mongo, so these run
 * locally (the integration test that needs a replica set self-skips without Docker). Timestamps use
 * epoch-millis fixtures because BSON dates are millisecond-precision; that keeps record equality
 * exact across the round-trip.
 */
final class MongoClusterStoreCodecTest {

    @Test
    void clusterMetaRoundTrips() {
        ClusterMeta meta = new ClusterMeta("cluster-abc", "c2VlZHNlY3JldA==", Instant.ofEpochMilli(1_700_000_000_000L), 1);
        assertEquals(meta, MongoClusterStore.toClusterMeta(MongoClusterStore.clusterMetaDoc(meta)));
    }

    @Test
    void memberRoundTrips() {
        Member m = new Member(
                "ctrl-2",
                "10.0.0.6:9190",
                "10.0.0.6:8080",
                "10.0.0.6:50051",
                "ctrl-2",
                Instant.ofEpochMilli(1_000L),
                Instant.ofEpochMilli(2_000L));
        assertEquals(m, MongoClusterStore.toMember(MongoClusterStore.memberDoc(m)));
    }

    @Test
    void outstandingJoinTokenRoundTrips() {
        JoinToken t = new JoinToken(
                "jti-1", "hmac", "edge", "admin", Instant.ofEpochMilli(0L), Instant.ofEpochMilli(60_000L), null, null,
                null, false, null, null);
        assertEquals(t, MongoClusterStore.toJoinToken(MongoClusterStore.joinTokenDoc(t)));
    }

    @Test
    void redeemedAndRevokedJoinTokensRoundTrip() {
        JoinToken redeemed = new JoinToken(
                "jti-2", "hmac", "edge", "admin", Instant.ofEpochMilli(0L), Instant.ofEpochMilli(60_000L),
                Instant.ofEpochMilli(30_000L), "10.0.0.0/24", "ctrl-3", false, null, null);
        assertEquals(redeemed, MongoClusterStore.toJoinToken(MongoClusterStore.joinTokenDoc(redeemed)));

        JoinToken revoked = new JoinToken(
                "jti-3", "hmac", "edge", "admin", Instant.ofEpochMilli(0L), Instant.ofEpochMilli(60_000L), null, null,
                null, true, "admin", Instant.ofEpochMilli(10_000L));
        assertEquals(revoked, MongoClusterStore.toJoinToken(MongoClusterStore.joinTokenDoc(revoked)));
    }

    @Test
    void clusterFileRoundTrips() {
        byte[] pem = "-----BEGIN CERTIFICATE-----\nMIIB...\n-----END CERTIFICATE-----".getBytes();
        ClusterFile f = new ClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT, "abc123sha", pem);
        ClusterFile back = MongoClusterStore.toClusterFile(MongoClusterStore.fileDoc(f));
        assertEquals(f.key(), back.key());
        assertEquals(f.sha256(), back.sha256());
        assertArrayEquals(f.bytes(), back.bytes());
    }
}
