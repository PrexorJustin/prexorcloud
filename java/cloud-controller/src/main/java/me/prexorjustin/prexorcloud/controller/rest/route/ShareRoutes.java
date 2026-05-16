package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.requireFound;

import java.util.NoSuchElementException;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.rest.ApiResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.ShareListPage;
import me.prexorjustin.prexorcloud.controller.rest.dto.ShareRecordDto;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;
import me.prexorjustin.prexorcloud.controller.share.ShareContext;
import me.prexorjustin.prexorcloud.controller.share.ShareKind;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

/**
 * List / view / revoke routes for persisted {@code ShareRecord}s. Sibling to
 * the per-surface {@code POST .../share} endpoints; centralises the operator-
 * facing reverse side of the paste-share workflow.
 */
public final class ShareRoutes {

    private final PrexorController controller;

    public ShareRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        path("/api/v1/shares", () -> {
            get(this::listShares);
            get("/{id}", this::getShare);
            post("/{id}/revoke", this::revokeShare);
        });
    }

    @OpenApi(
            path = "/api/v1/shares",
            methods = {HttpMethod.GET},
            operationId = "listShares",
            summary = "List recent paste shares",
            description = "Returns recent share records (paginated, newest first). Filter by `kind` and `activeOnly`.",
            tags = {"Shares"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            queryParams = {
                @OpenApiParam(
                        name = "kind",
                        description =
                                "Filter by surface (CRASH, CONTROLLER_LOGS, DAEMON_LOGS, DIAGNOSTICS, INSTANCE_CONSOLE)"),
                @OpenApiParam(name = "activeOnly", type = Boolean.class, description = "Hide revoked entries"),
                @OpenApiParam(name = "page", type = Integer.class),
                @OpenApiParam(name = "pageSize", type = Integer.class)
            },
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Recent share records",
                        content = {@OpenApiContent(from = ShareListPage.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden")
            })
    private void listShares(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.SHARE_REVOKE);
        ShareKind kind = parseKind(ctx.queryParam("kind"));
        boolean activeOnly = Boolean.parseBoolean(
                ctx.queryParamAsClass("activeOnly", String.class).getOrDefault("false"));
        int page = Math.max(1, ctx.queryParamAsClass("page", Integer.class).getOrDefault(1));
        int pageSize =
                Math.clamp(ctx.queryParamAsClass("pageSize", Integer.class).getOrDefault(50), 1, 200);
        int offset = (page - 1) * pageSize;
        var records = controller.stateStore().getShareRecords(kind, activeOnly, pageSize, offset).stream()
                .map(ShareRecordDto::from)
                .toList();
        int total = controller.stateStore().countShareRecords(kind, activeOnly);
        ApiResponse.paginated(ctx, records, total, page, pageSize);
    }

    @OpenApi(
            path = "/api/v1/shares/{id}",
            methods = {HttpMethod.GET},
            operationId = "getShare",
            summary = "Get a paste share by id",
            tags = {"Shares"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Share record",
                        content = {@OpenApiContent(from = ShareRecordDto.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Share not found")
            })
    private void getShare(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.SHARE_REVOKE);
        String id = ctx.pathParam("id");
        var record = requireFound(controller.stateStore().getShareRecord(id), "Share", id);
        ctx.json(ShareRecordDto.from(record));
    }

    @OpenApi(
            path = "/api/v1/shares/{id}/revoke",
            methods = {HttpMethod.POST},
            operationId = "revokeShare",
            summary = "Revoke a paste share",
            description =
                    "Calls pste DELETE using the stored delete token, marks the local record revoked, and audits.",
            tags = {"Shares"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Share revoked",
                        content = {@OpenApiContent(from = ShareRecordDto.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Share not found"),
                @OpenApiResponse(status = "409", description = "Already revoked, or sharing disabled"),
                @OpenApiResponse(status = "422", description = "No delete token on record"),
                @OpenApiResponse(status = "502", description = "Paste service unreachable")
            })
    private void revokeShare(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.SHARE_REVOKE);
        String id = ctx.pathParam("id");
        String username = ctx.attribute("username");
        try {
            var revoked = controller.shareService().revoke(id, ShareContext.of(username, ctx.ip()));
            ctx.json(ShareRecordDto.from(revoked));
        } catch (NoSuchElementException e) {
            throw new io.javalin.http.NotFoundResponse("Share " + id + " not found");
        }
    }

    private static ShareKind parseKind(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return ShareKind.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
