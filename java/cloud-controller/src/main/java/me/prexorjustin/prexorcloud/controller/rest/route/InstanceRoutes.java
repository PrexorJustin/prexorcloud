package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.audit;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.requireFound;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

import me.prexorjustin.prexorcloud.common.util.InputValidator;
import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.rest.ApiResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.ActionDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.dto.CommandRequest;
import me.prexorjustin.prexorcloud.controller.rest.dto.ErrorResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.InstanceDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.dto.StatusOnlyResponse;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;
import me.prexorjustin.prexorcloud.controller.session.NodeSessionManager;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.protocol.ControllerMessage;
import me.prexorjustin.prexorcloud.protocol.SendCommand;
import me.prexorjustin.prexorcloud.protocol.StopInstance;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class InstanceRoutes {

    // Substring anchor for scripts/check-openapi-routes.sh
    @SuppressWarnings("unused")
    private static final String P_SERVICE_CONSOLE = "/api/v1/services/{id}/console";

    private static final int CONSOLE_HISTORY_DEFAULT_LIMIT = 1000;
    private static final int CONSOLE_HISTORY_MAX_LIMIT = 10000;

    private final PrexorController controller;

    public InstanceRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        path("/api/v1/services", () -> {
            get(this::listInstances);
            get("/{id}", this::getInstance);
            get("/{id}/composition", this::getInstanceComposition);
            post("/{id}/stop", this::stopInstance);
            post("/{id}/force-stop", this::forceStopInstance);
            get("/{id}/metrics", this::getInstanceMetrics);
            get("/{id}/console/history", this::getConsoleHistory);
            post("/{id}/console/share", this::shareInstanceConsole);
            get("/{id}/files/content", this::readInstanceFile);
            get("/{id}/proxy-metrics", this::getProxyMetrics);
            delete("/{id}", this::deleteInstance);
            post("/{id}/command", this::sendInstanceCommand);
        });
    }

    @OpenApi(
            path = "/api/v1/services",
            methods = {HttpMethod.GET},
            operationId = "listInstances",
            summary = "List instances",
            description = "Returns all instances, optionally filtered by group, state, or node.",
            tags = {"Instances"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            queryParams = {
                @OpenApiParam(name = "page", type = Integer.class),
                @OpenApiParam(name = "pageSize", type = Integer.class),
                @OpenApiParam(name = "group", description = "Filter by group"),
                @OpenApiParam(
                        name = "state",
                        description = "Filter by state (SCHEDULED/STARTING/RUNNING/STOPPING/STOPPED/CRASHED)"),
                @OpenApiParam(name = "node", description = "Filter by node ID")
            },
            responses = {
                @OpenApiResponse(status = "200", description = "Instance list"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden")
            })
    private void listInstances(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.INSTANCES_VIEW);
        String group = ctx.queryParam("group");
        String state = ctx.queryParam("state");
        String node = ctx.queryParam("node");

        var instances = controller.clusterState().getAllInstances().stream()
                .filter(i -> group == null || i.group().equals(group))
                .filter(i -> state == null || i.state().name().equals(state))
                .filter(i -> node == null || i.nodeId().equals(node))
                .map(InstanceDtoMapper::toDto)
                .toList();
        ApiResponse.writeList(ctx, instances, 500);
    }

    @OpenApi(
            path = "/api/v1/services/{id}",
            methods = {HttpMethod.GET},
            operationId = "getInstance",
            summary = "Get instance by ID",
            tags = {"Instances"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {
                @OpenApiResponse(status = "200", description = "Instance detail"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Instance not found")
            })
    private void getInstance(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.INSTANCES_VIEW);
        String id = ctx.pathParam("id");
        ctx.json(InstanceDtoMapper.toDto(requireFound(controller.clusterState().getInstance(id), "Instance", id)));
    }

    @OpenApi(
            path = "/api/v1/services/{id}/composition",
            methods = {HttpMethod.GET},
            operationId = "getInstanceComposition",
            summary = "Get instance composition plan",
            tags = {"Instances"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {
                @OpenApiResponse(status = "200", description = "Composition plan"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Not found")
            })
    private void getInstanceComposition(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.INSTANCES_VIEW);
        String id = ctx.pathParam("id");
        ctx.json(requireFound(controller.stateStore().getInstanceCompositionPlan(id), "Instance composition", id));
    }

    @OpenApi(
            path = "/api/v1/services/{id}/stop",
            methods = {HttpMethod.POST},
            operationId = "stopInstance",
            summary = "Graceful stop",
            tags = {"Instances"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Stop scheduled",
                        content = {@OpenApiContent(from = StatusOnlyResponse.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Instance not found")
            })
    private void stopInstance(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.INSTANCES_STOP);
        String id = ctx.pathParam("id");
        var instance = requireFound(controller.clusterState().getInstance(id), "Instance", id);
        sendStopCommand(controller.sessionManager(), instance, false);
        audit(ctx, controller.stateStore(), "instance.stop", "instance", id);
        ctx.json(ActionDtoMapper.statusResponse("stopping"));
    }

    @OpenApi(
            path = "/api/v1/services/{id}/force-stop",
            methods = {HttpMethod.POST},
            operationId = "forceStopInstance",
            summary = "Force stop",
            tags = {"Instances"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Force-stop scheduled",
                        content = {@OpenApiContent(from = StatusOnlyResponse.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Instance not found")
            })
    private void forceStopInstance(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.INSTANCES_STOP);
        String id = ctx.pathParam("id");
        var instance = requireFound(controller.clusterState().getInstance(id), "Instance", id);
        sendStopCommand(controller.sessionManager(), instance, true);
        audit(ctx, controller.stateStore(), "instance.force-stop", "instance", id);
        ctx.json(ActionDtoMapper.statusResponse("force-stopping"));
    }

    @OpenApi(
            path = "/api/v1/services/{id}/metrics",
            methods = {HttpMethod.GET},
            operationId = "getInstanceMetricsSnapshot",
            summary = "Get instance metrics snapshot",
            tags = {"Instances"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {
                @OpenApiResponse(status = "200", description = "Metrics snapshot"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Metrics not found")
            })
    private void getInstanceMetrics(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.INSTANCES_VIEW);
        String id = ctx.pathParam("id");
        ctx.json(requireFound(controller.clusterState().getInstanceMetrics(id), "Instance metrics", id));
    }

    @OpenApi(
            path = "/api/v1/services/{id}/console/history",
            methods = {HttpMethod.GET},
            operationId = "getConsoleHistory",
            summary = "Get console history",
            description =
                    "Returns console history for the instance, optionally filtered by `since`/`until` (ISO-8601).",
            tags = {"Instances"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            queryParams = {
                @OpenApiParam(name = "since", description = "ISO-8601 start instant"),
                @OpenApiParam(name = "until", description = "ISO-8601 end instant"),
                @OpenApiParam(name = "limit", type = Integer.class, description = "Max lines (clamped to 10000)")
            },
            responses = {
                @OpenApiResponse(status = "200", description = "Console lines"),
                @OpenApiResponse(
                        status = "400",
                        description = "Invalid since/until",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Instance not found")
            })
    @OpenApi(
            path = "/api/v1/services/{id}/files/content",
            methods = {HttpMethod.GET},
            operationId = "readInstanceFile",
            summary = "Read a bounded slice of a single file under an instance directory",
            description =
                    "Returns up to `maxBytes` of a single file (head by default; `tail=true` for the last bytes). Path-traversal is rejected daemon-side; symlinks are never followed.",
            tags = {"Instances"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            queryParams = {
                @OpenApiParam(
                        name = "path",
                        required = true,
                        description = "Relative path under the instance working directory"),
                @OpenApiParam(
                        name = "maxBytes",
                        type = Integer.class,
                        description = "Cap on bytes returned (default 64 KiB, ceiling 1 MiB)"),
                @OpenApiParam(
                        name = "tail",
                        type = Boolean.class,
                        description = "Return the LAST maxBytes instead of the first")
            },
            responses = {
                @OpenApiResponse(status = "200", description = "File contents"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Instance or file not found")
            })
    private void readInstanceFile(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.INSTANCES_CONSOLE);
        String id = ctx.pathParam("id");
        var instance = requireFound(controller.clusterState().getInstance(id), "Instance", id);
        String path = ctx.queryParam("path");
        if (path == null || path.isBlank()) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Query parameter 'path' is required", 400));
            return;
        }
        int maxBytes = ctx.queryParamAsClass("maxBytes", Integer.class).getOrDefault(0);
        boolean tail =
                Boolean.parseBoolean(ctx.queryParamAsClass("tail", String.class).getOrDefault("false"));

        var service = controller.instanceFileContentService();
        if (service == null) {
            ctx.status(503);
            ctx.json(errorResponse("UNAVAILABLE", "Instance file content service not wired", 503));
            return;
        }
        var result = service.read(instance.nodeId(), instance.group(), id, path, maxBytes, tail);
        if (!result.error().isEmpty()) {
            String code = result.error();
            int httpStatus =
                    switch (code) {
                        case "FILE_NOT_FOUND", "INSTANCE_NOT_FOUND" -> 404;
                        case "NOT_REGULAR_FILE", "PATH_OUTSIDE_INSTANCE", "INVALID_REQUEST" -> 400;
                        case "DAEMON_UNREACHABLE", "TIMEOUT" -> 503;
                        default -> 500;
                    };
            ctx.status(httpStatus);
            ctx.json(errorResponse(code, "File read failed: " + code, httpStatus));
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", result.content());
        body.put("totalSizeBytes", result.totalSizeBytes());
        body.put("truncated", result.truncated());
        ctx.json(body);
    }

    @OpenApi(
            path = "/api/v1/services/{id}/console/share",
            methods = {HttpMethod.POST},
            operationId = "shareInstanceConsole",
            summary = "Share instance console history via pste",
            description =
                    "Uploads the recent console history of the instance to the configured paste service after server-side redaction. Limit defaults to 500 lines (clamped to 5000).",
            tags = {"Instances"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            requestBody =
                    @io.javalin.openapi.OpenApiRequestBody(
                            content = {
                                @OpenApiContent(
                                        from = me.prexorjustin.prexorcloud.controller.rest.dto.ShareLogRequestDto.class)
                            }),
            responses = {
                @OpenApiResponse(
                        status = "201",
                        description = "Paste created",
                        content = {
                            @OpenApiContent(from = me.prexorjustin.prexorcloud.controller.rest.dto.ShareResultDto.class)
                        }),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Instance not found"),
                @OpenApiResponse(status = "409", description = "Sharing not configured"),
                @OpenApiResponse(status = "502", description = "Paste service unreachable")
            })
    private void shareInstanceConsole(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.INSTANCES_CONSOLE);
        JwtAuthMiddleware.requirePermission(ctx, Permission.SHARE_INVOKE);
        String id = ctx.pathParam("id");
        requireFound(controller.clusterState().getInstance(id), "Instance", id);
        var dto = parseShareLogRequest(ctx);
        int limit = clampShareLimit(dto.limit());
        var history = controller.stateStore().getConsoleHistory(id, null, null, limit);
        java.util.List<String> lines = history.stream()
                .map(rec -> rec.timestamp().toString() + " " + rec.line())
                .toList();
        String username = ctx.attribute("username");
        var result = controller
                .shareService()
                .shareLogText(
                        me.prexorjustin.prexorcloud.controller.share.ShareKind.INSTANCE_CONSOLE,
                        id,
                        "console-" + id,
                        lines,
                        toShareServiceRequest(dto),
                        me.prexorjustin.prexorcloud.controller.share.ShareContext.of(username, ctx.ip()));
        ctx.status(201).json(me.prexorjustin.prexorcloud.controller.rest.dto.ShareResultDto.from(result));
    }

    private static me.prexorjustin.prexorcloud.controller.rest.dto.ShareLogRequestDto parseShareLogRequest(
            Context ctx) {
        String body = ctx.body();
        if (body == null || body.isBlank()) {
            return new me.prexorjustin.prexorcloud.controller.rest.dto.ShareLogRequestDto(
                    null, null, null, null, null, null);
        }
        return ctx.bodyAsClass(me.prexorjustin.prexorcloud.controller.rest.dto.ShareLogRequestDto.class);
    }

    private static me.prexorjustin.prexorcloud.controller.share.ShareRequest toShareServiceRequest(
            me.prexorjustin.prexorcloud.controller.rest.dto.ShareLogRequestDto dto) {
        if (dto == null) return me.prexorjustin.prexorcloud.controller.share.ShareRequest.empty();
        return new me.prexorjustin.prexorcloud.controller.share.ShareRequest(
                dto.expiry(), dto.isPrivate(), dto.burnAfterRead());
    }

    private static int clampShareLimit(Integer requested) {
        if (requested == null || requested <= 0) return 500;
        return Math.min(requested, 5000);
    }

    private void getConsoleHistory(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.INSTANCES_CONSOLE);
        String id = ctx.pathParam("id");
        requireFound(controller.clusterState().getInstance(id), "Instance", id);

        Instant since;
        Instant until;
        try {
            since = parseInstantQuery(ctx.queryParam("since"));
            until = parseInstantQuery(ctx.queryParam("until"));
        } catch (DateTimeParseException e) {
            ctx.status(400);
            ctx.json(errorResponse(
                    "BAD_REQUEST", "Invalid 'since'/'until' query parameter (expected ISO-8601 instant)", 400));
            return;
        }
        int limit = parseHistoryLimit(ctx.queryParam("limit"));

        var lines = controller.stateStore().getConsoleHistory(id, since, until, limit + 1);
        boolean truncated = lines.size() > limit;
        var capped = truncated ? lines.subList(0, limit) : lines;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(
                "lines",
                capped.stream()
                        .map(rec -> Map.of("ts", rec.timestamp().toString(), "line", rec.line()))
                        .toList());
        body.put("truncated", truncated);
        ctx.json(body);
    }

    @OpenApi(
            path = "/api/v1/services/{id}/proxy-metrics",
            methods = {HttpMethod.GET},
            operationId = "getProxyMetrics",
            summary = "Get proxy metrics",
            description = "Returns proxy-level metrics for the instance.",
            tags = {"Instances"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {
                @OpenApiResponse(status = "200", description = "Proxy metrics"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Not found")
            })
    private void getProxyMetrics(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.INSTANCES_VIEW);
        String id = ctx.pathParam("id");
        ctx.json(requireFound(controller.clusterState().getProxyMetrics(id), "Instance proxy metrics", id));
    }

    @OpenApi(
            path = "/api/v1/services/{id}",
            methods = {HttpMethod.DELETE},
            operationId = "deleteInstance",
            summary = "Delete instance",
            description = "Removes a STOPPED, CRASHED, or SCHEDULED instance.",
            tags = {"Instances"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Deleted",
                        content = {@OpenApiContent(from = StatusOnlyResponse.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Instance not found"),
                @OpenApiResponse(
                        status = "409",
                        description = "Instance not in a deletable state",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void deleteInstance(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.INSTANCES_DELETE);
        String id = ctx.pathParam("id");
        var instance = requireFound(controller.clusterState().getInstance(id), "Instance", id);
        var state = instance.state();
        if (state != me.prexorjustin.prexorcloud.protocol.InstanceState.STOPPED
                && state != me.prexorjustin.prexorcloud.protocol.InstanceState.CRASHED
                && state != me.prexorjustin.prexorcloud.protocol.InstanceState.SCHEDULED) {
            ctx.status(409);
            ctx.json(errorResponse(
                    "CONFLICT", "Cannot delete instance in state " + state.name() + " — stop it first", 409));
            return;
        }
        controller.clusterState().removeInstance(id);
        controller.stateStore().deleteInstanceCompositionPlan(id);
        audit(ctx, controller.stateStore(), "instance.delete", "instance", id);
        ctx.json(ActionDtoMapper.statusResponse("deleted"));
    }

    @OpenApi(
            path = "/api/v1/services/{id}/command",
            methods = {HttpMethod.POST},
            operationId = "sendInstanceCommand",
            summary = "Send console command",
            description = "Sends an arbitrary console command to the instance.",
            tags = {"Instances"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = CommandRequest.class)}),
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Command sent",
                        content = {@OpenApiContent(from = StatusOnlyResponse.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Instance not found")
            })
    private void sendInstanceCommand(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.INSTANCES_COMMAND);
        String id = ctx.pathParam("id");
        var instance = requireFound(controller.clusterState().getInstance(id), "Instance", id);

        var req = ctx.bodyAsClass(CommandRequest.class);
        InputValidator.requireSafeCommand(req.command());

        var session = controller.sessionManager().getByNodeId(instance.nodeId());
        if (session.isPresent()) {
            var msg = ControllerMessage.newBuilder()
                    .setSendCommand(SendCommand.newBuilder()
                            .setInstanceId(id)
                            .setCommand(req.command())
                            .build())
                    .build();
            session.get().send(msg);
        }
        ctx.json(ActionDtoMapper.statusResponse("sent"));
    }

    @OpenApi(
            path = "/api/v1/services/{id}/console",
            methods = {HttpMethod.GET},
            operationId = "streamInstanceConsole",
            summary = "SSE console output stream",
            description =
                    "Server-Sent Events stream of stdout/stderr lines from the instance's managed process. Authenticate via the `ticket` query parameter from `/api/v1/events/ticket`. Replays buffered history on connect.",
            tags = {"Instances"},
            security = {@OpenApiSecurity(name = "sseTicket")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            queryParams = {@OpenApiParam(name = "ticket", required = true)},
            responses = {
                @OpenApiResponse(status = "200", description = "Console SSE stream"),
                @OpenApiResponse(status = "401", description = "Invalid or expired ticket")
            })
    @SuppressWarnings("unused")
    private void docStreamInstanceConsole(Context ctx) {
        throw new UnsupportedOperationException("doc stub — real handler in RestServer");
    }

    static Instant parseInstantQuery(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return Instant.parse(raw);
    }

    static int parseHistoryLimit(String raw) {
        if (raw == null || raw.isBlank()) return CONSOLE_HISTORY_DEFAULT_LIMIT;
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed <= 0) return CONSOLE_HISTORY_DEFAULT_LIMIT;
            return Math.min(parsed, CONSOLE_HISTORY_MAX_LIMIT);
        } catch (NumberFormatException e) {
            return CONSOLE_HISTORY_DEFAULT_LIMIT;
        }
    }

    private void sendStopCommand(NodeSessionManager sessionManager, InstanceInfo instance, boolean force) {
        var session = sessionManager.getByNodeId(instance.nodeId());
        if (session.isPresent()) {
            var msg = ControllerMessage.newBuilder()
                    .setStopInstance(StopInstance.newBuilder()
                            .setInstanceId(instance.id())
                            .setForce(force)
                            .build())
                    .build();
            session.get().send(msg);
        }
    }
}
