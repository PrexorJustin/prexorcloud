package me.prexorjustin.prexorcloud.controller.rest.middleware;

import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;

import me.prexorjustin.prexorcloud.controller.PrexorController;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;

/**
 * Plugin/proxy workload-token auth filter. Registered on {@code /api/proxy/*}
 * and {@code /api/plugin/*} via Javalin {@code before(...)} so every workload
 * route is authenticated structurally — a new handler that forgets to call
 * the auth helper is now an impossibility.
 *
 * <p>Handlers retrieve the authenticated instance id via
 * {@link #instanceId(Context)}. Routes that additionally enforce a sequence
 * window for replay protection call {@code WorkloadRouteAuth.validateSequencedPluginToken}
 * (which atomically re-validates token + sequence against the cluster state).
 */
public final class WorkloadAuthFilter implements Handler {

    private static final String INSTANCE_ID_ATTR = "workloadInstanceId";

    private final PrexorController controller;

    public WorkloadAuthFilter(PrexorController controller) {
        this.controller = controller;
    }

    @Override
    public void handle(@NotNull Context ctx) {
        if (ctx.method().name().equals("OPTIONS")) {
            return;
        }
        // The refresh endpoint must accept a just-expired (within-grace) token to
        // bootstrap a fresh one -- which this strict filter would reject, permanently
        // locking out a plugin whose token lapsed. Let the refresh handler do its own
        // grace-aware validation (WorkloadRouteAuth.rotatePluginToken).
        if (ctx.path().endsWith("/auth/refresh")) {
            return;
        }
        String authHeader = ctx.header("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            ctx.status(401);
            ctx.json(errorResponse("UNAUTHORIZED", "Missing plugin token", 401));
            ctx.skipRemainingHandlers();
            return;
        }
        String token = authHeader.substring(7);
        var instanceIdOpt = controller.clusterState().validatePluginToken(token);
        if (instanceIdOpt.isEmpty()) {
            ctx.status(401);
            ctx.json(errorResponse("UNAUTHORIZED", "Invalid plugin token", 401));
            ctx.skipRemainingHandlers();
            return;
        }
        ctx.attribute(INSTANCE_ID_ATTR, instanceIdOpt.get());
    }

    /**
     * Returns the authenticated workload instance id stamped onto the context by
     * the filter. Never returns {@code null} for a request that reached a handler
     * under {@code /api/proxy/*} or {@code /api/plugin/*} — the filter rejects
     * unauthenticated requests before they get there.
     */
    public static String instanceId(Context ctx) {
        return ctx.attribute(INSTANCE_ID_ATTR);
    }
}
