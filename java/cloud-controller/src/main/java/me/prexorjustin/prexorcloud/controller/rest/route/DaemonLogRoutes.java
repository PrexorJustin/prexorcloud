package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.observability.ControllerLogBuffer.LogFilter;
import me.prexorjustin.prexorcloud.controller.observability.DaemonLogStore;
import me.prexorjustin.prexorcloud.controller.rest.dto.DaemonLogResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.ErrorResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.ShareLogRequestDto;
import me.prexorjustin.prexorcloud.controller.rest.dto.ShareResultDto;
import me.prexorjustin.prexorcloud.controller.rest.dto.SseTicketResponse;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;
import me.prexorjustin.prexorcloud.controller.rest.sse.LogStreamer;
import me.prexorjustin.prexorcloud.controller.rest.sse.SseTicketManager;
import me.prexorjustin.prexorcloud.controller.share.ShareContext;
import me.prexorjustin.prexorcloud.controller.share.ShareKind;
import me.prexorjustin.prexorcloud.controller.share.ShareRequest;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class DaemonLogRoutes {

    // Substring anchor for scripts/check-openapi-routes.sh — the SSE stream
    // endpoint is registered in RestServer via DaemonLogStreamer, not here.
    @SuppressWarnings("unused")
    private static final String P_DAEMON_LOGS_STREAM = "/api/v1/nodes/{id}/logs/stream";

    private final PrexorController controller;
    private final SseTicketManager sseTicketManager;

    public DaemonLogRoutes(PrexorController controller, SseTicketManager sseTicketManager) {
        this.controller = controller;
        this.sseTicketManager = sseTicketManager;
    }

    public void register() {
        path("/api/v1/nodes", () -> {
            get("/{id}/logs", this::getDaemonLogs);
            post("/{id}/logs/share", this::shareDaemonLogs);
            post("/{id}/logs/ticket", this::createDaemonLogTicket);
        });
    }

    @OpenApi(
            path = "/api/v1/nodes/{id}/logs",
            methods = {HttpMethod.GET},
            operationId = "getDaemonLogs",
            summary = "Recent daemon log records",
            description = "Returns recent records from the per-node daemon log ring buffer.",
            tags = {"Nodes"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true, description = "Node ID")},
            queryParams = {
                @OpenApiParam(
                        name = "level",
                        description = "Minimum level (TRACE, DEBUG, INFO, WARN, ERROR). Defaults to INFO."),
                @OpenApiParam(name = "logger", description = "Logger-name substring/prefix filter."),
                @OpenApiParam(
                        name = "limit",
                        type = Integer.class,
                        description = "Maximum number of records to return (clamped to 1000).")
            },
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Daemon log records",
                        content = {@OpenApiContent(from = DaemonLogResponse.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden")
            })
    private void getDaemonLogs(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.SYSTEM_LOGS_VIEW);
        String nodeId = ctx.pathParam("id");
        DaemonLogStore store = controller.daemonLogStore();
        if (store == null) {
            ctx.json(Map.of("records", List.of(), "size", 0, "capacity", 0));
            return;
        }
        String levelParam = ctx.queryParam("level");
        String loggerParam = ctx.queryParam("logger");
        int limit = parseLimitParam(ctx.queryParam("limit"), 200, 1000);
        LogFilter filter = LogFilter.atLeast(levelParam == null ? "INFO" : levelParam, loggerParam);

        var records = store.recent(nodeId, filter, limit).stream()
                .map(LogStreamer::toWire)
                .collect(Collectors.toList());
        ctx.json(Map.of(
                "nodeId",
                nodeId,
                "records",
                records,
                "size",
                store.size(nodeId),
                "capacity",
                store.capacity(),
                "level",
                levelParam == null ? "INFO" : levelParam.toUpperCase(Locale.ROOT),
                "logger",
                loggerParam == null ? "" : loggerParam));
    }

    @OpenApi(
            path = "/api/v1/nodes/{id}/logs/share",
            methods = {HttpMethod.POST},
            operationId = "shareDaemonLogs",
            summary = "Share recent daemon logs via pste",
            description =
                    "Uploads a filtered, redacted slice of the per-node daemon log ring buffer to the configured paste service.",
            tags = {"Nodes"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true, description = "Node ID")},
            requestBody =
                    @io.javalin.openapi.OpenApiRequestBody(
                            content = {@OpenApiContent(from = ShareLogRequestDto.class)}),
            responses = {
                @OpenApiResponse(
                        status = "201",
                        description = "Paste created",
                        content = {@OpenApiContent(from = ShareResultDto.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "409", description = "Sharing not configured"),
                @OpenApiResponse(status = "502", description = "Paste service unreachable")
            })
    private void shareDaemonLogs(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.SYSTEM_LOGS_VIEW);
        JwtAuthMiddleware.requirePermission(ctx, Permission.SHARE_INVOKE);
        String nodeId = ctx.pathParam("id");
        ShareLogRequestDto req = parseShareLogRequest(ctx);
        DaemonLogStore store = controller.daemonLogStore();
        java.util.List<String> lines = store == null
                ? java.util.List.of()
                : store
                        .recent(
                                nodeId,
                                LogFilter.atLeast(req.level() == null ? "INFO" : req.level(), req.logger()),
                                clampLimit(req.limit(), 1000))
                        .stream()
                        .map(DaemonLogRoutes::renderLogLine)
                        .collect(Collectors.toList());
        String username = ctx.attribute("username");
        var result = controller
                .shareService()
                .shareLogText(
                        ShareKind.DAEMON_LOGS,
                        nodeId,
                        "daemon-logs-" + nodeId,
                        lines,
                        toServiceRequest(req),
                        ShareContext.of(username, ctx.ip()));
        ctx.status(201).json(ShareResultDto.from(result));
    }

    private static ShareLogRequestDto parseShareLogRequest(Context ctx) {
        String body = ctx.body();
        if (body == null || body.isBlank()) return new ShareLogRequestDto(null, null, null, null, null, null);
        return ctx.bodyAsClass(ShareLogRequestDto.class);
    }

    static ShareRequest toServiceRequest(ShareLogRequestDto dto) {
        if (dto == null) return ShareRequest.empty();
        return new ShareRequest(dto.expiry(), dto.isPrivate(), dto.burnAfterRead());
    }

    private static int clampLimit(Integer requested, int max) {
        if (requested == null || requested <= 0) return 200;
        return Math.min(requested, max);
    }

    private static String renderLogLine(
            me.prexorjustin.prexorcloud.controller.observability.ControllerLogBuffer.LogRecord record) {
        return String.format(
                "%d %s %s [%s] %s%s",
                record.timestampMs(),
                record.level(),
                record.logger(),
                record.thread(),
                record.message(),
                record.throwable() == null ? "" : "\n" + record.throwable());
    }

    @OpenApi(
            path = "/api/v1/nodes/{id}/logs/ticket",
            methods = {HttpMethod.POST},
            operationId = "createDaemonLogTicket",
            summary = "Issue SSE ticket for daemon log stream",
            description =
                    "Exchange a JWT for a short-lived SSE ticket used to authenticate the daemon log stream connection.",
            tags = {"Nodes"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true, description = "Node ID")},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "SSE ticket issued",
                        content = {@OpenApiContent(from = SseTicketResponse.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(
                        status = "503",
                        description = "Daemon log streaming disabled",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void createDaemonLogTicket(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.SYSTEM_LOGS_VIEW);
        if (sseTicketManager == null) {
            ctx.status(503);
            ctx.json(errorResponse("UNAVAILABLE", "Daemon log streaming disabled", 503));
            return;
        }
        String username = ctx.attribute("username");
        String role = ctx.attribute("role");
        String ticket = sseTicketManager.issue(username, role);
        ctx.json(Map.of("ticket", ticket));
    }

    @OpenApi(
            path = "/api/v1/nodes/{id}/logs/stream",
            methods = {HttpMethod.GET},
            operationId = "streamDaemonLogs",
            summary = "SSE daemon log stream",
            description =
                    "Server-Sent Events stream tailing the per-node daemon log ring buffer. Authenticate via the `ticket` query parameter obtained from `/api/v1/nodes/{id}/logs/ticket`.",
            tags = {"Nodes"},
            security = {@OpenApiSecurity(name = "sseTicket")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            queryParams = {
                @OpenApiParam(name = "ticket", required = true),
                @OpenApiParam(name = "level"),
                @OpenApiParam(name = "logger")
            },
            responses = {
                @OpenApiResponse(status = "200", description = "SSE log stream"),
                @OpenApiResponse(status = "401", description = "Invalid or expired ticket")
            })
    @SuppressWarnings("unused")
    private void docStreamDaemonLogs(Context ctx) {
        throw new UnsupportedOperationException("doc stub — real handler in RestServer");
    }

    private static int parseLimitParam(String raw, int defaultValue, int max) {
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            int v = Integer.parseInt(raw);
            if (v <= 0) return defaultValue;
            return Math.min(v, max);
        } catch (NumberFormatException _) {
            return defaultValue;
        }
    }
}
