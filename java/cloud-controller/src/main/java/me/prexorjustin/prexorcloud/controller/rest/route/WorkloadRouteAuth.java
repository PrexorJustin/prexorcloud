package me.prexorjustin.prexorcloud.controller.rest.route;

import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;

import me.prexorjustin.prexorcloud.controller.PrexorController;

import io.javalin.http.Context;

final class WorkloadRouteAuth {

    private static final String SEQUENCE_HEADER = "X-Prexor-Sequence";

    private WorkloadRouteAuth() {}

    static String rotatePluginToken(Context ctx, PrexorController controller) {
        String currentToken = extractBearerToken(ctx);
        if (currentToken == null) {
            return null;
        }
        Long sequence = extractSequence(ctx);
        if (sequence == null) {
            return null;
        }
        var refreshed = controller.clusterState().refreshPluginToken(currentToken, sequence.longValue());
        if (refreshed.isEmpty()) {
            ctx.status(401);
            ctx.json(errorResponse("UNAUTHORIZED", "Invalid, expired, or replayed plugin token", 401));
            return null;
        }
        return refreshed.get();
    }

    // Non-sequenced token validation lives in WorkloadAuthFilter, registered as a
    // Javalin before(...) on /api/proxy/* and /api/plugin/*. Handlers retrieve
    // the authenticated instance id via WorkloadAuthFilter.instanceId(ctx).

    static String validateSequencedPluginToken(Context ctx, PrexorController controller) {
        String token = extractBearerToken(ctx);
        if (token == null) {
            return null;
        }
        Long sequence = extractSequence(ctx);
        if (sequence == null) {
            return null;
        }
        var instanceIdOpt = controller.clusterState().validatePluginToken(token, sequence.longValue());
        if (instanceIdOpt.isEmpty()) {
            ctx.status(401);
            ctx.json(errorResponse("UNAUTHORIZED", "Invalid, expired, or replayed plugin token", 401));
            return null;
        }
        return instanceIdOpt.get();
    }

    private static String extractBearerToken(Context ctx) {
        String authHeader = ctx.header("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            ctx.status(401);
            ctx.json(errorResponse("UNAUTHORIZED", "Missing plugin token", 401));
            return null;
        }
        return authHeader.substring(7);
    }

    private static Long extractSequence(Context ctx) {
        String rawSequence = ctx.header(SEQUENCE_HEADER);
        if (rawSequence == null || rawSequence.isBlank()) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Missing " + SEQUENCE_HEADER + " header", 400));
            return null;
        }
        try {
            long parsed = Long.parseLong(rawSequence);
            if (parsed <= 0) {
                throw new NumberFormatException("sequence must be positive");
            }
            return parsed;
        } catch (NumberFormatException _) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Invalid " + SEQUENCE_HEADER + " header", 400));
            return null;
        }
    }
}
