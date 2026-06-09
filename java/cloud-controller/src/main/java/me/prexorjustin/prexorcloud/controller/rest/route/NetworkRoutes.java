package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.delete;
import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.path;
import static io.javalin.apibuilder.ApiBuilder.post;
import static io.javalin.apibuilder.ApiBuilder.put;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.auditDiff;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.requireFound;

import me.prexorjustin.prexorcloud.api.domain.NetworkComposition;
import me.prexorjustin.prexorcloud.common.util.InputValidator;
import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.rest.ApiResponse;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class NetworkRoutes {

    private final PrexorController controller;

    public NetworkRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        path("/api/v1/networks", () -> {
            get(this::listNetworks);
            post(this::createNetwork);
            get("/{name}", this::getNetwork);
            put("/{name}", this::updateNetwork);
            delete("/{name}", this::deleteNetwork);
        });
    }

    @OpenApi(
            path = "/api/v1/networks",
            methods = {HttpMethod.GET},
            operationId = "listNetworks",
            summary = "List network compositions",
            description = "Returns all configured network compositions.",
            tags = {"Networks"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "OK",
                        content = {@OpenApiContent(from = NetworkComposition[].class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden")
            })
    private void listNetworks(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.NETWORKS_VIEW);
        ApiResponse.writeList(ctx, controller.networkManager().snapshot(), 500);
    }

    @OpenApi(
            path = "/api/v1/networks",
            methods = {HttpMethod.POST},
            operationId = "createNetwork",
            summary = "Create network composition",
            description =
                    "Creates a new network composition. The name is validated via InputValidator.requireSafeName and must match [a-z0-9_][a-z0-9_-]*. All referenced groups (lobbyGroup, fallbackGroups, memberGroups, proxyGroups) must already exist; proxyGroups entries must reference proxy-platform groups.",
            tags = {"Networks"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = NetworkComposition.class)}),
            responses = {
                @OpenApiResponse(
                        status = "201",
                        description = "Created",
                        content = {@OpenApiContent(from = NetworkComposition.class)}),
                @OpenApiResponse(status = "400", description = "Invalid name or referenced group"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "409", description = "Network already exists")
            })
    private void createNetwork(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.NETWORKS_CREATE);
        var network = ctx.bodyAsClass(NetworkComposition.class);
        InputValidator.requireSafeName(network.name(), "Network name");
        controller.networkManager().create(network);
        controller.networkStore().save(network);
        auditDiff(ctx, controller.stateStore(), "network.create", "network", network.name(), null, network);
        ctx.status(201);
        ctx.json(network);
    }

    @OpenApi(
            path = "/api/v1/networks/{name}",
            methods = {HttpMethod.GET},
            operationId = "getNetwork",
            summary = "Get network composition",
            tags = {"Networks"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true, description = "Network name")},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "OK",
                        content = {@OpenApiContent(from = NetworkComposition.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Network not found")
            })
    private void getNetwork(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.NETWORKS_VIEW);
        String name = ctx.pathParam("name");
        ctx.json(requireFound(controller.networkManager().get(name), "Network", name));
    }

    @OpenApi(
            path = "/api/v1/networks/{name}",
            methods = {HttpMethod.PUT},
            operationId = "updateNetwork",
            summary = "Update network composition",
            description =
                    "Replaces the network composition. The body name must match the path. Cross-reference validation against existing groups is re-applied.",
            tags = {"Networks"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true, description = "Network name")},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = NetworkComposition.class)}),
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "OK",
                        content = {@OpenApiContent(from = NetworkComposition.class)}),
                @OpenApiResponse(status = "400", description = "Invalid configuration or name mismatch"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Network not found")
            })
    private void updateNetwork(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.NETWORKS_UPDATE);
        String name = ctx.pathParam("name");
        var before = requireFound(controller.networkManager().get(name), "Network", name);
        var update = ctx.bodyAsClass(NetworkComposition.class);
        if (!update.name().equals(name)) {
            throw new IllegalArgumentException("Body name '" + update.name() + "' does not match path '" + name + "'");
        }
        controller.networkManager().update(update);
        controller.networkStore().save(update);
        auditDiff(ctx, controller.stateStore(), "network.update", "network", name, before, update);
        ctx.json(update);
    }

    @OpenApi(
            path = "/api/v1/networks/{name}",
            methods = {HttpMethod.DELETE},
            operationId = "deleteNetwork",
            summary = "Delete network composition",
            tags = {"Networks"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true, description = "Network name")},
            responses = {
                @OpenApiResponse(status = "204", description = "No Content"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Network not found")
            })
    private void deleteNetwork(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.NETWORKS_DELETE);
        String name = ctx.pathParam("name");
        var before = requireFound(controller.networkManager().get(name), "Network", name);
        controller.networkManager().delete(name);
        controller.networkStore().delete(name);
        auditDiff(ctx, controller.stateStore(), "network.delete", "network", name, before, null);
        ctx.status(204);
    }
}
