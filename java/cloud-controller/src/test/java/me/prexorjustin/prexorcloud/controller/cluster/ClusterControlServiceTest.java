package me.prexorjustin.prexorcloud.controller.cluster;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import me.prexorjustin.prexorcloud.controller.cluster.mongo.MongoClusterStore;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile;
import me.prexorjustin.prexorcloud.controller.config.ClusterConfig;
import me.prexorjustin.prexorcloud.controller.config.ClusterJoinTemplate;
import me.prexorjustin.prexorcloud.controller.config.ControllerConfig;
import me.prexorjustin.prexorcloud.controller.config.CorsConfig;
import me.prexorjustin.prexorcloud.controller.config.HttpConfig;
import me.prexorjustin.prexorcloud.controller.config.NetworkConfig;
import me.prexorjustin.prexorcloud.controller.config.RaftConfig;
import me.prexorjustin.prexorcloud.controller.config.RedisConfig;
import me.prexorjustin.prexorcloud.controller.config.RuntimeConfig;
import me.prexorjustin.prexorcloud.controller.config.SecurityControllerConfig;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test for {@link ClusterControlService} against the Mongo cluster store — the pure-Mongo,
 * single-writer control plane (no Raft). Day-0 stamps a fresh identity + CA + config seed + self member
 * into Mongo; a restart verifies the stored identity against {@code controller.yml} and reuses it; a
 * configured {@code cluster.id} that disagrees with the store refuses to boot; an empty store under a
 * retained {@code cluster.id} flags the catastrophic-reset signal. Needs a real replica-set Mongo
 * (majority/linearizable concerns, atomic CAS), so it boots a {@link MongoDBContainer} and self-skips
 * without Docker / an external URI; CI runs it.
 */
final class ClusterControlServiceTest {

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
        assumeTrue(dockerAvailable(), "Docker not available — skipping cluster control service integration test");
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

    private MongoDatabase freshDb() {
        return client.getDatabase("ccs_" + UUID.randomUUID().toString().replace("-", ""));
    }

    /** Shared with the materials + Mongo-join tests in this package. */
    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** Shared config factory; {@code clusterId} is the optional {@code controller.yml} cluster id mirror. */
    static ControllerConfig sampleConfig(Path tmp, int raftPort, String clusterId) {
        return new ControllerConfig(
                "node-local-uuid",
                new HttpConfig("0.0.0.0", 8080, new CorsConfig(List.of())),
                null,
                new NetworkConfig(List.of("10.0.0.0/8")),
                null,
                null,
                null,
                null,
                new RuntimeConfig(RuntimeConfig.PRODUCTION),
                new SecurityControllerConfig("jwt-secret-1234567890123456789012345678901234567890", 720, "", null),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                new RedisConfig("redis://10.0.0.50:6379"),
                new ClusterConfig(clusterId, null, null),
                new RaftConfig("127.0.0.1", raftPort, tmp.resolve("raft").toString(), List.of()));
    }

    @Test
    void day0StampsIdentityCaConfigAndSelfMemberIntoMongo(@TempDir Path tmp) throws Exception {
        MongoClusterStore store = new MongoClusterStore(freshDb());
        ControllerConfig cfg = sampleConfig(tmp, freePort(), null);

        try (ClusterControlService svc = new ClusterControlService(cfg, "controller-1", store)) {
            svc.start();

            assertNotNull(svc.clusterId());
            assertTrue(store.getClusterMeta().isPresent(), "identity stamped into Mongo");
            assertEquals(svc.clusterId(), store.getClusterMeta().orElseThrow().clusterId());
            assertTrue(store.getClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT).isPresent(), "CA cert stamped");
            assertTrue(store.getClusterFile(ClusterFile.KEY_CLUSTER_CA_KEY).isPresent(), "CA key stamped");
            assertEquals(1, store.listMembers().size(), "Day-0 self member registered");
            assertEquals("controller-1", store.listMembers().get(0).nodeId());
            assertFalse(
                    store.listMembers().get(0).gRPCAddr().startsWith("0.0.0.0"),
                    "advertised gRPC address must be routable, not the 0.0.0.0 bind host");
            if (!ClusterJoinTemplate.buildSharedMap(cfg).isEmpty()) {
                assertTrue(store.getActiveConfigVersion() > 0, "initial cluster_config seeded from controller.yml");
            }
        }
    }

    @Test
    void restartVerifiesAndReusesTheStoredIdentity(@TempDir Path tmp) throws Exception {
        MongoClusterStore store = new MongoClusterStore(freshDb());
        ControllerConfig cfg = sampleConfig(tmp, freePort(), null);

        String clusterId;
        byte[] caBytes;
        try (ClusterControlService svc = new ClusterControlService(cfg, "controller-1", store)) {
            svc.start();
            clusterId = svc.clusterId();
            caBytes = store.getClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT)
                    .orElseThrow()
                    .bytes();
        }

        try (ClusterControlService svc = new ClusterControlService(cfg, "controller-1", store)) {
            svc.start();
            assertEquals(clusterId, svc.clusterId(), "restart reuses the stored clusterId");
            assertArrayEquals(
                    caBytes,
                    store.getClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT)
                            .orElseThrow()
                            .bytes(),
                    "restart must not re-mint the CA");
            assertEquals(1, store.listMembers().size(), "restart must not duplicate the self member");
        }
    }

    @Test
    void refusesToBootOnConfiguredClusterIdMismatch(@TempDir Path tmp) throws Exception {
        MongoClusterStore store = new MongoClusterStore(freshDb());
        try (ClusterControlService svc = new ClusterControlService(sampleConfig(tmp, freePort(), null), "c1", store)) {
            svc.start();
        }

        ControllerConfig mismatched =
                sampleConfig(tmp, freePort(), UUID.randomUUID().toString());
        try (ClusterControlService svc = new ClusterControlService(mismatched, "c1", store)) {
            assertThrows(IllegalStateException.class, svc::start, "a configured cluster.id that disagrees must refuse");
        }
    }

    @Test
    void flagsUnsafeResetWhenStoreEmptyButYamlCarriesAnId(@TempDir Path tmp) throws Exception {
        MongoClusterStore store = new MongoClusterStore(freshDb());
        String configuredId = UUID.randomUUID().toString();
        ControllerConfig cfg = sampleConfig(tmp, freePort(), configuredId);

        try (ClusterControlService svc = new ClusterControlService(cfg, "c1", store)) {
            svc.start();
            assertEquals(configuredId, svc.clusterId(), "the configured id is preserved");
            assertTrue(svc.unsafeResetDetected(), "empty store + retained cluster.id is a catastrophic reset");
        }
    }
}
