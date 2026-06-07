package me.prexorjustin.prexorcloud.controller.cluster.raft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import me.prexorjustin.prexorcloud.controller.cluster.ClusterJoinFlow;
import me.prexorjustin.prexorcloud.controller.cluster.JoinTokenCodec;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.cluster.state.JoinToken;
import me.prexorjustin.prexorcloud.controller.config.RaftConfig;
import me.prexorjustin.prexorcloud.controller.grpc.ClusterMembershipServiceImpl;
import me.prexorjustin.prexorcloud.protocol.ClusterMembershipGrpc;
import me.prexorjustin.prexorcloud.security.ca.CertificateAuthority;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.apache.ratis.grpc.GrpcTlsConfig;
import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftPeer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase-4 closer: a fresh controller joins an established single-node cluster
 * end-to-end. Exercises every piece we've built — Day-0 CA generation, the
 * gRPC membership handshake, {@link ClusterJoinFlow}'s joiner-side CSR, the
 * TLS-enabled join-mode Ratis bring-up, and the {@link MembershipReconciler}
 * driving the leader's setConfiguration. If this test passes the
 * cluster-control-plane plumbing actually works end-to-end.
 *
 * <p>Tagged {@code spike} so the regular test suite skips it — it spins up
 * three Ratis instances (leader + joiner + the in-process gRPC service hosting
 * one of them), with real TLS handshakes, snapshot install, and joint
 * consensus. Run via {@code ./gradlew :cloud-controller:spikeTest}.
 */
@Tag("spike")
class EndToEndJoinTest {

    private static final UUID GROUP_ID = UUID.fromString("00000000-0000-0000-0000-00000000c003");
    private static final String CLUSTER_ID = "cluster-end-to-end-join";
    private static final String SEED_B64 = "VGhpcyBpcyBhIDMyLWJ5dGUgZmFrZSBzZWVkLi4uLi4u";

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    @DisplayName("controller-2 joins controller-1 end-to-end and converges on the cluster state")
    void joinAndConverge(@TempDir Path tmp) throws Exception {
        // === leader side (controller-1): existing single-node cluster ===
        int leaderPort = freePort();
        ClusterControlStateMachine leaderSm = new ClusterControlStateMachine();
        RaftConfig leaderCfg =
                new RaftConfig("127.0.0.1", leaderPort, tmp.resolve("p1").toString(), List.of());
        RaftBootstrap leaderRaft = new RaftBootstrap(leaderCfg, GROUP_ID, "controller-1", leaderSm);

        // Generate the cluster CA + signed leader cert FIRST so we can start the leader with TLS.
        CertificateAuthority ca = CertificateAuthority.createInMemory("PrexorCloud Cluster CA", 365);
        var leaderLeaf = ca.issueClusterPeerCertificate("controller-1", List.of("127.0.0.1", "localhost"), 365);
        GrpcTlsConfig leaderTls = new GrpcTlsConfig(
                leaderLeaf.keyPair().getPrivate(), leaderLeaf.certificate(), List.of(ca.certificate()), true);

        leaderRaft.start(leaderTls);
        leaderRaft.awaitLeader(15_000);
        ClusterControlPlane leaderPlane = new ClusterControlPlane(leaderRaft, leaderSm);

        // Stamp meta, store CA in raft state, add controller-1 to the SM (mirrors what
        // ClusterControlService.reconcileClusterIdentity + ensureClusterCa do at boot).
        leaderPlane.setClusterMeta(
                new ClusterMeta(CLUSTER_ID, SEED_B64, Instant.now(), ClusterMeta.CURRENT_SCHEMA_VERSION));
        leaderPlane.writeClusterFile(
                ClusterFile.KEY_CLUSTER_CA_CERT, ca.certificate().getEncoded());
        leaderPlane.writeClusterFile(
                ClusterFile.KEY_CLUSTER_CA_KEY, ca.keyPair().getPrivate().getEncoded());
        leaderPlane.addMember(new me.prexorjustin.prexorcloud.controller.cluster.state.Member(
                "controller-1",
                "127.0.0.1:" + leaderPort,
                "127.0.0.1:8443",
                "127.0.0.1:9090",
                "leader",
                Instant.now(),
                Instant.now()));

        // Start the membership reconciler — observes AddMember commits and drives Ratis-level
        // setConfiguration on the leader.
        MembershipReconciler reconciler = new MembershipReconciler(leaderRaft, leaderSm);
        reconciler.start();

        // In-process gRPC server hosts the ClusterMembership service backed by the leader's plane.
        String serverName = "e2e-join-" + UUID.randomUUID();
        Server inProcServer = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new ClusterMembershipServiceImpl(leaderPlane))
                .build()
                .start();
        ManagedChannel joinChannel =
                InProcessChannelBuilder.forName(serverName).directExecutor().build();

