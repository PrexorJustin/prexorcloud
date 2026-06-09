package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.post;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlPlane;
import me.prexorjustin.prexorcloud.controller.rest.RestServer;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;

import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cluster seed-secret rotation (Phase 5 of cluster-join-plan.md).
 *
 * <p>Rotating the seed invalidates every outstanding join token, since the
 * HMAC inside each token is keyed on the old seed. Operators run this after
 * suspecting token leakage or as part of routine credential hygiene.
 *
 * <ul>
 *   <li>{@code POST /api/v1/cluster/seed/rotate} — propose a fresh
 *       cryptographically-random seed and commit it via Raft. Permission:
 *       {@code CLUSTER_MANAGE}.</li>
 * </ul>
 *
 * <p>The fresh seed is never returned in the response — knowing the seed
 * would let a caller mint tokens out-of-band. Operators get back the
 * rotation metadata (who, when) only.
 */
public final class ClusterSeedRoutes {

    private static final Logger logger = LoggerFactory.getLogger(ClusterSeedRoutes.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SEED_BYTES = 32;

    private final PrexorController controller;

    public ClusterSeedRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        post("/api/v1/cluster/seed/rotate", this::rotate);
    }

    private void rotate(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CLUSTER_MANAGE);
        ClusterControlPlane plane = controller.clusterControlPlane();
        if (plane.getClusterMeta().isEmpty()) {
            ctx.status(409);
            ctx.json(errorResponse(
                    "META_NOT_STAMPED", "cluster meta has not been stamped yet — controller still bootstrapping", 409));
            return;
        }
        String mutator = ctx.attribute("username");
        String newSeedB64 = freshSeedBase64();
        try {
            plane.rotateSeed(newSeedB64, mutator);
        } catch (IOException e) {
            logger.error("Raft submit failed for seed rotate: {}", e.getMessage(), e);
            ctx.status(503);
            ctx.json(errorResponse("RAFT_UNAVAILABLE", "Could not rotate cluster seed: " + e.getMessage(), 503));
            return;
        }
        RestServer.audit(
                ctx,
                controller.stateStore(),
                "cluster.seed.rotated",
                "cluster_meta",
                plane.getClusterMeta().get().clusterId(),
                Map.of("by", mutator == null ? "(unknown)" : mutator));
        logger.info("cluster seed rotated by {}", mutator);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clusterId", plane.getClusterMeta().get().clusterId());
        body.put("rotatedBy", mutator);
        body.put("rotatedAt", Instant.now().toString());
        ctx.status(200);
        ctx.json(body);
    }

    /** Base64 encoding of {@value SEED_BYTES} cryptographically-random bytes. */
    static String freshSeedBase64() {
        byte[] bytes = new byte[SEED_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
