package me.prexorjustin.prexorcloud.controller.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
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
 * R3 acceptance test for the cluster control plane lifecycle: stamping
 * cluster identity on first boot, surviving restart, refusing on yaml
 * mismatch, and migrating v1.0 controller.yml into the Raft-held
 * cluster_config on first v1.1 boot.
 */
class ClusterControlServiceTest {

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static ControllerConfig sampleConfigWithRaft(Path tmp, int raftPort, String yamlClusterId)
            throws Exception {
        return new ControllerConfig(
                "node-local-uuid",
                new HttpConfig("0.0.0.0", 8080, new CorsConfig(List.of("https://dashboard.example.com"))),
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
                new ClusterConfig(yamlClusterId, null, null),
                new RaftConfig("127.0.0.1", raftPort, tmp.resolve("raft").toString(), List.of()));
    }

    @Test
    @DisplayName("first boot stamps a fresh clusterId and seedSecret into the Raft state machine")
    void firstBootStampsClusterIdentity(@TempDir Path tmp) throws Exception {
        int port = freePort();
        ControllerConfig cfg = sampleConfigWithRaft(tmp, port, null);

        try (ClusterControlService svc = new ClusterControlService(
                cfg,
                "controller-1",
                Clock.fixed(Instant.parse("2026-05-29T18:00:00Z"), ZoneOffset.UTC),
                new SecureRandom(new byte[] {1, 2, 3, 4}))) {
            svc.start();

            ClusterMeta meta = svc.controlPlane().getClusterMeta().orElseThrow();
            assertNotNull(meta.clusterId(), "clusterId must be stamped on first boot");
            assertNotNull(meta.seedSecretBase64(), "seedSecret must be stamped on first boot");
            assertTrue(meta.seedSecretBase64().length() > 16, "seedSecret must be base64 of >=24 bytes");
            assertEquals(Instant.parse("2026-05-29T18:00:00Z"), meta.createdAt());
            assertEquals(ClusterMeta.CURRENT_SCHEMA_VERSION, meta.schemaVersion());
            assertEquals(meta.clusterId(), svc.clusterId());
        }
    }

    @Test
    @DisplayName("restart reads the same clusterId and seedSecret from Raft")
    void restartReadsSameIdentity(@TempDir Path tmp) throws Exception {
        int port = freePort();
        ControllerConfig cfg = sampleConfigWithRaft(tmp, port, null);

        String firstClusterId;
        String firstSeed;
        try (ClusterControlService svc = new ClusterControlService(cfg, "controller-1")) {
            svc.start();
            ClusterMeta meta = svc.controlPlane().getClusterMeta().orElseThrow();
            firstClusterId = meta.clusterId();
            firstSeed = meta.seedSecretBase64();
        }
        try (ClusterControlService svc = new ClusterControlService(cfg, "controller-1")) {
            svc.start();
            ClusterMeta meta = svc.controlPlane().getClusterMeta().orElseThrow();
            assertEquals(firstClusterId, meta.clusterId(), "restart must read the same clusterId");
            assertEquals(firstSeed, meta.seedSecretBase64(), "restart must read the same seedSecret");
        }
    }

