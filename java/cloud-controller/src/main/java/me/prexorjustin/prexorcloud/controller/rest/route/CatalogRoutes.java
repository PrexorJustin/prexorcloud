package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.audit;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.rest.ApiResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.AddCatalogVersionRequest;
import me.prexorjustin.prexorcloud.controller.rest.dto.CatalogDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.dto.CatalogRecommendedResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.CatalogVersionResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.UpdateCatalogVersionRequest;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class CatalogRoutes {

    private final PrexorController controller;

    public CatalogRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        path("/api/v1/catalog", () -> {
            get(this::listCatalog);
            post("/{platform}/versions", this::addCatalogVersion);
            patch("/{platform}/versions/{version}", this::updateCatalogVersion);
            put("/{platform}/versions/{version}/recommended", this::setRecommendedCatalogVersion);
            delete("/{platform}/versions/{version}", this::deleteCatalogVersion);
        });
    }

    @OpenApi(
            path = "/api/v1/catalog",
            methods = {HttpMethod.GET},
            operationId = "listCatalog",
            summary = "List catalog entries",
            description = "Returns all catalog entries across all platforms.",
            tags = {"Catalog"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            queryParams = {
                @OpenApiParam(name = "page", type = Integer.class, description = "Page number (1-based)."),
                @OpenApiParam(name = "pageSize", type = Integer.class, description = "Items per page.")
            },
            responses = {
                @OpenApiResponse(status = "200", description = "Catalog entry list"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden")
            })
    private void listCatalog(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CATALOG_VIEW);
        var entries = controller.catalogStore().getAll();
        ApiResponse.writeList(ctx, entries, 100);
    }

    @OpenApi(
            path = "/api/v1/catalog/{platform}/versions",
            methods = {HttpMethod.POST},
            operationId = "addCatalogVersion",
            summary = "Add catalog version",
            description = "Adds a new version to the catalog for the specified platform.",
            tags = {"Catalog"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "platform", required = true)},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = AddCatalogVersionRequest.class)}),
            responses = {
                @OpenApiResponse(
                        status = "201",
                        description = "Version added",
                        content = {@OpenApiContent(from = CatalogVersionResponse.class)}),
                @OpenApiResponse(status = "400", description = "Invalid input"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "409", description = "Version already exists")
            })
    private void addCatalogVersion(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CATALOG_MANAGE);
        String platform = ctx.pathParam("platform");
        var req = ctx.bodyAsClass(AddCatalogVersionRequest.class);
        String category = req.category() != null ? req.category() : "SERVER";
        String configFormat = req.configFormat();
        boolean newPlatform = controller
                .catalogStore()
                .addEntry(platform, category, configFormat, req.version(), req.downloadUrl(), req.sha256());
        if (newPlatform) {
            controller.baseTemplateGenerator().ensurePlatformTemplate(platform, category, configFormat);
        }
        audit(ctx, controller.stateStore(), "catalog.add", "catalog", platform + "/" + req.version());
        ctx.status(201);
        ctx.json(CatalogDtoMapper.versionResponse(platform, req.version()));
    }

    @OpenApi(
            path = "/api/v1/catalog/{platform}/versions/{version}",
            methods = {HttpMethod.PATCH},
            operationId = "updateCatalogVersion",
            summary = "Update catalog version",
            description = "Updates metadata for an existing catalog version.",
            tags = {"Catalog"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {
                @OpenApiParam(name = "platform", required = true),
                @OpenApiParam(name = "version", required = true)
            },
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = UpdateCatalogVersionRequest.class)}),
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Version updated",
                        content = {@OpenApiContent(from = CatalogVersionResponse.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Platform or version not found")
            })
    private void updateCatalogVersion(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CATALOG_MANAGE);
        String platform = ctx.pathParam("platform");
        String oldVersion = ctx.pathParam("version");
        var req = ctx.bodyAsClass(UpdateCatalogVersionRequest.class);
        controller.catalogStore().updateEntry(platform, oldVersion, req.version(), req.downloadUrl(), req.sha256());
        audit(
                ctx,
                controller.stateStore(),
                "catalog.update",
                "catalog",
                platform + "/" + oldVersion + " -> " + req.version());
        ctx.json(CatalogDtoMapper.versionResponse(platform, req.version()));
    }

    @OpenApi(
            path = "/api/v1/catalog/{platform}/versions/{version}/recommended",
            methods = {HttpMethod.PUT},
            operationId = "setRecommendedCatalogVersion",
            summary = "Set version as recommended",
            description = "Marks the specified version as the recommended version for the platform.",
            tags = {"Catalog"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {
                @OpenApiParam(name = "platform", required = true),
                @OpenApiParam(name = "version", required = true)
            },
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Version set as recommended",
                        content = {@OpenApiContent(from = CatalogRecommendedResponse.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Platform or version not found")
            })
    private void setRecommendedCatalogVersion(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CATALOG_MANAGE);
        String platform = ctx.pathParam("platform");
        String version = ctx.pathParam("version");
        controller.catalogStore().setRecommended(platform, version);
        audit(ctx, controller.stateStore(), "catalog.recommend", "catalog", platform + "/" + version);
        ctx.json(CatalogDtoMapper.recommendedVersionResponse(platform, version));
    }

    @OpenApi(
            path = "/api/v1/catalog/{platform}/versions/{version}",
            methods = {HttpMethod.DELETE},
            operationId = "deleteCatalogVersion",
            summary = "Delete catalog version",
            description = "Removes a version from the catalog.",
            tags = {"Catalog"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {
                @OpenApiParam(name = "platform", required = true),
                @OpenApiParam(name = "version", required = true)
            },
            responses = {
                @OpenApiResponse(status = "204", description = "No Content"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Platform or version not found")
            })
    private void deleteCatalogVersion(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CATALOG_MANAGE);
        String platform = ctx.pathParam("platform");
        String version = ctx.pathParam("version");
        controller.catalogStore().removeEntry(platform, version);
        audit(ctx, controller.stateStore(), "catalog.remove", "catalog", platform + "/" + version);
        ctx.status(204);
    }
}
