package me.prexorjustin.prexorcloud.controller.grpc;

import java.security.cert.X509Certificate;
import java.util.Set;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

import me.prexorjustin.prexorcloud.protocol.*;
import me.prexorjustin.prexorcloud.security.token.JoinTokenStore;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AdminServiceImpl extends AdminServiceGrpc.AdminServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(AdminServiceImpl.class);
    private static final int DEFAULT_TTL_SECONDS = 3600;

    private final JoinTokenStore tokenStore;
    private final Set<String> adminNodeIds;

    /**
     * @param tokenStore
     *            join token store
     * @param adminNodeIds
     *            set of certificate CNs (node IDs) authorized to call admin
     *            operations. If empty, only the controller's own certificate (CN
     *            containing "controller") is allowed.
     */
    public AdminServiceImpl(JoinTokenStore tokenStore, Set<String> adminNodeIds) {
        this.tokenStore = tokenStore;
        this.adminNodeIds = adminNodeIds != null ? Set.copyOf(adminNodeIds) : Set.of();
    }

    public AdminServiceImpl(JoinTokenStore tokenStore) {
        this(tokenStore, Set.of());
    }

    @Override
    public void createJoinToken(
            CreateJoinTokenRequest request, StreamObserver<CreateJoinTokenResponse> responseObserver) {
        if (checkAuthorization(responseObserver)) return;

        int ttl = request.getTtlSeconds() > 0 ? request.getTtlSeconds() : DEFAULT_TTL_SECONDS;
        var result = tokenStore.create(request.getNodeId(), ttl);

        responseObserver.onNext(CreateJoinTokenResponse.newBuilder()
                .setTokenId(result.token().tokenId())
                .setJoinToken(result.plaintextToken())
                .setExpiresAtEpochMs(result.token().expiresAt().toEpochMilli())
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void revokeJoinToken(
            RevokeJoinTokenRequest request, StreamObserver<RevokeJoinTokenResponse> responseObserver) {
        if (checkAuthorization(responseObserver)) return;

        tokenStore.consume(request.getTokenId());
        logger.info("Join token revoked: {}", request.getTokenId());
        responseObserver.onNext(RevokeJoinTokenResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void listJoinTokens(ListJoinTokensRequest request, StreamObserver<ListJoinTokensResponse> responseObserver) {
        if (checkAuthorization(responseObserver)) return;

        var builder = ListJoinTokensResponse.newBuilder();
        for (var token : tokenStore.list()) {
            builder.addTokens(JoinTokenInfo.newBuilder()
                    .setTokenId(token.tokenId())
                    .setNodeId(token.nodeId())
                    .setExpiresAtEpochMs(token.expiresAt().toEpochMilli())
                    .setExpired(token.isExpired()));
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    /**
     * Checks that the calling client's certificate CN is authorized for admin
     * operations. Returns true if authorized, false (and sends PERMISSION_DENIED)
     * otherwise.
     */
    private boolean checkAuthorization(StreamObserver<?> responseObserver) {
        String cn = extractClientCn();
        if (cn == null) {
            logger.warn("Admin request rejected: no client certificate CN available");
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription("Admin operations require a valid client certificate")
                    .asRuntimeException());
            return true;
        }

        // Allow if CN is in the explicit admin list, or if it contains "controller"
        // (the controller's own certificate, used by the REST API layer)
        boolean authorized = cn.contains("controller") || adminNodeIds.contains(cn);
        if (!authorized) {
            logger.warn("Admin request rejected: CN '{}' is not authorized", cn);
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription("Node '" + cn + "' is not authorized for admin operations")
                    .asRuntimeException());
            return true;
        }

        return false;
    }

    /**
     * Extracts the Common Name (CN) from the client certificate presented in the
     * current gRPC call. Relies on {@link MtlsEnforcementInterceptor} propagating
     * the SSLSession into the gRPC Context.
     */
    private static String extractClientCn() {
        try {
            SSLSession session = GrpcContextKeys.SSL_SESSION_KEY.get();
            if (session == null) return null;

            var certs = session.getPeerCertificates();
            if (certs.length == 0) return null;

            if (certs[0] instanceof X509Certificate x509) {
                String dn = x509.getSubjectX500Principal().getName();
                // Parse CN from DN like "CN=my-node-id"
                for (String part : dn.split(",")) {
                    String trimmed = part.trim();
                    if (trimmed.startsWith("CN=")) {
                        return trimmed.substring(3);
                    }
                }
            }
        } catch (SSLPeerUnverifiedException e) {
            logger.debug("Could not extract client CN: {}", e.getMessage());
        }
        return null;
    }
}
