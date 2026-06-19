package me.prexorjustin.prexorcloud.controller.cluster.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterConfigVersion;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.cluster.state.JoinToken;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for the Mongo-backed cluster store. They need a real replica-set Mongo (majority
 * concerns, atomic {@code findOneAndUpdate} for single-use redeem), so they boot a
 * {@link MongoDBContainer} (auto single-node RS — the deployed topology) and self-skip where Docker
 * is unavailable; CI runs them. Mirrors the harness of {@code MongoLeaderElectorTest}.
 *
 * <p>Covers the guarded-write semantics: anti-fork identity CAS, member upsert/self-heal, the
 * single-use/expired/revoked join-token gate, and last-write-wins cluster files.
 */
final class MongoClusterStoreTest {

    private static MongoClient client;
    private static MongoDBContainer mongo;

    private MongoClusterStore store;

    @BeforeAll
    static void up() {
        String externalUri = System.getenv("PREXOR_TEST_MONGO_URI");
        if (externalUri == null || externalUri.isBlank()) {
            externalUri = System.getProperty("prexor.test.mongoUri");
        }
        if (externalUri != null && !externalUri.isBlank()) {
            client = MongoClients.create(externalUri);
            return;
        }
        assumeTrue(dockerAvailable(), "Docker not available — skipping Mongo cluster-store integration tests");
        mongo = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));
        mongo.start();
        client = MongoClients.create(mongo.getConnectionString());
    }

    @AfterAll
    static void down() {
        if (client != null) {
            client.close();
        }
        if (mongo != null) {
            mongo.stop();
        }
    }

    @BeforeEach
    void freshStore() {
        // A fresh database per test isolates documents without cross-test cleanup.
        MongoDatabase db = client.getDatabase(
                "clusterstore_" + UUID.randomUUID().toString().replace("-", ""));
        store = new MongoClusterStore(db);
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    private static ClusterMeta meta(String clusterId, String seed) {
        return new ClusterMeta(
                clusterId, seed, Instant.ofEpochMilli(1_700_000_000_000L), ClusterMeta.CURRENT_SCHEMA_VERSION);
    }

    private static Member member(String nodeId, String grpcAddr) {
        return new Member(nodeId, nodeId + ":9190", nodeId + ":8080", grpcAddr, nodeId, Instant.now(), Instant.now());
    }

    private static JoinToken outstanding(String jti, Instant expiresAt) {
        return new JoinToken(
                jti, "hmac", "edge", "admin", Instant.now(), expiresAt, null, null, null, false, null, null);
    }

    private static ClusterConfigVersion cv(int version, int parentVersion) {
        return new ClusterConfigVersion(
                version, parentVersion, "admin", Instant.ofEpochMilli(version), Map.of("v", version), "rev " + version);
    }

    // --- identity (anti-fork CAS) ---

    @Test
    void identityFirstWriteWinsThenRefreshesSameClusterId() {
        store.putClusterMeta(meta("cluster-1", "seed-A"));
        assertEquals("cluster-1", store.getClusterMeta().orElseThrow().clusterId());

        // same clusterId → idempotent refresh of the seed
        store.putClusterMeta(meta("cluster-1", "seed-B"));
        assertEquals("seed-B", store.getClusterMeta().orElseThrow().seedSecretBase64());
    }

    @Test
    void identityForkIsRejected() {
        store.putClusterMeta(meta("cluster-1", "seed-A"));
        assertThrows(
                MongoClusterStore.ClusterForkException.class, () -> store.putClusterMeta(meta("cluster-2", "seed-X")));
        // original identity is preserved
        assertEquals("cluster-1", store.getClusterMeta().orElseThrow().clusterId());
    }

    // --- members ---

    @Test
    void memberUpsertSelfHealsAddressAndRemoves() {
        store.putMember(member("ctrl-2", "10.0.0.6:50051"));
        assertEquals(1, store.listMembers().size());

        store.putMember(member("ctrl-2", "10.0.0.99:50051")); // address changed across reconnect
        assertEquals(1, store.listMembers().size());
        assertEquals("10.0.0.99:50051", store.getMember("ctrl-2").orElseThrow().gRPCAddr());

        assertTrue(store.removeMember("ctrl-2"));
        assertFalse(store.removeMember("ctrl-2"));
        assertTrue(store.listMembers().isEmpty());
    }

    @Test
    void membersAreListedSortedByNodeId() {
        store.putMember(member("ctrl-3", "a"));
        store.putMember(member("ctrl-1", "b"));
        store.putMember(member("ctrl-2", "c"));
        assertEquals(
                List.of("ctrl-1", "ctrl-2", "ctrl-3"),
                store.listMembers().stream().map(Member::nodeId).toList());
    }

    // --- join tokens (single-use redeem) ---

    @Test
    void joinTokenRedeemsExactlyOnce() {
        Instant now = Instant.now();
        store.putJoinToken(outstanding("jti-1", now.plus(Duration.ofHours(1))));

        assertTrue(store.redeemJoinToken("jti-1", now, "10.0.0.0/24", "ctrl-2"));
        assertFalse(store.redeemJoinToken("jti-1", now, "10.0.0.0/24", "ctrl-3"));

        JoinToken redeemed = store.getJoinToken("jti-1").orElseThrow();
        assertEquals("ctrl-2", redeemed.redeemedAs());
        assertEquals("10.0.0.0/24", redeemed.redeemedFrom());
    }

    @Test
    void expiredJoinTokenCannotBeRedeemed() {
        Instant now = Instant.now();
        store.putJoinToken(outstanding("jti-old", now.minus(Duration.ofMinutes(1))));
        assertFalse(store.redeemJoinToken("jti-old", now, "10.0.0.0/24", "ctrl-2"));
    }

    @Test
    void revokedJoinTokenCannotBeRedeemed() {
        Instant now = Instant.now();
        store.putJoinToken(outstanding("jti-rev", now.plus(Duration.ofHours(1))));

        assertTrue(store.revokeJoinToken("jti-rev", "admin", now));
        assertFalse(store.revokeJoinToken("jti-rev", "admin", now)); // idempotent
        assertFalse(store.redeemJoinToken("jti-rev", now, "10.0.0.0/24", "ctrl-2"));
    }

    // --- cluster files ---

    @Test
    void clusterFileIsLastWriteWins() {
        store.putClusterFile(new ClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT, "sha-1", "cert-v1".getBytes()));
        assertEquals(
                "sha-1",
                store.getClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT)
                        .orElseThrow()
                        .sha256());

        store.putClusterFile(new ClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT, "sha-2", "cert-v2".getBytes()));
        ClusterFile latest =
                store.getClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT).orElseThrow();
        assertEquals("sha-2", latest.sha256());
        assertEquals("cert-v2", new String(latest.bytes()));
    }

    // --- config versions (optimistic concurrency on parentVersion + active pointer) ---

    @Test
    void configVersionsAppendInOrderAndAdvanceActive() {
        assertEquals(0, store.getActiveConfigVersion());
        assertTrue(store.getActiveConfigPatch().isEmpty());

        assertTrue(store.writeConfigVersion(cv(1, 0)));
        assertTrue(store.writeConfigVersion(cv(2, 1)));
        assertTrue(store.writeConfigVersion(cv(3, 2)));

        assertEquals(3, store.getActiveConfigVersion());
        assertEquals(3, store.getActiveConfigPatch().orElseThrow().version());
        assertEquals(
                List.of(1, 2, 3),
                store.listConfigVersions().stream()
                        .map(ClusterConfigVersion::version)
                        .toList());
    }

    @Test
    void firstConfigVersionMustDeclareParentZero() {
        assertFalse(store.writeConfigVersion(cv(1, 5)));
        assertTrue(store.listConfigVersions().isEmpty());
        assertEquals(0, store.getActiveConfigVersion());
    }

    @Test
    void configVersionMustBeNextOrdinal() {
        assertTrue(store.writeConfigVersion(cv(1, 0)));
        // version 3 skips 2 — rejected, no append.
        assertFalse(store.writeConfigVersion(cv(3, 1)));
        assertEquals(
                List.of(1),
                store.listConfigVersions().stream()
                        .map(ClusterConfigVersion::version)
                        .toList());
    }

    @Test
    void staleParentVersionIsRejected() {
        assertTrue(store.writeConfigVersion(cv(1, 0)));
        assertTrue(store.writeConfigVersion(cv(2, 1)));
        // next ordinal is correct (3) but parentVersion=1 != active (2) — lost the race, rejected.
        assertFalse(store.writeConfigVersion(cv(3, 1)));
        assertEquals(2, store.getActiveConfigVersion());
        assertEquals(2, store.listConfigVersions().size());
    }

    @Test
    void writingTheSameConfigVersionTwiceIsANoOp() {
        assertTrue(store.writeConfigVersion(cv(1, 0)));
        assertFalse(store.writeConfigVersion(cv(1, 0))); // replay
        assertEquals(1, store.listConfigVersions().size());
    }

    @Test
    void setActiveRollsBackThenNewVersionRebasesOntoIt() {
        store.writeConfigVersion(cv(1, 0));
        store.writeConfigVersion(cv(2, 1));
        store.writeConfigVersion(cv(3, 2));

        assertFalse(store.setActiveConfigVersion(9)); // unknown version
        assertTrue(store.setActiveConfigVersion(1)); // roll back the active pointer
        assertEquals(1, store.getActiveConfigVersion());

        // the new head is still ordinal 4, but it must declare parentVersion = active (1).
        assertFalse(store.writeConfigVersion(cv(4, 2)));
        assertTrue(store.writeConfigVersion(cv(4, 1)));
        assertEquals(4, store.getActiveConfigVersion());
        assertEquals(
                List.of(1, 2, 3, 4),
                store.listConfigVersions().stream()
                        .map(ClusterConfigVersion::version)
                        .toList());
    }
}
