package me.prexorjustin.prexorcloud.controller.cluster.mongo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlPlane;
import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlStateMachine;
import me.prexorjustin.prexorcloud.controller.cluster.raft.RaftBootstrap;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterConfigVersion;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile;
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
 * The Phase-4 backfill ({@link MongoClusterBackfill}) and the local analog of the fleet dual-write
 * soak: drive a rich committed cluster state through real embedded Raft, seed a real replica-set
 * {@link MongoClusterStore} from it, and assert the Mongo projection matches Raft across every state
 * type — then prove ongoing dual-write keeps them convergent. Self-skips without Docker; CI runs it.
 *
 * <p>The config history deliberately includes a <em>rebase</em> (a version whose {@code parentVersion}
 * is neither {@code 0} nor {@code version-1}) and an active pointer below the max version — the cases
 * an in-order replay through the per-write guard cannot reproduce, which is why backfill seeds the
 * aggregate directly.
 */
final class MongoClusterBackfillTest {

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
        assumeTrue(dockerAvailable(), "Docker not available — skipping Mongo cluster-backfill test");
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
                "clusterbackfill_" + UUID.randomUUID().toString().replace("-", ""));
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

    private static Member member(String nodeId, String grpcAddr) {
        return new Member(
                nodeId,
                nodeId + ":9190",
                nodeId + ":8080",
                grpcAddr,
                nodeId,
                Instant.ofEpochMilli(1_000),
                Instant.ofEpochMilli(2_000));
    }

    /** Lay down members, a redeemed + a revoked token, two CA files, and a config history with a rebase. */
    private static void seedRaftState(ClusterControlPlane cp) throws Exception {
        cp.setClusterMeta(new ClusterMeta(
                "cluster-backfill", SEED, Instant.parse("2026-06-20T12:00:00Z"), ClusterMeta.CURRENT_SCHEMA_VERSION));

        cp.addMember(member("controller-1", "10.0.0.1:50051"));
        cp.addMember(member("controller-2", "10.0.0.2:50051"));

        cp.writeJoinToken(token("jti-redeemed"));
        cp.redeemJoinToken("jti-redeemed", Instant.parse("2026-06-20T12:05:00Z"), "10.0.0.9", "controller-3");
        cp.writeJoinToken(token("jti-revoked"));
        cp.revokeJoinToken("jti-revoked", "admin");

        cp.writeClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT, "cert-bytes".getBytes());
        cp.writeClusterFile(ClusterFile.KEY_CLUSTER_CA_KEY, "key-bytes".getBytes());

        cp.proposeConfigPatch(0, "wizard", Map.of("k", "v0"), "init"); // v1, active 1
        cp.proposeConfigPatch(1, "alice", Map.of("k", "v1"), "second"); // v2, active 2
        cp.rollbackConfig(1, "alice"); // active 1
        cp.proposeConfigPatch(1, "bob", Map.of("k", "v2"), "rebase"); // v3 parent=1 (REBASE), active 3
        cp.rollbackConfig(2, "carol"); // active 2 (< max 3)
    }

    private static JoinToken token(String jti) {
        return new JoinToken(
                jti,
                "hmac",
                "edge",
                "admin",
                Instant.parse("2026-06-20T12:00:00Z"),
                Instant.parse("2026-06-21T12:00:00Z"),
                null,
                null,
                null,
                false,
                null,
                null);
    }

    @Test
    @DisplayName("backfill seeds a rich Raft state (incl. a rebase + active<max) into Mongo verbatim")
    void backfillSeedsRichRaftStateIncludingRebase(@TempDir Path tmp) throws Exception {
        int port = freePort();
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-0000000006a1");
        ClusterControlStateMachine sm = new ClusterControlStateMachine();
        MongoClusterStore store = freshStore();

        try (RaftBootstrap raft = newBootstrap(tmp, sm, port, groupId)) {
            raft.start();
            raft.awaitLeader(10_000);
            ClusterControlPlane cp = new ClusterControlPlane(raft, sm);
            seedRaftState(cp);

            assertTrue(store.getClusterMeta().isEmpty(), "Mongo starts empty — nothing mirrored yet");

            MongoClusterBackfill.seed(cp, store);

            assertStoreMirrorsPlane(store, cp);
            // The rebase and the rolled-back active pointer survived the seed exactly.
            assertEquals(1, store.listConfigVersions().get(2).parentVersion(), "v3 is a rebase off v1");
            assertEquals(3, store.listConfigVersions().size());
            assertEquals(2, store.getActiveConfigVersion());
        }
    }

    @Test
    @DisplayName("backfill then ongoing dual-write stays convergent (the local soak)")
    void backfillThenDualWriteStaysConvergent(@TempDir Path tmp) throws Exception {
        int port = freePort();
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-0000000006a2");
        ClusterControlStateMachine sm = new ClusterControlStateMachine();
        MongoClusterStore store = freshStore();

        try (RaftBootstrap raft = newBootstrap(tmp, sm, port, groupId)) {
            raft.start();
            raft.awaitLeader(10_000);
            ClusterControlPlane cp = new ClusterControlPlane(raft, sm);
            seedRaftState(cp);

            // Cutover moment: seed the existing state, then attach the shadow for everything after.
            MongoClusterBackfill.seed(cp, store);
            sm.addCommitListener(new MongoClusterShadow(store));

            // Writes after the cutover flow through dual-write; the seeded maxVersion lets the per-write
            // config guard pick up exactly where the backfill left off.
            cp.addMember(member("controller-3", "10.0.0.3:50051"));
            cp.proposeConfigPatch(cp.getActiveConfigVersion(), "dave", Map.of("k", "v3"), "post-cutover");
            cp.writeJoinToken(token("jti-postcutover"));
            cp.redeemJoinToken("jti-postcutover", Instant.parse("2026-06-20T13:00:00Z"), "10.0.0.4", "controller-4");

            assertStoreMirrorsPlane(store, cp);
            assertEquals(4, store.getActiveConfigVersion(), "the post-cutover patch advanced both stores");
            assertEquals(3, store.listMembers().size());
            assertEquals(
                    "controller-4",
                    store.getJoinToken("jti-postcutover").orElseThrow().redeemedAs());
        }
    }

    private static void assertStoreMirrorsPlane(MongoClusterStore store, ClusterControlPlane cp) {
        assertEquals(
                cp.getClusterMeta().map(ClusterMeta::clusterId),
                store.getClusterMeta().map(ClusterMeta::clusterId));
        assertEquals(
                cp.getClusterMeta().map(ClusterMeta::seedSecretBase64),
                store.getClusterMeta().map(ClusterMeta::seedSecretBase64));

        List<Member> members = cp.listMembers();
        assertEquals(members.size(), store.listMembers().size());
        for (Member m : members) {
            Member s = store.getMember(m.nodeId()).orElseThrow();
            assertEquals(m.raftAddr(), s.raftAddr());
            assertEquals(m.gRPCAddr(), s.gRPCAddr());
            assertEquals(m.label(), s.label());
        }

        List<JoinToken> tokens = cp.listJoinTokens();
        assertEquals(tokens.size(), store.listJoinTokens().size());
        for (JoinToken t : tokens) {
            JoinToken s = store.getJoinToken(t.jti()).orElseThrow();
            assertEquals(t.revoked(), s.revoked());
            assertEquals(t.redeemedAs(), s.redeemedAs());
            assertEquals(t.redeemedFrom(), s.redeemedFrom());
        }

        List<ClusterFile> files = cp.listClusterFiles();
        assertEquals(files.size(), store.listClusterFiles().size());
        for (ClusterFile f : files) {
            ClusterFile s = store.getClusterFile(f.key()).orElseThrow();
            assertEquals(f.sha256(), s.sha256());
            assertArrayEquals(f.bytes(), s.bytes());
        }

        assertEquals(cp.getActiveConfigVersion(), store.getActiveConfigVersion());
        List<ClusterConfigVersion> planeVersions = cp.listConfigVersions();
        List<ClusterConfigVersion> storeVersions = store.listConfigVersions();
        assertEquals(planeVersions.size(), storeVersions.size());
        for (int i = 0; i < planeVersions.size(); i++) {
            ClusterConfigVersion p = planeVersions.get(i);
            ClusterConfigVersion s = storeVersions.get(i);
            assertEquals(p.version(), s.version());
            assertEquals(p.parentVersion(), s.parentVersion());
            assertEquals(p.mutator(), s.mutator());
            assertEquals(p.reason(), s.reason());
            assertEquals(p.patch(), s.patch());
            // BSON dates are millisecond-precision; the in-memory stamp is Instant.now() (nanos).
            assertEquals(p.mutatedAt().toEpochMilli(), s.mutatedAt().toEpochMilli());
        }
    }
}
