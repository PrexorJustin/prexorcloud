package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.delete;
import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.post;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.controller.PrexorCloudBootstrap;
import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.cluster.ClusterReadView;
import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlPlane;
import me.prexorjustin.prexorcloud.controller.cluster.state.Lease;
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
 *   <li>{@code GET    /api/v1/cluster/leases} — current Raft lease holders (Phase 8).</li>
 *   <li>{@code DELETE /api/v1/cluster/members/{nodeId}} — force-eject a member.</li>
 *   <li>{@code POST   /api/v1/cluster/leave} — graceful self-removal.</li>
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
        get("/api/v1/cluster/leases", this::listLeases);
        delete("/api/v1/cluster/members/{nodeId}", this::ejectMember);
        post("/api/v1/cluster/leave", this::leave);
    }

    private void getStatus(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CLUSTER_VIEW);
        ClusterReadView view = controller.clusterReadView();
        Map<String, Object> body = new LinkedHashMap<>();
        view.getClusterMeta().ifPresent(meta -> {
            body.put("clusterId", meta.clusterId());
            body.put("createdAt", meta.createdAt());
            body.put("schemaVersion", meta.schemaVersion());
        });
        body.put("memberCount", view.listMembers().size());
        body.put("activeConfigVersion", view.getActiveConfigVersion());
        ctx.status(200);
        ctx.json(body);
    }

    private void listMembers(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CLUSTER_VIEW);
        ClusterReadView view = controller.clusterReadView();
        List<Map<String, Object>> members = view.listMembers().stream()
                .map(ClusterMembersRoutes::memberJson)
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("members", members);
        ctx.status(200);
        ctx.json(body);
    }

    /**
     * Lease-holder overview (Phase 8): which controller currently holds each
     * Raft lease (scheduler, deployment-reconciler, audit-pruner). Read from the
     * control-plane state machine so the view is leader-authoritative.
     */
    private void listLeases(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CLUSTER_VIEW);
        ClusterControlPlane plane = controller.clusterControlPlane();
        List<Map<String, Object>> leases =
                plane.getLeases().stream().map(ClusterMembersRoutes::leaseJson).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("leases", leases);
        ctx.status(200);
        ctx.json(body);
    }

    private void ejectMember(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CLUSTER_MANAGE);
        String nodeId = ctx.pathParam("nodeId");
        ClusterControlPlane plane = controller.clusterControlPlane();
        if (controller.clusterReadView().listMembers().stream()
                .noneMatch(m -> m.nodeId().equals(nodeId))) {
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

    /**
     * Graceful self-removal: propose {@code RemoveMember(self)} via Raft, then
     * trigger the controller's shutdown latch so the JVM exits cleanly. We delay
     * the latch trigger by 1 s so the HTTP response and audit write have a
     * chance to flush before the shutdown hook starts tearing down the server.
     *
     * <p>Refuses if this is the only member — a one-controller cluster has no
     * peer to take over, and leave-then-rejoin would require recovery tooling
     * not graceful leave.
     */
    private void leave(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CLUSTER_MANAGE);
        ClusterControlPlane plane = controller.clusterControlPlane();
        ClusterReadView view = controller.clusterReadView();
        String selfNodeId = controller.config().uuid();
        LeaveDecision decision = decideLeavability(view.listMembers(), selfNodeId);
        if (!decision.ok()) {
            ctx.status(409);
            ctx.json(errorResponse(decision.refusalCode(), decision.refusalMessage(), 409));
            return;
        }
        String mutator = ctx.attribute("username");
        try {
            plane.removeMember(selfNodeId, "graceful leave by " + mutator);
        } catch (IOException e) {
            logger.error("Raft submit failed for cluster leave: {}", e.getMessage(), e);
            ctx.status(503);
            ctx.json(errorResponse("RAFT_UNAVAILABLE", "Could not leave cluster: " + e.getMessage(), 503));
            return;
        }
        // Fence against the leave-orphan split-brain: drop a marker so that if the process is
        // auto-restarted (systemd Restart=, Docker restart policy) it refuses to re-form a rogue
        // single-node group from its now-stale Raft state instead of corrupting the live cluster.
        // Best-effort: the leave already committed; a missing marker only loses the restart guard.
        try {
            String leftClusterId =
                    view.getClusterMeta().map(meta -> meta.clusterId()).orElse("(unknown)");
            Files.writeString(
                    PrexorCloudBootstrap.LEFT_MARKER_FILE,
                    "clusterId=" + leftClusterId + " leftAt=" + Instant.now() + " by="
                            + (mutator == null ? "(unknown)" : mutator) + System.lineSeparator());
        } catch (IOException e) {
            logger.warn("could not write cluster-left fence marker (restart-orphan protection): {}", e.getMessage());
        }
        RestServer.audit(
                ctx,
                controller.stateStore(),
                "cluster.leave",
                "cluster_member",
                selfNodeId,
                Map.of("by", mutator == null ? "(unknown)" : mutator));
        logger.info("cluster leave initiated by {} for nodeId={}", mutator, selfNodeId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(
                "clusterId", view.getClusterMeta().map(meta -> meta.clusterId()).orElse(null));
        body.put("nodeId", selfNodeId);
        body.put("status", "leaving");
        ctx.status(202);
        ctx.json(body);

        // Schedule the latch trigger off the request thread so the response actually flushes.
        var exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cluster-leave-shutdown");
            t.setDaemon(true);
            return t;
        });
        exec.schedule(controller::shutdown, 1, TimeUnit.SECONDS);
        exec.shutdown();
    }

    /**
     * Pure leave-guard logic exposed for unit tests. The route applies this
     * decision and surfaces refusals as 409s; tests can assert on the codes.
     */
    static LeaveDecision decideLeavability(List<Member> members, String selfNodeId) {
        if (members.size() <= 1) {
            return new LeaveDecision(
                    false,
                    "LAST_MEMBER",
                    "cannot leave: this controller is the only cluster member. Use cluster recovery"
                            + " tooling to tear the cluster down.");
        }
        boolean selfIsMember = members.stream().anyMatch(m -> selfNodeId != null && selfNodeId.equals(m.nodeId()));
        if (!selfIsMember) {
            return new LeaveDecision(false, "NOT_A_MEMBER", "this controller is not a member of the cluster");
        }
        return new LeaveDecision(true, null, null);
    }

    record LeaveDecision(boolean ok, String refusalCode, String refusalMessage) {}

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

    private static Map<String, Object> leaseJson(Lease l) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", l.name());
        out.put("holder", l.holder());
        out.put("grantedAt", l.grantedAt());
        out.put("ttlMillis", l.ttlMillis());
        out.put("renewedAt", l.renewedAt());
        return out;
    }
}
