package me.prexorjustin.prexorcloud.controller.cluster;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlPlane;
import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlStateMachine;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile;
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
import me.prexorjustin.prexorcloud.controller.grpc.ClusterMembershipServiceImpl;
import me.prexorjustin.prexorcloud.security.ca.CertificateAuthority;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end exercise of {@link ClusterControlService#startInJoinMode}: stand up a leader
 * (via the Day-0 path), issue a join token, point a second controller at the membership
 * RPC, watch it join the Raft group, then restart it from the on-disk material it just
 * persisted. Covers the three branches of {@link
 * me.prexorjustin.prexorcloud.controller.PrexorCloudBootstrap}: Day-0, join, restart.
 *
 * <p>Tagged {@code spike} — like {@code EndToEndJoinTest}, this spins up two real Ratis
 * instances and does real TLS handshakes. Run via {@code :spikeTest}.
 */
@Tag("spike")
class ClusterControlServiceJoinTest {

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static ControllerConfig sampleConfig(Path raftDir, int raftPort, String uuid) {
        return new ControllerConfig(
                uuid,
                new HttpConfig("127.0.0.1", 8443, new CorsConfig(List.of())),
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
                new ClusterConfig(null, null, null),
                new RaftConfig("127.0.0.1", raftPort, raftDir.toString(), List.of()));
    }

    @Test
    @DisplayName("joiner: startInJoinMode persists materials, converges on cluster state, restart reuses materials")
    void joinThenRestart(@TempDir Path tmp) throws Exception {
        // --- leader side: Day-0 via ClusterControlService.start(materials) ---
        int leaderRaftPort = freePort();
        Path leaderRaftDir = tmp.resolve("leader-raft");
        Path leaderMaterialsDir = tmp.resolve("leader-materials");
        LocalClusterMaterials leaderMaterials = new LocalClusterMaterials(leaderMaterialsDir);
        ControllerConfig leaderCfg = sampleConfig(leaderRaftDir, leaderRaftPort, "leader-uuid");
        ClusterControlService leader = new ClusterControlService(leaderCfg, "controller-1");

        Server inProcServer = null;
        ManagedChannel joinerChannel = null;
        ClusterControlService joiner = null;
        try {
            leader.start(leaderMaterials);
            String clusterId = leader.clusterId();
            assertNotNull(clusterId);

            // Issue an operator-side join token. The joinAddrs[] is the leader's grpc
            // address — but we'll intercept the dial via the test's JoinChannelFactory
            // and route it to the in-process server instead.
            var issued = leader.controlPlane()
                    .issueJoinToken(
                            List.of("127.0.0.1:9999"), // placeholder — interceptor below dials in-proc
                            Duration.ofHours(1),
                            "controller-2",
                            "test-operator");

            String serverName = "ccs-join-" + UUID.randomUUID();
            inProcServer = InProcessServerBuilder.forName(serverName)
                    .directExecutor()
                    .addService(new ClusterMembershipServiceImpl(leader.controlPlane()))
                    .build()
                    .start();
            final String capturedServerName = serverName;
            // Intercept the channel factory used by ClusterControlService — point it
            // at the in-process server so we don't actually open a network socket.
            ClusterControlService.JoinChannelFactory inProcFactory =
                    hostPort -> InProcessChannelBuilder.forName(capturedServerName)
                            .directExecutor()
                            .build();

            // --- joiner side: pristine state. Run startInJoinMode. ---
            int joinerRaftPort = freePort();
            Path joinerRaftDir = tmp.resolve("joiner-raft");
            Path joinerMaterialsDir = tmp.resolve("joiner-materials");
            LocalClusterMaterials joinerMaterials = new LocalClusterMaterials(joinerMaterialsDir);
            ControllerConfig joinerCfg = sampleConfig(joinerRaftDir, joinerRaftPort, "joiner-uuid");
            joiner = new ClusterControlService(joinerCfg, "controller-2");
            joiner.setJoinChannelFactory(inProcFactory);
            joiner.startInJoinMode(
                    issued.token(),
                    joinerMaterials,
                    new ClusterControlService.JoinIdentity(
                            "127.0.0.1:" + joinerRaftPort, "127.0.0.1:8444", "127.0.0.1:9091"));

            // Joiner sees the leader-stamped cluster meta — proves the SM replicated.
            awaitMeta(joiner.controlPlane(), clusterId, 30_000);
            // #22: startInJoinMode joins as a non-voting LISTENER (so the leader's deferred
            // setConfiguration can't NOPROGRESS on an unsynced joiner), then — once the leader's
            // reconciler promotes the caught-up listener into the voting set — restarts the division
            // in-process to assume the voting FOLLOWER role. By the time startInJoinMode returns the
            // joiner must be a voting member (FOLLOWER, or LEADER if the restarted node wins the
            // election in this small group), not a stuck/phantom LISTENER.
            awaitVotingMember(joiner, 15_000);
            // Joiner persisted its TLS material to disk for a future restart.
            assertTrue(Files.exists(joinerMaterialsDir.resolve(LocalClusterMaterials.CA_CERT_FILE)));
            assertTrue(Files.exists(joinerMaterialsDir.resolve(LocalClusterMaterials.LEAF_CERT_FILE)));
            assertTrue(Files.exists(joinerMaterialsDir.resolve(LocalClusterMaterials.LEAF_KEY_FILE)));
            // Leaf chains to the leader's cluster CA.
            LocalClusterMaterials.Loaded persisted = joinerMaterials.load();
            persisted.leafCert().verify(persisted.caCert().getPublicKey());
            // Cluster id mirrored into the joiner's effectiveConfig.
            assertEquals(clusterId, joiner.effectiveConfig().cluster().id());

            joiner.close();
            joiner = null;

            // --- restart joiner: must take the "materials exist" path and reuse the persisted leaf. ---
            joiner = new ClusterControlService(joinerCfg, "controller-2");
            joiner.start(joinerMaterials);
            // Restart's clusterMeta comes back via Ratis log replay (the joiner's own raft dir was
            // populated by the join). Same id as the leader.
            ClusterMeta restartMeta = joiner.controlPlane().getClusterMeta().orElseThrow();
            assertEquals(clusterId, restartMeta.clusterId());
        } finally {
            if (joiner != null) {
                joiner.close();
            }
            if (joinerChannel != null) {
                joinerChannel.shutdownNow();
            }
            if (inProcServer != null) {
                inProcServer.shutdownNow();
                inProcServer.awaitTermination(5, TimeUnit.SECONDS);
            }
            leader.close();
        }
    }

    @Test
    @DisplayName("self CA reconcile: a stale on-disk CA (single-survivor-reset split) is realigned to the Raft-state CA")
    void selfCaReconcileRealignsStaleOnDiskCa(@TempDir Path tmp) throws Exception {
        int raftPort = freePort();
        Path raftDir = tmp.resolve("raft");
        Path materialsDir = tmp.resolve("materials");
        LocalClusterMaterials materials = new LocalClusterMaterials(materialsDir);
        ControllerConfig cfg = sampleConfig(raftDir, raftPort, "survivor-uuid");

        // Day-0: mints a cluster CA that is consistent on disk and in Raft state.
        String clusterId;
        byte[] raftCaDer;
        ClusterControlService svc = new ClusterControlService(cfg, "controller-1");
        try {
            svc.start(materials);
            clusterId = svc.clusterId();
            raftCaDer = svc.controlPlane()
                    .getClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT)
                    .orElseThrow()
                    .bytes();
        } finally {
            svc.close();
        }
        assertArrayEquals(
                raftCaDer, materials.load().caCert().getEncoded(), "precondition: on-disk CA matches Raft-state CA");

        // Simulate the single-survivor-reset split: the Raft-state CA was regenerated but this
        // controller's on-disk leaf+trust was left on an OLDER CA. Overwrite the on-disk material
        // with a different, unrelated CA and a leaf signed by it.
        CertificateAuthority rogue = CertificateAuthority.createInMemory("PrexorCloud Cluster CA", 3650);
        var rogueLeaf = rogue.issueClusterPeerCertificate(
                "controller-1", List.of("controller-1", "127.0.0.1", "localhost"), 365);
        materials.persist(
                rogue.certificate(), rogueLeaf.certificate(), rogueLeaf.keyPair().getPrivate());
        assertFalse(
                java.util.Arrays.equals(raftCaDer, materials.load().caCert().getEncoded()),
                "precondition: on-disk CA now differs from the Raft-state CA");

        // Restart: reconcileSelfClusterTls must detect the split and realign the on-disk material to
        // the authoritative Raft-state CA, preserving the clusterId.
        ClusterControlService restarted = new ClusterControlService(cfg, "controller-1");
        try {
            restarted.start(materials);
            assertEquals(clusterId, restarted.clusterId(), "clusterId must be preserved across the realign");
            assertArrayEquals(
                    raftCaDer,
                    materials.load().caCert().getEncoded(),
                    "on-disk CA must be realigned to the Raft-state CA");
            // The re-issued leaf must chain to the realigned CA.
            materials.load().leafCert().verify(materials.load().caCert().getPublicKey());
        } finally {
            restarted.close();
        }
    }

    private static void awaitVotingMember(ClusterControlService svc, long timeoutMs) throws TimeoutException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        org.apache.ratis.proto.RaftProtos.RaftPeerRole last = null;
        while (System.nanoTime() < deadline) {
            try {
                last = svc.raftRole();
                if (last == org.apache.ratis.proto.RaftProtos.RaftPeerRole.FOLLOWER
                        || last == org.apache.ratis.proto.RaftProtos.RaftPeerRole.LEADER) {
                    return;
                }
            } catch (Exception ignored) {
                // server still settling — retry until deadline
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            }
        }
        throw new TimeoutException("joiner did not become a voting member within " + timeoutMs + "ms (last=" + last
                + ") — #22: stuck as a listener");
    }

    private static void awaitMeta(ClusterControlPlane plane, String expectedClusterId, long timeoutMs)
            throws TimeoutException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            var meta = plane.getClusterMeta();
            if (meta.isPresent() && expectedClusterId.equals(meta.get().clusterId())) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            }
        }
        throw new TimeoutException("joiner SM did not see meta " + expectedClusterId + " within " + timeoutMs + "ms");
    }

    /** Silence unused-warning for the field — referenced only as a generic placeholder above. */
    @SuppressWarnings("unused")
    private static ClusterControlStateMachine stateMachineHolder;
}
