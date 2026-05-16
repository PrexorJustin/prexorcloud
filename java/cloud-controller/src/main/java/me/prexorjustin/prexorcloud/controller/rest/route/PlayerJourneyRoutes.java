package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.path;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import me.prexorjustin.prexorcloud.api.domain.PlayerJourneyEntry;
import me.prexorjustin.prexorcloud.api.module.capability.PlayerJourneyTracker;
import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.rest.ApiResponse;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;
import me.prexorjustin.prexorcloud.modules.runtime.CapabilityRegistry;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class PlayerJourneyRoutes {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    private final PrexorController controller;

    public PlayerJourneyRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        path("/api/v1/players", () -> get("/{id}/journey", this::getPlayerJourney));
    }

    @OpenApi(
            path = "/api/v1/players/{id}/journey",
            methods = {HttpMethod.GET},
            operationId = "getPlayerJourney",
            summary = "Get a player's journey log",
            description =
                    "Returns the raw per-player event log (joins, proxy-observed transfers, crashes affecting the player, disconnects) recorded by the controller, newest first. This is an append-only audit log; modules layer typed stage interpretations on top of it in their own storage.",
            tags = {"Players"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true, description = "Player UUID")},
            queryParams = {
                @OpenApiParam(
                        name = "limit",
                        type = Integer.class,
                        description =
                                "Maximum number of entries to return (1..1000, default 100). Ignored when 'since' is provided."),
                @OpenApiParam(
                        name = "since",
                        description =
                                "ISO-8601 instant; returns entries with a timestamp >= this value. Mutually exclusive with 'limit'.")
            },
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Journey entries",
                        content = {@OpenApiContent(from = PlayerJourneyEntry[].class)}),
                @OpenApiResponse(status = "400", description = "Invalid UUID or 'since' value"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden")
            })
    private void getPlayerJourney(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.PLAYERS_VIEW);

        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(ctx.pathParam("id"));
        } catch (IllegalArgumentException _) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Invalid UUID format", 400));
            return;
        }

        PlayerJourneyTracker tracker = resolveTracker(controller);
        if (tracker == null) {
            ctx.status(503);
            ctx.json(errorResponse(
                    "CAPABILITY_UNAVAILABLE",
                    "prexor.player.journey provider is not active — install the player-journey module",
                    503));
            return;
        }

        String sinceParam = ctx.queryParam("since");
        if (sinceParam != null && !sinceParam.isBlank()) {
            Instant since;
            try {
                since = Instant.parse(sinceParam);
            } catch (DateTimeParseException _) {
                ctx.status(400);
                ctx.json(errorResponse("BAD_REQUEST", "Invalid 'since' (expected ISO-8601 instant)", 400));
                return;
            }
            List<PlayerJourneyEntry> entries = tracker.since(playerUuid, since);
            ApiResponse.writeList(ctx, entries, MAX_LIMIT);
            return;
        }

        int limit = parseLimit(ctx.queryParam("limit"));
        List<PlayerJourneyEntry> entries = tracker.recent(playerUuid, limit);
        ApiResponse.writeList(ctx, entries, MAX_LIMIT);
    }

    private static PlayerJourneyTracker resolveTracker(PrexorController controller) {
        CapabilityRegistry registry =
                controller.moduleRegistry().platformManager().capabilityRegistry();
        return registry.find(PlayerJourneyTracker.CAPABILITY_ID)
                .map(CapabilityRegistry.CapabilityBinding::handle)
                .filter(PlayerJourneyTracker.class::isInstance)
                .map(PlayerJourneyTracker.class::cast)
                .orElse(null);
    }

    private static int parseLimit(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_LIMIT;
        try {
            int value = Integer.parseInt(raw);
            if (value <= 0) return DEFAULT_LIMIT;
            return Math.min(value, MAX_LIMIT);
        } catch (NumberFormatException _) {
            return DEFAULT_LIMIT;
        }
    }
}
