package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.AuthManager.LoginOutcome;
import me.prexorjustin.prexorcloud.controller.auth.RequestContext;
import me.prexorjustin.prexorcloud.controller.rest.dto.AuthDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.dto.ChangePasswordRequest;
import me.prexorjustin.prexorcloud.controller.rest.dto.ErrorResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.LoginRequest;
import me.prexorjustin.prexorcloud.controller.rest.dto.LoginResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.StatusResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.TokenResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.UserDto;
import me.prexorjustin.prexorcloud.controller.rest.dto.UserDtoMapper;
import me.prexorjustin.prexorcloud.security.password.PasswordHasher;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class AuthRoutes {

    private final PrexorController controller;

    public AuthRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        path("/api/v1/auth", () -> {
            post("/login", this::loginUser);
            post("/logout", this::logoutUser);
            post("/refresh", this::refreshToken);
            get("/me", this::getCurrentUser);
            post("/change-password", this::changePassword);
        });
    }

    @OpenApi(
            path = "/api/v1/auth/login",
            methods = {HttpMethod.POST},
            operationId = "loginUser",
            summary = "Login",
            description = "Authenticate with username and password. Returns a JWT token and the authenticated user.",
            tags = {"Auth"},
            security = {},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = LoginRequest.class)}),
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Login successful",
                        content = {@OpenApiContent(from = LoginResponse.class)}),
                @OpenApiResponse(
                        status = "401",
                        description = "Invalid credentials",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(status = "429", description = "Rate limited")
            })
    private void loginUser(Context ctx) {
        var req = ctx.bodyAsClass(LoginRequest.class);

        var outcome = controller.authManager().login(req.username(), req.password());
        switch (outcome) {
            case LoginOutcome.Success success -> ctx.json(AuthDtoMapper.loginResponse(success.token(), success.user()));
            case LoginOutcome.Locked locked -> {
                long retryAfterSeconds = Math.max(
                        1L,
                        locked.lockedUntil().getEpochSecond() - Instant.now().getEpochSecond());
                ctx.status(429);
                ctx.header("Retry-After", Long.toString(retryAfterSeconds));
                ctx.json(AuthDtoMapper.lockedResponse(locked.lockedUntil()));
            }
            case LoginOutcome.InvalidCredentials __ -> {
                ctx.status(401);
                ctx.json(errorResponse("UNAUTHORIZED", "Invalid username or password", 401));
            }
        }
    }

    @OpenApi(
            path = "/api/v1/auth/logout",
            methods = {HttpMethod.POST},
            operationId = "logoutUser",
            summary = "Logout (revoke token)",
            description =
                    "Revoke the bearer token used on this request by adding its JTI to the JWT revocation store until the token's natural expiry. Returns 501 when no revocation store is configured.",
            tags = {"Auth"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Token revoked",
                        content = {@OpenApiContent(from = StatusResponse.class)}),
                @OpenApiResponse(
                        status = "401",
                        description = "Missing or invalid token",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "501",
                        description = "JWT revocation store is not configured",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void logoutUser(Context ctx) {
        var revocationStore = controller.revocationStore();
        if (revocationStore == null) {
            ctx.status(501);
            ctx.json(errorResponse("NOT_IMPLEMENTED", "JWT revocation store is not configured", 501));
            return;
        }
        String header = ctx.header("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            ctx.status(401);
            ctx.json(errorResponse("UNAUTHORIZED", "Missing token", 401));
            return;
        }
        String token = header.substring(7);
        var claims = controller.jwtManager().validate(token);
        if (claims.isEmpty()) {
            ctx.status(401);
            ctx.json(errorResponse("UNAUTHORIZED", "Invalid token", 401));
            return;
        }
        String jti = claims.get().getId();
        Date exp = claims.get().getExpiration();
        if (jti != null && exp != null) {
            Duration ttl = Duration.between(Instant.now(), exp.toInstant());
            if (!ttl.isNegative()) revocationStore.revoke(jti, ttl);
        }
        ctx.json(AuthDtoMapper.statusResponse("ok"));
    }

    @OpenApi(
            path = "/api/v1/auth/refresh",
            methods = {HttpMethod.POST},
            operationId = "refreshToken",
            summary = "Refresh JWT",
            description = "Exchange a valid JWT for a new token.",
            tags = {"Auth"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Token refreshed",
                        content = {@OpenApiContent(from = TokenResponse.class)}),
                @OpenApiResponse(
                        status = "401",
                        description = "Unauthorized",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void refreshToken(Context ctx) {
        var rc = RequestContext.from(ctx);
        var token = controller.authManager().refresh(rc.username(), rc.role());
        ctx.json(AuthDtoMapper.tokenResponse(token.orElseThrow()));
    }

    @OpenApi(
            path = "/api/v1/auth/me",
            methods = {HttpMethod.GET},
            operationId = "getCurrentUser",
            summary = "Get current user",
            description = "Returns the authenticated user's profile.",
            tags = {"Auth"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Current user",
                        content = {@OpenApiContent(from = UserDto.class)}),
                @OpenApiResponse(
                        status = "401",
                        description = "Unauthorized",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void getCurrentUser(Context ctx) {
        String username = ctx.attribute("username");
        var user = controller.authManager().getUser(username);
        if (user.isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "User not found", 404));
            return;
        }
        ctx.json(UserDtoMapper.toDto(user.get()));
    }

    @OpenApi(
            path = "/api/v1/auth/change-password",
            methods = {HttpMethod.POST},
            operationId = "changePassword",
            summary = "Change own password",
            description = "Change the authenticated user's password. New password must be at least 8 characters.",
            tags = {"Auth"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = ChangePasswordRequest.class)}),
            responses = {
                @OpenApiResponse(status = "200", description = "Password changed"),
                @OpenApiResponse(
                        status = "401",
                        description = "Unauthorized",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "422",
                        description = "Validation error (e.g. newPassword too short)",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void changePassword(Context ctx) {
        String username = ctx.attribute("username");
        var user = controller.authManager().getUser(username);
        if (user.isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "User not found", 404));
            return;
        }

        var req = ctx.bodyAsClass(ChangePasswordRequest.class);

        if (!PasswordHasher.verify(req.currentPassword(), user.get().passwordHash())) {
            ctx.status(401);
            ctx.json(errorResponse("UNAUTHORIZED", "Current password is incorrect", 401));
            return;
        }

        if (req.newPassword() == null || req.newPassword().length() < 8) {
            ctx.status(422);
            ctx.json(errorResponse("VALIDATION_ERROR", "New password must be at least 8 characters", 422));
            return;
        }

        controller.authManager().updateUser(username, null, null, req.newPassword());
        ctx.json(AuthDtoMapper.statusResponse("ok"));
    }
}
