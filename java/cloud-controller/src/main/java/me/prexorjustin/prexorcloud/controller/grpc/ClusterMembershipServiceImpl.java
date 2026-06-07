package me.prexorjustin.prexorcloud.controller.grpc;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.cluster.JoinTokenCodec;
import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlPlane;
import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterWriteConflict;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.protocol.ClusterMembershipGrpc;
import me.prexorjustin.prexorcloud.protocol.KnownPeer;
import me.prexorjustin.prexorcloud.protocol.RequestJoinRequest;
import me.prexorjustin.prexorcloud.protocol.RequestJoinResponse;
import me.prexorjustin.prexorcloud.security.ca.CertificateAuthority;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves the controller-to-controller join handshake. A controller that wants
 * to join the cluster posts a {@code prexor-jt:v1:...} token plus a CSR; this
 * service validates the token's HMAC against the cluster seed secret, atomically
 * redeems it via Raft, signs the CSR with the cluster CA, and returns the
 * signed leaf + CA cert. The joiner then brings up its own Ratis server with
 * those credentials (sub-step 3) and the leader expands the group via
 * setConfiguration. See {@code docs/engineering/cluster-join-plan.md}.
 *
 * <p>This service is exempt from the controller's mTLS interceptor — the join
 * token is the authentication. {@link GrpcServer} excludes it the same way it
 * exempts {@link BootstrapServiceImpl}.
 */
public final class ClusterMembershipServiceImpl extends ClusterMembershipGrpc.ClusterMembershipImplBase {

    private static final Logger logger = LoggerFactory.getLogger(ClusterMembershipServiceImpl.class);
    private static final ObjectMapper AUDIT_MAPPER = new ObjectMapper();
    private static final int LEAF_VALIDITY_DAYS = 365;

    private final ClusterControlPlane controlPlane;
    private final Clock clock;
    /** Audit sink for {@code cluster.member.joined}; {@code null} in tests (no-op). */
    private final StateStore auditStore;

    public ClusterMembershipServiceImpl(ClusterControlPlane controlPlane) {
        this(controlPlane, Clock.systemUTC(), null);
    }

    /** Test seam — fixed clock, no audit sink. */
    public ClusterMembershipServiceImpl(ClusterControlPlane controlPlane, Clock clock) {
        this(controlPlane, clock, null);
    }

    public ClusterMembershipServiceImpl(ClusterControlPlane controlPlane, Clock clock, StateStore auditStore) {
        this.controlPlane = controlPlane;
        this.clock = clock;
        this.auditStore = auditStore;
    }

