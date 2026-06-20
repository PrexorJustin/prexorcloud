package me.prexorjustin.prexorcloud.controller.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import me.prexorjustin.prexorcloud.controller.cluster.mongo.MongoClusterStore;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.cluster.state.JoinToken;
import me.prexorjustin.prexorcloud.controller.config.ControllerConfig;
import me.prexorjustin.prexorcloud.security.ca.CertificateAuthority;

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
 * Integration test for the Mongo-register join. Seeds a real replica-set Mongo cluster store with a
 * founder's identity, CA, and a valid join token, then drives a second controller through
 * {@link ClusterControlService#startInJoinMode} and asserts it registers itself in {@code cluster_members},
 * single-use-redeems the token, and persists CA-chained TLS material — all without a consensus group join
 * or a peer mTLS handshake. Needs a real RS Mongo (atomic redeem, majority concerns), so it boots a
 * {@link MongoDBContainer} and self-skips without Docker / an external URI; CI runs it.
 */
final class ClusterControlServiceMongoJoinTest {

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
        assumeTrue(dockerAvailable(), "Docker not available — skipping Mongo-register join integration test");
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
        return client.getDatabase("mongojoin_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @Test
    void mongoRegisterJoinRegistersInMongoWithoutRaftGroupJoin(@TempDir Path tmp) throws Exception {
        MongoClusterStore store = new MongoClusterStore(freshDb());

        // --- seed the cluster as a founder would have (identity + CA + a valid join token) ---
        CertificateAuthority ca = CertificateAuthority.createInMemory("Test Cluster CA", 3650);
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        String clusterId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        store.putClusterMeta(new ClusterMeta(clusterId, Base64.getEncoder().encodeToString(seed), now, 1));
        store.putClusterFile(new ClusterFile(
                ClusterFile.KEY_CLUSTER_CA_CERT, "sha", ca.certificate().getEncoded()));
        store.putClusterFile(new ClusterFile(
                ClusterFile.KEY_CLUSTER_CA_KEY, "sha", ca.keyPair().getPrivate().getEncoded()));
        Instant expiresAt = now.plusSeconds(3600);
        JoinTokenCodec.Issued issued = JoinTokenCodec.encode(clusterId, List.of("10.0.0.3:9190"), expiresAt, seed);
        store.putJoinToken(new JoinToken(
                issued.jti(),
                issued.hmacBase64(),
                "ctrl-2",
                "admin",
                now,
                expiresAt,
                null,
                null,
                null,
                false,
                null,
                null));

        // --- the joiner runs the Mongo-register path ---
        ControllerConfig cfg =
                ClusterControlServiceTest.sampleConfig(tmp, ClusterControlServiceTest.freePort(), clusterId);
        var materials = new LocalClusterMaterials(tmp.resolve("cluster-materials"));
        var identity = new ClusterControlService.JoinIdentity("10.0.0.6:9190", "10.0.0.6:8080", "10.0.0.6:50051");

        try (ClusterControlService svc = new ClusterControlService(cfg, "ctrl-2", store)) {
            svc.startInJoinMode(issued.token(), materials, identity);

            assertEquals(clusterId, svc.clusterId(), "adopted the cluster identity from Mongo");
            assertTrue(store.getMember("ctrl-2").isPresent(), "joiner registered itself in cluster_members");
            assertEquals(
                    "10.0.0.6:9190", store.getMember("ctrl-2").orElseThrow().raftAddr());
            assertTrue(
                    store.getJoinToken(issued.jti()).orElseThrow().redeemedAt() != null,
                    "the join token was single-use redeemed");
            assertTrue(materials.exists(), "CA-chained TLS material persisted locally for daemon mTLS");
        }
    }

    @Test
    void mongoRegisterJoinRejectsAReplayedToken(@TempDir Path tmp) throws Exception {
        MongoClusterStore store = new MongoClusterStore(freshDb());
        CertificateAuthority ca = CertificateAuthority.createInMemory("Test Cluster CA", 3650);
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        String clusterId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        store.putClusterMeta(new ClusterMeta(clusterId, Base64.getEncoder().encodeToString(seed), now, 1));
        store.putClusterFile(new ClusterFile(
                ClusterFile.KEY_CLUSTER_CA_CERT, "sha", ca.certificate().getEncoded()));
        store.putClusterFile(new ClusterFile(
                ClusterFile.KEY_CLUSTER_CA_KEY, "sha", ca.keyPair().getPrivate().getEncoded()));
        Instant expiresAt = now.plusSeconds(3600);
        JoinTokenCodec.Issued issued = JoinTokenCodec.encode(clusterId, List.of("10.0.0.3:9190"), expiresAt, seed);
        // already redeemed by a prior joiner
        store.putJoinToken(new JoinToken(
                issued.jti(),
                issued.hmacBase64(),
                "ctrl-2",
                "admin",
                now,
                expiresAt,
                now,
                "10.0.0.9",
                "ctrl-9",
                false,
                null,
                null));

        ControllerConfig cfg =
                ClusterControlServiceTest.sampleConfig(tmp, ClusterControlServiceTest.freePort(), clusterId);
        var materials = new LocalClusterMaterials(tmp.resolve("cluster-materials"));
        var identity = new ClusterControlService.JoinIdentity("10.0.0.6:9190", "10.0.0.6:8080", "10.0.0.6:50051");

        try (ClusterControlService svc = new ClusterControlService(cfg, "ctrl-2", store)) {
            assertThrows(
                    IOException.class,
                    () -> svc.startInJoinMode(issued.token(), materials, identity),
                    "a replayed (already-redeemed) token must be rejected");
        }
    }
}
