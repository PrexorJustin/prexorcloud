package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.api.event.CustomCloudEvent;
import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.rest.dto.WorkloadDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.middleware.WorkloadAuthFilter;
import me.prexorjustin.prexorcloud.controller.rest.sse.SseTicketManager;
import me.prexorjustin.prexorcloud.controller.state.ProxyMetrics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class ProxyRoutes {

    private final PrexorController controller;
    private final SseTicketManager ticketManager;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlayerJoinRequest(String uuid, String name, String instanceId, String group, String edition) {}

    public record PlayerLeaveRequest(String uuid) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProxyEventRequest(
            @JsonProperty("type") String type,
            @JsonProperty("data") Map<String, Object> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProxyMetricsPayload(
            @JsonProperty("proxyMemoryUsedMb") long proxyMemoryUsedMb,
            @JsonProperty("proxyMemoryMaxMb") long proxyMemoryMaxMb,
            @JsonProperty("proxyUptimeMs") long proxyUptimeMs,
            @JsonProperty("totalNetworkPlayers") int totalNetworkPlayers,
            @JsonProperty("playerPings") List<ProxyMetrics.PlayerPingSample> playerPings) {}

    public ProxyRoutes(PrexorController controller) {
        this(controller, null);
    }

    public ProxyRoutes(PrexorController controller, SseTicketManager ticketManager) {
        this.controller = controller;
        this.ticketManager = ticketManager;
    }

    public void register() {
        path("/api/proxy", () -> {
            post("/auth/refresh", this::refreshProxyToken);
            post("/events/ticket", this::createProxyEventsTicket);
            post("/ready", this::reportProxyReady);
            post("/player-join", this::reportPlayerJoin);
            post("/player-leave", this::reportPlayerLeave);
            get("/instances", this::listProxyInstances);
            get("/groups", this::listProxyGroups);
            get("/networks", this::listProxyNetworks);
            get("/players", this::listProxyPlayers);
            get("/pending-transfers", this::listPendingTransfers);
            post("/events", this::publishProxyEvent);
            post("/metrics", this::reportProxyMetrics);
            post("/transfer-ack/{uuid}", this::ackTransfer);
            get("/messages/pending", this::listPendingMessages);
            post("/messages/{id}/ack", this::ackMessage);
        });
    }

    @OpenApi(
            path = "/api/proxy/auth/refresh",
            methods = {HttpMethod.POST},
            operationId = "refreshProxyToken",
            summary = "Rotate workload token",
            tags = {"Proxy"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            responses = {@OpenApiResponse(status = "200", description = "Rotated")})
    private void refreshProxyToken(Context ctx) {
        String newToken = WorkloadRouteAuth.rotatePluginToken(ctx, controller);
        if (newToken == null) return;
        ctx.json(WorkloadDtoMapper.tokenRefreshResponse(newToken));
    }

    @OpenApi(
            path = "/api/proxy/events/ticket",
            methods = {HttpMethod.POST},
            operationId = "createProxyEventsTicket",
            summary = "Issue SSE ticket for proxy events",
            tags = {"Proxy"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            responses = {
                @OpenApiResponse(status = "200", description = "Ticket issued"),
                @OpenApiResponse(status = "503", description = "Ticket manager unavailable")
            })
    private void createProxyEventsTicket(Context ctx) {
        String instanceId = WorkloadAuthFilter.instanceId(ctx);
        if (ticketManager == null) {
            ctx.status(503);
            ctx.json(errorResponse("SERVICE_UNAVAILABLE", "SSE ticket manager is not available", 503));
            return;
        }
        ctx.json(Map.of("ticket", ticketManager.issue("workload:" + instanceId, "WORKLOAD")));
    }

    @OpenApi(
            path = "/api/proxy/ready",
            methods = {HttpMethod.POST},
            operationId = "reportProxyReady",
            summary = "Report proxy ready",
            tags = {"Proxy"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            responses = {@OpenApiResponse(status = "200", description = "OK")})
    private void reportProxyReady(Context ctx) {
        String instanceId = WorkloadRouteAuth.validateSequencedPluginToken(ctx, controller);
        if (instanceId == null) return;
        // Renewable readiness: the proxy re-asserts this on every heartbeat, so a lost one-shot
        // call or a cold-leader rebuild self-heals to RUNNING. Gated so a repeat ping never
        // un-drains or resurrects — see ClusterState#renewInstanceReadiness.
        controller.clusterState().renewInstanceReadiness(instanceId);
        ctx.json(WorkloadDtoMapper.statusResponse("ok"));
    }

    @OpenApi(
            path = "/api/proxy/player-join",
            methods = {HttpMethod.POST},
            operationId = "reportPlayerJoin",
            summary = "Report player join",
            tags = {"Proxy"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            responses = {@OpenApiResponse(status = "200", description = "OK")})
    private void reportPlayerJoin(Context ctx) {
        if (WorkloadRouteAuth.validateSequencedPluginToken(ctx, controller) == null) return;
        var req = ctx.bodyAsClass(PlayerJoinRequest.class);
        UUID playerUuid = UUID.fromString(req.uuid());
        controller
                .clusterState()
                .addPlayer(playerUuid, req.name(), req.instanceId(), req.group(), req.instanceId(), req.edition());
        ctx.json(WorkloadDtoMapper.statusResponse("ok"));
    }

    @OpenApi(
            path = "/api/proxy/player-leave",
            methods = {HttpMethod.POST},
            operationId = "reportPlayerLeave",
            summary = "Report player leave",
            tags = {"Proxy"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            responses = {@OpenApiResponse(status = "200", description = "OK")})
    private void reportPlayerLeave(Context ctx) {
        if (WorkloadRouteAuth.validateSequencedPluginToken(ctx, controller) == null) return;
        var req = ctx.bodyAsClass(PlayerLeaveRequest.class);
        UUID playerUuid = UUID.fromString(req.uuid());
        controller.clusterState().removePlayer(playerUuid);
        ctx.json(WorkloadDtoMapper.statusResponse("ok"));
    }

    @OpenApi(
            path = "/api/proxy/instances",
            methods = {HttpMethod.GET},
            operationId = "listProxyInstances",
            summary = "List backend instances visible to proxy",
            tags = {"Proxy"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            responses = {@OpenApiResponse(status = "200", description = "Instances")})
    private void listProxyInstances(Context ctx) {
        var proxyPlatforms = new java.util.HashSet<String>();
        try {
            for (var entry : controller.catalogStore().getAll()) {
                if (entry.isProxy()) proxyPlatforms.add(entry.platform().toUpperCase());
            }
        } catch (Exception _) {
        }
        var result = new ArrayList<Map<String, Object>>();
        for (var i : controller.clusterState().getAllInstances()) {
            var groupOpt = controller.groupManager().get(i.group());
            if (groupOpt.isPresent()
                    && proxyPlatforms.contains(groupOpt.get().platform().toUpperCase())) {
                continue;
            }
            String nodeAddress = controller
                    .clusterState()
                    .getNode(i.nodeId())
                    .map(n -> n.address().isBlank() ? i.nodeId() : n.address())
                    .orElse(i.nodeId());
            result.add(WorkloadDtoMapper.toInstanceDto(i, nodeAddress));
        }
        ctx.json(result);
    }

    @OpenApi(
            path = "/api/proxy/groups",
            methods = {HttpMethod.GET},
            operationId = "listProxyGroups",
            summary = "List groups visible to proxy",
            tags = {"Proxy"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            responses = {@OpenApiResponse(status = "200", description = "Groups")})
    private void listProxyGroups(Context ctx) {
        var allPlayers = controller.clusterState().getAllPlayers();
        var result = new ArrayList<Map<String, Object>>();
        for (var g : controller.groupManager().getAll()) {
            long onlineCount =
                    allPlayers.stream().filter(p -> p.group().equals(g.name())).count();
            result.add(WorkloadDtoMapper.toGroupDto(g, onlineCount));
        }
        ctx.json(result);
    }

    @OpenApi(
            path = "/api/proxy/networks",
            methods = {HttpMethod.GET},
            operationId = "listProxyNetworks",
            summary = "List networks visible to proxy",
            tags = {"Proxy"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            responses = {@OpenApiResponse(status = "200", description = "Networks")})
    private void listProxyNetworks(Context ctx) {
        ctx.json(controller.networkManager().snapshot());
    }

    @OpenApi(
            path = "/api/proxy/players",
            methods = {HttpMethod.GET},
            operationId = "listProxyPlayers",
            summary = "List players visible to proxy",
            tags = {"Proxy"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            responses = {@OpenApiResponse(status = "200", description = "Players")})
    private void listProxyPlayers(Context ctx) {
        var result = new ArrayList<Map<String, Object>>();
        for (var p : controller.clusterState().getAllPlayers()) {
            result.add(WorkloadDtoMapper.toPlayerDto(p));
        }
        ctx.json(result);
    }

    @OpenApi(
            path = "/api/proxy/pending-transfers",
            methods = {HttpMethod.GET},
            operationId = "listPendingTransfers",
            summary = "List pending transfers",
            tags = {"Proxy"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            queryParams = {@OpenApiParam(name = "proxyId", description = "Filter by proxy ID")},
            responses = {@OpenApiResponse(status = "200", description = "Pending transfers")})
    private void listPendingTransfers(Context ctx) {
        String proxyId = ctx.queryParam("proxyId");
        var pending = controller.workflowStateStore().pendingTransfers();
        var result = new ArrayList<Map<String, Object>>();
        for (var entry : pending.entrySet()) {
            if (proxyId != null) {
                var playerOpt = controller.clusterState().getPlayer(entry.getKey());
                if (playerOpt.isEmpty() || !proxyId.equals(playerOpt.get().proxyInstanceId())) {
                    continue;
                }
            }
            String targetId = entry.getValue();
            var instanceOpt = controller.clusterState().getInstance(targetId);
            if (instanceOpt.isPresent()) {
                var inst = instanceOpt.get();
                String nodeAddress = controller
                        .clusterState()
                        .getNode(inst.nodeId())
                        .map(n -> n.address().isBlank() ? inst.nodeId() : n.address())
                        .orElse(inst.nodeId());
                result.add(WorkloadDtoMapper.toPendingTransferDto(entry.getKey(), inst, nodeAddress));
            }
        }
        ctx.json(result);
    }

    @OpenApi(
            path = "/api/proxy/events",
            methods = {HttpMethod.POST},
            operationId = "publishProxyEvent",
            summary = "Publish a custom event from proxy",
            tags = {"Proxy"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            responses = {@OpenApiResponse(status = "200", description = "Event published")})
    private void publishProxyEvent(Context ctx) {
        String instanceId = WorkloadRouteAuth.validateSequencedPluginToken(ctx, controller);
        if (instanceId == null) return;
        var req = ctx.bodyAsClass(ProxyEventRequest.class);
        var event = new CustomCloudEvent(req.type(), instanceId, req.data());
        controller.eventBus().publish(event);
        ctx.json(WorkloadDtoMapper.statusResponse("ok"));
    }

    @OpenApi(
            path = "/api/proxy/metrics",
            methods = {HttpMethod.POST},
            operationId = "reportProxyMetrics",
            summary = "Report proxy metrics",
            tags = {"Proxy"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            responses = {@OpenApiResponse(status = "200", description = "OK")})
    private void reportProxyMetrics(Context ctx) {
        String instanceId = WorkloadRouteAuth.validateSequencedPluginToken(ctx, controller);
        if (instanceId == null) return;
        var payload = ctx.bodyAsClass(ProxyMetricsPayload.class);
        var metrics = new ProxyMetrics(
                instanceId,
                payload.proxyMemoryUsedMb(),
                payload.proxyMemoryMaxMb(),
                payload.proxyUptimeMs(),
                payload.totalNetworkPlayers(),
                payload.playerPings() != null ? payload.playerPings() : List.of(),
                Instant.now());
        controller.clusterState().updateProxyMetrics(metrics);
        ctx.json(WorkloadDtoMapper.statusResponse("ok"));
    }

    @OpenApi(
            path = "/api/proxy/transfer-ack/{uuid}",
            methods = {HttpMethod.POST},
            operationId = "ackTransfer",
            summary = "Acknowledge transfer",
            tags = {"Proxy"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            pathParams = {@OpenApiParam(name = "uuid", required = true)},
            responses = {
                @OpenApiResponse(status = "200", description = "OK"),
                @OpenApiResponse(status = "400", description = "Invalid UUID")
            })
    private void ackTransfer(Context ctx) {
        if (WorkloadRouteAuth.validateSequencedPluginToken(ctx, controller) == null) return;
        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(ctx.pathParam("uuid"));
        } catch (IllegalArgumentException _) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Invalid UUID format", 400));
            return;
        }
        controller.workflowStateStore().ackTransfer(playerUuid);
        ctx.json(WorkloadDtoMapper.statusResponse("ok"));
    }

    @OpenApi(
            path = "/api/proxy/messages/pending",
            methods = {HttpMethod.GET},
            operationId = "listPendingMessages",
            summary = "List pending messages for proxy",
            tags = {"Proxy"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            responses = {@OpenApiResponse(status = "200", description = "Pending messages")})
    private void listPendingMessages(Context ctx) {
        String proxyId = WorkloadAuthFilter.instanceId(ctx);
        var api = controller.moduleRegistry().messageDeliveryApi();
        if (api.isEmpty()) {
            ctx.json(java.util.List.of());
            return;
        }
        ctx.json(api.get().getPendingForProxy(proxyId));
    }

    @OpenApi(
            path = "/api/proxy/messages/{id}/ack",
            methods = {HttpMethod.POST},
            operationId = "ackMessage",
            summary = "Mark a message delivered",
            tags = {"Proxy"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {
                @OpenApiResponse(status = "200", description = "OK"),
                @OpenApiResponse(status = "503", description = "Message module not loaded")
            })
    private void ackMessage(Context ctx) {
        if (WorkloadRouteAuth.validateSequencedPluginToken(ctx, controller) == null) return;
        String messageId = ctx.pathParam("id");
        var api = controller.moduleRegistry().messageDeliveryApi();
        if (api.isEmpty()) {
            ctx.status(503);
            ctx.json(errorResponse("SERVICE_UNAVAILABLE", "Message module not loaded", 503));
            return;
        }
        api.get().markDelivered(messageId);
        ctx.json(WorkloadDtoMapper.statusResponse("ok"));
    }
}
