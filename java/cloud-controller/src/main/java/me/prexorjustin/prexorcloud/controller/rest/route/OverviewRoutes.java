package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.rest.dto.OverviewDto;
import me.prexorjustin.prexorcloud.controller.rest.dto.OverviewDtoMapper;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class OverviewRoutes {

    private final PrexorController controller;

    public OverviewRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        get("/api/v1/overview", this::getOverview);
    }

    @OpenApi(
            path = "/api/v1/overview",
            methods = {HttpMethod.GET},
            operationId = "getOverview",
            summary = "Dashboard overview",
            description =
                    "Returns high-level counts for the dashboard. Requires authentication but no specific permission.",
            tags = {"Overview"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Overview counts",
                        content = {@OpenApiContent(from = OverviewDto.class)}),
                @OpenApiResponse(status = "401", description = "Missing or invalid bearer token")
            })
    private void getOverview(Context ctx) {
        var state = controller.clusterState();
        ctx.json(OverviewDtoMapper.toDto(
                state.nodeCount(),
                state.instanceCount(),
                state.playerCount(),
                controller.groupManager().getAll().size()));
    }
}
