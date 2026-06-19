package me.prexorjustin.prexorcloud.controller.cluster.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlPlane;
import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlStateMachine;
import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterWriteConflict;
import me.prexorjustin.prexorcloud.controller.cluster.raft.RaftBootstrap;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.cluster.state.JoinToken;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;
import me.prexorjustin.prexorcloud.controller.config.RaftConfig;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end test of the dual-write <em>seam</em>: a {@link MongoClusterShadow} registered on a real
 * {@link ClusterControlStateMachine}'s commit-listener, driving committed entries through real
 * embedded Raft and asserting they land in a real (replica-set) {@link MongoClusterStore}. The
 * per-type mapping is pinned with mocks in {@code MongoClusterShadowTest} and the store's guards in
 * {@code MongoClusterStoreTest}; this proves the two things only the live seam can:
 *
 * <ul>
 *   <li>a representative multi-type committed sequence actually flows Raft apply → commit listener →
 *       shadow → Mongo;
 *   <li><b>the correctness linchpin:</b> a <em>rejected</em> write (stale parentVersion) does NOT
 *       mirror — {@code notifyCommit} fires only on {@code reply.ok()}, so Mongo can never shadow a
 *       write Raft refused. This is what keeps the dual-write from diverging.
 * </ul>
 *
 * <p>Needs a replica-set Mongo (the store uses majority concerns + atomic guards), so it boots a
 * {@link MongoDBContainer} and self-skips where Docker is unavailable; CI runs it. Mirrors the
 * harness of {@code MongoClusterStoreTest} (Mongo) and {@code ClusterControlPlaneTest} (Raft).
 */
final class MongoClusterShadowSeamTest {

    private static final String SEED = "Y29udHJvbC1wbGFuZS1zZWVkLW5vdC1yZWFsLWp1c3QtdGVzdGluZw==";

    private static MongoClient client;
    private static MongoDBContainer mongo;

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
        assumeTrue(dockerAvailable(), "Docker not available — skipping Mongo cluster-shadow seam test");
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

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    private static MongoClusterStore freshStore() {
        MongoDatabase db = client.getDatabase(
                "clustershadow_" + UUID.randomUUID().toString().replace("-", ""));
        return new MongoClusterStore(db);
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static RaftBootstrap newBootstrap(Path tmp, ClusterControlStateMachine sm, int port, UUID groupId) {
        RaftConfig cfg = new RaftConfig("127.0.0.1", port, tmp.resolve("raft").toString(), List.of());
        return new RaftBootstrap(cfg, groupId, "controller-1", sm);
    }

    @Test
    @DisplayName("a committed multi-type sequence flows through the listener into Mongo")
    void committedWritesAreMirroredIntoMongo(@TempDir Path tmp) throws Exception {
        int port = freePort();
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-0000000005a1");
        ClusterControlStateMachine sm = new ClusterControlStateMachine();
        MongoClusterStore store = freshStore();
        sm.addCommitListener(new MongoClusterShadow(store));

        try (RaftBootstrap raft = newBootstrap(tmp, sm, port, groupId)) {
            raft.start();
            raft.awaitLeader(10_000);
            ClusterControlPlane cp = new ClusterControlPlane(raft, sm);

            ClusterMeta meta = new ClusterMeta(
                    "cluster-seam", SEED, Instant.parse("2026-06-19T12:00:00Z"), ClusterMeta.CURRENT_SCHEMA_VERSION);
            cp.setClusterMeta(meta);

            Member m = new Member(
                    "controller-1",
                    "127.0.0.1:" + port,
                    "127.0.0.1:8080",
                    "127.0.0.1:9090",
                    "primary",
                    Instant.parse("2026-06-19T12:00:01Z"),
                    Instant.parse("2026-06-19T12:00:01Z"));
            cp.addMember(m);

            JoinToken token = new JoinToken(
                    "jti-seam",
                    "deadbeef",
                    "controller-2-label",
                    "alice",
                    Instant.parse("2026-06-19T12:00:00Z"),
                    Instant.parse("2026-06-20T12:00:00Z"),
                    null,
                    null,
                    null,
                    false,
                    null,
                    null);
            cp.writeJoinToken(token);
            cp.redeemJoinToken("jti-seam", Instant.parse("2026-06-19T12:01:00Z"), "10.0.0.42", "controller-2-node");

            cp.proposeConfigPatch(0, "wizard", Map.of("k", "v0"), "init"); // version 1
            cp.proposeConfigPatch(1, "alice", Map.of("k", "v1"), "second"); // version 2
            cp.rollbackConfig(1, "alice"); // active pointer back to 1

            // The commit listener runs synchronously inside apply(), so the shadow has already written
            // by the time each cp call returns — no await needed.
            assertEquals(meta, store.getClusterMeta().orElseThrow());
            assertEquals(
                    "primary", store.getMember("controller-1").orElseThrow().label());

            JoinToken mirrored = store.getJoinToken("jti-seam").orElseThrow();
            assertNotNull(mirrored.redeemedAt());
            assertEquals("controller-2-node", mirrored.redeemedAs());

            assertEquals(2, store.listConfigVersions().size());
            assertEquals(1, store.getActiveConfigVersion());
        }
    }

    @Test
    @DisplayName("a Raft-rejected write must NOT mirror into Mongo (listener fires only on reply.ok())")
    void rejectedWriteIsNotMirrored(@TempDir Path tmp) throws Exception {
        int port = freePort();
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-0000000005a2");
        ClusterControlStateMachine sm = new ClusterControlStateMachine();
        MongoClusterStore store = freshStore();
        sm.addCommitListener(new MongoClusterShadow(store));

        try (RaftBootstrap raft = newBootstrap(tmp, sm, port, groupId)) {
            raft.start();
            raft.awaitLeader(10_000);
            ClusterControlPlane cp = new ClusterControlPlane(raft, sm);

            cp.proposeConfigPatch(0, "wizard", Map.of("k", "v0"), "init"); // version 1, active 1
            cp.proposeConfigPatch(1, "alice", Map.of("k", "v1"), "second"); // version 2, active 2
            assertEquals(2, store.listConfigVersions().size(), "accepted writes mirror");

            // parentVersion=1 while active=2 → Raft rejects with PARENT_VERSION_STALE.
            ClusterWriteConflict ex = assertThrows(
                    ClusterWriteConflict.class, () -> cp.proposeConfigPatch(1, "bob", Map.of("k", "v2"), "stale"));
            assertEquals("PARENT_VERSION_STALE", ex.code());

            // The rejected write left no shadow: still 2 versions, active unchanged.
            assertEquals(2, store.listConfigVersions().size(), "a rejected write must never reach Mongo");
            assertEquals(2, store.getActiveConfigVersion());
        }
    }
}
