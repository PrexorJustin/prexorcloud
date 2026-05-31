package me.prexorjustin.prexorcloud.controller.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile;
import me.prexorjustin.prexorcloud.controller.config.ClusterConfig;
import me.prexorjustin.prexorcloud.controller.config.ControllerConfig;
import me.prexorjustin.prexorcloud.controller.config.CorsConfig;
import me.prexorjustin.prexorcloud.controller.config.HttpConfig;
import me.prexorjustin.prexorcloud.controller.config.NetworkConfig;
import me.prexorjustin.prexorcloud.controller.config.RaftConfig;
import me.prexorjustin.prexorcloud.controller.config.RedisConfig;
import me.prexorjustin.prexorcloud.controller.config.RuntimeConfig;
import me.prexorjustin.prexorcloud.controller.config.SecurityControllerConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Day-0 with TLS material persistence: when {@link ClusterControlService#start(LocalClusterMaterials)}
 * runs against an empty materials directory it mints a CA + self leaf cert, persists them to disk
 * for the next restart, and stamps the CA into the Raft state machine so future joiners receive
 * it via snapshot. The next restart reuses the persisted materials instead of minting again.
 */
class ClusterControlServiceMaterialsTest {

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static ControllerConfig sampleConfig(Path tmp, int raftPort) {
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
                new SecurityControllerConfig(
                        "jwt-secret-1234567890123456789012345678901234567890", 720, "", null),
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
                new ClusterConfig(null, null, null),
                new RaftConfig("127.0.0.1", raftPort, tmp.resolve("raft").toString(), List.of()));
    }

    @Test
    @DisplayName("Day-0 mints + persists cluster TLS material and writes the CA into Raft state")
    void day0MintsAndPersistsTlsMaterial(@TempDir Path tmp) throws Exception {
        int port = freePort();
        ControllerConfig cfg = sampleConfig(tmp, port);
        Path materialsDir = tmp.resolve("cluster-materials");
        LocalClusterMaterials materials = new LocalClusterMaterials(materialsDir);

        String clusterId;
        byte[] caCertBytes;
        try (ClusterControlService svc = new ClusterControlService(cfg, "controller-1")) {
            svc.start(materials);
            clusterId = svc.clusterId();
            assertNotNull(clusterId);
            // CA in Raft state.
            var caFile = svc.controlPlane().getClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT).orElseThrow();
            caCertBytes = caFile.bytes();
            // Self appears in the member list (Day-0 founder).
            assertEquals(1, svc.controlPlane().listMembers().size());
            assertEquals("controller-1", svc.controlPlane().listMembers().get(0).nodeId());
        }

        assertTrue(Files.exists(materialsDir.resolve(LocalClusterMaterials.CA_CERT_FILE)));
        assertTrue(Files.exists(materialsDir.resolve(LocalClusterMaterials.LEAF_CERT_FILE)));
        assertTrue(Files.exists(materialsDir.resolve(LocalClusterMaterials.LEAF_KEY_FILE)));

        // Restart: must reuse persisted material AND the same clusterId.
        try (ClusterControlService svc = new ClusterControlService(cfg, "controller-1")) {
            svc.start(materials);
            assertEquals(clusterId, svc.clusterId(), "restart must reuse persisted clusterId");
            var caFile = svc.controlPlane().getClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT).orElseThrow();
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    caCertBytes,
                    caFile.bytes(),
                    "CA in raft state must be the same bytes after restart (we don't re-mint)");
        }

        // The on-disk leaf cert must chain to the persisted CA (sanity check on the issuance).
        LocalClusterMaterials.Loaded loaded = materials.load();
        loaded.leafCert().verify(loaded.caCert().getPublicKey());
    }
}