    @Override
    public void requestJoin(RequestJoinRequest request, StreamObserver<RequestJoinResponse> responseObserver) {
        try {
            RequestJoinResponse response = handle(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (TokenRejected e) {
            logger.info("RequestJoin rejected ({}): {}", e.code, e.getMessage());
            responseObserver.onError(Status.UNAUTHENTICATED
                    .augmentDescription(e.code)
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (ClusterWriteConflict e) {
            logger.info("RequestJoin redeem conflict ({}): {}", e.code(), e.getMessage());
            // Replayed or revoked tokens, parallel redemptions — all surface here.
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .augmentDescription(e.code())
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (Exception e) {
            logger.warn("RequestJoin internal error", e);
            responseObserver.onError(
                    Status.INTERNAL.withDescription(e.getMessage()).withCause(e).asRuntimeException());
        }
    }

    private RequestJoinResponse handle(RequestJoinRequest request) throws Exception {
        if (request.getToken().isEmpty()) {
            throw new TokenRejected("TOKEN_EMPTY", "token required");
        }
        if (request.getCsrDer().isEmpty()) {
            throw new TokenRejected("CSR_EMPTY", "csr_der required");
        }
        if (request.getNodeId().isBlank()) {
            throw new TokenRejected("NODE_ID_EMPTY", "node_id required");
        }

        ClusterMeta meta = controlPlane
                .getClusterMeta()
                .orElseThrow(() -> new IOException("cluster meta not yet stamped — controller still bootstrapping"));
        byte[] seed = JoinTokenCodec.decodeSeed(meta.seedSecretBase64());

        JoinTokenCodec.Parsed parsed;
        try {
            parsed = JoinTokenCodec.parse(request.getToken());
        } catch (JoinTokenCodec.InvalidJoinToken e) {
            throw new TokenRejected("TOKEN_MALFORMED", e.getMessage());
        }
        if (!JoinTokenCodec.verifyHmac(parsed, seed)) {
            throw new TokenRejected("TOKEN_HMAC_INVALID", "token HMAC does not match cluster seed");
        }
        JoinTokenCodec.Payload payload = parsed.payload();
        if (!meta.clusterId().equals(payload.clusterId())) {
            throw new TokenRejected(
                    "CLUSTER_ID_MISMATCH",
                    "token was issued for clusterId=" + payload.clusterId() + " but this cluster is "
                            + meta.clusterId());
        }
        Instant now = clock.instant();
        if (payload.expiresAt() != null && !payload.expiresAt().isAfter(now)) {
            throw new TokenRejected("TOKEN_EXPIRED", "token expired at " + payload.expiresAt());
        }

        // Atomic single-use redemption — replays land in the state machine as TOKEN_ALREADY_REDEEMED
        // and ClusterWriteConflict bubbles up.
        String peerCidr = peerCidr(request);
        controlPlane.redeemJoinToken(payload.jti(), now, peerCidr, request.getNodeId());

        ClusterFile certFile = controlPlane
                .getClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT)
                .orElseThrow(() -> new IOException("cluster CA cert missing from raft state"));
        ClusterFile keyFile = controlPlane
                .getClusterFile(ClusterFile.KEY_CLUSTER_CA_KEY)
                .orElseThrow(() -> new IOException("cluster CA key missing from raft state"));
        CertificateAuthority ca = CertificateAuthority.loadFromDer(certFile.bytes(), keyFile.bytes());

        List<String> sans = new ArrayList<>();
        sans.add(request.getNodeId());
        addHostnameSan(sans, request.getRaftAddr());
        addHostnameSan(sans, request.getRestAddr());
        addHostnameSan(sans, request.getGrpcAddr());
        var signedCert = ca.signCsr(request.getCsrDer().toByteArray(), sans, LEAF_VALIDITY_DAYS);

        // Snapshot the current peer set BEFORE adding the joiner so the response carries
        // only the peers the joiner needs to dial. Adding self again is harmless if the
        // joiner included its own raft addr, but excluding it keeps the response semantics
        // clean: "the cluster you are joining, as it currently is".
        List<Member> existingMembers = controlPlane.listMembers();

        // Pin the new peer's addresses in the cluster state. Members survive snapshots; the joiner's
        // Ratis-side member-add comes in sub-step 3 via setConfiguration on the leader.
        controlPlane.addMember(new Member(
                request.getNodeId(),
                request.getRaftAddr(),
                request.getRestAddr(),
                request.getGrpcAddr(),
                request.getNodeId(),
                now,
                now));

        logger.info(
                "Issued cluster cert for joining controller {} (raft={}, rest={}, grpc={}, jti={})",
                request.getNodeId(),
                request.getRaftAddr(),
                request.getRestAddr(),
                request.getGrpcAddr(),
                payload.jti());

        writeJoinAudit(request, payload.jti(), peerCidr);

        RequestJoinResponse.Builder responseBuilder = RequestJoinResponse.newBuilder()
                .setSignedCertDer(ByteString.copyFrom(signedCert.getEncoded()))
                .setCaCertDer(ByteString.copyFrom(certFile.bytes()))
                .setClusterId(meta.clusterId());
        for (Member peer : existingMembers) {
            if (peer.nodeId().equals(request.getNodeId())) {
                // Defensive: a join retry would otherwise echo the joiner back to itself.
                continue;
            }
            responseBuilder.addCurrentPeers(KnownPeer.newBuilder()
                    .setNodeId(peer.nodeId())
                    .setRaftAddr(peer.raftAddr())
                    .build());
        }
        return responseBuilder.build();
    }

    /**
     * Best-effort audit entry for a successful join. The actor is the joining
     * node itself (gRPC join is token-authenticated, no operator user); the
     * source CIDR mirrors what {@code RedeemJoinToken} recorded. Never throws —
     * a join must not fail because the audit write hiccupped.
     */
    private void writeJoinAudit(RequestJoinRequest request, String jti, String peerCidr) {
        if (auditStore == null) {
            return;
        }
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("raftAddr", request.getRaftAddr());
            details.put("restAddr", request.getRestAddr());
            details.put("grpcAddr", request.getGrpcAddr());
            details.put("jti", jti);
            auditStore.audit(
                    request.getNodeId(),
                    "cluster.member.joined",
                    "cluster_member",
                    request.getNodeId(),
                    AUDIT_MAPPER.writeValueAsString(details),
                    peerCidr);
        } catch (Exception e) {
            logger.warn("Failed to write cluster.member.joined audit for {}: {}", request.getNodeId(), e.getMessage());
        }
    }

    private static String peerCidr(RequestJoinRequest request) {
        // The PeerAddressInterceptor stashes the source IP for daemon flows; we mirror that
        // here so the audit trail in RedeemJoinToken records where the join came from.
        String peer = DaemonServiceImpl.PEER_ADDRESS_KEY.get();
        return BootstrapServiceImpl.peerToHostCidr(peer);
    }

    /**
     * Best-effort: parse a {@code host:port} into a SAN entry. {@link CertificateAuthority#signCsr}
     * already distinguishes IPs from DNS names; we just need to strip the port.
     */
    private static void addHostnameSan(List<String> sans, String hostPort) {
        if (hostPort == null || hostPort.isBlank()) {
            return;
        }
        try {
            URI parsed = URI.create("dummy://" + hostPort);
            String host = parsed.getHost();
            if (host != null && !host.isBlank() && !sans.contains(host)) {
                sans.add(host);
            }
        } catch (IllegalArgumentException ignored) {
            // Caller passed something we can't parse — skip the SAN rather than fail the join.
        }
    }

    private static final class TokenRejected extends RuntimeException {
        final String code;

        TokenRejected(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