    @Test
    @DisplayName("first boot mints a cluster CA into the Raft state, restart reuses it")
    void firstBootMintsClusterCa(@TempDir Path tmp) throws Exception {
        int port = freePort();
        ControllerConfig cfg = sampleConfigWithRaft(tmp, port, null);

        byte[] firstCert;
        byte[] firstKey;
        try (ClusterControlService svc = new ClusterControlService(cfg, "controller-1")) {
            svc.start();
            var cert = svc.controlPlane()
                    .getClusterFile(
                            me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile.KEY_CLUSTER_CA_CERT)
                    .orElseThrow(() -> new AssertionError("CA cert must be stamped on first boot"));
            var key = svc.controlPlane()
                    .getClusterFile(me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile.KEY_CLUSTER_CA_KEY)
                    .orElseThrow(() -> new AssertionError("CA key must be stamped on first boot"));
            assertTrue(cert.bytes().length > 0);
            assertTrue(key.bytes().length > 0);
            // Verify the bytes round-trip through the security helper as a real EC keypair / X.509 cert.
            var reloaded =
                    me.prexorjustin.prexorcloud.security.ca.CertificateAuthority.loadFromDer(cert.bytes(), key.bytes());
            assertNotNull(reloaded.certificate());
            assertNotNull(reloaded.keyPair().getPrivate());
            firstCert = cert.bytes();
            firstKey = key.bytes();
        }
        try (ClusterControlService svc = new ClusterControlService(cfg, "controller-1")) {
            svc.start();
            var cert = svc.controlPlane()
                    .getClusterFile(
                            me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile.KEY_CLUSTER_CA_CERT)
                    .orElseThrow();
            var key = svc.controlPlane()
                    .getClusterFile(me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile.KEY_CLUSTER_CA_KEY)
                    .orElseThrow();
            org.junit.jupiter.api.Assertions.assertArrayEquals(firstCert, cert.bytes(), "CA cert survives restart");
            org.junit.jupiter.api.Assertions.assertArrayEquals(firstKey, key.bytes(), "CA key survives restart");
        }
    }

    @Test
    @DisplayName("yaml mismatch refuses to boot")
    void yamlMismatchRefusesBoot(@TempDir Path tmp) throws Exception {
        int port = freePort();
        ControllerConfig firstCfg = sampleConfigWithRaft(tmp, port, null);

        // First boot — Raft stamps some random clusterId.
        String stampedClusterId;
        try (ClusterControlService svc = new ClusterControlService(firstCfg, "controller-1")) {
            svc.start();
            stampedClusterId = svc.clusterId();
        }

        // Second boot with a DIFFERENT yaml cluster.id — must refuse.
        ControllerConfig wrongCfg = sampleConfigWithRaft(tmp, port, "deliberately-wrong-cluster-id");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            try (ClusterControlService svc = new ClusterControlService(wrongCfg, "controller-1")) {
                svc.start();
            }
        });
        assertTrue(ex.getMessage().contains("deliberately-wrong-cluster-id"));
        assertTrue(ex.getMessage().contains(stampedClusterId), "error must show the actual Raft id");
    }

    @Test
    @DisplayName("v1.0 migration seeds cluster_config v1 from controller.yml on first boot")
    void v10MigrationSeedsClusterConfig(@TempDir Path tmp) throws Exception {
        int port = freePort();
        ControllerConfig cfg = sampleConfigWithRaft(tmp, port, null);

        try (ClusterControlService svc = new ClusterControlService(cfg, "controller-1")) {
            svc.start();

            assertEquals(
                    1,
                    svc.controlPlane().getActiveConfigVersion(),
                    "v1.0 migration must write version 1 on first boot");
            var versions = svc.controlPlane().listConfigVersions();
            assertEquals(1, versions.size());
            var v1 = versions.get(0);
            assertEquals(1, v1.version());
            assertEquals(0, v1.parentVersion());
            assertEquals("v1.0-migration", v1.mutator());
            // Spot-check that recognisable cluster-shared sections are present in the seeded patch.
            assertTrue(v1.patch().containsKey("runtime"), "migration must carry runtime profile");
            assertTrue(v1.patch().containsKey("security"), "migration must carry security block");
            assertTrue(v1.patch().containsKey("redis"), "migration must carry redis URI");
        }
    }

    @Test
    @DisplayName("v1.0 migration is idempotent — restart does not write a second version")
    void v10MigrationIdempotentOnRestart(@TempDir Path tmp) throws Exception {
        int port = freePort();
        ControllerConfig cfg = sampleConfigWithRaft(tmp, port, null);

        try (ClusterControlService svc = new ClusterControlService(cfg, "controller-1")) {
            svc.start();
            assertEquals(1, svc.controlPlane().listConfigVersions().size());
        }
        try (ClusterControlService svc = new ClusterControlService(cfg, "controller-1")) {
            svc.start();
            assertEquals(
                    1,
                    svc.controlPlane().listConfigVersions().size(),
                    "migration must not run again on restart — version 1 already present");
            assertEquals(1, svc.controlPlane().getActiveConfigVersion());
        }
    }
}
