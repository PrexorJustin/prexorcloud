package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.audit;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.rest.ApiResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.CreateJoinTokenRequest;
import me.prexorjustin.prexorcloud.controller.rest.dto.CreateJoinTokenResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.ErrorResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.JoinTokenPage;
import me.prexorjustin.prexorcloud.controller.rest.dto.TokenDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class TokenRoutes {

    private final PrexorController controller;

    public TokenRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        path("/api/v1/admin/tokens", () -> {
            get(this::listJoinTokens);
            post(this::createJoinToken);
            delete("/{id}", this::revokeJoinToken);
        });
    }

    @OpenApi(
            path = "/api/v1/admin/tokens",
            methods = {HttpMethod.GET},
            operationId = "listJoinTokens",
            summary = "List join tokens",
            description = "Returns all join tokens.",
            tags = {"Tokens"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            queryParams = {
                @OpenApiParam(name = "page", type = Integer.class),
                @OpenApiParam(name = "pageSize", type = Integer.class)
            },
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Token list",
                        content = {@OpenApiContent(from = JoinTokenPage.class)}),
                @OpenApiResponse(
                        status = "401",
                        description = "Unauthorized",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "403",
                        description = "Forbidden",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void listJoinTokens(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TOKENS_VIEW);
        var tokens = controller.joinTokenStore().list().stream()
                .map(TokenDtoMapper::toDto)
                .toList();
        ApiResponse.writeList(ctx, tokens, 100);
    }

    @OpenApi(
            path = "/api/v1/admin/tokens",
            methods = {HttpMethod.POST},
            operationId = "createJoinToken",
            summary = "Create join token",
            description =
                    "Create a join token for a node. Returns 409 if the node already exists, is currently connected, or has a pending token.",
            tags = {"Tokens"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = CreateJoinTokenRequest.class)}),
            responses = {
                @OpenApiResponse(
                        status = "201",
                        description = "Join token created",
                        content = {@OpenApiContent(from = CreateJoinTokenResponse.class)}),
                @OpenApiResponse(
                        status = "400",
                        description = "Bad request",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "401",
                        description = "Unauthorized",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "403",
                        description = "Forbidden",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "409",
                        description = "Node already exists, connected, or has pending token",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void createJoinToken(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TOKENS_CREATE);
        @SuppressWarnings("unchecked")
        var body = (java.util.Map<String, Object>) ctx.bodyAsClass(java.util.LinkedHashMap.class);
        String nodeId = (String) body.get("nodeId");

        if (nodeId == null || nodeId.isBlank()) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "nodeId is required", 400));
            return;
        }

        if (controller.clusterState().getNode(nodeId).isPresent()) {
            ctx.status(409);
            ctx.json(errorResponse("CONFLICT", "Node '" + nodeId + "' is already connected", 409));
            return;
        }
        if (controller.stateStore().getRegisteredNode(nodeId).isPresent()) {
            ctx.status(409);
            ctx.json(errorResponse("CONFLICT", "Node '" + nodeId + "' is already registered", 409));
            return;
        }
        boolean hasPendingToken = controller.joinTokenStore().list().stream()
                .anyMatch(t -> !t.isExpired() && t.nodeId().equals(nodeId));
        if (hasPendingToken) {
            ctx.status(409);
            ctx.json(errorResponse("CONFLICT", "Node '" + nodeId + "' already has a pending token", 409));
            return;
        }

        int ttl = parseTtlSeconds(body);

        var result = controller.joinTokenStore().create(nodeId, ttl);
        audit(
                ctx,
                controller.stateStore(),
                "token.create",
                "token",
                result.token().tokenId());
        ctx.status(201);
        ctx.json(TokenDtoMapper.createResponse(result, nodeId));
    }

    @OpenApi(
            path = "/api/v1/admin/tokens/{id}",
            methods = {HttpMethod.DELETE},
            operationId = "revokeJoinToken",
            summary = "Revoke join token",
            description = "Delete/revoke a join token by ID.",
            tags = {"Tokens"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true, description = "Token ID")},
            responses = {
                @OpenApiResponse(status = "204", description = "No Content"),
                @OpenApiResponse(
                        status = "401",
                        description = "Unauthorized",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "403",
                        description = "Forbidden",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "404",
                        description = "Token not found",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void revokeJoinToken(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TOKENS_REVOKE);
        String id = ctx.pathParam("id");
        controller.joinTokenStore().consume(id);
        audit(ctx, controller.stateStore(), "token.revoke", "token", id);
        ctx.status(204);
    }

    private static int parseTtlSeconds(java.util.Map<String, Object> body) {
        Object ttlSecondsVal = body.get("ttlSeconds");
        if (ttlSecondsVal instanceof Number n && n.intValue() > 0) {
            return n.intValue();
        }

        Object ttlVal = body.get("ttl");
        if (ttlVal instanceof String s && !s.isBlank()) {
            return parseDurationToSeconds(s);
        }

        return 3600;
    }

    private static int parseDurationToSeconds(String duration) {
        var matcher = java.util.regex.Pattern.compile("^(\\d+)([smhd])$")
                .matcher(duration.trim().toLowerCase());
        if (!matcher.matches()) {
            return 3600;
        }
        int value = Integer.parseInt(matcher.group(1));
        return switch (matcher.group(2)) {
            case "s" -> value;
            case "m" -> value * 60;
            case "h" -> value * 3600;
            case "d" -> value * 86400;
            default -> 3600;
        };
    }
}