        // === joiner side (controller-2): nothing yet on disk, no certs, no raft state ===
        int joinerPort = freePort();
        ClusterControlStateMachine joinerSm = new ClusterControlStateMachine();
        RaftConfig joinerCfg =
                new RaftConfig("127.0.0.1", joinerPort, tmp.resolve("p2").toString(), List.of());
        RaftBootstrap joinerRaft = new RaftBootstrap(joinerCfg, GROUP_ID, "controller-2", joinerSm);

        try {
            // Operator-issued token, written into the leader's raft state.
            var issued = JoinTokenCodec.encode(
                    CLUSTER_ID,
                    List.of("127.0.0.1:" + leaderPort),
                    Instant.now().plusSeconds(3600),
                    JoinTokenCodec.decodeSeed(SEED_B64));
            leaderPlane.writeJoinToken(new JoinToken(
                    issued.jti(),
                    "hmac-not-used-by-state-machine",
                    "controller-2",
                    "alice",
                    Instant.now(),
                    Instant.now().plusSeconds(3600),
                    null,
                    null,
                    null,
                    false,
                    null,
                    null));

            // --- Run the joiner flow: CSR → server signs → joiner has its cluster cert. ---
            ClusterJoinFlow flow = new ClusterJoinFlow(ClusterMembershipGrpc.newBlockingStub(joinChannel));
            ClusterJoinFlow.JoinResult result = flow.join(
                    issued.token(),
                    new ClusterJoinFlow.JoinIdentity(
                            "controller-2", "127.0.0.1:" + joinerPort, "127.0.0.1:8444", "127.0.0.1:9091"));
            assertEquals(CLUSTER_ID, result.clusterId());

            // --- Joiner builds its TLS Parameters from the signed materials and brings up
            // a join-mode Ratis server, then calls add() on itself. ---
            GrpcTlsConfig joinerTls =
                    new GrpcTlsConfig(result.privateKey(), result.signedCert(), List.of(result.caCert()), true);
            // The known group at join time: the leader plus this peer (which leader will commit
            // via setConfiguration when the reconciler reacts to AddMember).
            RaftGroup knownGroup = RaftGroup.valueOf(
                    org.apache.ratis.protocol.RaftGroupId.valueOf(GROUP_ID),
                    List.of(
                            leaderRaft.selfPeer(),
                            RaftPeer.newBuilder()
                                    .setId("controller-2")
                                    .setAddress("127.0.0.1:" + joinerPort)
                                    .build()));
            joinerRaft.startInJoinMode(joinerTls, knownGroup);
            joinerRaft.joinExistingGroup(knownGroup);

            // --- Wait for the joiner's local SM to see the meta the leader wrote. This goes via
            // InstallSnapshot (since AddMember was committed before joiner came up). The reconciler
            // is what unsticks the membership change so log replication can flow. ---
            awaitMetaApplied(joinerSm, CLUSTER_ID, 30_000);

            // And the cluster files (CA cert + key) replicated via the same path.
            ClusterControlPlane joinerPlane = new ClusterControlPlane(joinerRaft, joinerSm);
            assertTrue(
                    joinerPlane.getClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT).isPresent());
            assertTrue(
                    joinerPlane.getClusterFile(ClusterFile.KEY_CLUSTER_CA_KEY).isPresent());

            // Joiner's view of the member list converges on what the leader recorded.
            assertEquals(2, joinerPlane.listMembers().size());

            // Cert chains to the cluster CA stored in the leader's raft state.
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate joinerCertReloaded = (X509Certificate) cf.generateCertificate(
                    new java.io.ByteArrayInputStream(result.signedCert().getEncoded()));
            joinerCertReloaded.verify(ca.certificate().getPublicKey());
        } finally {
            joinChannel.shutdownNow();
            inProcServer.shutdownNow();
            reconciler.close();
            try {
                joinerRaft.close();
            } catch (Exception ignored) {
                // best-effort cleanup
            }
            leaderRaft.close();
        }
    }

    private static void awaitMetaApplied(ClusterControlStateMachine sm, String expectedClusterId, long timeoutMs)
            throws TimeoutException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            var meta = sm.getClusterMeta();
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
        throw new TimeoutException("joiner did not see meta " + expectedClusterId + " within " + timeoutMs + "ms");
    }
}
