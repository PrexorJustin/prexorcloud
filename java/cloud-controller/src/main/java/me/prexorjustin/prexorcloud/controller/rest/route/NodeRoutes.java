package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.audit;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.requireFound;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import me.prexorjustin.prexorcloud.api.event.events.NodeDrainRequestedEvent;
import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.rest.ApiResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.ErrorResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.NodeCacheDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.dto.NodeDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;
import me.prexorjustin.prexorcloud.controller.runtime.RuntimeServices;
import me.prexorjustin.prexorcloud.controller.security.NodeCertificateRevocationStore;
import me.prexorjustin.prexorcloud.controller.state.NodeState;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.protocol.ControllerMessage;
import me.prexorjustin.prexorcloud.protocol.RequestCacheStatus;
import me.prexorjustin.prexorcloud.protocol.ShutdownNode;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class NodeRoutes {

    private final PrexorController controller;
    private final NodeCertificateRevocationStore revocationStore;

    public NodeRoutes(PrexorController controller, RuntimeServices runtime) {
        this.controller = controller;
        this.revocationStore = runtime.nodeCertRevocationStore();
    }

    public void register() {
        path("/api/v1/nodes", () -> {
            get(this::listNodes);
            get("/{id}", this::getNode);
            delete("/{id}", this::deleteNode);
            post("/{id}/drain", this::drainNode);
            post("/{id}/shutdown", this::shutdownNode);
            post("/{id}/undrain", this::undrainNode);
            post("/{id}/cordon", this::cordonNode);
            post("/{id}/uncordon", this::uncordonNode);
            get("/{id}/cache", this::getNodeCache);
            post("/{id}/cache/refresh", this::refreshNodeCache);
            post("/{id}/cache/warm", this::warmNodeCache);
            post("/{id}/revoke-cert", this::revokeNodeCert);
            post("/{id}/unrevoke-cert", this::unrevokeNodeCert);
            get("/revoked-certs", this::listRevokedCerts);
        });
    }

    @OpenApi(
            path = "/api/v1/nodes",
            methods = {HttpMethod.GET},
            operationId = "listNodes",
            summary = "List nodes",
            description = "Returns connected, registered-disconnected, and pending (active join token) nodes.",
            tags = {"Nodes"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {
                @OpenApiResponse(status = "200", description = "Node list"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden")
            })
    private void listNodes(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.NODES_VIEW);

        var results = new ArrayList<Map<String, Object>>();

        var registeredMap = controller.stateStore().getAllRegisteredNodes().stream()
                .collect(Collectors.toMap(StateStore.RegisteredNode::nodeId, r -> r));

        Set<String> connectedIds = new HashSet<>();
        for (var node : controller.clusterState().getAllNodes()) {
            connectedIds.add(node.nodeId());
            var dto = NodeDtoMapper.toConnectedDto(node, registeredMap.get(node.nodeId()));
            results.add(dto);
        }

        for (var reg : registeredMap.values()) {
            if (!connectedIds.contains(reg.nodeId())) {
                results.add(NodeDtoMapper.toDisconnectedDto(reg));
            }
        }

        for (var token : controller.joinTokenStore().list()) {
            if (!token.isExpired()
                    && !connectedIds.contains(token.nodeId())
                    && !registeredMap.containsKey(token.nodeId())) {
                results.add(NodeDtoMapper.toPendingDto(token));
            }
        }

        ApiResponse.writeList(ctx, results, 500);
    }

    @OpenApi(
            path = "/api/v1/nodes/{id}",
            methods = {HttpMethod.GET},
            operationId = "getNode",
            summary = "Get node by ID",
            tags = {"Nodes"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {
                @OpenApiResponse(status = "200", description = "Node detail"),
                @OpenApiResponse(
                        status = "404",
                        description = "Node not found",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden")
            })
    private void getNode(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.NODES_VIEW);
        String id = ctx.pathParam("id");

        var node = controller.clusterState().getNode(id);
        if (node.isPresent()) {
            var reg = controller.stateStore().getRegisteredNode(id).orElse(null);
            ctx.json(NodeDtoMapper.toConnectedDto(node.get(), reg));
            return;
        }

        var reg = controller.stateStore().getRegisteredNode(id);
        if (reg.isPresent()) {
            ctx.json(NodeDtoMapper.toDisconnectedDto(reg.get()));
            return;
        }

        var pendingToken = controller.joinTokenStore().list().stream()
                .filter(t -> !t.isExpired() && t.nodeId().equals(id))
                .findFirst();
        if (pendingToken.isPresent()) {
            ctx.json(NodeDtoMapper.toPendingDto(pendingToken.get()));
            return;
        }

        ctx.status(404);
        ctx.json(errorResponse("NOT_FOUND", "Node not found: " + id, 404));
    }

    @OpenApi(
            path = "/api/v1/nodes/{id}",
            methods = {HttpMethod.DELETE},
            operationId = "deleteNode",
            summary = "Delete (unregister) a disconnected node",
            tags = {"Nodes"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {
                @OpenApiResponse(status = "204", description = "No Content"),
                @OpenApiResponse(
                        status = "409",
                        description = "Cannot delete a connected node",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden")
            })
    private void deleteNode(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.NODES_DRAIN);
        String id = ctx.pathParam("id");

        if (controller.clusterState().getNode(id).isPresent()) {
            ctx.status(409);
            ctx.json(errorResponse("CONFLICT", "Cannot delete a connected node", 409));
            return;
        }

        controller.stateStore().deleteRegisteredNode(id);
        audit(ctx, controller.stateStore(), "node.delete", "node", id);
        ctx.status(204);
    }

    @OpenApi(
            path = "/api/v1/nodes/{id}/drain",
            methods = {HttpMethod.POST},
            operationId = "drainNode",
            summary = "Drain a node",
            tags = {"Nodes"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            queryParams = {
                @OpenApiParam(
                        name = "shutdown",
                        description = "Whether the node shuts down after drain. Default true."),
                @OpenApiParam(
                        name = "timeout",
                        type = Integer.class,
                        description = "Drain timeout seconds (default 60)."),
                @OpenApiParam(name = "kickMessage", description = "Message shown when players are kicked.")
            },
            responses = {
                @OpenApiResponse(status = "200", description = "Drain requested"),
                @OpenApiResponse(status = "404", description = "Node not found")
            })
    private void drainNode(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.NODES_DRAIN);
        String id = ctx.pathParam("id");
        requireFound(controller.clusterState().getNode(id), "Node", id);
        boolean shutdown = !"false".equalsIgnoreCase(ctx.queryParam("shutdown"));
        int drainTimeout = ctx.queryParamAsClass("timeout", Integer.class).getOrDefault(60);
        String kickMessage = ctx.queryParam("kickMessage");
        if (kickMessage == null || kickMessage.isBlank()) {
            kickMessage = "This server is shutting down for maintenance.";
        }
        controller.clusterState().setNodeStatus(id, NodeState.NodeStatus.DRAINING);
        controller
                .eventBus()
                .publish(new NodeDrainRequestedEvent(id, shutdown, drainTimeout, kickMessage, Instant.now()));
        audit(
                ctx,
                controller.stateStore(),
                "node.drain",
                "node",
                id,
                Map.of("shutdown", shutdown, "timeout", drainTimeout));
        ctx.json(NodeDtoMapper.nodeDrainDto("DRAINING", shutdown, drainTimeout, kickMessage));
    }

    @OpenApi(
            path = "/api/v1/nodes/{id}/shutdown",
            methods = {HttpMethod.POST},
            operationId = "shutdownNode",
            summary = "Immediately stop a node's daemon",
            description =
                    "Sends a ShutdownNode command straight to the daemon — no drain. The daemon stops its"
                            + " running instances and exits; the scheduler reschedules them onto other nodes if"
                            + " capacity allows. Use `node drain` instead for a graceful, instance-preserving stop.",
            tags = {"Nodes"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {
                @OpenApiResponse(status = "202", description = "Shutdown command sent"),
                @OpenApiResponse(status = "404", description = "Node not found"),
                @OpenApiResponse(status = "409", description = "Node not currently connected")
            })
    private void shutdownNode(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.NODES_SHUTDOWN);
        String id = ctx.pathParam("id");
        requireFound(controller.clusterState().getNode(id), "Node", id);

        String reason = ctx.queryParam("reason");
        if (reason == null || reason.isBlank()) {
            reason = "Operator requested immediate shutdown";
        }

        var session = controller.sessionManager().getByNodeId(id);
        if (session.isEmpty()) {
            ctx.status(409);
            ctx.json(errorResponse("NODE_NOT_CONNECTED", "Node " + id + " is not currently connected", 409));
            return;
        }

        session.get()
                .send(ControllerMessage.newBuilder()
                        .setShutdownNode(ShutdownNode.newBuilder().setReason(reason))
                        .build());
        controller.clusterState().setNodeStatus(id, NodeState.NodeStatus.DRAINING);
        audit(ctx, controller.stateStore(), "node.shutdown", "node", id, Map.of("reason", reason));
        ctx.status(202);
        ctx.json(NodeDtoMapper.nodeStatusDto("STOPPING"));
    }

    @OpenApi(
            path = "/api/v1/nodes/{id}/undrain",
            methods = {HttpMethod.POST},
            operationId = "undrainNode",
            summary = "Cancel drain on a node",
            tags = {"Nodes"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {@OpenApiResponse(status = "200", description = "Undrain accepted")})
    private void undrainNode(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.NODES_DRAIN);
        String id = ctx.pathParam("id");
        requireFound(controller.clusterState().getNode(id), "Node", id);
        controller.clusterState().setNodeStatus(id, NodeState.NodeStatus.ONLINE);
        audit(ctx, controller.stateStore(), "node.undrain", "node", id);
        ctx.json(NodeDtoMapper.nodeStatusDto("ONLINE"));
    }

    @OpenApi(
            path = "/api/v1/nodes/{id}/cordon",
            methods = {HttpMethod.POST},
            operationId = "cordonNode",
            summary = "Cordon a node (no new schedules, existing instances stay)",
            tags = {"Nodes"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {@OpenApiResponse(status = "200", description = "Cordoned")})
    private void cordonNode(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.NODES_DRAIN);
        String id = ctx.pathParam("id");
        requireFound(controller.clusterState().getNode(id), "Node", id);
        controller.clusterState().setNodeStatus(id, NodeState.NodeStatus.CORDONED);
        audit(ctx, controller.stateStore(), "node.cordon", "node", id);
        ctx.json(NodeDtoMapper.nodeStatusDto("CORDONED"));
    }

    @OpenApi(
            path = "/api/v1/nodes/{id}/uncordon",
            methods = {HttpMethod.POST},
            operationId = "uncordonNode",
            summary = "Uncordon a node",
            tags = {"Nodes"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {@OpenApiResponse(status = "200", description = "Uncordoned")})
    private void uncordonNode(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.NODES_DRAIN);
        String id = ctx.pathParam("id");
        requireFound(controller.clusterState().getNode(id), "Node", id);
        controller.clusterState().setNodeStatus(id, NodeState.NodeStatus.ONLINE);
        audit(ctx, controller.stateStore(), "node.uncordon", "node", id);
        ctx.json(NodeDtoMapper.nodeStatusDto("ONLINE"));
    }

    @OpenApi(
            path = "/api/v1/nodes/{id}/cache",
            methods = {HttpMethod.GET},
            operationId = "getNodeCache",
            summary = "Get node cache status",
            tags = {"Nodes"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {@OpenApiResponse(status = "200", description = "Cache status")})
    private void getNodeCache(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.NODES_VIEW);
        String id = ctx.pathParam("id");
        var cacheStatus = controller.clusterState().getCacheStatus(id);
        if (cacheStatus.isEmpty()) {
            ctx.json(NodeCacheDtoMapper.emptyDto());
            return;
        }
        ctx.json(NodeCacheDtoMapper.toDto(cacheStatus.get()));
    }

    @OpenApi(
            path = "/api/v1/nodes/{id}/cache/refresh",
            methods = {HttpMethod.POST},
            operationId = "refreshNodeCache",
            summary = "Request cache status refresh",
            tags = {"Nodes"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {
                @OpenApiResponse(status = "202", description = "Refresh requested"),
                @OpenApiResponse(status = "404", description = "Node session not found")
            })
    private void refreshNodeCache(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.NODES_VIEW);
        String id = ctx.pathParam("id");
        var session = requireFound(controller.sessionManager().getByNodeId(id), "Node session", id);
        session.send(ControllerMessage.newBuilder()
                .setRequestCacheStatus(RequestCacheStatus.newBuilder())
                .build());
        ctx.status(202);
        ctx.json(NodeDtoMapper.nodeStatusDto("REQUESTED"));
    }

    @OpenApi(
            path = "/api/v1/nodes/{id}/cache/warm",
            methods = {HttpMethod.POST},
            operationId = "warmNodeCache",
            summary = "Pre-warm node cache",
            tags = {"Nodes"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {
                @OpenApiResponse(status = "202", description = "Warming requested"),
                @OpenApiResponse(status = "404", description = "Node session not found")
            })
    private void warmNodeCache(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.NODES_DRAIN);
        String id = ctx.pathParam("id");
        var session = requireFound(controller.sessionManager().getByNodeId(id), "Node session", id);
        controller.sendPreWarmToNode(session);
        ctx.status(202);
        ctx.json(NodeDtoMapper.nodeStatusDto("WARMING"));
    }

    @OpenApi(
            path = "/api/v1/nodes/{id}/revoke-cert",
            methods = {HttpMethod.POST},
            operationId = "revokeNodeCert",
            summary = "Revoke a node's certificate",
            tags = {"Nodes"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            queryParams = {@OpenApiParam(name = "ttlDays", type = Integer.class, description = "Revocation TTL in days")
            },
            responses = {@OpenApiResponse(status = "200", description = "Revoked")})
    private void revokeNodeCert(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.NODES_REVOKE_CERT);
        String id = ctx.pathParam("id");
        int days = ctx.queryParamAsClass("ttlDays", Integer.class).getOrDefault(365);
        Duration ttl = Duration.ofDays(Math.max(1, days));
        revocationStore.revoke(null, id, ttl);
        audit(ctx, controller.stateStore(), "node.revoke-cert", "node", id, Map.of("ttlDays", days));
        ctx.json(Map.of("nodeId", id, "revoked", true, "ttlDays", days));
    }

    @OpenApi(
            path = "/api/v1/nodes/{id}/unrevoke-cert",
            methods = {HttpMethod.POST},
            operationId = "unrevokeNodeCert",
            summary = "Unrevoke a node's certificate",
            tags = {"Nodes"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {@OpenApiResponse(status = "200", description = "Unrevoked")})
    private void unrevokeNodeCert(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.NODES_REVOKE_CERT);
        String id = ctx.pathParam("id");
        revocationStore.unrevoke(null, id);
        audit(ctx, controller.stateStore(), "node.unrevoke-cert", "node", id);
        ctx.json(Map.of("nodeId", id, "revoked", false));
    }

    @OpenApi(
            path = "/api/v1/nodes/revoked-certs",
            methods = {HttpMethod.GET},
            operationId = "listRevokedCerts",
            summary = "List revoked subject CNs",
            tags = {"Nodes"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {@OpenApiResponse(status = "200", description = "Revoked CNs")})
    private void listRevokedCerts(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.NODES_REVOKE_CERT);
        ctx.json(Map.of("revokedCns", revocationStore.revokedSubjectCns()));
    }
}
