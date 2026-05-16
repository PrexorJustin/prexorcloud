package me.prexorjustin.prexorcloud.controller.rest;

import java.util.Map;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.config.CorsConfig;
import me.prexorjustin.prexorcloud.controller.config.HttpConfig;
import me.prexorjustin.prexorcloud.controller.health.ControllerReadinessProbe;
import me.prexorjustin.prexorcloud.controller.recovery.BackupServices;
import me.prexorjustin.prexorcloud.controller.rest.middleware.DynamicCorsHandler;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;
import me.prexorjustin.prexorcloud.controller.rest.middleware.RateLimitMiddleware;
import me.prexorjustin.prexorcloud.controller.rest.middleware.RequestIdMiddleware;
import me.prexorjustin.prexorcloud.controller.rest.middleware.SubnetGuardMiddleware;
import me.prexorjustin.prexorcloud.controller.rest.middleware.WorkloadAuthFilter;
import me.prexorjustin.prexorcloud.controller.rest.route.*;
import me.prexorjustin.prexorcloud.controller.rest.sse.ConsoleStreamer;
import me.prexorjustin.prexorcloud.controller.rest.sse.DaemonLogStreamer;
import me.prexorjustin.prexorcloud.controller.rest.sse.LogStreamer;
import me.prexorjustin.prexorcloud.controller.rest.sse.SseEventStreamer;
import me.prexorjustin.prexorcloud.controller.rest.sse.SseTicketManager;
import me.prexorjustin.prexorcloud.controller.runtime.InMemoryRuntimeServices;
import me.prexorjustin.prexorcloud.controller.runtime.RuntimeServices;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.modules.runtime.ModuleRouteRegistry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.json.JavalinJackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Javalin-based REST API server.
 */
public final class RestServer {

    private static final Logger logger = LoggerFactory.getLogger(RestServer.class);
    private static final ObjectMapper AUDIT_MAPPER = new ObjectMapper();

    private final PrexorController controller;
    private final RuntimeServices runtime;
    private final ControllerReadinessProbe readinessProbe;
    private final BackupServices backupServices;
    private final ModuleRouteRegistry moduleRouteRegistry;
    private Javalin app;

    /**
     * Thrown by {@link #requireFound} when an entity is not present. Caught by the
     * Javalin exception handler registered in {@link #start()} and converted to a
     * 404 response automatically — no explicit null-check + return needed in
     * routes.
     */
    public static final class NotFoundException extends RuntimeException {

        public NotFoundException(String message) {
            super(message);
        }
    }

    public RestServer(PrexorController controller) {
        this(controller, new InMemoryRuntimeServices());
    }

    public RestServer(PrexorController controller, RuntimeServices runtime) {
        this(
                controller,
                runtime,
                ControllerReadinessProbe.from(
                        controller, () -> controller.stateStore() != null, runtime::coordinationEnabled));
    }

    public RestServer(PrexorController controller, RuntimeServices runtime, ControllerReadinessProbe readinessProbe) {
        this(controller, runtime, readinessProbe, null);
    }

    public RestServer(
            PrexorController controller,
            RuntimeServices runtime,
            ControllerReadinessProbe readinessProbe,
            BackupServices backupServices) {
        this(controller, runtime, readinessProbe, backupServices, null);
    }

    public RestServer(
            PrexorController controller,
            RuntimeServices runtime,
            ControllerReadinessProbe readinessProbe,
            BackupServices backupServices,
            ModuleRouteRegistry moduleRouteRegistry) {
        this.controller = controller;
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
        this.readinessProbe = readinessProbe;
        this.backupServices = backupServices;
        this.moduleRouteRegistry = moduleRouteRegistry;
    }

    /**
     * Returns the underlying Javalin instance (for dynamic route registration by
     * modules).
     */
    public Javalin javalin() {
        return app;
    }

