package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.delete;
import static io.javalin.apibuilder.ApiBuilder.get;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlPlane;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;
import me.prexorjustin.prexorcloud.controller.rest.RestServer;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;

import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cluster status + member management surface (Phase 5 of cluster-join-plan.md).
 *
 * <ul>
 *   <li>{@code GET    /api/v1/cluster} — cluster id, member count, this node's role.</li>
 *   <li>{@code GET    /api/v1/cluster/members} — detailed member list (sorted by nodeId).</li>
 *   <li>{@code DELETE /api/v1/cluster/members/{nodeId}} — force-eject a member.</li>
 * </ul>
 */
public final class ClusterMembersRoutes {

    private static final Logger logger = LoggerFactory.getLogger(ClusterMembersRoutes.class);

    private final PrexorController controller;

    public ClusterMembersRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        get("/api/v1/cluster", this::getStatus);
        get("/api/v1/cluster/members", this::listMembers);
        delete("/api/v1/cluster/members/{nodeId}", this::ejectMember);
    }

    private void getStatus(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CLUSTER_VIEW);
        ClusterControlPlane plane = controller.clusterControlPlane();
        Map<String, Object> body = new LinkedHashMap<>();
        plane.getClusterMeta().ifPresent(meta -> {
            body.put("clusterId", meta.clusterId());
            body.put("createdAt", meta.createdAt());
            body.put("schemaVersion", meta.schemaVersion());
        });
        body.put("memberCount", plane.listMembers().size());
        body.put("activeConfigVersion", plane.getActiveConfigVersion());
        ctx.status(200);
        ctx.json(body);
    }

    private void listMembers(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CLUSTER_VIEW);
        ClusterControlPlane plane = controller.clusterControlPlane();
        List<Map<String, Object>> members = plane.listMembers().stream()
                .map(ClusterMembersRoutes::memberJson)
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("members", members);
        ctx.status(200);
        ctx.json(body);
    }

    private void ejectMember(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CLUSTER_MANAGE);
        String nodeId = ctx.pathParam("nodeId");
        ClusterControlPlane plane = controller.clusterControlPlane();
        if (plane.listMembers().stream().noneMatch(m -> m.nodeId().equals(nodeId))) {
            ctx.status(404);
            ctx.json(errorResponse("MEMBER_NOT_FOUND", "no cluster member with nodeId=" + nodeId, 404));
            return;
        }
        String reason = ctx.queryParam("reason");
        String mutator = ctx.attribute("username");
        try {
            plane.removeMember(nodeId, reason == null ? "ejected by " + mutator : reason);
            ctx.status(204);
            RestServer.audit(
                    ctx,
                    controller.stateStore(),
                    "cluster.member.ejected",
                    "cluster_member",
                    nodeId,
                    Map.of("reason", reason == null ? "(none)" : reason));
            logger.info("cluster member {} ejected by {} (reason={})", nodeId, mutator, reason);
        } catch (IOException e) {
            logger.error("Raft submit failed for member eject: {}", e.getMessage(), e);
            ctx.status(503);
            ctx.json(errorResponse("RAFT_UNAVAILABLE", "Could not eject member: " + e.getMessage(), 503));
        }
    }

    private static Map<String, Object> memberJson(Member m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodeId", m.nodeId());
        out.put("raftAddr", m.raftAddr());
        out.put("restAddr", m.restAddr());
        out.put("gRPCAddr", m.gRPCAddr());
        out.put("label", m.label());
        out.put("joinedAt", m.joinedAt());
        out.put("lastSeen", m.lastSeen());
        return out;
    }
}
