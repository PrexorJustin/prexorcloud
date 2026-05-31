package me.prexorjustin.prexorcloud.controller.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import me.prexorjustin.prexorcloud.controller.cluster.JoinTokenCodec;
import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlPlane;
import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlStateMachine;
import me.prexorjustin.prexorcloud.controller.cluster.raft.RaftBootstrap;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.config.RaftConfig;
import me.prexorjustin.prexorcloud.protocol.RequestJoinRequest;
import me.prexorjustin.prexorcloud.protocol.RequestJoinResponse;
import me.prexorjustin.prexorcloud.security.ca.CertificateAuthority;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClusterMembershipServiceImplTest {

    private static final String CLUSTER_ID = "cluster-membership-test";
    private static final String SEED_B64 = "VGhpcyBpcyBhIDMyLWJ5dGUgZmFrZSBzZWVkLi4uLi4u";

    static {
        if (java.security.Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            java.security.Security.addProvider(new BouncyCastleProvider());
        }
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

    private static byte[] generateCsr(KeyPair kp, String cn) throws Exception {
        var builder = new JcaPKCS10CertificationRequestBuilder(new X500Name("CN=" + cn), kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(kp.getPrivate());
        PKCS10CertificationRequest csr = builder.build(signer);
        return csr.getEncoded();
    }

    private static KeyPair generateEcKeyPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        g.initialize(256, new SecureRandom());
        return g.generateKeyPair();
    }

    /** Spin up a single-node Ratis + plane, seed it with a cluster CA + meta, return the plane. */
    private record Harness(RaftBootstrap raft, ClusterControlPlane plane, CertificateAuthority ca)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            raft.close();
        }
    }

    private static Harness bootHarness(Path tmp) throws Exception {
        int port = freePort();
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-00000000c001");
        ClusterControlStateMachine sm = new ClusterControlStateMachine();
        RaftBootstrap raft = newBootstrap(tmp, sm, port, groupId);
        raft.start();
        raft.awaitLeader(10_000);
        ClusterControlPlane plane = new ClusterControlPlane(raft, sm);
        plane.setClusterMeta(new ClusterMeta(
                CLUSTER_ID, SEED_B64, Instant.parse("2026-05-29T12:00:00Z"), ClusterMeta.CURRENT_SCHEMA_VERSION));
        CertificateAuthority ca = CertificateAuthority.createInMemory("PrexorCloud Cluster CA", 365);
        plane.writeClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT, ca.certificate().getEncoded());
        plane.writeClusterFile(
                ClusterFile.KEY_CLUSTER_CA_KEY, ca.keyPair().getPrivate().getEncoded());
        return new Harness(raft, plane, ca);
    }

    private static me.prexorjustin.prexorcloud.controller.cluster.state.JoinToken writeToken(
            ClusterControlPlane plane, String jti, Instant expiresAt) throws Exception {
        var token = new me.prexorjustin.prexorcloud.controller.cluster.state.JoinToken(
                jti,
                "hmac-not-used-by-the-state-machine",
                null,
                "alice",
                Instant.parse("2026-05-29T12:01:00Z"),
                expiresAt,
                null,
                null,
                null,
                false,
                null,
                null);
        plane.writeJoinToken(token);
        return token;
    }

    private static RequestJoinRequest joinRequest(String token, byte[] csrDer, String nodeId) {
        return RequestJoinRequest.newBuilder()
                .setToken(token)
                .setNodeId(nodeId)
                .setRaftAddr("controller-2.cluster.test:9091")
                .setRestAddr("controller-2.cluster.test:8443")
                .setGrpcAddr("controller-2.cluster.test:9090")
                .setCsrDer(ByteString.copyFrom(csrDer))
                .build();
    }

    /** Capturing observer that fans the success or failure into atomics for the test to inspect. */
    private static final class CapturingObserver implements StreamObserver<RequestJoinResponse> {
        final AtomicReference<RequestJoinResponse> success = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        @Override
        public void onNext(RequestJoinResponse value) {
            success.set(value);
        }

        @Override
        public void onError(Throwable t) {
            failure.set(t);
        }

        @Override
        public void onCompleted() {}
    }

    @Test
    @DisplayName("happy path: valid token + CSR returns a CA-signed leaf and pins the new member")
    void happyPath(@TempDir Path tmp) throws Exception {
        try (Harness h = bootHarness(tmp)) {
            Instant now = Instant.parse("2026-05-29T12:30:00Z");
            var issued = JoinTokenCodec.encode(
                    CLUSTER_ID,
                    List.of("controller-1.cluster.test:9091"),
                    now.plusSeconds(3600),
                    JoinTokenCodec.decodeSeed(SEED_B64));
            writeToken(h.plane, issued.jti(), now.plusSeconds(3600));

            KeyPair joinerKp = generateEcKeyPair();
            byte[] csr = generateCsr(joinerKp, "controller-2");

            var svc = new ClusterMembershipServiceImpl(h.plane, Clock.fixed(now, ZoneOffset.UTC));
            CapturingObserver obs = new CapturingObserver();
            svc.requestJoin(joinRequest(issued.token(), csr, "controller-2"), obs);

            assertNull(obs.failure.get(), () -> "expected success but got: " + obs.failure.get());
            RequestJoinResponse response = obs.success.get();
            assertNotNull(response);
            assertEquals(CLUSTER_ID, response.getClusterId());

            // Returned cert chains to the cluster CA.
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate leaf = (X509Certificate) cf.generateCertificate(
                    new java.io.ByteArrayInputStream(response.getSignedCertDer().toByteArray()));
            assertNotNull(leaf);
            leaf.verify(h.ca.certificate().getPublicKey());
            assertEquals(
                    joinerKp.getPublic(), leaf.getPublicKey(), "leaf must use the joiner's public key from the CSR");

            // Returned CA cert is the cluster CA bytes that were stamped earlier.
            assertEquals(ByteString.copyFrom(h.ca.certificate().getEncoded()), response.getCaCertDer());

            // Token is marked redeemed.
            var redeemed = h.plane.getJoinToken(issued.jti()).orElseThrow();
            assertNotNull(redeemed.redeemedAt());
            assertEquals("controller-2", redeemed.redeemedAs());

            // Member is in the cluster's member projection.
            var members = h.plane.listMembers();
            assertEquals(1, members.size());
            assertEquals("controller-2", members.get(0).nodeId());
            assertEquals("controller-2.cluster.test:9091", members.get(0).raftAddr());
        }
    }

    @Test
    @DisplayName("HMAC tampering is rejected with UNAUTHENTICATED")
    void hmacMismatchRejected(@TempDir Path tmp) throws Exception {
        try (Harness h = bootHarness(tmp)) {
            Instant now = Instant.parse("2026-05-29T12:30:00Z");
            byte[] wrongSeed = "wrong-seed-for-this-cluster".getBytes();
            var issued = JoinTokenCodec.encode(
                    CLUSTER_ID, List.of("controller-1.cluster.test:9091"), now.plusSeconds(3600), wrongSeed);

            KeyPair joinerKp = generateEcKeyPair();
            byte[] csr = generateCsr(joinerKp, "controller-2");

            var svc = new ClusterMembershipServiceImpl(h.plane, Clock.fixed(now, ZoneOffset.UTC));
            CapturingObserver obs = new CapturingObserver();
            svc.requestJoin(joinRequest(issued.token(), csr, "controller-2"), obs);

            assertNotNull(obs.failure.get());
            assertEquals(
                    Status.UNAUTHENTICATED.getCode(),
                    Status.fromThrowable(obs.failure.get()).getCode());
            // No member was added.
            assertTrue(h.plane.listMembers().isEmpty());
        }
    }

    @Test
    @DisplayName("expired token is rejected with UNAUTHENTICATED")
    void expiredTokenRejected(@TempDir Path tmp) throws Exception {
        try (Harness h = bootHarness(tmp)) {
            Instant now = Instant.parse("2026-05-29T12:30:00Z");
            var issued = JoinTokenCodec.encode(
                    CLUSTER_ID,
                    List.of("controller-1.cluster.test:9091"),
                    now.minusSeconds(60), // already expired
                    JoinTokenCodec.decodeSeed(SEED_B64));
            writeToken(h.plane, issued.jti(), now.minusSeconds(60));

            KeyPair joinerKp = generateEcKeyPair();
            byte[] csr = generateCsr(joinerKp, "controller-2");

            var svc = new ClusterMembershipServiceImpl(h.plane, Clock.fixed(now, ZoneOffset.UTC));
            CapturingObserver obs = new CapturingObserver();
            svc.requestJoin(joinRequest(issued.token(), csr, "controller-2"), obs);

            assertNotNull(obs.failure.get());
            StatusRuntimeException ex = (StatusRuntimeException) obs.failure.get();
            assertEquals(Status.UNAUTHENTICATED.getCode(), ex.getStatus().getCode());
            assertTrue(ex.getStatus().getDescription().contains("expired"));
        }
    }

    @Test
    @DisplayName("token for a different cluster is rejected with UNAUTHENTICATED")
    void wrongClusterRejected(@TempDir Path tmp) throws Exception {
        try (Harness h = bootHarness(tmp)) {
            Instant now = Instant.parse("2026-05-29T12:30:00Z");
            var issued = JoinTokenCodec.encode(
                    "some-other-cluster",
                    List.of("controller-1.cluster.test:9091"),
                    now.plusSeconds(3600),
                    JoinTokenCodec.decodeSeed(SEED_B64));

            KeyPair joinerKp = generateEcKeyPair();
            byte[] csr = generateCsr(joinerKp, "controller-2");

            var svc = new ClusterMembershipServiceImpl(h.plane, Clock.fixed(now, ZoneOffset.UTC));
            CapturingObserver obs = new CapturingObserver();
            svc.requestJoin(joinRequest(issued.token(), csr, "controller-2"), obs);

            assertNotNull(obs.failure.get());
            assertEquals(
                    Status.UNAUTHENTICATED.getCode(),
                    Status.fromThrowable(obs.failure.get()).getCode());
        }
    }

    @Test
    @DisplayName("replayed redemption is rejected with FAILED_PRECONDITION")
    void replayedTokenRejected(@TempDir Path tmp) throws Exception {
        try (Harness h = bootHarness(tmp)) {
            Instant now = Instant.parse("2026-05-29T12:30:00Z");
            var issued = JoinTokenCodec.encode(
                    CLUSTER_ID,
                    List.of("controller-1.cluster.test:9091"),
                    now.plusSeconds(3600),
                    JoinTokenCodec.decodeSeed(SEED_B64));
            writeToken(h.plane, issued.jti(), now.plusSeconds(3600));

            KeyPair joinerKp = generateEcKeyPair();
            byte[] csr = generateCsr(joinerKp, "controller-2");

            var svc = new ClusterMembershipServiceImpl(h.plane, Clock.fixed(now, ZoneOffset.UTC));

            CapturingObserver first = new CapturingObserver();
            svc.requestJoin(joinRequest(issued.token(), csr, "controller-2"), first);
            assertNull(first.failure.get());

            // Second redemption with the same token must be rejected even if everything else is
            // identical — the state machine's RedeemJoinToken apply rejects on TOKEN_ALREADY_REDEEMED.
            CapturingObserver second = new CapturingObserver();
            svc.requestJoin(joinRequest(issued.token(), csr, "controller-2"), second);
            assertNotNull(second.failure.get());
            assertEquals(
                    Status.FAILED_PRECONDITION.getCode(),
                    Status.fromThrowable(second.failure.get()).getCode());
        }
    }
}
