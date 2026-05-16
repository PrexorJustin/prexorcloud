package me.prexorjustin.prexorcloud.controller.rest.middleware;

import java.util.Set;

import me.prexorjustin.prexorcloud.controller.auth.JwtRevocationStore;
import me.prexorjustin.prexorcloud.controller.auth.Role;
import me.prexorjustin.prexorcloud.controller.rest.RestServer;
import me.prexorjustin.prexorcloud.security.jwt.JwtManager;

import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.Handler;
import io.javalin.http.UnauthorizedResponse;
import org.jetbrains.annotations.NotNull;

/**
 * JWT authentication middleware. Validates Bearer tokens and stores the
 * authenticated user's identity as Javalin context attributes (username, role)
 * for downstream route handlers.
 */
public final class JwtAuthMiddleware implements Handler {

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/password-reset/request",
            "/api/v1/auth/password-reset/complete",
            "/api/v1/system/health",
            "/api/v1/system/ready",
            "/api/v1/system/version",
            "/api/v1/events/stream",
            // Bootstrap exchange uses the join token itself as auth — no JWT is
            // present yet when a fresh daemon host's CLI calls it.
            "/api/v1/bootstrap/exchange");

    // SSE console endpoints authenticate via short-lived tickets inside the
    // stream handler instead of Bearer headers.
    private static final String CONSOLE_PATH_PREFIX = "/api/v1/services/";

    private final JwtManager jwtManager;
    private final JwtRevocationStore revocationStore;

    public JwtAuthMiddleware(JwtManager jwtManager, JwtRevocationStore revocationStore) {
        this.jwtManager = jwtManager;
        this.revocationStore = revocationStore;
    }

    public JwtAuthMiddleware(JwtManager jwtManager) {
        this(jwtManager, null);
    }

    @Override
    public void handle(@NotNull Context ctx) {
        // Skip CORS preflight requests -- the CorsPlugin handles them
        if (ctx.method().name().equals("OPTIONS")) {
            return;
        }

        String path = ctx.path();

        // Skip public endpoints
        if (PUBLIC_PATHS.contains(path) || path.equals("/metrics")) {
            return;
        }

        // SSE console streams authenticate via short-lived tickets inside
        // ConsoleStreamer.
        if (path.startsWith(CONSOLE_PATH_PREFIX) && path.endsWith("/console")) {
            return;
        }

        // SSE controller log stream authenticates via short-lived ticket inside
        // LogStreamer (ticket issuance at POST /api/v1/system/logs/ticket is
        // permission-gated on SYSTEM_LOGS_VIEW).
        if (path.equals("/api/v1/system/logs/stream")) {
            return;
        }

        // SSE daemon log stream authenticates via short-lived ticket inside
        // DaemonLogStreamer (ticket issuance at POST /api/v1/nodes/{id}/logs/ticket
        // is permission-gated on SYSTEM_LOGS_VIEW).
        if (path.matches("/api/v1/nodes/[^/]+/logs/stream")) {
            return;
        }

        // Proxy and game-server plugin endpoints authenticate via plugin token (not
        // JWT)
        if (path.startsWith("/api/proxy/") || path.startsWith("/api/plugin/")) {
            return;
        }

        // Avatar images are served publicly (loaded via <img src>)
        if (ctx.method().name().equals("GET") && path.matches("/api/v1/users/[a-z0-9._-]+/avatar")) {
            return;
        }

        // Module frontend assets are served publicly (loaded via <script>/<link> tags
        // which cannot send auth headers)
        if (ctx.method().name().equals("GET") && path.matches("/api/v1/modules/[a-z0-9._-]+/frontend/.*")) {
            return;
        }

        String authHeader = ctx.header("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new UnauthorizedResponse("Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7);
        var claimsOpt = jwtManager.validate(token);
        if (claimsOpt.isEmpty()) {
            throw new UnauthorizedResponse("Invalid or expired token");
        }

        var claims = claimsOpt.get();
        String jti = claims.getId();
        if (jti != null && revocationStore != null && revocationStore.isRevoked(jti)) {
            throw new UnauthorizedResponse("Token has been revoked");
        }
        ctx.attribute("username", claims.getSubject());
        ctx.attribute("role", claims.get("role", String.class));
    }

    /**
     * Check if the current user has the required permission.
     */
    public static void requirePermission(Context ctx, String permission) {
        String role = ctx.attribute("role");
        if (role == null || !Role.hasPermission(role, permission)) {
            ctx.status(403);
            ctx.json(RestServer.errorResponse("FORBIDDEN", "Insufficient permissions", 403));
            throw new ForbiddenResponse("Insufficient permissions");
        }
    }
}
