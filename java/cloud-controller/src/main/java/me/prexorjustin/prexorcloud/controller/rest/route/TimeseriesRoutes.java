package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.requireFound;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.rest.dto.ErrorResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.MetricsTimeseriesDto;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;
import me.prexorjustin.prexorcloud.controller.state.MetricsTimeseries;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class TimeseriesRoutes {

    private final PrexorController controller;

    public TimeseriesRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        get("/api/v1/overview/timeseries", this::getOverviewTimeseries);
        path("/api/v1/services", () -> {
            get("/{id}/metrics/timeseries", this::getInstanceMetricsTimeseries);
        });
        path("/api/v1/nodes", () -> {
            get("/{id}/metrics/timeseries", this::getNodeMetricsTimeseries);
        });
    }

    @OpenApi(
            path = "/api/v1/overview/timeseries",
            methods = {HttpMethod.GET},
            operationId = "getOverviewTimeseries",
            summary = "Overview timeseries",
            description =
                    "Bucketed history for cluster-wide counts (`players`, `instances`, `onlineNodes`). Sampled every 15s in-memory; rebuilt on controller restart. Empty buckets contain `null`.",
            tags = {"Overview"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            queryParams = {
                @OpenApiParam(name = "window", description = "Window size: 15m, 1h, 6h, or 24h. Default 1h."),
                @OpenApiParam(
                        name = "buckets",
                        type = Integer.class,
                        description = "Bucket count, clamped to [10, 360]. Default 60.")
            },
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "OK",
                        content = {@OpenApiContent(from = MetricsTimeseriesDto.class)}),
                @OpenApiResponse(
                        status = "400",
                        description = "Invalid window or buckets parameter",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized")
            })
    private void getOverviewTimeseries(Context ctx) {
        respond(
                ctx,
                (windowMs, buckets) ->
                        controller.metricsTimeseries().queryOverview(windowMs, buckets, System.currentTimeMillis()));
    }

    @OpenApi(
            path = "/api/v1/services/{id}/metrics/timeseries",
            methods = {HttpMethod.GET},
            operationId = "getInstanceMetricsTimeseries",
            summary = "Instance metrics timeseries",
            description =
                    "Bucketed history of instance metrics (`tps1m`, `msptAvg`, `heapUsedMb`, `playerCount`). Sampled every 15s; rebuilt on controller restart. Empty buckets contain `null`.",
            tags = {"Instances"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true, description = "Instance ID")},
            queryParams = {
                @OpenApiParam(name = "window", description = "Window size: 15m, 1h, 6h, or 24h. Default 1h."),
                @OpenApiParam(
                        name = "buckets",
                        type = Integer.class,
                        description = "Bucket count, clamped to [10, 360]. Default 60.")
            },
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "OK",
                        content = {@OpenApiContent(from = MetricsTimeseriesDto.class)}),
                @OpenApiResponse(status = "400", description = "Invalid window or buckets parameter"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Instance not found")
            })
    private void getInstanceMetricsTimeseries(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.INSTANCES_VIEW);
        String id = ctx.pathParam("id");
        requireFound(controller.clusterState().getInstance(id), "Instance", id);
        respond(
                ctx,
                (windowMs, buckets) -> controller
                        .metricsTimeseries()
                        .queryInstance(id, windowMs, buckets, System.currentTimeMillis()));
    }

    @OpenApi(
            path = "/api/v1/nodes/{id}/metrics/timeseries",
            methods = {HttpMethod.GET},
            operationId = "getNodeMetricsTimeseries",
            summary = "Node metrics timeseries",
            description = "Bucketed history of node metrics. Sampled every 15s; rebuilt on controller restart.",
            tags = {"Nodes"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true, description = "Node ID")},
            queryParams = {
                @OpenApiParam(name = "window", description = "Window size: 15m, 1h, 6h, or 24h. Default 1h."),
                @OpenApiParam(
                        name = "buckets",
                        type = Integer.class,
                        description = "Bucket count, clamped to [10, 360]. Default 60.")
            },
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "OK",
                        content = {@OpenApiContent(from = MetricsTimeseriesDto.class)}),
                @OpenApiResponse(status = "400", description = "Invalid window or buckets parameter"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Node not found")
            })
    private void getNodeMetricsTimeseries(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.NODES_VIEW);
        String id = ctx.pathParam("id");
        requireFound(controller.clusterState().getNode(id), "Node", id);
        respond(
                ctx,
                (windowMs, buckets) ->
                        controller.metricsTimeseries().queryNode(id, windowMs, buckets, System.currentTimeMillis()));
    }

    @FunctionalInterface
    private interface QueryFn {
        MetricsTimeseries.Response apply(long windowMs, int buckets);
    }

    private void respond(Context ctx, QueryFn fn) {
        long windowMs;
        int buckets;
        try {
            windowMs = MetricsTimeseries.parseWindowMs(ctx.queryParam("window"));
            buckets = MetricsTimeseries.parseBuckets(ctx.queryParam("buckets"));
        } catch (IllegalArgumentException e) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", e.getMessage(), 400));
            return;
        }
        ctx.json(fn.apply(windowMs, buckets));
    }
}
