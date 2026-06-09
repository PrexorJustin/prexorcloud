package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.passwordreset.PasswordResetManager;
import me.prexorjustin.prexorcloud.controller.rest.dto.ErrorResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.PasswordResetComplete;
import me.prexorjustin.prexorcloud.controller.rest.dto.PasswordResetRequest;
import me.prexorjustin.prexorcloud.controller.rest.dto.StatusResponse;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

public final class PasswordResetRoutes {

    private final PasswordResetManager manager;

    public PasswordResetRoutes(PrexorController controller) {
        this.manager = controller.passwordResetManager();
    }

    public void register() {
        path("/api/v1/auth/password-reset", () -> {
            post("/request", this::requestPasswordReset);
            post("/complete", this::completePasswordReset);
        });
    }

    @OpenApi(
            path = "/api/v1/auth/password-reset/request",
            methods = {HttpMethod.POST},
            operationId = "requestPasswordReset",
            summary = "Request password reset",
            description =
                    "Public, unauthenticated endpoint that initiates a password reset for the given email. Always returns 202 Accepted regardless of whether the email exists, to prevent account enumeration. Returns 404 when password reset is disabled (`security.passwordReset.enabled=false`).",
            tags = {"Auth"},
            security = {},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = PasswordResetRequest.class)}),
            responses = {
                @OpenApiResponse(
                        status = "202",
                        description = "Reset request accepted (email may or may not exist)",
                        content = {@OpenApiContent(from = StatusResponse.class)}),
                @OpenApiResponse(
                        status = "400",
                        description = "Invalid JSON body",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "404",
                        description = "Password reset not enabled on this controller",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void requestPasswordReset(Context ctx) {
        if (manager == null) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_ENABLED", "Password reset is not enabled on this controller", 404));
            return;
        }
        PasswordResetRequest req;
        try {
            req = ctx.bodyAsClass(PasswordResetRequest.class);
        } catch (Exception _) {
            ctx.status(400);
            ctx.json(errorResponse("VALIDATION_ERROR", "Invalid JSON body", 400));
            return;
        }
        manager.request(req == null ? null : req.email());
        ctx.status(202);
        ctx.json(new StatusResponse("accepted"));
    }

    @OpenApi(
            path = "/api/v1/auth/password-reset/complete",
            methods = {HttpMethod.POST},
            operationId = "completePasswordReset",
            summary = "Complete password reset",
            description =
                    "Public, unauthenticated endpoint that consumes a reset token and sets a new password. Returns 400 for invalid/expired tokens or weak passwords. Returns 404 when password reset is disabled.",
            tags = {"Auth"},
            security = {},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = PasswordResetComplete.class)}),
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Password updated",
                        content = {@OpenApiContent(from = StatusResponse.class)}),
                @OpenApiResponse(
                        status = "400",
                        description = "Invalid token, expired token, or weak password",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "404",
                        description = "Password reset not enabled on this controller",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void completePasswordReset(Context ctx) {
        if (manager == null) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_ENABLED", "Password reset is not enabled on this controller", 404));
            return;
        }
        PasswordResetComplete req;
        try {
            req = ctx.bodyAsClass(PasswordResetComplete.class);
        } catch (Exception _) {
            ctx.status(400);
            ctx.json(errorResponse("VALIDATION_ERROR", "Invalid JSON body", 400));
            return;
        }
        if (req == null) {
            ctx.status(400);
            ctx.json(errorResponse("VALIDATION_ERROR", "missing body", 400));
            return;
        }
        var outcome = manager.complete(req.token(), req.newPassword());
        switch (outcome) {
            case PasswordResetManager.CompleteOutcome.Success __ -> ctx.json(new StatusResponse("ok"));
            case PasswordResetManager.CompleteOutcome.InvalidToken __ -> {
                ctx.status(400);
                ctx.json(errorResponse("INVALID_TOKEN", "Reset token is invalid or has expired", 400));
            }
            case PasswordResetManager.CompleteOutcome.WeakPassword w -> {
                ctx.status(400);
                ctx.json(errorResponse("VALIDATION_ERROR", w.reason(), 400));
            }
        }
    }
}
