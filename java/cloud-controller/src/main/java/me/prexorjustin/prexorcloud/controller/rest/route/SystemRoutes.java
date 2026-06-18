package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.audit;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import me.prexorjustin.prexorcloud.common.util.VersionInfo;
import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.health.ControllerReadinessProbe;
import me.prexorjustin.prexorcloud.controller.observability.ControllerLogBuffer;
import me.prexorjustin.prexorcloud.controller.observability.ControllerLogBuffer.LogFilter;
import me.prexorjustin.prexorcloud.controller.recovery.BackupScope;
import me.prexorjustin.prexorcloud.controller.redis.RedisKeys;
import me.prexorjustin.prexorcloud.controller.redis.RedisKeyspaceInspector;
import me.prexorjustin.prexorcloud.controller.rest.dto.ShareLogRequestDto;
import me.prexorjustin.prexorcloud.controller.rest.dto.ShareRequestDto;
import me.prexorjustin.prexorcloud.controller.rest.dto.ShareResultDto;
import me.prexorjustin.prexorcloud.controller.rest.dto.SseTicketResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.SystemDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;
import me.prexorjustin.prexorcloud.controller.rest.sse.LogStreamer;
import me.prexorjustin.prexorcloud.controller.rest.sse.SseTicketManager;
import me.prexorjustin.prexorcloud.controller.runtime.InMemoryRuntimeServices;
import me.prexorjustin.prexorcloud.controller.runtime.RuntimeServices;
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

public final class SystemRoutes {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(SystemRoutes.class);

    // Substring anchor for scripts/check-openapi-routes.sh (the stream endpoint
    // is registered in RestServer, not here).
    @SuppressWarnings("unused")
    private static final String P_SYSTEM_LOGS_STREAM = "/api/v1/system/logs/stream";

    private final PrexorController controller;
    private final RuntimeServices runtime;
    private final SseTicketManager sseTicketManager;
    private final ControllerReadinessProbe readinessProbe;

    public SystemRoutes(PrexorController controller) {
        this(controller, new InMemoryRuntimeServices(), null);
    }

    public SystemRoutes(PrexorController controller, RuntimeServices runtime) {
        this(controller, runtime, null);
    }

    public SystemRoutes(PrexorController controller, RuntimeServices runtime, SseTicketManager sseTicketManager) {
        this.controller = controller;
        this.runtime = runtime;
        this.sseTicketManager = sseTicketManager;
        this.readinessProbe = ControllerReadinessProbe.from(
                controller, () -> controller.stateStore() != null, runtime::coordinationEnabled);
    }

    public void register() {
        path("/api/v1/system", () -> {
            get("/health", this::getSystemHealth);
            get("/ready", this::getSystemReady);
            get("/version", this::getSystemVersion);
            get("/settings", this::getSystemSettings);
            get("/redis/keyspace", this::getRedisKeyspace);
            get("/redis/schema", this::getRedisSchema);
            get("/logs", this::getSystemLogs);
            post("/logs/share", this::shareControllerLogs);
            post("/logs/ticket", this::createSystemLogTicket);
            get("/diagnostics", this::getDiagnostics);
            post("/diagnostics/share", this::shareDiagnostics);
            post("/shutdown", this::shutdownController);
        });
    }

