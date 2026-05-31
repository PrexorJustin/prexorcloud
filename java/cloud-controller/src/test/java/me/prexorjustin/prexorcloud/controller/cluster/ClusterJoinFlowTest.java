package me.prexorjustin.prexorcloud.controller.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlPlane;
import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlStateMachine;
import me.prexorjustin.prexorcloud.controller.cluster.raft.RaftBootstrap;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.config.RaftConfig;
import me.prexorjustin.prexorcloud.controller.grpc.ClusterMembershipServiceImpl;
import me.prexorjustin.prexorcloud.protocol.ClusterMembershipGrpc;
import me.prexorjustin.prexorcloud.security.ca.CertificateAuthority;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClusterJoinFlowTest {

    private static final String CLUSTER_ID = "cluster-joinflow-test";
    private static final String SEED_B64 = "VGhpcyBpcyBhIDMyLWJ5dGUgZmFrZSBzZWVkLi4uLi4u";

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private record Harness(
            RaftBootstrap raft,
            ClusterControlPlane plane,
            CertificateAuthority ca,
            Server inProcessServer,
            ManagedChannel inProcessChannel,
            ClusterJoinFlow flow)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            inProcessChannel.shutdownNow();
            inProcessServer.shutdownNow();
            raft.close();
        }
    }

    private static Harness boot(Path tmp) throws Exception {
        int port = freePort();
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-00000000c002");
        ClusterControlStateMachine sm = new ClusterControlStateMachine();
        RaftConfig cfg = new RaftConfig("127.0.0.1", port, tmp.resolve("raft").toString(), List.of());
        RaftBootstrap raft = new RaftBootstrap(cfg, groupId, "controller-1", sm);
        raft.start();
        raft.awaitLeader(10_000);
        ClusterControlPlane plane = new ClusterControlPlane(raft, sm);

        plane.setClusterMeta(new ClusterMeta(
                CLUSTER_ID, SEED_B64, Instant.parse("2026-05-29T12:00:00Z"), ClusterMeta.CURRENT_SCHEMA_VERSION));
        CertificateAuthority ca = CertificateAuthority.createInMemory("PrexorCloud Cluster CA", 365);
        plane.writeClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT, ca.certificate().getEncoded());
        plane.writeClusterFile(ClusterFile.KEY_CLUSTER_CA_KEY, ca.keyPair().getPrivate().getEncoded());

        String serverName = "join-flow-test-" + UUID.randomUUID();
        Server inProc = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new ClusterMembershipServiceImpl(plane))
                .build()
                .start();
        ManagedChannel channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();
        ClusterMembershipGrpc.ClusterMembershipBlockingStub stub = ClusterMembershipGrpc.newBlockingStub(channel);
        return new Harness(raft, plane, ca, inProc, channel, new ClusterJoinFlow(stub));
    }

    private static String stampValidToken(Harness h, Instant expiresAt) throws Exception {
        var issued = JoinTokenCodec.encode(
                CLUSTER_ID,
                List.of("controller-1.cluster.test:9091"),
                expiresAt,
                JoinTokenCodec.decodeSeed(SEED_B64));
        h.plane.writeJoinToken(new me.prexorjustin.prexorcloud.controller.cluster.state.JoinToken(
                issued.jti(),
                "hmac-not-used-by-state-machine",
                null,
                "alice",
                Instant.parse("2026-05-29T12:01:00Z"),
                expiresAt,
                null,
                null,
                null,
                false,
                null,
                null));
        return issued.token();
    }

    @Test
    @DisplayName("end-to-end: joiner generates CSR, dials peer, receives CA-signed leaf")
    void endToEndJoin(@TempDir Path tmp) throws Exception {
        try (Harness h = boot(tmp)) {
            String token = stampValidToken(h, Instant.now().plusSeconds(3600));

            var result = h.flow.join(
                    token,
                    new ClusterJoinFlow.JoinIdentity(
                            "controller-2",
                            "controller-2.cluster.test:9091",
                            "controller-2.cluster.test:8443",
                            "controller-2.cluster.test:9090"));

            assertEquals(CLUSTER_ID, result.clusterId());
            assertNotNull(result.signedCert());
            assertNotNull(result.privateKey());
            assertNotNull(result.caCert());
            // The signed leaf chains to the cluster CA the server holds.
            result.signedCert().verify(h.ca.certificate().getPublicKey());
            // The returned CA cert is identical to the one the server holds.
            assertEquals(h.ca.certificate(), result.caCert());

            // Server-side membership state reflects the join.
            assertEquals(1, h.plane.listMembers().size());
            assertEquals("controller-2", h.plane.listMembers().get(0).nodeId());
        }
    }

    @Test
    @DisplayName("HMAC-mismatch token surfaces as a StatusRuntimeException")
    void invalidTokenSurfacesAsStatusException(@TempDir Path tmp) throws Exception {
        try (Harness h = boot(tmp)) {
            byte[] wrongSeed = "wrong-seed".getBytes();
            var bad = JoinTokenCodec.encode(
                    CLUSTER_ID, List.of("controller-1.cluster.test:9091"),
                    Instant.now().plusSeconds(3600), wrongSeed);

            StatusRuntimeException ex = assertThrows(
                    StatusRuntimeException.class,
                    () -> h.flow.join(
                            bad.token(),
                            new ClusterJoinFlow.JoinIdentity(
                                    "controller-2",
                                    "controller-2.cluster.test:9091",
                                    "controller-2.cluster.test:8443",
                                    "controller-2.cluster.test:9090")));
            assertTrue(ex.getStatus().getCode().toString().contains("UNAUTHENTICATED"));
            assertTrue(h.plane.listMembers().isEmpty());
        }
    }
}
