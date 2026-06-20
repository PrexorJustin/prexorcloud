package me.prexorjustin.prexorcloud.controller.cluster;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import me.prexorjustin.prexorcloud.controller.cluster.mongo.MongoClusterStore;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile;
import me.prexorjustin.prexorcloud.controller.config.ControllerConfig;

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
 * Day-0 with on-disk TLS material: {@link ClusterControlService#start(LocalClusterMaterials)} against an
 * empty materials directory mints a cluster CA + self leaf cert, persists them to disk for the next
 * restart, and stamps the CA into the Mongo cluster store so joiners adopt it. The next restart reuses
 * the persisted material instead of re-minting. RS-gated; self-skips without Docker / an external URI.
 */
final class ClusterControlServiceMaterialsTest {

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
        assumeTrue(dockerAvailable(), "Docker not available — skipping cluster materials integration test");
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
        return client.getDatabase("ccsmat_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @Test
    @DisplayName("Day-0 mints + persists cluster TLS material and writes the CA into the Mongo store")
    void day0MintsAndPersistsTlsMaterial(@TempDir Path tmp) throws Exception {
        MongoClusterStore store = new MongoClusterStore(freshDb());
        ControllerConfig cfg = ClusterControlServiceTest.sampleConfig(tmp, ClusterControlServiceTest.freePort(), null);
        Path materialsDir = tmp.resolve("cluster-materials");
        LocalClusterMaterials materials = new LocalClusterMaterials(materialsDir);

        String clusterId;
        byte[] caCertBytes;
        try (ClusterControlService svc = new ClusterControlService(cfg, "controller-1", store)) {
            svc.start(materials);
            clusterId = svc.clusterId();
            assertNotNull(clusterId);
            // CA stamped into the Mongo cluster store.
            ClusterFile caFile =
                    store.getClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT).orElseThrow();
            caCertBytes = caFile.bytes();
            // Self appears in the member list (Day-0 founder).
            assertEquals(1, store.listMembers().size());
            assertEquals("controller-1", store.listMembers().get(0).nodeId());
        }

        assertTrue(Files.exists(materialsDir.resolve(LocalClusterMaterials.CA_CERT_FILE)));
        assertTrue(Files.exists(materialsDir.resolve(LocalClusterMaterials.LEAF_CERT_FILE)));
        assertTrue(Files.exists(materialsDir.resolve(LocalClusterMaterials.LEAF_KEY_FILE)));

        // Restart: must reuse persisted material AND the same clusterId, without re-minting the CA.
        try (ClusterControlService svc = new ClusterControlService(cfg, "controller-1", store)) {
            svc.start(materials);
            assertEquals(clusterId, svc.clusterId(), "restart must reuse the persisted clusterId");
            assertArrayEquals(
                    caCertBytes,
                    store.getClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT)
                            .orElseThrow()
                            .bytes(),
                    "the CA in the store must be the same bytes after restart (we don't re-mint)");
        }

        // The on-disk leaf cert must chain to the persisted CA (sanity check on the issuance).
        LocalClusterMaterials.Loaded loaded = materials.load();
        loaded.leafCert().verify(loaded.caCert().getPublicKey());
    }
}
