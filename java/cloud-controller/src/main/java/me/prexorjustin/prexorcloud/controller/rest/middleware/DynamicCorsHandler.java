package me.prexorjustin.prexorcloud.controller.rest.middleware;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;

/**
 * CORS handler whose allow-list is read on every request, so changes via
 * {@link CorsAllowList#add} are visible immediately — no Javalin restart.
 * <p>
 * Replaces {@code config.bundledPlugins.enableCors(...)}. Wire it as the first
 * {@code before} handler in the chain; pair with a catch-all OPTIONS handler
 * that returns 204 so preflight requests don't fall through to a 404.
 */
public final class DynamicCorsHandler implements Handler {

    private static final String ALLOWED_METHODS = "GET, POST, PUT, PATCH, DELETE, OPTIONS";
    private static final String DEFAULT_ALLOWED_HEADERS = "Authorization, Content-Type, X-Requested-With";

    private final CorsAllowList allowList;

    public DynamicCorsHandler(CorsAllowList allowList) {
        this.allowList = allowList;
    }

    @Override
    public void handle(@NotNull Context ctx) {
        String origin = ctx.header("Origin");
        if (origin == null || origin.isBlank()) {
            // Not a CORS request — nothing to do.
            return;
        }
        if (!allowList.allows(origin)) {
            // Don't echo the origin back; browser will block the request because no
            // Access-Control-Allow-Origin header is set. Keep the request alive so the
            // server still logs/metricises the call.
            return;
        }
        ctx.header("Access-Control-Allow-Origin", origin);
        ctx.header("Access-Control-Allow-Credentials", "true");
        ctx.header("Vary", "Origin");

        if ("OPTIONS".equalsIgnoreCase(ctx.method().name())) {
            String requestedHeaders = ctx.header("Access-Control-Request-Headers");
            ctx.header("Access-Control-Allow-Methods", ALLOWED_METHODS);
            ctx.header(
                    "Access-Control-Allow-Headers",
                    requestedHeaders != null && !requestedHeaders.isBlank()
                            ? requestedHeaders
                            : DEFAULT_ALLOWED_HEADERS);
            ctx.header("Access-Control-Max-Age", "3600");
            ctx.status(204);
            // Short-circuit: no endpoint handler is registered for OPTIONS, so without
            // this we'd fall through to a 404. skipRemainingHandlers commits the empty
            // 204 response.
            ctx.skipRemainingHandlers();
        }
    }
}
