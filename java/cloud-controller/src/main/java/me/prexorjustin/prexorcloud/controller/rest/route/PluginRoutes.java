package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.api.event.CustomCloudEvent;
import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.rest.dto.ErrorResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.WorkloadDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.middleware.WorkloadAuthFilter;
import me.prexorjustin.prexorcloud.controller.rest.sse.SseTicketManager;
import me.prexorjustin.prexorcloud.controller.state.InstanceMetrics;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class PluginRoutes {

    private final PrexorController controller;
    private final SseTicketManager ticketManager;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PluginPlayerJoinRequest(
            @JsonProperty("uuid") String uuid,
            @JsonProperty("name") String name,
            @JsonProperty("group") String group) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PluginPlayerLeaveRequest(
            @JsonProperty("uuid") String uuid) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PluginEventRequest(
            @JsonProperty("type") String type,
            @JsonProperty("data") Map<String, Object> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PluginTransferRequest(
            @JsonProperty("playerUuid") String playerUuid,
            @JsonProperty("targetInstanceId") String targetInstanceId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PluginTransferToGroupRequest(
            @JsonProperty("playerUuid") String playerUuid,
            @JsonProperty("group") String group) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PluginSendMessageRequest(
            @JsonProperty("fromUuid") String fromUuid,
            @JsonProperty("fromName") String fromName,
            @JsonProperty("toUuid") String toUuid,
            @JsonProperty("toName") String toName,
            @JsonProperty("content") String content,
            @JsonProperty("replyToId") String replyToId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MetricsPayload(
            @JsonProperty("tps1m") double tps1m,
            @JsonProperty("tps5m") double tps5m,
            @JsonProperty("tps15m") double tps15m,
            @JsonProperty("msptAvg") double msptAvg,
            @JsonProperty("heapUsedMb") long heapUsedMb,
            @JsonProperty("heapMaxMb") long heapMaxMb,
            @JsonProperty("heapCommittedMb") long heapCommittedMb,
            @JsonProperty("gcCollections") long gcCollections,
            @JsonProperty("gcTimeMs") long gcTimeMs,
            @JsonProperty("threadCount") int threadCount,
            @JsonProperty("daemonThreadCount") int daemonThreadCount,
            @JsonProperty("playerCount") int playerCount,
            @JsonProperty("maxPlayers") int maxPlayers,
            @JsonProperty("worldCount") int worldCount,
            @JsonProperty("totalEntities") long totalEntities,
            @JsonProperty("totalChunks") long totalChunks,
            @JsonProperty("worlds") List<WorldPayload> worlds,
            @JsonProperty("serverVersion") String serverVersion,
            @JsonProperty("pluginCount") int pluginCount,
            @JsonProperty("uptimeMs") long uptimeMs) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorldPayload(
            @JsonProperty("name") String name,
            @JsonProperty("environment") String environment,
            @JsonProperty("entityCount") int entityCount,
            @JsonProperty("chunkCount") int chunkCount,
            @JsonProperty("playerCount") int playerCount) {}

    public PluginRoutes(PrexorController controller) {
        this(controller, null);
    }

    public PluginRoutes(PrexorController controller, SseTicketManager ticketManager) {
        this.controller = controller;
        this.ticketManager = ticketManager;
    }

    public void register() {
        path("/api/plugin", () -> {
            post("/auth/refresh", this::refreshPluginToken);
            post("/events/ticket", this::createPluginEventsTicket);
            post("/ready", this::reportPluginReady);
            post("/player-join", this::reportPluginPlayerJoin);
            post("/player-leave", this::reportPluginPlayerLeave);
            post("/events", this::publishPluginEvent);
            get("/instances", this::listPluginInstances);
            get("/groups", this::listPluginGroups);
            get("/players", this::listPluginPlayers);
            post("/transfer", this::queuePluginTransfer);
            post("/transfer-to-group", this::queuePluginTransferToGroup);
            post("/metrics", this::reportPluginMetrics);
            post("/message/send", this::sendPluginMessage);
        });
    }

    @OpenApi(
            path = "/api/plugin/auth/refresh",
            methods = {HttpMethod.POST},
            operationId = "refreshPluginToken",
            summary = "Rotate plugin workload token",
            tags = {"Plugin"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            responses = {@OpenApiResponse(status = "200", description = "Rotated")})
    private void refreshPluginToken(Context ctx) {
        String newToken = WorkloadRouteAuth.rotatePluginToken(ctx, controller);
        if (newToken == null) return;
        ctx.json(WorkloadDtoMapper.tokenRefreshResponse(newToken));
    }

    @OpenApi(
            path = "/api/plugin/events/ticket",
            methods = {HttpMethod.POST},
            operationId = "createPluginEventsTicket",
            summary = "Issue SSE ticket for plugin events",
            tags = {"Plugin"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            responses = {
                @OpenApiResponse(status = "200", description = "Ticket issued"),
                @OpenApiResponse(status = "503", description = "Ticket manager unavailable")
            })
    private void createPluginEventsTicket(Context ctx) {
        String instanceId = WorkloadAuthFilter.instanceId(ctx);
        if (ticketManager == null) {
            ctx.status(503);
            ctx.json(errorResponse("SERVICE_UNAVAILABLE", "SSE ticket manager is not available", 503));
            return;
        }
        ctx.json(Map.of("ticket", ticketManager.issue("workload:" + instanceId, "WORKLOAD")));
    }

    @OpenApi(
            path = "/api/plugin/ready",
            methods = {HttpMethod.POST},
            operationId = "reportPluginReady",
            summary = "Report server startup complete",
            tags = {"Plugin"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            responses = {@OpenApiResponse(status = "200", description = "OK")})
    private void reportPluginReady(Context ctx) {
        String instanceId = WorkloadRouteAuth.validateSequencedPluginToken(ctx, controller);
        if (instanceId == null) return;
        controller.clusterState().updateInstanceState(instanceId, InstanceState.RUNNING);
        ctx.json(WorkloadDtoMapper.statusResponse("ok"));
    }

    @OpenApi(
            path = "/api/plugin/player-join",
            methods = {HttpMethod.POST},
            operationId = "reportPluginPlayerJoin",
            summary = "Report player join from game server",
            tags = {"Plugin"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = PluginPlayerJoinRequest.class)}),
            responses = {@OpenApiResponse(status = "200", description = "OK")})
    private void reportPluginPlayerJoin(Context ctx) {
        String instanceId = WorkloadRouteAuth.validateSequencedPluginToken(ctx, controller);
        if (instanceId == null) return;
        var req = ctx.bodyAsClass(PluginPlayerJoinRequest.class);
        UUID playerUuid = UUID.fromString(req.uuid());
        String group = req.group() != null
                ? req.group()
                : controller
                        .clusterState()
                        .getInstance(instanceId)
                        .map(i -> i.group())
                        .orElse("unknown");
        controller.clusterState().addPlayer(playerUuid, req.name(), instanceId, group);
        ctx.json(WorkloadDtoMapper.statusResponse("ok"));
    }

    @OpenApi(
            path = "/api/plugin/player-leave",
            methods = {HttpMethod.POST},
            operationId = "reportPluginPlayerLeave",
            summary = "Report player leave from game server",
            tags = {"Plugin"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = PluginPlayerLeaveRequest.class)}),
            responses = {@OpenApiResponse(status = "200", description = "OK")})
    private void reportPluginPlayerLeave(Context ctx) {
        if (WorkloadRouteAuth.validateSequencedPluginToken(ctx, controller) == null) return;
        var req = ctx.bodyAsClass(PluginPlayerLeaveRequest.class);
        UUID playerUuid = UUID.fromString(req.uuid());
        controller.clusterState().removePlayer(playerUuid);
        ctx.json(WorkloadDtoMapper.statusResponse("ok"));
    }

    @OpenApi(
            path = "/api/plugin/events",
            methods = {HttpMethod.POST},
            operationId = "publishPluginEvent",
            summary = "Publish a custom event from game server",
            tags = {"Plugin"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = PluginEventRequest.class)}),
            responses = {@OpenApiResponse(status = "200", description = "OK")})
    private void publishPluginEvent(Context ctx) {
        String instanceId = WorkloadRouteAuth.validateSequencedPluginToken(ctx, controller);
        if (instanceId == null) return;
        var req = ctx.bodyAsClass(PluginEventRequest.class);
        var event = new CustomCloudEvent(req.type(), instanceId, req.data());
        controller.eventBus().publish(event);
        ctx.json(WorkloadDtoMapper.statusResponse("ok"));
    }

    @OpenApi(
            path = "/api/plugin/instances",
            methods = {HttpMethod.GET},
            operationId = "listPluginInstances",
            summary = "List backend instances visible to plugin",
            tags = {"Plugin"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            responses = {@OpenApiResponse(status = "200", description = "Instances")})
    private void listPluginInstances(Context ctx) {
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
            path = "/api/plugin/groups",
            methods = {HttpMethod.GET},
            operationId = "listPluginGroups",
            summary = "List groups visible to plugin",
            tags = {"Plugin"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            responses = {@OpenApiResponse(status = "200", description = "Groups")})
    private void listPluginGroups(Context ctx) {
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
            path = "/api/plugin/players",
            methods = {HttpMethod.GET},
            operationId = "listPluginPlayers",
            summary = "List players visible to plugin",
            tags = {"Plugin"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            responses = {@OpenApiResponse(status = "200", description = "Players")})
    private void listPluginPlayers(Context ctx) {
        var result = new ArrayList<Map<String, Object>>();
        for (var p : controller.clusterState().getAllPlayers()) {
            result.add(WorkloadDtoMapper.toPlayerDto(p));
        }
        ctx.json(result);
    }

    @OpenApi(
            path = "/api/plugin/transfer",
            methods = {HttpMethod.POST},
            operationId = "queuePluginTransfer",
            summary = "Queue a player transfer",
            tags = {"Plugin"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = PluginTransferRequest.class)}),
            responses = {
                @OpenApiResponse(status = "200", description = "Queued"),
                @OpenApiResponse(
                        status = "400",
                        description = "Invalid UUID",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "404",
                        description = "Player or target not found",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void queuePluginTransfer(Context ctx) {
        if (WorkloadRouteAuth.validateSequencedPluginToken(ctx, controller) == null) return;
        var req = ctx.bodyAsClass(PluginTransferRequest.class);
        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(req.playerUuid());
        } catch (IllegalArgumentException _) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Invalid player UUID", 400));
            return;
        }

        var player = controller.clusterState().getPlayer(playerUuid);
        if (player.isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Player not found", 404));
            return;
        }

        var target = controller.clusterState().getInstance(req.targetInstanceId());
        if (target.isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Target instance not found: " + req.targetInstanceId(), 404));
            return;
        }

        controller.workflowStateStore().queueTransfer(playerUuid, req.targetInstanceId());
        ctx.json(WorkloadDtoMapper.transferQueuedResponse(playerUuid, req.targetInstanceId()));
    }

    @OpenApi(
            path = "/api/plugin/transfer-to-group",
            methods = {HttpMethod.POST},
            operationId = "queuePluginTransferToGroup",
            summary = "Queue a group-based transfer",
            tags = {"Plugin"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = PluginTransferToGroupRequest.class)}),
            responses = {
                @OpenApiResponse(status = "200", description = "Queued"),
                @OpenApiResponse(
                        status = "404",
                        description = "Player or group not found",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "409",
                        description = "No running instances in group",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void queuePluginTransferToGroup(Context ctx) {
        if (WorkloadRouteAuth.validateSequencedPluginToken(ctx, controller) == null) return;
        var req = ctx.bodyAsClass(PluginTransferToGroupRequest.class);
        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(req.playerUuid());
        } catch (IllegalArgumentException _) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Invalid player UUID", 400));
            return;
        }

        var player = controller.clusterState().getPlayer(playerUuid);
        if (player.isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Player not found", 404));
            return;
        }

        var group = controller.groupManager().get(req.group());
        if (group.isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Group not found: " + req.group(), 404));
            return;
        }

        var target = controller.clusterState().getAllInstances().stream()
                .filter(i -> i.group().equals(req.group()) && i.state() == InstanceState.RUNNING)
                .min(Comparator.comparingInt(i -> i.playerCount()));

        if (target.isEmpty()) {
            ctx.status(409);
            ctx.json(errorResponse("CONFLICT", "No running instances in group: " + req.group(), 409));
            return;
        }

        String targetInstanceId = target.get().id();
        controller.workflowStateStore().queueTransfer(playerUuid, targetInstanceId);
        ctx.json(WorkloadDtoMapper.transferQueuedResponse(playerUuid, targetInstanceId));
    }

    @OpenApi(
            path = "/api/plugin/metrics",
            methods = {HttpMethod.POST},
            operationId = "reportPluginMetrics",
            summary = "Report periodic metrics snapshot",
            tags = {"Plugin"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = MetricsPayload.class)}),
            responses = {@OpenApiResponse(status = "200", description = "OK")})
    private void reportPluginMetrics(Context ctx) {
        String instanceId = WorkloadRouteAuth.validateSequencedPluginToken(ctx, controller);
        if (instanceId == null) return;
        var payload = ctx.bodyAsClass(MetricsPayload.class);

        List<InstanceMetrics.WorldSnapshot> worlds = payload.worlds() == null
                ? List.of()
                : payload.worlds().stream()
                        .map(w -> new InstanceMetrics.WorldSnapshot(
                                w.name(), w.environment(), w.entityCount(), w.chunkCount(), w.playerCount()))
                        .toList();

        var metrics = new InstanceMetrics(
                instanceId,
                payload.tps1m(),
                payload.tps5m(),
                payload.tps15m(),
                payload.msptAvg(),
                payload.heapUsedMb(),
                payload.heapMaxMb(),
                payload.heapCommittedMb(),
                payload.gcCollections(),
                payload.gcTimeMs(),
                payload.threadCount(),
                payload.daemonThreadCount(),
                payload.playerCount(),
                payload.maxPlayers(),
                payload.worldCount(),
                payload.totalEntities(),
                payload.totalChunks(),
                worlds,
                payload.serverVersion(),
                payload.pluginCount(),
                payload.uptimeMs(),
                Instant.now());

        controller.clusterState().updateInstanceMetrics(metrics);
        ctx.json(WorkloadDtoMapper.statusResponse("ok"));
    }

    @OpenApi(
            path = "/api/plugin/message/send",
            methods = {HttpMethod.POST},
            operationId = "sendPluginMessage",
            summary = "Send a /msg from game server",
            tags = {"Plugin"},
            security = {@OpenApiSecurity(name = "workloadToken")},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = PluginSendMessageRequest.class)}),
            responses = {
                @OpenApiResponse(status = "201", description = "Message queued"),
                @OpenApiResponse(
                        status = "400",
                        description = "Validation error",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "403",
                        description = "Recipient has blocked sender",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "503",
                        description = "Message module not loaded",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void sendPluginMessage(Context ctx) {
        if (WorkloadRouteAuth.validateSequencedPluginToken(ctx, controller) == null) return;
        var req = ctx.bodyAsClass(PluginSendMessageRequest.class);
        if (req.fromUuid() == null
                || req.toUuid() == null
                || req.content() == null
                || req.content().isBlank()) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "fromUuid, toUuid and content are required", 400));
            return;
        }

        var api = controller.moduleRegistry().messageDeliveryApi();
        if (api.isEmpty()) {
            ctx.status(503);
            ctx.json(errorResponse("SERVICE_UNAVAILABLE", "Message module not loaded", 503));
            return;
        }

        UUID fromUuid, toUuid;
        try {
            fromUuid = UUID.fromString(req.fromUuid());
            toUuid = UUID.fromString(req.toUuid());
        } catch (IllegalArgumentException _) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Invalid UUID", 400));
            return;
        }

        if (api.get().isBlocked(fromUuid, toUuid)) {
            ctx.status(403);
            ctx.json(errorResponse("BLOCKED", "Recipient has blocked this sender", 403));
            return;
        }

        String fromName =
                req.fromName() != null ? req.fromName() : req.fromUuid().substring(0, 8);
        String toName = req.toName() != null ? req.toName() : req.toUuid().substring(0, 8);

        String id = api.get().sendMessage(fromUuid, fromName, toUuid, toName, req.content(), req.replyToId());
        ctx.status(201);
        ctx.json(WorkloadDtoMapper.messageQueuedResponse(id));
    }
}
