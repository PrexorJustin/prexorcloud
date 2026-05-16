package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.auditDiff;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.requireFound;

import java.util.List;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.auth.RoleConfig;
import me.prexorjustin.prexorcloud.controller.rest.ApiResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.CreateRoleRequest;
import me.prexorjustin.prexorcloud.controller.rest.dto.ErrorResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.RoleDto;
import me.prexorjustin.prexorcloud.controller.rest.dto.RoleDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.dto.RoleListPage;
import me.prexorjustin.prexorcloud.controller.rest.dto.UpdateRoleRequest;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class RoleRoutes {

    private final PrexorController controller;

    public RoleRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        path("/api/v1/roles", () -> {
            get(this::listRoles);
            post(this::createRole);
            get("/{name}", this::getRoleByName);
            patch("/{name}", this::updateRole);
            delete("/{name}", this::deleteRole);
        });
    }

    @OpenApi(
            path = "/api/v1/roles",
            methods = {HttpMethod.GET},
            operationId = "listRoles",
            summary = "List roles",
            description = "Returns all roles including built-in and custom roles.",
            tags = {"Roles"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            queryParams = {
                @OpenApiParam(name = "page", type = Integer.class, description = "Page number (1-based)."),
                @OpenApiParam(name = "pageSize", type = Integer.class, description = "Items per page.")
            },
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Role list",
                        content = {@OpenApiContent(from = RoleListPage.class)}),
                @OpenApiResponse(
                        status = "401",
                        description = "Unauthorized",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "403",
                        description = "Forbidden",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void listRoles(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.ROLES_VIEW);
        var roles = controller.roleStore().loadAll().stream()
                .map(r -> RoleDtoMapper.toDto(r, countUsers(controller, r.name())))
                .toList();
        ApiResponse.writeList(ctx, roles, 100);
    }

    @OpenApi(
            path = "/api/v1/roles",
            methods = {HttpMethod.POST},
            operationId = "createRole",
            summary = "Create role",
            description = "Create a custom role. Name must match `[A-Z][A-Z0-9_]*` and be at most 32 characters.",
            tags = {"Roles"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = CreateRoleRequest.class)}),
            responses = {
                @OpenApiResponse(
                        status = "201",
                        description = "Role created",
                        content = {@OpenApiContent(from = RoleDto.class)}),
                @OpenApiResponse(
                        status = "400",
                        description = "Invalid role name or permissions",
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
                        description = "Role name already exists",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void createRole(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.ROLES_MANAGE);
        var req = ctx.bodyAsClass(CreateRoleRequest.class);

        if (req.name() == null || req.name().isBlank()) {
            throw new IllegalArgumentException("Role name is required");
        }

        String roleName = req.name().toUpperCase();

        if (!roleName.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("Role name must match [A-Z][A-Z0-9_]*");
        }

        if (roleName.length() > 32) {
            throw new IllegalArgumentException("Role name too long (max 32)");
        }

        var existing = controller.roleStore().get(roleName);
        if (existing.isPresent() && existing.get().builtIn()) {
            throw new IllegalStateException("Cannot overwrite built-in role: " + roleName);
        }
        if (existing.isPresent()) {
            throw new IllegalStateException("Role already exists: " + roleName);
        }

        List<String> permissions = req.permissions() != null ? req.permissions() : List.of();
        var role = new RoleConfig(roleName, permissions, false);
        controller.roleStore().save(role);

        auditDiff(ctx, controller.stateStore(), "role.create", "role", roleName, null, role);

        ctx.status(201);
        ctx.json(RoleDtoMapper.toDto(role, countUsers(controller, role.name())));
    }

    @OpenApi(
            path = "/api/v1/roles/{name}",
            methods = {HttpMethod.GET},
            operationId = "getRoleByName",
            summary = "Get role",
            description = "Returns a single role by its uppercase name.",
            tags = {"Roles"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true, description = "Uppercase role name")},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Role found",
                        content = {@OpenApiContent(from = RoleDto.class)}),
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
                        description = "Role not found",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void getRoleByName(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.ROLES_VIEW);
        String name = ctx.pathParam("name").toUpperCase();
        var role = requireFound(controller.roleStore().get(name), "Role", name);
        ctx.json(RoleDtoMapper.toDto(role, countUsers(controller, role.name())));
    }

    @OpenApi(
            path = "/api/v1/roles/{name}",
            methods = {HttpMethod.PATCH},
            operationId = "updateRole",
            summary = "Update role permissions",
            description = "Update the permissions of a custom role. ADMIN role permissions are immutable.",
            tags = {"Roles"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true, description = "Uppercase role name")},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = UpdateRoleRequest.class)}),
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Role updated",
                        content = {@OpenApiContent(from = RoleDto.class)}),
                @OpenApiResponse(
                        status = "400",
                        description = "Cannot modify ADMIN permissions",
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
                        status = "404",
                        description = "Role not found",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void updateRole(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.ROLES_MANAGE);
        String name = ctx.pathParam("name").toUpperCase();

        var existing = requireFound(controller.roleStore().get(name), "Role", name);

        var req = ctx.bodyAsClass(UpdateRoleRequest.class);

        List<String> permissions;
        if ("ADMIN".equals(name)) {
            permissions = existing.permissions();
        } else {
            permissions = req.permissions() != null ? req.permissions() : existing.permissions();
        }

        var updated = new RoleConfig(name, permissions, existing.builtIn());
        controller.roleStore().save(updated);

        auditDiff(ctx, controller.stateStore(), "role.update", "role", name, existing, updated);

        ctx.json(RoleDtoMapper.toDto(updated, countUsers(controller, updated.name())));
    }

    @OpenApi(
            path = "/api/v1/roles/{name}",
            methods = {HttpMethod.DELETE},
            operationId = "deleteRole",
            summary = "Delete role",
            description =
                    "Delete a custom role. Built-in roles cannot be deleted. Roles with assigned users cannot be deleted (409).",
            tags = {"Roles"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true, description = "Uppercase role name")},
            responses = {
                @OpenApiResponse(status = "204", description = "Role deleted"),
                @OpenApiResponse(
                        status = "401",
                        description = "Unauthorized",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "403",
                        description = "Forbidden — cannot delete built-in role",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "404",
                        description = "Role not found",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "409",
                        description = "Role has assigned users; reassign them first",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void deleteRole(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.ROLES_MANAGE);
        String name = ctx.pathParam("name").toUpperCase();

        var existing = requireFound(controller.roleStore().get(name), "Role", name);
        if (existing.builtIn()) {
            ctx.status(403);
            ctx.json(errorResponse("FORBIDDEN", "Cannot delete built-in role", 403));
            return;
        }

        long userCount = controller.authManager().getAllUsers().stream()
                .filter(u -> u.role().equals(name))
                .count();
        if (userCount > 0) {
            ctx.status(409);
            ctx.json(errorResponse(
                    "CONFLICT",
                    "Cannot delete role with %d assigned user(s). Reassign them first.".formatted(userCount),
                    409));
            return;
        }

        controller.roleStore().delete(name);

        auditDiff(ctx, controller.stateStore(), "role.delete", "role", name, existing, null);

        ctx.status(204);
    }

    private static long countUsers(PrexorController controller, String roleName) {
        return controller.authManager().getAllUsers().stream()
                .filter(u -> u.role().equals(roleName))
                .count();
    }
}