    @OpenApi(
            path = "/api/v1/system/shutdown",
            methods = {HttpMethod.POST},
            operationId = "shutdownController",
            summary = "Shut down this controller",
            description = "Gracefully stops the controller process this request is served by. Responds 202 first,"
                    + " then exits shortly after so the response can flush. In an HA cluster this stops only"
                    + " the targeted controller; the remaining peers re-elect a leader. ADMIN-only.",
            tags = {"System"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {@OpenApiResponse(status = "202", description = "Shutdown initiated")})
    private void shutdownController(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.SYSTEM_SHUTDOWN);
        String reason = ctx.queryParam("reason");
        if (reason == null || reason.isBlank()) {
            reason = "Operator requested controller shutdown";
        }
        audit(ctx, controller.stateStore(), "system.shutdown", "controller", "self");
        logger.warn("Controller shutdown requested via API: {}", reason);
        ctx.status(202);
        ctx.json(Map.of("status", "STOPPING", "reason", reason));

        // Exit on a separate thread after a short grace so the 202 flushes to the client first. The
        // JVM shutdown hook runs the controller's graceful close(); under systemd Restart=on-failure a
        // clean exit(0) stays down.
        Thread shutdownThread = new Thread(
                () -> {
                    try {
                        Thread.sleep(750);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    System.exit(0);
                },
                "api-shutdown");
        shutdownThread.setDaemon(true);
        shutdownThread.start();
    }

    @OpenApi(
            path = "/api/v1/system/health",
            methods = {HttpMethod.GET},
            operationId = "getSystemHealth",
            summary = "System health check",
            description = "Returns system health status. Requires authentication but no specific permission.",
            tags = {"System"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {@OpenApiResponse(status = "200", description = "System is healthy")})
    private void getSystemHealth(Context ctx) {
        ctx.json(readinessProbe.healthBody());
    }

    @OpenApi(
            path = "/api/v1/system/ready",
            methods = {HttpMethod.GET},
            operationId = "getSystemReady",
            summary = "Readiness check",
            description = "Returns whether the controller is ready to serve requests.",
            tags = {"System"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {
                @OpenApiResponse(status = "200", description = "Ready"),
                @OpenApiResponse(status = "503", description = "Not ready")
            })
    private void getSystemReady(Context ctx) {
        var snapshot = readinessProbe.snapshot();
        ctx.status(snapshot.httpStatus());
        ctx.json(readinessProbe.readinessBody());
    }

    @OpenApi(
            path = "/api/v1/system/version",
            methods = {HttpMethod.GET},
            operationId = "getSystemVersion",
            summary = "Controller version info",
            tags = {"System"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {@OpenApiResponse(status = "200", description = "Version info")})
    private void getSystemVersion(Context ctx) {
        VersionInfo v = VersionInfo.get();
        ctx.json(SystemDtoMapper.versionDto(v));
    }

    @OpenApi(
            path = "/api/v1/system/settings",
            methods = {HttpMethod.GET},
            operationId = "getSystemSettings",
            summary = "Non-sensitive settings",
            tags = {"System"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {
                @OpenApiResponse(status = "200", description = "Settings"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden")
            })
    private void getSystemSettings(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.SYSTEM_SETTINGS);
        ctx.json(SystemDtoMapper.settingsDto(
                controller.clusterState().nodeCount(),
                controller.clusterState().instanceCount(),
                controller.clusterState().playerCount(),
                controller.config().scheduler().evaluationIntervalSeconds(),
                controller.config().heartbeat().intervalMs(),
                controller.config().metrics().enabled(),
                controller.config().share().enabled(),
                controller.config().telemetry().enabled(),
                controller.config().telemetry().traceUiTemplate()));
    }

    @OpenApi(
            path = "/api/v1/system/redis/keyspace",
            methods = {HttpMethod.GET},
            operationId = "getRedisKeyspace",
            summary = "Redis keyspace report",
            tags = {"System"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {
                @OpenApiResponse(status = "200", description = "Keyspace report"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden")
            })
    private void getRedisKeyspace(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.SYSTEM_SETTINGS);
        if (!runtime.coordinationEnabled()) {
            ctx.json(new RedisKeyspaceInspector.KeyspaceReport(false, 0, java.util.List.of(), "redis disabled"));
            return;
        }
        ctx.json(new RedisKeyspaceInspector(runtime.redisCommands()).inspect(BackupScope.defaultRedisKeyPrefixes()));
    }

    @OpenApi(
            path = "/api/v1/system/redis/schema",
            methods = {HttpMethod.GET},
            operationId = "getRedisSchema",
            summary = "Redis key policies",
            tags = {"System"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {
                @OpenApiResponse(status = "200", description = "Key policies"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden")
            })
    private void getRedisSchema(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.SYSTEM_SETTINGS);
        ctx.json(RedisKeys.keyPolicies());
    }

    @OpenApi(
            path = "/api/v1/system/logs",
            methods = {HttpMethod.GET},
            operationId = "getSystemLogs",
            summary = "Recent controller log records",
            tags = {"System"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            queryParams = {
                @OpenApiParam(name = "level", description = "Minimum level (TRACE/DEBUG/INFO/WARN/ERROR)."),
                @OpenApiParam(name = "logger", description = "Logger-name filter."),
                @OpenApiParam(name = "limit", type = Integer.class, description = "Max records (clamped to 1000).")
            },
            responses = {
                @OpenApiResponse(status = "200", description = "Log records"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden")
            })
    private void getSystemLogs(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.SYSTEM_LOGS_VIEW);
        ControllerLogBuffer buffer = controller.logBuffer();
        if (buffer == null) {
            ctx.json(Map.of("records", List.of(), "size", 0, "capacity", 0));
            return;
        }
        String levelParam = ctx.queryParam("level");
        String loggerParam = ctx.queryParam("logger");
        int limit = parseLimitParam(ctx.queryParam("limit"), 200, 1000);
        LogFilter filter = LogFilter.atLeast(levelParam == null ? "INFO" : levelParam, loggerParam);
        var records =
                buffer.recent(filter, limit).stream().map(LogStreamer::toWire).collect(Collectors.toList());
        ctx.json(Map.of(
                "records",
                records,
                "size",
                buffer.size(),
                "capacity",
                buffer.capacity(),
                "level",
                levelParam == null ? "INFO" : levelParam.toUpperCase(java.util.Locale.ROOT),
                "logger",
                loggerParam == null ? "" : loggerParam));
    }

    @OpenApi(
            path = "/api/v1/system/logs/share",
            methods = {HttpMethod.POST},
            operationId = "shareControllerLogs",
            summary = "Share recent controller logs via pste",
            description =
                    "Uploads a filtered, redacted slice of the controller log ring buffer to the configured paste service.",
            tags = {"System"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
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
    private void shareControllerLogs(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.SYSTEM_LOGS_VIEW);
        JwtAuthMiddleware.requirePermission(ctx, Permission.SHARE_INVOKE);
        ShareLogRequestDto req = parseShareLogRequest(ctx);
        ControllerLogBuffer buffer = controller.logBuffer();
        java.util.List<String> lines = buffer == null
                ? java.util.List.of()
                : buffer
                        .recent(
                                LogFilter.atLeast(req.level() == null ? "INFO" : req.level(), req.logger()),
                                clampLimit(req.limit(), 1000))
                        .stream()
                        .map(SystemRoutes::renderLogLine)
                        .collect(Collectors.toList());
        String username = ctx.attribute("username");
        var result = controller
                .shareService()
                .shareLogText(
                        ShareKind.CONTROLLER_LOGS,
                        null,
                        "controller-logs",
                        lines,
                        toServiceRequest(req),
                        ShareContext.of(username, ctx.ip()));
        ctx.status(201).json(ShareResultDto.from(result));
    }

    @OpenApi(
            path = "/api/v1/system/diagnostics/share",
            methods = {HttpMethod.POST},
            operationId = "shareDiagnostics",
            summary = "Share the diagnostics bundle via pste",
            description =
                    "Renders the expanded diagnostics bundle (config + cluster overview + nodes/instances/groups/templates/per-instance filetrees) as a redacted text document and uploads it to the configured paste service.",
            tags = {"System"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            requestBody =
                    @io.javalin.openapi.OpenApiRequestBody(content = {@OpenApiContent(from = ShareRequestDto.class)}),
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
    private void shareDiagnostics(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.SYSTEM_SETTINGS);
        JwtAuthMiddleware.requirePermission(ctx, Permission.SHARE_INVOKE);
        ShareRequestDto req = parseShareRequest(ctx);
        var snapshot = new me.prexorjustin.prexorcloud.controller.diagnostics.DiagnosticsCollector(
                        controller, runtime, readinessProbe)
                .collect();
        String username = ctx.attribute("username");
        var result = controller
                .shareService()
                .shareText(
                        "diagnostics-bundle",
                        snapshot.toTextDocument(),
                        toServiceRequest(req),
                        ShareContext.of(username, ctx.ip()));
        ctx.status(201).json(ShareResultDto.from(result));
    }

    private static ShareRequestDto parseShareRequest(Context ctx) {
        String body = ctx.body();
        if (body == null || body.isBlank()) return new ShareRequestDto(null, null, null);
        return ctx.bodyAsClass(ShareRequestDto.class);
    }

    private static ShareLogRequestDto parseShareLogRequest(Context ctx) {
        String body = ctx.body();
        if (body == null || body.isBlank()) return new ShareLogRequestDto(null, null, null, null, null, null);
        return ctx.bodyAsClass(ShareLogRequestDto.class);
    }

    static ShareRequest toServiceRequest(ShareRequestDto dto) {
        if (dto == null) return ShareRequest.empty();
        return new ShareRequest(dto.expiry(), dto.isPrivate(), dto.burnAfterRead());
    }

    static ShareRequest toServiceRequest(ShareLogRequestDto dto) {
        if (dto == null) return ShareRequest.empty();
        return new ShareRequest(dto.expiry(), dto.isPrivate(), dto.burnAfterRead());
    }

    private static int clampLimit(Integer requested, int max) {
        if (requested == null || requested <= 0) return 200;
        return Math.min(requested, max);
    }

    private static String renderLogLine(ControllerLogBuffer.LogRecord record) {
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
            path = "/api/v1/system/logs/ticket",
            methods = {HttpMethod.POST},
            operationId = "createSystemLogTicket",
            summary = "Issue SSE ticket for controller log stream",
            tags = {"System"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "SSE ticket issued",
                        content = {@OpenApiContent(from = SseTicketResponse.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden")
            })
    private void createSystemLogTicket(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.SYSTEM_LOGS_VIEW);
        if (sseTicketManager == null) {
            throw new IllegalStateException("SSE ticket manager not configured - controller logs streaming disabled");
        }
        String username = ctx.attribute("username");
        String role = ctx.attribute("role");
        String ticket = sseTicketManager.issue(username, role);
        ctx.json(Map.of("ticket", ticket));
    }

    @OpenApi(
            path = "/api/v1/system/diagnostics",
            methods = {HttpMethod.GET},
            operationId = "getDiagnostics",
            summary = "Aggregated diagnostics document",
            description =
                    "Pulls together version, readiness, settings, redacted config, redis keyspace summary, lease snapshot, and cluster overview behind a single ADMIN-gated read so `prexorctl diagnostics bundle` doesn't fan out separate calls.",
            tags = {"System"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {
                @OpenApiResponse(status = "200", description = "Diagnostics bundle"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden")
            })
    private void getDiagnostics(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.SYSTEM_SETTINGS);
        ctx.json(new me.prexorjustin.prexorcloud.controller.diagnostics.DiagnosticsCollector(
                        controller, runtime, readinessProbe)
                .collect()
                .sections());
    }

    @OpenApi(
            path = "/api/v1/system/logs/stream",
            methods = {HttpMethod.GET},
            operationId = "streamControllerLogs",
            summary = "SSE controller log stream",
            description =
                    "Server-Sent Events stream tailing the controller log ring buffer. Authenticate via the `ticket` query parameter obtained from `/api/v1/system/logs/ticket`.",
            tags = {"System"},
            security = {@OpenApiSecurity(name = "sseTicket")},
            queryParams = {
                @OpenApiParam(name = "ticket", required = true),
                @OpenApiParam(name = "level"),
                @OpenApiParam(name = "logger"),
                @OpenApiParam(name = "tail", type = Integer.class)
            },
            responses = {
                @OpenApiResponse(status = "200", description = "SSE log stream"),
                @OpenApiResponse(status = "401", description = "Invalid or expired ticket")
            })
    @SuppressWarnings("unused")
    private void docStreamControllerLogs(Context ctx) {
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
