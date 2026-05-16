package me.prexorjustin.prexorcloud.controller.grpc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.function.Consumer;

import me.prexorjustin.prexorcloud.controller.auth.Role;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.protocol.*;
import me.prexorjustin.prexorcloud.security.ca.CertificateAuthority;
import me.prexorjustin.prexorcloud.security.jwt.JwtManager;
import me.prexorjustin.prexorcloud.security.token.JoinTokenStore;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BootstrapServiceImpl extends BootstrapServiceGrpc.BootstrapServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(BootstrapServiceImpl.class);

    private final JoinTokenStore tokenStore;
    private final CertificateAuthority ca;
    private final Path caPemPath;
    private final int nodeCertValidityDays;
    private final StateStore stateStore;
    private final JwtManager jwtManager;
    /**
     * Optional callback fired after a successful exchange with the source IP
     * as a CIDR ({@code /32} or {@code /128}). Wired by the bootstrap layer to
     * add it to {@code AllowedSubnetsList} + persist to {@code controller.yml}
     * so subsequent mTLS connections from the daemon don't get blocked by the
     * subnet guard. Null means "feature disabled" — used by older constructors
     * and tests.
     */
    private final Consumer<String> hostSubnetRegister;

    public BootstrapServiceImpl(
            JoinTokenStore tokenStore,
            CertificateAuthority ca,
            Path caPemPath,
            int nodeCertValidityDays,
            StateStore stateStore) {
        this(tokenStore, ca, caPemPath, nodeCertValidityDays, stateStore, null, null);
    }

    public BootstrapServiceImpl(
            JoinTokenStore tokenStore,
            CertificateAuthority ca,
            Path caPemPath,
            int nodeCertValidityDays,
            StateStore stateStore,
            JwtManager jwtManager) {
        this(tokenStore, ca, caPemPath, nodeCertValidityDays, stateStore, jwtManager, null);
    }

    public BootstrapServiceImpl(
            JoinTokenStore tokenStore,
            CertificateAuthority ca,
            Path caPemPath,
            int nodeCertValidityDays,
            StateStore stateStore,
            JwtManager jwtManager,
            Consumer<String> hostSubnetRegister) {
        this.tokenStore = tokenStore;
        this.ca = ca;
        this.caPemPath = caPemPath;
        this.nodeCertValidityDays = nodeCertValidityDays;
        this.stateStore = stateStore;
        this.jwtManager = jwtManager;
        this.hostSubnetRegister = hostSubnetRegister;
    }

    /**
     * Exchange a join token for a node certificate. Shared by the gRPC stub and the
     * REST wizard endpoint — both produce identical artifacts. When
     * {@code sourceCidr} is non-null and a register callback is wired, the source IP
     * is added to the controller's allowed-subnets list as a side effect so the
     * daemon's subsequent mTLS connections pass the subnet guard.
     */
    public ExchangeResult exchange(String joinToken, String nodeId, String sourceCidr) throws Exception {
        var result = exchange(joinToken, nodeId);
        if (result != null && sourceCidr != null && hostSubnetRegister != null) {
            try {
                hostSubnetRegister.accept(sourceCidr);
            } catch (RuntimeException e) {
                // Non-fatal: cert was minted, daemon will still work if the operator's
                // existing allowedSubnets covers them. Surface the failure in logs.
                logger.warn("Failed to auto-register subnet {} for node {}: {}", sourceCidr, nodeId, e.getMessage());
            }
        }
        return result;
    }

    /**
     * Exchange a join token for a node certificate. Shared by the gRPC stub and the
     * REST wizard endpoint — both produce identical artifacts.
     */
    public ExchangeResult exchange(String joinToken, String nodeId) throws Exception {
        var tokenOpt = tokenStore.validate(joinToken);
        if (tokenOpt.isEmpty()) {
            return null;
        }
        var token = tokenOpt.get();

        var nodeCert = ca.issueNodeCertificate(nodeId, nodeCertValidityDays);
        String pkcs12Password = generatePassword();
        byte[] pkcs12Bytes = nodeCert.toPkcs12Bytes(pkcs12Password.toCharArray());
        byte[] caPem = Files.readAllBytes(caPemPath);

        tokenStore.consume(token.tokenId());
        stateStore.registerNode(nodeId);

        // Mint a DAEMON_HOST JWT so the CLI on this host can auto-link without a
        // separate `prexorctl login`. Optional: pre-JwtManager controllers return empty.
        String cliToken = "";
        if (jwtManager != null) {
            cliToken = jwtManager.issue("daemon-host-" + nodeId, Role.DAEMON_HOST);
        }

        logger.debug("Bootstrap completed for node {} (token={})", nodeId, token.tokenId());
        return new ExchangeResult(pkcs12Bytes, pkcs12Password, caPem, cliToken);
    }

    /**
     * Convert a peer address string (as stashed by {@link PeerAddressInterceptor})
     * into a {@code /32} or {@code /128} CIDR suitable for the allowed-subnets list.
     * Returns null for blank, loopback, or unresolvable input — those don't need
     * to be auto-registered (loopback is always allowed; the rest can't be
     * matched anyway).
     */
    static String peerToHostCidr(String peerAddress) {
        if (peerAddress == null || peerAddress.isBlank()) return null;
        try {
            var addr = java.net.InetAddress.getByName(peerAddress);
            if (addr.isLoopbackAddress()) return null;
            return addr.getHostAddress() + (addr instanceof java.net.Inet6Address ? "/128" : "/32");
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }

    public record ExchangeResult(byte[] pkcs12, String pkcs12Password, byte[] caPem, String cliToken) {}

    @Override
    public void exchangeJoinToken(
            ExchangeJoinTokenRequest request, StreamObserver<ExchangeJoinTokenResponse> responseObserver) {
        try {
            // Peer address stashed in the gRPC Context by PeerAddressInterceptor.
            String peer = DaemonServiceImpl.PEER_ADDRESS_KEY.get();
            String sourceCidr = peerToHostCidr(peer);
            var result = exchange(request.getJoinToken(), request.getNodeId(), sourceCidr);
            if (result == null) {
                responseObserver.onError(Status.UNAUTHENTICATED
                        .withDescription("Invalid or expired join token")
                        .asRuntimeException());
                return;
            }

            responseObserver.onNext(ExchangeJoinTokenResponse.newBuilder()
                    .setPkcs12(com.google.protobuf.ByteString.copyFrom(result.pkcs12()))
                    .setPkcs12Password(result.pkcs12Password())
                    .setCaCertificatePem(com.google.protobuf.ByteString.copyFrom(result.caPem()))
                    .setCliToken(result.cliToken())
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Bootstrap failed for node {}: {}", request.getNodeId(), e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Bootstrap failed: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    private static String generatePassword() {
        SecureRandom random = new SecureRandom();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
