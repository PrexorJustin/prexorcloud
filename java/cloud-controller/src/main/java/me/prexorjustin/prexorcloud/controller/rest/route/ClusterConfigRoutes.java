package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.post;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.prexorjustin.prexorcloud.api.event.events.ClusterConfigChangedEvent;
import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.cluster.ClusterPlane;
import me.prexorjustin.prexorcloud.controller.cluster.ClusterWriteConflict;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterConfigVersion;
import me.prexorjustin.prexorcloud.controller.rest.RestServer;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;

import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Versioned cluster_config surface. Reads project the Mongo cluster store; writes go through the Mongo
 * {@link ClusterPlane} with optimistic {@code parentVersion}-based conflict detection. A successful write
 * publishes a {@link ClusterConfigChangedEvent} on the local event bus so the serving leader's
 * live-reload fan-out re-applies the new config without a restart.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/v1/cluster/config} — currently active patch (masked)</li>
 *   <li>{@code GET  /api/v1/cluster/config/versions} — version metadata list</li>
 *   <li>{@code GET  /api/v1/cluster/config/versions/{n}} — one version (masked)</li>
 *   <li>{@code POST /api/v1/cluster/config} — propose a patch
 *       ({@code {parentVersion, patch, reason?}})</li>
 *   <li>{@code POST /api/v1/cluster/config/rollback} — set the active version
 *       to an earlier one ({@code {targetVersion, reason?}})</li>
 * </ul>
 *
 * <p>Reads are masked: fields like {@code security.jwtSecret}
 * and SMTP credentials are replaced with {@code "***"} unless the caller passes
 * {@code ?reveal=true} <em>and</em> holds {@link Permission#CLUSTER_MANAGE}.
 * Writes are <em>never</em> masked — operators submit cleartext values.
 */
public final class ClusterConfigRoutes {

    private static final Logger logger = LoggerFactory.getLogger(ClusterConfigRoutes.class);

    /**
     * Dotted paths that must never be returned in cleartext to a non-{@code CLUSTER_MANAGE}
     * principal. Matched as exact paths during a recursive walk of the patch map.
     */
    private static final Set<String> SENSITIVE_PATHS = Set.of(
            "security.jwtSecret",
            "security.jwtPreviousSecrets",
            "database.uri",
            "share.smtpPassword",
            "share.pasteApiKey",
            "modules.signing.trustRoot");

    private static final String MASK = "***";

    private final PrexorController controller;

    public ClusterConfigRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        get("/api/v1/cluster/config", this::getActiveConfig);
        get("/api/v1/cluster/config/versions", this::listVersions);
        get("/api/v1/cluster/config/versions/{version}", this::getVersion);
        post("/api/v1/cluster/config", this::proposePatch);
        post("/api/v1/cluster/config/rollback", this::rollback);
    }

    private void getActiveConfig(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CLUSTER_VIEW);
        ClusterPlane plane = controller.clusterPlane();
        boolean reveal = revealRequested(ctx);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("activeVersion", plane.getActiveConfigVersion());
        plane.getActiveConfigPatch()
                .ifPresentOrElse(
                        v -> body.put("patch", reveal ? v.patch() : maskPatch(v.patch(), "")),
                        () -> body.put("patch", Map.of()));
        ctx.status(200);
        ctx.json(body);
    }

    private void listVersions(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CLUSTER_VIEW);
        ClusterPlane plane = controller.clusterPlane();
        int active = plane.getActiveConfigVersion();
        List<Map<String, Object>> versions = plane.listConfigVersions().stream()
                .map(v -> versionMetadata(v, v.version() == active))
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("activeVersion", active);
        body.put("versions", versions);
        ctx.status(200);
        ctx.json(body);
    }

    private void getVersion(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CLUSTER_VIEW);
        int requested;
        try {
            requested = Integer.parseInt(ctx.pathParam("version"));
        } catch (NumberFormatException e) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_VERSION", "version must be an integer", 400));
            return;
        }
        ClusterPlane plane = controller.clusterPlane();
        boolean reveal = revealRequested(ctx);
        ClusterConfigVersion match = plane.listConfigVersions().stream()
                .filter(v -> v.version() == requested)
                .findFirst()
                .orElse(null);
        if (match == null) {
            ctx.status(404);
            ctx.json(errorResponse("VERSION_NOT_FOUND", "cluster_config has no version " + requested, 404));
            return;
        }
        Map<String, Object> body = versionMetadata(match, match.version() == plane.getActiveConfigVersion());
        body.put("patch", reveal ? match.patch() : maskPatch(match.patch(), ""));
        ctx.status(200);
        ctx.json(body);
    }

    @SuppressWarnings("unchecked")
    private void proposePatch(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CLUSTER_CONFIG_WRITE);
        Map<String, Object> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_BODY", "request body must be JSON object", 400));
            return;
        }
        Object parentObj = body.get("parentVersion");
        if (!(parentObj instanceof Number parentNum)) {
            ctx.status(400);
            ctx.json(errorResponse("MISSING_PARENT_VERSION", "parentVersion is required", 400));
            return;
        }
        Object patchObj = body.get("patch");
        if (!(patchObj instanceof Map<?, ?> patchMap) || patchMap.isEmpty()) {
            ctx.status(400);
            ctx.json(errorResponse("EMPTY_PATCH", "patch must be a non-empty JSON object", 400));
            return;
        }
        int parentVersion = parentNum.intValue();
        String reason = body.get("reason") instanceof String s ? s : null;
        String mutator = ctx.attribute("username");
        ClusterPlane plane = controller.clusterPlane();
        try {
            int newVersion = plane.proposeConfigPatch(parentVersion, mutator, (Map<String, Object>) patchMap, reason);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("version", newVersion);
            resp.put("parentVersion", parentVersion);
            ctx.status(201);
            ctx.json(resp);
            RestServer.audit(
                    ctx,
                    controller.stateStore(),
                    "cluster.config.patched",
                    "cluster_config",
                    String.valueOf(newVersion),
                    Map.of("parentVersion", parentVersion, "newVersion", newVersion, "reason", reason));
            controller
                    .eventBus()
                    .publish(new ClusterConfigChangedEvent(
                            newVersion, parentVersion, mutator, ClusterConfigChangedEvent.ACTION_PATCH));
            logger.info(
                    "cluster_config patched to version {} by {} (parent={}, reason={})",
                    newVersion,
                    mutator,
                    parentVersion,
                    reason);
        } catch (ClusterWriteConflict e) {
            ctx.status(409);
            ctx.json(errorResponse(e.code(), e.getMessage(), 409));
        } catch (IOException e) {
            logger.error("Cluster store write failed for config patch: {}", e.getMessage(), e);
            ctx.status(503);
            ctx.json(errorResponse(
                    "CLUSTER_STORE_UNAVAILABLE",
                    "Could not propose patch to the cluster store: " + e.getMessage(),
                    503));
        }
    }

    private void rollback(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CLUSTER_CONFIG_WRITE);
        Map<String, Object> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_BODY", "request body must be JSON object", 400));
            return;
        }
        Object targetObj = body.get("targetVersion");
        if (!(targetObj instanceof Number targetNum)) {
            ctx.status(400);
            ctx.json(errorResponse("MISSING_TARGET_VERSION", "targetVersion is required", 400));
            return;
        }
        int targetVersion = targetNum.intValue();
        String mutator = ctx.attribute("username");
        ClusterPlane plane = controller.clusterPlane();
        boolean targetExists = plane.listConfigVersions().stream().anyMatch(v -> v.version() == targetVersion);
        if (!targetExists) {
            ctx.status(404);
            ctx.json(errorResponse("VERSION_NOT_FOUND", "cluster_config has no version " + targetVersion, 404));
            return;
        }
        try {
            plane.rollbackConfig(targetVersion, mutator);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("activeVersion", targetVersion);
            ctx.status(200);
            ctx.json(resp);
            RestServer.audit(
                    ctx,
                    controller.stateStore(),
                    "cluster.config.rolled_back",
                    "cluster_config",
                    String.valueOf(targetVersion),
                    Map.of("targetVersion", targetVersion));
            controller
                    .eventBus()
                    .publish(new ClusterConfigChangedEvent(
                            targetVersion, -1, mutator, ClusterConfigChangedEvent.ACTION_ROLLBACK));
            logger.info("cluster_config rolled back to version {} by {}", targetVersion, mutator);
        } catch (ClusterWriteConflict e) {
            ctx.status(409);
            ctx.json(errorResponse(e.code(), e.getMessage(), 409));
        } catch (IOException e) {
            logger.error("Cluster store write failed for config rollback: {}", e.getMessage(), e);
            ctx.status(503);
            ctx.json(errorResponse(
                    "CLUSTER_STORE_UNAVAILABLE",
                    "Could not commit rollback to the cluster store: " + e.getMessage(),
                    503));
        }
    }

    // --- helpers ---

    private static Map<String, Object> versionMetadata(ClusterConfigVersion v, boolean isActive) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", v.version());
        m.put("parentVersion", v.parentVersion());
        m.put("mutator", v.mutator());
        m.put("mutatedAt", v.mutatedAt().toString());
        m.put("reason", v.reason());
        m.put("isActive", isActive);
        return m;
    }

    private static boolean revealRequested(Context ctx) {
        if (!"true".equalsIgnoreCase(ctx.queryParam("reveal"))) {
            return false;
        }
        // Reveal is gated on CLUSTER_MANAGE — checked here rather than as a
        // requirePermission() above so a CLUSTER_VIEW caller can still GET (masked).
        String role = ctx.attribute("role");
        return role != null
                && me.prexorjustin.prexorcloud.controller.auth.Role.hasPermission(role, Permission.CLUSTER_MANAGE);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> maskPatch(Map<String, Object> patch, String prefix) {
        Map<String, Object> out = new LinkedHashMap<>(patch.size());
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (SENSITIVE_PATHS.contains(path)) {
                out.put(entry.getKey(), MASK);
            } else if (value instanceof Map<?, ?> nested) {
                out.put(entry.getKey(), maskPatch((Map<String, Object>) nested, path));
            } else {
                out.put(entry.getKey(), value);
            }
        }
        return out;
    }
}
