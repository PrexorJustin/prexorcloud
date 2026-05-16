package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.rest.dto.ErrorResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.MetricsDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.dto.MetricsSummaryDto;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class MetricsRoutes {

    private final PrexorController controller;

    public MetricsRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        path("/api/v1/metrics", () -> get("/summary", this::getMetricsSummary));
    }

    @OpenApi(
            path = "/api/v1/metrics/summary",
            methods = {HttpMethod.GET},
            operationId = "getMetricsSummary",
            summary = "Metrics summary",
            description = "Returns a summary of key metrics.",
            tags = {"Metrics"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Metrics summary",
                        content = {@OpenApiContent(from = MetricsSummaryDto.class)}),
                @OpenApiResponse(
                        status = "401",
                        description = "Unauthorized",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "403",
                        description = "Forbidden",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void getMetricsSummary(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.METRICS_VIEW);
        var state = controller.clusterState();
        ctx.json(MetricsDtoMapper.summaryDto(
                state.nodeCount(),
                state.instanceCount(),
                state.playerCount(),
                controller.groupManager().getAll().size(),
                controller.crashStore().size()));
    }
}