    public void start() {
        HttpConfig httpConfig = controller.config().http();
        CorsConfig corsConfig = httpConfig.cors();

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        var jwtMiddleware = new JwtAuthMiddleware(controller.jwtManager(), controller.revocationStore());
        var requestIdMiddleware = new RequestIdMiddleware();
        var rateLimitMiddleware =
                new RateLimitMiddleware(controller.config().security().rateLimiting(), runtime);

        // Bootstrap the live CORS allow-list from controller.yml — admin routes
        // mutate the same instance so changes take effect without a restart.
        var corsAllowList = controller.corsAllowList();
        if (corsConfig.allowedOrigins() != null) {
            for (String origin : corsConfig.allowedOrigins()) {
                corsAllowList.add(origin);
            }
        }
        if (corsAllowList.snapshot().isEmpty()) {
            // Match the previous "no origins configured" safety: fall back to localhost
            // dev origins so a misconfigured controller can still serve the local
            // dashboard for triage.
            corsAllowList.add("http://localhost:3000");
            corsAllowList.add("http://localhost:5173");
            corsAllowList.add("https://localhost");
            logger.warn("No CORS origins configured — defaulting to localhost only");
        }
        var corsHandler = new DynamicCorsHandler(corsAllowList);
        var subnetGuard = new SubnetGuardMiddleware(controller.allowedSubnetsList());

        var redis = runtime.coordinationEnabled() ? runtime.redisCommands() : null;

        // SSE ticket manager (short-lived single-use tickets for SSE auth)
        var sseTicketManager = new SseTicketManager(redis);

        // Create SSE streamers (registered inside config block below)
        SseEventStreamer sseStreamer =
                new SseEventStreamer(controller.eventBus(), objectMapper, sseTicketManager, redis);
        if (controller.metricsCollector() != null) {
            controller.metricsCollector().registerSseStreamerMetrics(sseStreamer);
        }
        ConsoleStreamer consoleStreamer =
                new ConsoleStreamer(controller.eventBus(), sseTicketManager, controller.consoleBuffer());
        LogStreamer logStreamer = controller.logBuffer() == null
                ? null
                : new LogStreamer(sseTicketManager, controller.logBuffer(), objectMapper);
        DaemonLogStreamer daemonLogStreamer = controller.daemonLogStore() == null
                ? null
                : new DaemonLogStreamer(sseTicketManager, controller.daemonLogStore(), objectMapper);
        var capabilityStreamer = new me.prexorjustin.prexorcloud.controller.rest.sse.CapabilityStreamer(
                controller.eventBus(), sseTicketManager, objectMapper);

        app = Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson(objectMapper, false));
            config.concurrency.useVirtualThreads = true;

            // Multipart file uploads (module JARs, avatars)
            config.jetty.multipartConfig.maxFileSize(50, io.javalin.config.SizeUnit.MB);
            config.jetty.multipartConfig.maxInMemoryFileSize(2, io.javalin.config.SizeUnit.MB);
            config.jetty.multipartConfig.maxTotalRequestSize(50, io.javalin.config.SizeUnit.MB);

            // CORS is handled by DynamicCorsHandler (registered as the first before-handler
            // below) instead of Javalin's bundled CORS plugin, so origins added at runtime
            // via PATCH /api/v1/admin/cors/origins take effect on the next request.

