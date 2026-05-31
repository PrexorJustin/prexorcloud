package me.prexorjustin.prexorcloud.controller.cluster;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import me.prexorjustin.prexorcloud.protocol.ClusterMembershipGrpc;
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
        return NettyChannelBuilder.forTarget(hostPort).sslContext(sslContext).build();
    }

    /** Identity the joiner advertises to the cluster as part of {@code RequestJoin}. */
    public record JoinIdentity(String nodeId, String raftAddr, String restAddr, String grpcAddr) {}

    /**
     * Materials returned to the joiner after a successful RequestJoin: the
     * joiner's signed leaf cert + the matching private key + the cluster CA
     * cert + the cluster identifier. The private key never leaves this JVM.
     */
    public record JoinResult(
            X509Certificate signedCert, PrivateKey privateKey, X509Certificate caCert, String clusterId) {}

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
        X509Certificate signed = (X509Certificate)
                cf.generateCertificate(new java.io.ByteArrayInputStream(resp.getSignedCertDer().toByteArray()));
        X509Certificate ca = (X509Certificate)
                cf.generateCertificate(new java.io.ByteArrayInputStream(resp.getCaCertDer().toByteArray()));

        logger.info(
                "Joined cluster {} as {} (cert serial={})",
                resp.getClusterId(),
                identity.nodeId(),
                signed.getSerialNumber());
        return new JoinResult(signed, keyPair.getPrivate(), ca, resp.getClusterId());
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
