package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.audit;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.auditDiff;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.rest.ApiResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.CreateUserRequest;
import me.prexorjustin.prexorcloud.controller.rest.dto.ErrorResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.LinkMinecraftRequest;
import me.prexorjustin.prexorcloud.controller.rest.dto.UpdateUserRequest;
import me.prexorjustin.prexorcloud.controller.rest.dto.UserDto;
import me.prexorjustin.prexorcloud.controller.rest.dto.UserDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UserRoutes {

    private static final Logger logger = LoggerFactory.getLogger(UserRoutes.class);
    private static final Path AVATARS_DIR = Path.of("data", "avatars");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp");
    private static final long MAX_AVATAR_SIZE = 5 * 1024 * 1024; // 5MB
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_PREFERENCES = """
            {
              "notifications": {
                "NODE_CONNECTED": true,
                "NODE_DISCONNECTED": true,
                "INSTANCE_SCHEDULED": false,
                "INSTANCE_STARTED": false,
                "INSTANCE_STOPPED": false,
                "INSTANCE_CRASHED": true,
                "INSTANCE_STATE_CHANGED": false,
                "PLAYER_CONNECTED": false,
                "PLAYER_DISCONNECTED": false,
                "GROUP_CREATED": true,
                "GROUP_UPDATED": false,
                "GROUP_DELETED": true,
                "GROUP_CRASH_LOOP": true,
                "DEPLOYMENT_CREATED": false,
                "DEPLOYMENT_COMPLETED": true,
                "DEPLOYMENT_ROLLED_BACK": true,
                "TEMPLATE_UPDATED": false
              },
              "defaultLandingPage": "/",
              "theme": "system",
              "sidebarExpanded": true
            }
            """;

    private final PrexorController controller;

    public UserRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        path("/api/v1/users", () -> {
            get(this::listUsers);
            post(this::createUser);
            get("/{username}", this::getUser);
            patch("/{username}", this::updateUser);
            delete("/{username}", this::deleteUser);
            post("/{username}/avatar", this::uploadAvatar);
            get("/{username}/avatar", this::getAvatar);
            delete("/{username}/avatar", this::deleteAvatar);
            get("/{username}/preferences", this::getUserPreferences);
            put("/{username}/preferences", this::updateUserPreferences);
            put("/{username}/minecraft", this::linkMinecraft);
            delete("/{username}/minecraft", this::unlinkMinecraft);
        });
    }

    @OpenApi(
            path = "/api/v1/users",
            methods = {HttpMethod.GET},
            operationId = "listUsers",
            summary = "List users",
            tags = {"Users"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            queryParams = {
                @OpenApiParam(name = "page", type = Integer.class),
                @OpenApiParam(name = "pageSize", type = Integer.class)
            },
            responses = {
                @OpenApiResponse(status = "200", description = "User list"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden")
            })
    private void listUsers(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.USERS_VIEW);
        var users = controller.authManager().getAllUsers().stream()
                .map(UserDtoMapper::toDto)
                .toList();
        ApiResponse.writeList(ctx, users, 200);
    }

    @OpenApi(
            path = "/api/v1/users",
            methods = {HttpMethod.POST},
            operationId = "createUser",
            summary = "Create user",
            tags = {"Users"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = CreateUserRequest.class)}),
            responses = {
                @OpenApiResponse(
                        status = "201",
                        description = "User created",
                        content = {@OpenApiContent(from = UserDto.class)}),
                @OpenApiResponse(status = "400", description = "Invalid input"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "409", description = "User already exists")
            })
    private void createUser(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.USERS_CREATE);
        var req = ctx.bodyAsClass(CreateUserRequest.class);
        var user = controller.authManager().createUser(req.username(), req.password(), req.role());
        auditDiff(ctx, controller.stateStore(), "user.create", "user", req.username(), null, UserDtoMapper.toDto(user));
        ctx.status(201);
        ctx.json(UserDtoMapper.toDto(user));
    }

    @OpenApi(
            path = "/api/v1/users/{username}",
            methods = {HttpMethod.GET},
            operationId = "getUser",
            summary = "Get user",
            tags = {"Users"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "username", required = true)},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "User",
                        content = {@OpenApiContent(from = UserDto.class)}),
                @OpenApiResponse(
                        status = "404",
                        description = "User not found",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void getUser(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.USERS_VIEW);
        String username = ctx.pathParam("username");
        var user = controller.authManager().getUser(username);
        if (user.isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "User not found", 404));
            return;
        }
        ctx.json(UserDtoMapper.toDto(user.get()));
    }

    @OpenApi(
            path = "/api/v1/users/{username}",
            methods = {HttpMethod.PATCH},
            operationId = "updateUser",
            summary = "Update user",
            description =
                    "Self-update: users can change their own username. Changing role or password of others requires users.update permission.",
            tags = {"Users"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "username", required = true)},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = UpdateUserRequest.class)}),
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "User updated",
                        content = {@OpenApiContent(from = UserDto.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "User not found")
            })
    private void updateUser(Context ctx) {
        String targetUsername = ctx.pathParam("username");
        String callerUsername = ctx.attribute("username");
        boolean isSelf = callerUsername.equals(targetUsername);

        var req = ctx.bodyAsClass(UpdateUserRequest.class);

        if (!isSelf || req.role() != null || req.password() != null) {
            JwtAuthMiddleware.requirePermission(ctx, Permission.USERS_UPDATE);
        }

        var beforeUser = controller.authManager().getUser(targetUsername);
        controller.authManager().updateUser(targetUsername, req.username(), req.role(), req.password());
        var updated = controller
                .authManager()
                .getUser(req.username() != null ? req.username().toLowerCase() : targetUsername);
        auditDiff(
                ctx,
                controller.stateStore(),
                "user.update",
                "user",
                targetUsername,
                beforeUser.map(UserDtoMapper::toDto).orElse(null),
                updated.map(UserDtoMapper::toDto).orElse(null));
        ctx.json(UserDtoMapper.toDto(updated.orElseThrow()));
    }

    @OpenApi(
            path = "/api/v1/users/{username}",
            methods = {HttpMethod.DELETE},
            operationId = "deleteUser",
            summary = "Delete user",
            tags = {"Users"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "username", required = true)},
            responses = {
                @OpenApiResponse(status = "204", description = "No Content"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "User not found")
            })
    private void deleteUser(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.USERS_DELETE);
        String username = ctx.pathParam("username");
        var beforeUser = controller.authManager().getUser(username);
        controller.authManager().deleteUser(username);
        auditDiff(
                ctx,
                controller.stateStore(),
                "user.delete",
                "user",
                username,
                beforeUser.map(UserDtoMapper::toDto).orElse(null),
                null);
        ctx.status(204);
    }

    @OpenApi(
            path = "/api/v1/users/{username}/avatar",
            methods = {HttpMethod.POST},
            operationId = "uploadAvatar",
            summary = "Upload user avatar",
            description = "Multipart form upload. Accepts PNG/JPG/GIF/WebP up to 5 MB.",
            tags = {"Users"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "username", required = true)},
            responses = {
                @OpenApiResponse(status = "200", description = "Avatar updated"),
                @OpenApiResponse(
                        status = "400",
                        description = "Validation error",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "User not found")
            })
    private void uploadAvatar(Context ctx) throws Exception {
        String targetUsername = ctx.pathParam("username");
        String callerUsername = ctx.attribute("username");
        if (!callerUsername.equals(targetUsername)) {
            JwtAuthMiddleware.requirePermission(ctx, Permission.USERS_UPDATE);
        }

        var user = controller.authManager().getUser(targetUsername);
        if (user.isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "User not found", 404));
            return;
        }

        var uploadedFile = ctx.uploadedFile("avatar");
        if (uploadedFile == null) {
            ctx.status(400);
            ctx.json(errorResponse("VALIDATION_ERROR", "No avatar file provided", 400));
            return;
        }

        if (uploadedFile.size() > MAX_AVATAR_SIZE) {
            ctx.status(400);
            ctx.json(errorResponse("VALIDATION_ERROR", "Avatar must be smaller than 5MB", 400));
            return;
        }

        String originalName = uploadedFile.filename();
        String ext = originalName.contains(".")
                ? originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase()
                : "";
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            ctx.status(400);
            ctx.json(errorResponse("VALIDATION_ERROR", "Allowed formats: PNG, JPG, GIF, WebP", 400));
            return;
        }

        String oldPath = user.get().avatarPath();
        if (oldPath != null) {
            try {
                Files.deleteIfExists(Path.of(oldPath));
            } catch (IOException e) {
                logger.warn("Failed to delete old avatar: {}", oldPath, e);
            }
        }

        Files.createDirectories(AVATARS_DIR);
        String filename = targetUsername + "." + ext;
        Path target = AVATARS_DIR.resolve(filename);
        try (var input = uploadedFile.content()) {
            Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        String avatarPath = target.toString();
        controller.authManager().updateUserAvatar(targetUsername, avatarPath);

        ctx.json(UserDtoMapper.avatarResponse(targetUsername));
    }

    @OpenApi(
            path = "/api/v1/users/{username}/avatar",
            methods = {HttpMethod.GET},
            operationId = "getAvatar",
            summary = "Get user avatar image",
            tags = {"Users"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "username", required = true)},
            responses = {
                @OpenApiResponse(status = "200", description = "Avatar image"),
                @OpenApiResponse(
                        status = "404",
                        description = "Avatar not found",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void getAvatar(Context ctx) throws Exception {
        String targetUsername = ctx.pathParam("username");
        var user = controller.authManager().getUser(targetUsername);
        if (user.isEmpty() || user.get().avatarPath() == null) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "No avatar set", 404));
            return;
        }

        Path avatarFile = Path.of(user.get().avatarPath());
        if (!Files.exists(avatarFile)) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Avatar file missing", 404));
            return;
        }

        String contentType = Files.probeContentType(avatarFile);
        if (contentType == null) contentType = "application/octet-stream";
        ctx.contentType(contentType);
        ctx.header("Cache-Control", "public, max-age=3600");
        ctx.result(Files.newInputStream(avatarFile));
    }

    @OpenApi(
            path = "/api/v1/users/{username}/avatar",
            methods = {HttpMethod.DELETE},
            operationId = "deleteAvatar",
            summary = "Delete user avatar",
            tags = {"Users"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "username", required = true)},
            responses = {
                @OpenApiResponse(status = "204", description = "No Content"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "User not found")
            })
    private void deleteAvatar(Context ctx) {
        String targetUsername = ctx.pathParam("username");
        String callerUsername = ctx.attribute("username");
        if (!callerUsername.equals(targetUsername)) {
            JwtAuthMiddleware.requirePermission(ctx, Permission.USERS_UPDATE);
        }

        var user = controller.authManager().getUser(targetUsername);
        if (user.isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "User not found", 404));
            return;
        }

        String avatarPath = user.get().avatarPath();
        if (avatarPath != null) {
            try {
                Files.deleteIfExists(Path.of(avatarPath));
            } catch (IOException e) {
                logger.warn("Failed to delete avatar file: {}", avatarPath, e);
            }
            controller.authManager().updateUserAvatar(targetUsername, null);
        }

        ctx.status(204);
    }

    @OpenApi(
            path = "/api/v1/users/{username}/preferences",
            methods = {HttpMethod.GET},
            operationId = "getUserPreferences",
            summary = "Get user preferences",
            tags = {"Users"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "username", required = true)},
            responses = {@OpenApiResponse(status = "200", description = "Preferences (JSON blob)")})
    private void getUserPreferences(Context ctx) {
        String targetUsername = ctx.pathParam("username");
        String callerUsername = ctx.attribute("username");
        if (!callerUsername.equals(targetUsername)) {
            JwtAuthMiddleware.requirePermission(ctx, Permission.USERS_VIEW);
        }
        var prefs = controller.stateStore().getUserPreferences(targetUsername);
        ctx.contentType("application/json");
        ctx.result(prefs.orElse(DEFAULT_PREFERENCES));
    }

    @OpenApi(
            path = "/api/v1/users/{username}/preferences",
            methods = {HttpMethod.PUT},
            operationId = "updateUserPreferences",
            summary = "Update user preferences",
            tags = {"Users"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "username", required = true)},
            responses = {
                @OpenApiResponse(status = "200", description = "Preferences (JSON blob)"),
                @OpenApiResponse(
                        status = "400",
                        description = "Invalid JSON",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void updateUserPreferences(Context ctx) {
        String targetUsername = ctx.pathParam("username");
        String callerUsername = ctx.attribute("username");
        if (!callerUsername.equals(targetUsername)) {
            JwtAuthMiddleware.requirePermission(ctx, Permission.USERS_UPDATE);
        }

        String body = ctx.body();
        try {
            JSON.readTree(body);
        } catch (Exception _) {
            ctx.status(400);
            ctx.json(errorResponse("VALIDATION_ERROR", "Invalid JSON", 400));
            return;
        }

        controller.stateStore().saveUserPreferences(targetUsername, body);
        ctx.contentType("application/json");
        ctx.result(body);
    }

    @OpenApi(
            path = "/api/v1/users/{username}/minecraft",
            methods = {HttpMethod.PUT},
            operationId = "linkMinecraft",
            summary = "Link a Minecraft account",
            tags = {"Users"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "username", required = true)},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = LinkMinecraftRequest.class)}),
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Linked",
                        content = {@OpenApiContent(from = UserDto.class)}),
                @OpenApiResponse(
                        status = "400",
                        description = "Validation error",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "404",
                        description = "User not found",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "409",
                        description = "Minecraft account already linked",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void linkMinecraft(Context ctx) {
        String targetUsername = ctx.pathParam("username");
        String callerUsername = ctx.attribute("username");
        if (!callerUsername.equals(targetUsername)) {
            JwtAuthMiddleware.requirePermission(ctx, Permission.USERS_UPDATE);
        }

        var user = controller.authManager().getUser(targetUsername);
        if (user.isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "User not found", 404));
            return;
        }

        var req = ctx.bodyAsClass(LinkMinecraftRequest.class);

        if (req.uuid() == null
                || req.uuid().isBlank()
                || req.name() == null
                || req.name().isBlank()) {
            ctx.status(400);
            ctx.json(errorResponse("VALIDATION_ERROR", "Both uuid and name are required", 400));
            return;
        }

        var allUsers = controller.authManager().getAllUsers();
        var conflict = allUsers.stream()
                .filter(u ->
                        req.uuid().equals(u.minecraftUuid()) && !u.username().equals(targetUsername))
                .findFirst();
        if (conflict.isPresent()) {
            ctx.status(409);
            ctx.json(errorResponse(
                    "CONFLICT",
                    "This Minecraft account is already linked to user: "
                            + conflict.get().username(),
                    409));
            return;
        }

        controller.authManager().updateMinecraftLink(targetUsername, req.uuid(), req.name());
        audit(
                ctx,
                controller.stateStore(),
                "user.minecraft.link",
                "user",
                targetUsername,
                Map.of("minecraftName", req.name()));

        var updated = controller.authManager().getUser(targetUsername);
        ctx.json(UserDtoMapper.toDto(updated.orElseThrow()));
    }

    @OpenApi(
            path = "/api/v1/users/{username}/minecraft",
            methods = {HttpMethod.DELETE},
            operationId = "unlinkMinecraft",
            summary = "Unlink Minecraft account",
            tags = {"Users"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "username", required = true)},
            responses = {
                @OpenApiResponse(status = "204", description = "No Content"),
                @OpenApiResponse(status = "404", description = "User not found")
            })
    private void unlinkMinecraft(Context ctx) {
        String targetUsername = ctx.pathParam("username");
        String callerUsername = ctx.attribute("username");
        if (!callerUsername.equals(targetUsername)) {
            JwtAuthMiddleware.requirePermission(ctx, Permission.USERS_UPDATE);
        }

        var user = controller.authManager().getUser(targetUsername);
        if (user.isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "User not found", 404));
            return;
        }

        controller.authManager().updateMinecraftLink(targetUsername, null, null);
        audit(ctx, controller.stateStore(), "user.minecraft.unlink", "user", targetUsername);
        ctx.status(204);
    }
}