            // Routes
            config.routes.apiBuilder(() -> {
                // Serve the auto-generated OpenAPI spec from the classpath
                // resource the javalin-openapi annotation processor produced.
                // The community runtime OpenApiPlugin/SwaggerPlugin (6.7.0-1)
                // doesn't register its routes against Javalin 7.1.0's plugin
                // SPI, so we wire this manually. UI consumption: website's
                // Scalar playground reads /openapi.json directly.
                io.javalin.apibuilder.ApiBuilder.get("/openapi", ctx -> {
                    var stream = RestServer.class.getResourceAsStream("/openapi-plugin/openapi-default.json");
                    if (stream == null) {
                        ctx.status(500);
                        ctx.json(java.util.Map.of("error", "OpenAPI spec resource missing"));
                        return;
                    }
                    ctx.contentType("application/json");
                    ctx.result(stream);
                });

                // CORS — runs first so OPTIONS preflight gets the right headers and
                // short-circuits before any downstream middleware can reject it.
                io.javalin.apibuilder.ApiBuilder.before(corsHandler);
                // Subnet guard — defense-in-depth IP filter. Runs after CORS so OPTIONS
                // preflight (which has no payload and is already short-circuited above)
                // never gets here, but before auth so disallowed IPs don't even reach
                // JWT validation. Bootstrap endpoint and health probes are exempt; see
                // SubnetGuardMiddleware.EXEMPT_PATHS.
                io.javalin.apibuilder.ApiBuilder.before(subnetGuard);

                // Correlation ID for every request
                io.javalin.apibuilder.ApiBuilder.before("/api/v1/*", requestIdMiddleware);
                // IP-based rate limiting (before auth, so brute-force is blocked early)
                io.javalin.apibuilder.ApiBuilder.before("/api/v1/*", rateLimitMiddleware);
                // Auth middleware (before all /api/ except public endpoints)
                io.javalin.apibuilder.ApiBuilder.before("/api/v1/*", jwtMiddleware);
                // Per-user rate limiting (after auth, so username attribute is available)
                io.javalin.apibuilder.ApiBuilder.before("/api/v1/*", rateLimitMiddleware.perUserHandler());

                // Workload (plugin/proxy) token auth — structural, so a new
                // /api/proxy/* or /api/plugin/* handler cannot accidentally
                // expose itself by forgetting to call the auth helper.
                var workloadAuthFilter = new WorkloadAuthFilter(controller);
                io.javalin.apibuilder.ApiBuilder.before("/api/proxy/*", workloadAuthFilter);
                io.javalin.apibuilder.ApiBuilder.before("/api/plugin/*", workloadAuthFilter);

                // Routes
                new AuthRoutes(controller).register();
                new BootstrapRoutes(controller).register();
                new AdminConfigRoutes(controller).register();
                new PasswordResetRoutes(controller).register();
                new OverviewRoutes(controller).register();
                new TimeseriesRoutes(controller).register();
                new NodeRoutes(controller, runtime).register();
                new GroupRoutes(controller).register();
                new NetworkRoutes(controller).register();
                new EventRoutes(controller).register();
                new InstanceRoutes(controller).register();
                new PlayerRoutes(controller).register();
                new PlayerJourneyRoutes(controller).register();
                new TemplateRoutes(controller).register();
                new CrashRoutes(controller).register();
                new ShareRoutes(controller).register();
                new TokenRoutes(controller).register();
                new WorkloadCredentialRoutes(controller).register();
                new CatalogRoutes(controller).register();
                new UserRoutes(controller).register();
                new RoleRoutes(controller).register();
                new AuditRoutes(controller).register();
                new SystemRoutes(controller, runtime, sseTicketManager).register();
                new DaemonLogRoutes(controller, sseTicketManager).register();
                new MaintenanceRoutes(controller).register();
                new ModuleRoutes(controller).register();
                if (moduleRouteRegistry != null) {
                    registerModuleApiDispatcher();
                }
                new MetricsRoutes(controller).register();
                new ProxyRoutes(controller, sseTicketManager).register();
                new PluginRoutes(controller, sseTicketManager).register();
                if (backupServices != null) {
                    new BackupRoutes(controller, runtime, backupServices).register();
                }
            });

            // Per-request HTTP metrics (only when metrics collector is enabled)
            if (controller.metricsCollector() != null) {
                installHttpMetricsHandlers(config.routes);
            }

            // Clear MDC after each request
            config.routes.after(RequestIdMiddleware::clearMdc);

            // SSE ticket endpoint (authenticated — exchange JWT for SSE ticket)
            config.routes.post("/api/v1/events/ticket", ctx -> {
                String username = ctx.attribute("username");
                String role = ctx.attribute("role");
                String ticket = sseTicketManager.issue(username, role);
                ctx.json(Map.of("ticket", ticket));
            });

