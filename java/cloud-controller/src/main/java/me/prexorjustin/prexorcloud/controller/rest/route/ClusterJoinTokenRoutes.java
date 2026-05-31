package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.delete;
import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.post;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlPlane;
import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterWriteConflict;
import me.prexorjustin.prexorcloud.controller.cluster.state.JoinToken;
import me.prexorjustin.prexorcloud.controller.rest.RestServer;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;

import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cluster join-token surface (Phase 5 of cluster-join-plan.md). The token
 * issuance flow runs on any controller — the {@code WriteJoinToken} entry is
 * a Raft commit so every peer ends up with the same token registry.
 *
 * <ul>
 *   <li>{@code POST   /api/v1/cluster/join-tokens} — issue (body:
 *       {@code {ttlSeconds, label, joinAddrs}}); returns the wire token
 *       string ONCE — the controller does NOT store it in cleartext.</li>
 *   <li>{@code GET    /api/v1/cluster/join-tokens} — list outstanding (jti,
 *       label, status, createdAt, expiresAt). The token string itself is
 *       NEVER returned after issuance.</li>
 *   <li>{@code DELETE /api/v1/cluster/join-tokens/{jti}} — revoke an
 *       outstanding token. Idempotent on already-revoked.</li>
 * </ul>
 */
public final class ClusterJoinTokenRoutes {

    private static final Logger logger = LoggerFactory.getLogger(ClusterJoinTokenRoutes.class);

    /** Hard cap on the requested TTL (30 days). Operators who need longer can re-issue. */
    private static final long MAX_TTL_SECONDS = Duration.ofDays(30).toSeconds();

    /** Default TTL when none is supplied (24 hours). */
    private static final long DEFAULT_TTL_SECONDS = Duration.ofHours(24).toSeconds();

    private final PrexorController controller;

    public ClusterJoinTokenRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        post("/api/v1/cluster/join-tokens", this::issue);
        get("/api/v1/cluster/join-tokens", this::list);
        delete("/api/v1/cluster/join-tokens/{jti}", this::revoke);
    }

    @SuppressWarnings("unchecked")
    private void issue(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CLUSTER_MANAGE);
        Map<String, Object> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_BODY", "request body must be JSON object", 400));
            return;
        }
        long ttlSeconds = body.get("ttlSeconds") instanceof Number n ? n.longValue() : DEFAULT_TTL_SECONDS;
        if (ttlSeconds <= 0 || ttlSeconds > MAX_TTL_SECONDS) {
            ctx.status(400);
            ctx.json(errorResponse(
                    "BAD_TTL",
                    "ttlSeconds must be in (0, " + MAX_TTL_SECONDS + "]; got " + ttlSeconds,
                    400));
            return;
        }
        String label = body.get("label") instanceof String s ? s : null;
        Object joinAddrsObj = body.get("joinAddrs");
        if (!(joinAddrsObj instanceof List<?> joinAddrsRaw) || joinAddrsRaw.isEmpty()) {
            ctx.status(400);
            ctx.json(errorResponse(
                    "MISSING_JOIN_ADDRS",
                    "joinAddrs must be a non-empty array of host:port strings",
                    400));
            return;
        }
        List<String> joinAddrs = (List<String>) joinAddrsRaw;
        String mutator = ctx.attribute("username");
        ClusterControlPlane plane = controller.clusterControlPlane();
        try {
            ClusterControlPlane.IssuedJoinToken issued =
                    plane.issueJoinToken(joinAddrs, Duration.ofSeconds(ttlSeconds), label, mutator);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("jti", issued.jti());
            resp.put("token", issued.token());
            resp.put("expiresAt", issued.expiresAt());
            ctx.status(201);
            ctx.json(resp);
            RestServer.audit(
                    ctx,
                    controller.stateStore(),
                    "cluster.join_token.issued",
                    "cluster_join_token",
                    issued.jti(),
                    Map.of(
                            "label", label == null ? "(none)" : label,
                            "ttlSeconds", ttlSeconds));
            logger.info("cluster join token issued (jti={}, ttl={}s, by={})", issued.jti(), ttlSeconds, mutator);
        } catch (IllegalStateException e) {
            ctx.status(409);
            ctx.json(errorResponse("CLUSTER_NOT_READY", e.getMessage(), 409));
        } catch (IOException e) {
            logger.error("Raft submit failed for join token issue: {}", e.getMessage(), e);
            ctx.status(503);
            ctx.json(errorResponse("RAFT_UNAVAILABLE", "Could not issue token: " + e.getMessage(), 503));
        }
    }

    private void list(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CLUSTER_MANAGE);
        Instant now = Instant.now();
        ClusterControlPlane plane = controller.clusterControlPlane();
        List<Map<String, Object>> tokens = plane.listJoinTokens().stream()
                .map(t -> tokenJson(t, now))
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tokens", tokens);
        ctx.status(200);
        ctx.json(body);
    }

    private void revoke(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CLUSTER_MANAGE);
        String jti = ctx.pathParam("jti");
        String mutator = ctx.attribute("username");
        ClusterControlPlane plane = controller.clusterControlPlane();
        if (plane.getJoinToken(jti).isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("TOKEN_NOT_FOUND", "no join token with jti=" + jti, 404));
            return;
        }
        try {
            plane.revokeJoinToken(jti, mutator);
            ctx.status(204);
            RestServer.audit(
                    ctx,
                    controller.stateStore(),
                    "cluster.join_token.revoked",
                    "cluster_join_token",
                    jti,
                    Map.of());
            logger.info("cluster join token {} revoked by {}", jti, mutator);
        } catch (ClusterWriteConflict e) {
            ctx.status(409);
            ctx.json(errorResponse(e.code(), e.getMessage(), 409));
        } catch (IOException e) {
            logger.error("Raft submit failed for join token revoke: {}", e.getMessage(), e);
            ctx.status(503);
            ctx.json(errorResponse("RAFT_UNAVAILABLE", "Could not revoke token: " + e.getMessage(), 503));
        }
    }

    private static Map<String, Object> tokenJson(JoinToken t, Instant now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jti", t.jti());
        out.put("label", t.label());
        out.put("createdBy", t.createdBy());
        out.put("createdAt", t.createdAt());
        out.put("expiresAt", t.expiresAt());
        out.put("status", statusOf(t, now));
        out.put("redeemedAt", t.redeemedAt());
        out.put("redeemedAs", t.redeemedAs());
        out.put("revokedAt", t.revokedAt());
        out.put("revokedBy", t.revokedBy());
        return out;
    }

    private static String statusOf(JoinToken t, Instant now) {
        if (t.revoked()) return "REVOKED";
        if (t.redeemedAt() != null) return "REDEEMED";
        if (t.isExpired(now)) return "EXPIRED";
        return "OUTSTANDING";
    }
}
