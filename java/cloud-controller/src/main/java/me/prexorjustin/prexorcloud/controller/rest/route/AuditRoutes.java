package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.rest.ApiResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.AuditDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.dto.AuditEntryPage;
import me.prexorjustin.prexorcloud.controller.rest.dto.ErrorResponse;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class AuditRoutes {

    private final PrexorController controller;

    public AuditRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        get("/api/v1/audit", this::listAuditLog);
    }

    @OpenApi(
            path = "/api/v1/audit",
            methods = {HttpMethod.GET},
            operationId = "listAuditLog",
            summary = "List audit log",
            description =
                    "Returns paginated audit log entries. `page` and `pageSize` are the canonical pagination parameters; legacy `offset` and `limit` aliases remain accepted for compatibility.",
            tags = {"Audit"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            queryParams = {
                @OpenApiParam(name = "page", type = Integer.class, description = "Page number (1-based)."),
                @OpenApiParam(name = "pageSize", type = Integer.class, description = "Items per page (1..500)."),
                @OpenApiParam(
                        name = "limit",
                        type = Integer.class,
                        deprecated = true,
                        description = "Legacy alias for `pageSize`."),
                @OpenApiParam(
                        name = "offset",
                        type = Integer.class,
                        deprecated = true,
                        description = "Legacy absolute record offset.")
            },
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Audit entries",
                        content = {@OpenApiContent(from = AuditEntryPage.class)}),
                @OpenApiResponse(
                        status = "401",
                        description = "Unauthorized",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "403",
                        description = "Forbidden",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void listAuditLog(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.AUDIT_VIEW);
        AuditPageRequest request = resolveAuditPageRequest(
                ctx.queryParam("page") != null
                        ? ctx.queryParamAsClass("page", Integer.class).getOrDefault(1)
                        : null,
                ctx.queryParam("pageSize") != null
                        ? ctx.queryParamAsClass("pageSize", Integer.class).getOrDefault(100)
                        : null,
                ctx.queryParam("offset") != null
                        ? ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0)
                        : null,
                ctx.queryParam("limit") != null
                        ? ctx.queryParamAsClass("limit", Integer.class).getOrDefault(100)
                        : null,
                500);
        var entries = controller.stateStore().getAuditLog(request.limit(), request.offset()).stream()
                .map(AuditDtoMapper::toDto)
                .toList();
        ApiResponse.paginated(
                ctx, entries, controller.stateStore().countAuditLog(), request.page(), request.pageSize());
    }

    static AuditPageRequest resolveAuditPageRequest(
            Integer pageParam, Integer pageSizeParam, Integer offsetParam, Integer limitParam, int maxPageSize) {
        boolean explicitPage = pageParam != null || pageSizeParam != null;
        if (explicitPage) {
            int page = Math.max(1, pageParam != null ? pageParam : 1);
            int pageSize = Math.clamp(pageSizeParam != null ? pageSizeParam : maxPageSize, 1, maxPageSize);
            return new AuditPageRequest(page, pageSize, (page - 1) * pageSize, pageSize);
        }

        int offset = Math.max(0, offsetParam != null ? offsetParam : 0);
        int limit = Math.clamp(limitParam != null ? limitParam : maxPageSize, 1, maxPageSize);
        int page = (offset / limit) + 1;
        return new AuditPageRequest(page, limit, offset, limit);
    }

    record AuditPageRequest(int page, int pageSize, int offset, int limit) {}
}