            // SSE endpoints
            sseStreamer.register(config.routes);
            consoleStreamer.register(config.routes);
            capabilityStreamer.register(config.routes);
            if (logStreamer != null) {
                logStreamer.register(config.routes);
            }
            if (daemonLogStreamer != null) {
                daemonLogStreamer.register(config.routes);
            }

            // Health check endpoints (no auth)
            config.routes.get("/health", ctx -> ctx.json(readinessProbe.healthBody()));
            config.routes.get("/ready", ctx -> {
                var snapshot = readinessProbe.snapshot();
                ctx.status(snapshot.httpStatus());
                ctx.json(readinessProbe.readinessBody());
            });

            // Prometheus metrics (no auth)
            if (controller.metricsCollector() != null) {
                config.routes.get("/metrics", ctx -> {
                    ctx.contentType("text/plain; version=0.0.4");
                    ctx.result(controller.metricsCollector().scrape());
                });
            }

            // Exception handlers
            config.routes.exception(NotFoundException.class, (e, ctx) -> {
                ctx.status(404);
                ctx.json(errorResponse("NOT_FOUND", e.getMessage(), 404));
            });

            config.routes.exception(IllegalArgumentException.class, (e, ctx) -> {
                ctx.status(422);
                ctx.json(errorResponse("VALIDATION_ERROR", e.getMessage(), 422));
            });

            config.routes.exception(IllegalStateException.class, (e, ctx) -> {
                ctx.status(409);
                ctx.json(errorResponse("CONFLICT", e.getMessage(), 409));
            });

            config.routes.exception(
                    me.prexorjustin.prexorcloud.controller.share.ShareNotConfiguredException.class, (e, ctx) -> {
                        ctx.status(409);
                        ctx.json(errorResponse("SHARE_DISABLED", e.getMessage(), 409));
                    });

            config.routes.exception(
                    me.prexorjustin.prexorcloud.controller.share.ShareAlreadyRevokedException.class, (e, ctx) -> {
                        ctx.status(409);
                        ctx.json(errorResponse("SHARE_ALREADY_REVOKED", e.getMessage(), 409));
                    });

            config.routes.exception(
                    me.prexorjustin.prexorcloud.controller.share.ShareNotRevocableException.class, (e, ctx) -> {
                        ctx.status(422);
                        ctx.json(errorResponse("SHARE_NOT_REVOCABLE", e.getMessage(), 422));
                    });

            config.routes.exception(me.prexorjustin.prexorcloud.controller.share.PasteException.class, (e, ctx) -> {
                logger.warn("Paste service error on {} {}: {}", ctx.method(), ctx.path(), e.getMessage());
                ctx.status(502);
                ctx.json(errorResponse("PASTE_UPSTREAM_ERROR", e.getMessage(), 502));
            });

