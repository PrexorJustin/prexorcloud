package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.audit;

import java.util.Map;

import me.prexorjustin.prexorcloud.api.event.events.MaintenanceUpdatedEvent;
import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.config.MaintenanceConfig;
import me.prexorjustin.prexorcloud.controller.rest.dto.ErrorResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.MaintenanceDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class MaintenanceRoutes {

    private final PrexorController controller;

    public MaintenanceRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        path("/api/v1/maintenance", () -> {
            get(this::getMaintenanceConfig);
            put(this::updateMaintenanceConfig);
        });
    }

    @OpenApi(
            path = "/api/v1/maintenance",
            methods = {HttpMethod.GET},
            operationId = "getMaintenanceConfig",
            summary = "Get maintenance config",
            description = "Returns the current maintenance mode configuration.",
            tags = {"Maintenance"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Maintenance configuration",
                        content = {@OpenApiContent(from = MaintenanceConfig.class)}),
                @OpenApiResponse(
                        status = "401",
                        description = "Unauthorized",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "403",
                        description = "Forbidden",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void getMaintenanceConfig(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.GROUPS_VIEW);
        var config = controller.config().maintenance();
        ctx.json(MaintenanceDtoMapper.toDto(config));
    }

    @OpenApi(
            path = "/api/v1/maintenance",
            methods = {HttpMethod.PUT},
            operationId = "updateMaintenanceConfig",
            summary = "Update maintenance config",
            description = "Update the maintenance mode configuration.",
            tags = {"Maintenance"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = MaintenanceConfig.class)}),
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Maintenance configuration updated",
                        content = {@OpenApiContent(from = MaintenanceConfig.class)}),
                @OpenApiResponse(
                        status = "400",
                        description = "Invalid configuration",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "401",
                        description = "Unauthorized",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "403",
                        description = "Forbidden",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void updateMaintenanceConfig(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.GROUPS_UPDATE);
        var update = ctx.bodyAsClass(MaintenanceConfig.class);

        var old = controller.config();
        var updated = new me.prexorjustin.prexorcloud.controller.config.ControllerConfig(
                old.uuid(),
                old.http(),
                old.grpc(),
                old.network(),
                old.database(),
                old.logging(),
                old.scheduler(),
                old.heartbeat(),
                old.runtime(),
                old.security(),
                old.crashes(),
                old.metrics(),
                old.modules(),
                update,
                old.dashboard(),
                old.backup(),
                old.share());
        controller.updateConfig(updated);

        audit(
                ctx,
                controller.stateStore(),
                "maintenance.update",
                "maintenance",
                "global",
                Map.of("enabled", update.enabled()));

        controller.eventBus().publish(new MaintenanceUpdatedEvent(update.enabled(), update.message()));

        ctx.json(MaintenanceDtoMapper.toDto(update));
    }
}
