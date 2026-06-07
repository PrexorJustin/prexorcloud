package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;

import java.util.UUID;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.rest.ApiResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.ErrorResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.PlayerDto;
import me.prexorjustin.prexorcloud.controller.rest.dto.PlayerDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.dto.PlayerPage;
import me.prexorjustin.prexorcloud.controller.rest.dto.TransferQueuedResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.TransferRequest;
import me.prexorjustin.prexorcloud.controller.rest.dto.WorkloadDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class PlayerRoutes {

    private final PrexorController controller;

    public PlayerRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        path("/api/v1/players", () -> {
            get(this::listPlayers);
            get("/{id}", this::getPlayer);
            post("/{id}/transfer", this::transferPlayer);
        });
    }

    @OpenApi(
            path = "/api/v1/players",
            methods = {HttpMethod.GET},
            operationId = "listPlayers",
            summary = "List online players",
            description = "Returns all currently online players across all instances.",
            tags = {"Players"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            queryParams = {
                @OpenApiParam(name = "page", type = Integer.class, description = "Page number (1-based)."),
                @OpenApiParam(name = "pageSize", type = Integer.class, description = "Items per page.")
            },
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Player list",
                        content = {@OpenApiContent(from = PlayerPage.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden")
            })
    private void listPlayers(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.PLAYERS_VIEW);
        var players = controller.clusterState().getAllPlayers().stream()
                .map(PlayerDtoMapper::toDto)
                .toList();
        ApiResponse.writeList(ctx, players, 500);
    }

    @OpenApi(
            path = "/api/v1/players/{id}",
            methods = {HttpMethod.GET},
            operationId = "getPlayer",
            summary = "Get player by UUID",
            description = "Returns a single player by their UUID. Returns 400 if the UUID format is invalid.",
            tags = {"Players"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true, description = "Player UUID")},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Player detail",
                        content = {@OpenApiContent(from = PlayerDto.class)}),
                @OpenApiResponse(
                        status = "400",
                        description = "Invalid UUID format",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(
                        status = "404",
                        description = "Player not found",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void getPlayer(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.PLAYERS_VIEW);
        UUID id;
        try {
            id = UUID.fromString(ctx.pathParam("id"));
        } catch (IllegalArgumentException _) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Invalid UUID format", 400));
            return;
        }
        var player = controller.clusterState().getPlayer(id);
        if (player.isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Player not found", 404));
            return;
        }
        ctx.json(PlayerDtoMapper.toDto(player.get()));
    }

    @OpenApi(
            path = "/api/v1/players/{id}/transfer",
            methods = {HttpMethod.POST},
            operationId = "transferPlayer",
            summary = "Transfer player to another instance",
            description =
                    "Queues a player transfer to the specified target instance. Returns 404 if the target instance does not exist.",
            tags = {"Players"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true, description = "Player UUID")},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = TransferRequest.class)}),
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Transfer queued",
                        content = {@OpenApiContent(from = TransferQueuedResponse.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(
                        status = "404",
                        description = "Player or target instance not found",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void transferPlayer(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.PLAYERS_TRANSFER);
        UUID id;
        try {
            id = UUID.fromString(ctx.pathParam("id"));
        } catch (IllegalArgumentException _) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Invalid UUID format", 400));
            return;
        }
        var player = controller.clusterState().getPlayer(id);
        if (player.isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Player not found", 404));
            return;
        }

        var req = ctx.bodyAsClass(TransferRequest.class);

        var target = controller.clusterState().getInstance(req.targetInstanceId());
        if (target.isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Target instance not found: " + req.targetInstanceId(), 404));
            return;
        }

        controller.workflowStateStore().queueTransfer(id, req.targetInstanceId());
        ctx.json(WorkloadDtoMapper.transferQueuedResponse(id, req.targetInstanceId()));
    }
}