            config.routes.exception(Exception.class, (e, ctx) -> {
                logger.error("Unhandled exception on {} {}: {}", ctx.method(), ctx.path(), e.getMessage(), e);
                ctx.status(500);
                ctx.json(errorResponse("INTERNAL_ERROR", "An internal error occurred", 500));
            });
        });

        app.start(httpConfig.host(), httpConfig.port());
        logger.debug("REST API listening on {}:{}", httpConfig.host(), httpConfig.port());
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
    }

    /** Module-name segments reserved by the controller. A module that picks one of these is rejected at install time. */
    private static final java.util.Set<String> RESERVED_MODULE_SEGMENT = java.util.Set.of("platform");

    /**
     * Mounts one wildcard handler per HTTP method at {@code /api/v1/modules/{moduleId}/<sub>}.
     * The handler delegates to {@link ModuleRouteRegistry} which holds the per-module
     * route table. Registered after {@link ModuleRoutes#register} so Javalin's
     * specificity-first matcher continues to route the literal {@code /platform/...}
     * and {@code /{name}/frontend/...} paths to their controller-side handlers.
     */
    private void registerModuleApiDispatcher() {
        io.javalin.apibuilder.ApiBuilder.get(
                "/api/v1/modules/{moduleId}/<sub>", ctx -> dispatchModuleRoute(ctx, "GET"));
        io.javalin.apibuilder.ApiBuilder.post(
                "/api/v1/modules/{moduleId}/<sub>", ctx -> dispatchModuleRoute(ctx, "POST"));
        io.javalin.apibuilder.ApiBuilder.put(
                "/api/v1/modules/{moduleId}/<sub>", ctx -> dispatchModuleRoute(ctx, "PUT"));
        io.javalin.apibuilder.ApiBuilder.delete(
                "/api/v1/modules/{moduleId}/<sub>", ctx -> dispatchModuleRoute(ctx, "DELETE"));
        io.javalin.apibuilder.ApiBuilder.patch(
                "/api/v1/modules/{moduleId}/<sub>", ctx -> dispatchModuleRoute(ctx, "PATCH"));
    }

    private void dispatchModuleRoute(Context ctx, String method) {
        String moduleId = ctx.pathParam("moduleId");
        if (RESERVED_MODULE_SEGMENT.contains(moduleId)) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Not Found", 404));
            return;
        }
        String subpath = ctx.pathParam("sub");
        var match = moduleRouteRegistry.resolve(moduleId, method, subpath);
        if (match.isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Module route not found", 404));
            return;
        }
        ModuleApiRequestAdapter request =
                new ModuleApiRequestAdapter(ctx, match.get().pathParams());
        ModuleApiResponseAdapter response = new ModuleApiResponseAdapter(ctx);
        try {
            match.get().route().handler().handle(request, response);
        } catch (IllegalArgumentException e) {
            ctx.status(422);
            ctx.json(errorResponse("VALIDATION_ERROR", e.getMessage(), 422));
        } catch (NotFoundException e) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", e.getMessage(), 404));
        } catch (Exception e) {
            logger.error(
                    "Unhandled exception in module {} route {} {}: {}", moduleId, method, subpath, e.getMessage(), e);
            ctx.status(500);
            ctx.json(errorResponse("INTERNAL_ERROR", "An internal error occurred", 500));
        }
    }

    /** Adapts a Javalin {@link Context} to the cloud-api {@link me.prexorjustin.prexorcloud.api.module.rest.ApiRequest}. */
    private static final class ModuleApiRequestAdapter
            implements me.prexorjustin.prexorcloud.api.module.rest.ApiRequest {

        private static final ObjectMapper BODY_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

        private final Context ctx;
        private final Map<String, String> pathParams;

        private ModuleApiRequestAdapter(Context ctx, Map<String, String> pathParams) {
            this.ctx = ctx;
            this.pathParams = Map.copyOf(pathParams);
        }

        @Override
        public String method() {
            return ctx.method().name();
        }

        @Override
        public String path() {
            return ctx.path();
        }

        @Override
        public Map<String, String> pathParams() {
            return pathParams;
        }

        @Override
        public Map<String, String> queryParams() {
            Map<String, String> flat = new java.util.LinkedHashMap<>();
            ctx.queryParamMap().forEach((k, values) -> {
                if (values != null && !values.isEmpty()) flat.put(k, values.get(0));
            });
            return flat;
        }

        @Override
        public Map<String, String> headers() {
            Map<String, String> flat = new java.util.LinkedHashMap<>();
            ctx.headerMap().forEach(flat::put);
            return flat;
        }

        @Override
        public String body() {
            return ctx.body();
        }

        @Override
        public <T> T bodyAs(Class<T> type) {
            try {
                return BODY_MAPPER.readValue(ctx.body(), type);
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("invalid request body: " + e.getMessage(), e);
            }
        }
    }

    /** Adapts a Javalin {@link Context} to the cloud-api {@link me.prexorjustin.prexorcloud.api.module.rest.ApiResponse}. */
    private static final class ModuleApiResponseAdapter
            implements me.prexorjustin.prexorcloud.api.module.rest.ApiResponse {

        private final Context ctx;

        private ModuleApiResponseAdapter(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public me.prexorjustin.prexorcloud.api.module.rest.ApiResponse status(int code) {
            ctx.status(code);
            return this;
        }

        @Override
        public void json(Object body) {
            ctx.json(body);
        }

        @Override
        public void text(String body) {
            ctx.result(body);
        }

        @Override
        public me.prexorjustin.prexorcloud.api.module.rest.ApiResponse header(String name, String value) {
            ctx.header(name, value);
            return this;
        }
    }

    private static final String METRICS_START_NANOS_ATTR = "prexor.metrics.startNanos";

    private void installHttpMetricsHandlers(io.javalin.config.RoutesConfig routes) {
        routes.before(ctx -> ctx.attribute(METRICS_START_NANOS_ATTR, System.nanoTime()));
        routes.after(ctx -> {
            Long start = ctx.attribute(METRICS_START_NANOS_ATTR);
            java.time.Duration duration = start == null ? null : java.time.Duration.ofNanos(System.nanoTime() - start);
            controller.metricsCollector().recordHttpRequest(ctx.method().name(), ctx.statusCode(), duration);
        });
    }

    /**
     * Standard error response format.
     */
    public static Map<String, Object> errorResponse(String code, String message, int status) {
        return Map.of("error", Map.of("code", code, "message", message, "status", status));
    }

    /**
     * Unwraps an Optional or throws {@link NotFoundException}, which the registered
     * exception handler converts to a 404 response. Eliminates the repetitive
     * isEmpty-check + ctx.status(404) + return pattern in every route handler.
     *
     * <pre>{@code
     *
     * var node = requireFound(controller.clusterState().getNode(id), "Node", id);
     * }</pre>
     */
    public static <T> T requireFound(Optional<T> opt, String label, String id) {
        return opt.orElseThrow(() -> new NotFoundException(label + " not found: " + id));
    }

    /**
     * Writes a no-payload audit log entry using the authenticated user from the
     * request context.
     */
    public static void audit(Context ctx, StateStore store, String action, String resourceType, String resourceId) {
        store.audit(ctx.attribute("username"), action, resourceType, resourceId, "{}", ctx.ip());
    }

    /**
     * Writes an audit log entry serializing {@code payload} to JSON safely via
     * Jackson instead of manual string concatenation.
     */
    public static void audit(
            Context ctx,
            StateStore store,
            String action,
            String resourceType,
            String resourceId,
            Map<String, ?> payload) {
        String details;
        try {
            details = AUDIT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize audit payload for {}.{}: {}", resourceType, action, e.getMessage());
            details = "{}";
        }
        store.audit(ctx.attribute("username"), action, resourceType, resourceId, details, ctx.ip());
    }

    /**
     * Writes an audit log entry with before/after snapshots so the dashboard
     * can render a diff. Either snapshot may be {@code null} (create has no
     * before, delete has no after). Both are serialized with Jackson; failures
     * fall back to {@code null} for that slot so a serialization issue never
     * blocks the underlying mutation from being logged.
     */
    public static void auditDiff(
            Context ctx,
            StateStore store,
            String action,
            String resourceType,
            String resourceId,
            Object before,
            Object after) {
        store.audit(
                ctx.attribute("username"),
                action,
                resourceType,
                resourceId,
                "{}",
                serializeSnapshot(action, resourceType, "before", before),
                serializeSnapshot(action, resourceType, "after", after),
                ctx.ip());
    }

    private static String serializeSnapshot(String action, String resourceType, String slot, Object value) {
        if (value == null) return null;
        try {
            return AUDIT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            logger.warn(
                    "Failed to serialize audit {} snapshot for {}.{}: {}", slot, resourceType, action, e.getMessage());
            return null;
        }
    }
}
