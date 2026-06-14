package me.prexorjustin.prexorcloud.controller.cluster;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;

import me.prexorjustin.prexorcloud.protocol.ClusterMembershipGrpc;
import me.prexorjustin.prexorcloud.protocol.KnownPeer;
import me.prexorjustin.prexorcloud.protocol.RequestJoinRequest;
import me.prexorjustin.prexorcloud.protocol.RequestJoinResponse;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Joiner-side of the cluster control-plane handshake. Generates an ephemeral
 * EC keypair, builds a PKCS#10 CSR, posts it via {@code
 * ClusterMembership.RequestJoin} on an existing cluster peer, and returns the
 * signed leaf + CA cert + clusterId.
 *
 * <p>The bootstrap call to the existing cluster goes over an <em>insecure</em>
 * channel (server cert not verified, no client cert presented). That's safe
 * because the join token's HMAC is the authentication — without the seed
 * secret, no MITM can forge a valid token. Once we have the CA cert from this
 * call, all subsequent cluster traffic uses cluster-CA-pinned mTLS.
 *
 * <p>See {@code docs/engineering/cluster-join-plan.md} ("TLS bootstrap for
 * Raft traffic") for the rationale.
 */
public final class ClusterJoinFlow {

    private static final Logger logger = LoggerFactory.getLogger(ClusterJoinFlow.class);

    static {
        if (java.security.Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            java.security.Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final ClusterMembershipGrpc.ClusterMembershipBlockingStub stub;

    public ClusterJoinFlow(ClusterMembershipGrpc.ClusterMembershipBlockingStub stub) {
        this.stub = stub;
    }

    /**
     * Convenience: build a JoinFlow over a Netty channel pointed at {@code
     * hostPort}. Server cert verification is disabled per the class doc.
     * Caller owns the channel and must shut it down after the join completes.
     */
    public static ManagedChannel insecureChannelTo(String hostPort) throws Exception {
        var sslContext = GrpcSslContexts.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();
        // Parse host:port and use the forAddress(SocketAddress) overload — it bypasses gRPC name
        // resolution entirely. forTarget("host:port") and forAddress(String,int) both consult the
        // NameResolver registry, which in the shaded jar has only the unix-domain-socket provider
        // (the DNS resolver's META-INF/services is dropped by shading), so they mis-read
        // "10.0.0.3:9190" as a unix socket path. Same fix as the daemon→controller channel.
        int idx = hostPort.lastIndexOf(':');
        if (idx <= 0 || idx == hostPort.length() - 1) {
            throw new IllegalArgumentException("Expected host:port, got: " + hostPort);
        }
        String host = hostPort.substring(0, idx);
        int port = Integer.parseInt(hostPort.substring(idx + 1));
        return NettyChannelBuilder.forAddress(new java.net.InetSocketAddress(host, port))
                .sslContext(sslContext)
                .build();
    }

    /** Identity the joiner advertises to the cluster as part of {@code RequestJoin}. */
    public record JoinIdentity(String nodeId, String raftAddr, String restAddr, String grpcAddr) {}

    /**
     * Materials returned to the joiner after a successful RequestJoin: the
     * joiner's signed leaf cert + the matching private key + the cluster CA
     * cert + the cluster identifier, plus a snapshot of the existing Raft
     * peer set so the joiner can build its initial known {@code RaftGroup}.
     * The private key never leaves this JVM.
     */
    public record JoinResult(
            X509Certificate signedCert,
            PrivateKey privateKey,
            X509Certificate caCert,
            String clusterId,
            List<KnownRaftPeer> existingPeers) {}

    /** Identifier + Raft transport address of a controller already in the cluster. */
    public record KnownRaftPeer(String nodeId, String raftAddr) {}

    public JoinResult join(String token, JoinIdentity identity) throws Exception {
        KeyPair keyPair = generateEcKeyPair();
        byte[] csrDer = buildCsr(keyPair, identity.nodeId());

        RequestJoinRequest req = RequestJoinRequest.newBuilder()
                .setToken(token)
                .setNodeId(identity.nodeId())
                .setRaftAddr(identity.raftAddr())
                .setRestAddr(identity.restAddr())
                .setGrpcAddr(identity.grpcAddr())
                .setCsrDer(ByteString.copyFrom(csrDer))
                .build();

        RequestJoinResponse resp = stub.requestJoin(req);

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate signed = (X509Certificate) cf.generateCertificate(
                new java.io.ByteArrayInputStream(resp.getSignedCertDer().toByteArray()));
        X509Certificate ca = (X509Certificate) cf.generateCertificate(
                new java.io.ByteArrayInputStream(resp.getCaCertDer().toByteArray()));

        List<KnownRaftPeer> peers = resp.getCurrentPeersList().stream()
                .map(ClusterJoinFlow::toKnownPeer)
                .toList();

        logger.info(
                "Joined cluster {} as {} (cert serial={}, peers={})",
                resp.getClusterId(),
                identity.nodeId(),
                signed.getSerialNumber(),
                peers.size());
        return new JoinResult(signed, keyPair.getPrivate(), ca, resp.getClusterId(), peers);
    }

    private static KnownRaftPeer toKnownPeer(KnownPeer p) {
        return new KnownRaftPeer(p.getNodeId(), p.getRaftAddr());
    }

    private static KeyPair generateEcKeyPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        g.initialize(256, new SecureRandom());
        return g.generateKeyPair();
    }

    private static byte[] buildCsr(KeyPair kp, String commonName) throws Exception {
        var builder = new JcaPKCS10CertificationRequestBuilder(new X500Name("CN=" + commonName), kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(kp.getPrivate());
        PKCS10CertificationRequest csr = builder.build(signer);
        return csr.getEncoded();
    }
}
