package me.prexorjustin.prexorcloud.controller.grpc;

import java.security.cert.X509Certificate;
import java.util.Set;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

import me.prexorjustin.prexorcloud.security.tls.NodeRevocationCheck;

import io.grpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC server interceptor that enforces mutual TLS (client certificate) for
 * authenticated services while allowing unauthenticated access to the
 * BootstrapService (which is used by new nodes to obtain their certificates).
 *
 * <p>
 * With {@code ClientAuth.OPTIONAL} in the TLS config, the TLS handshake
 * succeeds even without a client cert. This interceptor rejects calls to
 * DaemonService and AdminService if no valid client certificate was presented.
 * </p>
 *
 * <p>The interceptor also consults a {@link NodeRevocationCheck} so RPCs that
 * arrive on connections opened before a revocation are rejected on the next
 * call. New connections are blocked at the TLS layer by the trust manager
 * inside {@link me.prexorjustin.prexorcloud.security.tls.ReloadableServerSslContext}.
 */
public final class MtlsEnforcementInterceptor implements ServerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(MtlsEnforcementInterceptor.class);

    /**
     * Service names that are allowed without a client certificate. BootstrapService
     * is the join-token exchange used before a node has certs.
     */
    private static final Set<String> UNAUTHENTICATED_SERVICES =
            Set.of("me.prexorjustin.prexorcloud.protocol.BootstrapService");

    private final NodeRevocationCheck revocationCheck;

    public MtlsEnforcementInterceptor() {
        this(NodeRevocationCheck.NONE);
    }

    public MtlsEnforcementInterceptor(NodeRevocationCheck revocationCheck) {
        this.revocationCheck = revocationCheck == null ? NodeRevocationCheck.NONE : revocationCheck;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String fullMethodName = call.getMethodDescriptor().getFullMethodName();
        String serviceName = call.getMethodDescriptor().getServiceName();

        // Allow unauthenticated services (BootstrapService)
        if (serviceName != null && UNAUTHENTICATED_SERVICES.contains(serviceName)) {
            return next.startCall(call, headers);
        }

        // Check for client certificate
        SSLSession sslSession = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        if (sslSession == null) {
            logger.warn("Rejected {} — no TLS session (plain-text connection)", fullMethodName);
            call.close(Status.UNAUTHENTICATED.withDescription("mTLS required — no TLS session"), new Metadata());
            return new ServerCall.Listener<>() {};
        }

        try {
            var peerCerts = sslSession.getPeerCertificates();
            if (peerCerts == null || peerCerts.length == 0) {
                logger.warn("Rejected {} — no client certificate presented", fullMethodName);
                call.close(
                        Status.UNAUTHENTICATED.withDescription(
                                "mTLS required — no client certificate. Use BootstrapService to obtain one."),
                        new Metadata());
                return new ServerCall.Listener<>() {};
            }
            if (peerCerts[0] instanceof X509Certificate leaf) {
                String cn = extractCn(leaf.getSubjectX500Principal().getName());
                if (revocationCheck.isRevoked(leaf.getSerialNumber(), cn)) {
                    logger.warn(
                            "Rejected {} — client certificate revoked (CN={}, serial={})",
                            fullMethodName,
                            cn,
                            leaf.getSerialNumber().toString(16));
                    call.close(
                            Status.UNAUTHENTICATED.withDescription("mTLS — client certificate revoked"),
                            new Metadata());
                    return new ServerCall.Listener<>() {};
                }
            }
        } catch (SSLPeerUnverifiedException e) {
            logger.warn("Rejected {} — client certificate not verified: {}", fullMethodName, e.getMessage());
            call.close(
                    Status.UNAUTHENTICATED.withDescription("mTLS required — client certificate not verified"),
                    new Metadata());
            return new ServerCall.Listener<>() {};
        }

        // Propagate the SSL session into the gRPC Context so service implementations
        // (e.g. AdminServiceImpl) can extract the client certificate CN for
        // authorization.
        Context ctx = Context.current().withValue(GrpcContextKeys.SSL_SESSION_KEY, sslSession);
        return Contexts.interceptCall(ctx, call, headers, next);
    }

    private static String extractCn(String distinguishedName) {
        if (distinguishedName == null) {
            return "";
        }
        for (String part : distinguishedName.split(",")) {
            String trimmed = part.trim();
            if (trimmed.regionMatches(true, 0, "CN=", 0, 3)) {
                return trimmed.substring(3);
            }
        }
        return "";
    }
}
