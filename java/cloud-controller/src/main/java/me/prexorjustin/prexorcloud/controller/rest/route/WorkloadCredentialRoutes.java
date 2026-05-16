package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.delete;
import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.path;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.audit;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.rest.ApiResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.ErrorResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.RevokeInstanceCredentialsResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.WorkloadCredentialDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.dto.WorkloadCredentialPage;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class WorkloadCredentialRoutes {

    private final PrexorController controller;

    public WorkloadCredentialRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        path("/api/v1/workloads/credentials", () -> {
            get(this::listWorkloadCredentials);
            delete("/{tokenId}", this::revokeWorkloadCredential);
            delete("/instances/{instanceId}", this::revokeWorkloadCredentialsForInstance);
        });
    }

    @OpenApi(
            path = "/api/v1/workloads/credentials",
            methods = {HttpMethod.GET},
            operationId = "listWorkloadCredentials",
            summary = "List workload credentials",
            description =
                    "Returns all currently issued workload (plugin/proxy) credentials. Each credential is bound to a specific instance and authenticates that instance against `/api/plugin/*` and `/api/proxy/*`.",
            tags = {"Tokens"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Workload credentials",
                        content = {@OpenApiContent(from = WorkloadCredentialPage.class)}),
                @OpenApiResponse(
                        status = "401",
                        description = "Unauthorized",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "403",
                        description = "Forbidden",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void listWorkloadCredentials(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TOKENS_VIEW);
        var credentials = controller.clusterState().workloadIdentityRegistry().tokenSnapshots().stream()
                .map(WorkloadCredentialDtoMapper::toDto)
                .toList();
        ApiResponse.writeList(ctx, credentials, 200);
    }

    @OpenApi(
            path = "/api/v1/workloads/credentials/{tokenId}",
            methods = {HttpMethod.DELETE},
            operationId = "revokeWorkloadCredential",
            summary = "Revoke a workload credential",
            description = "Revokes a single workload credential by its token ID.",
            tags = {"Tokens"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "tokenId", required = true, description = "Workload credential token ID")
            },
            responses = {
                @OpenApiResponse(status = "204", description = "Workload credential revoked"),
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
                        description = "Workload credential not found",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void revokeWorkloadCredential(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TOKENS_REVOKE);
        String tokenId = ctx.pathParam("tokenId");
        if (!controller.clusterState().revokePluginTokenId(tokenId)) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Workload credential not found: " + tokenId, 404));
            return;
        }
        audit(ctx, controller.stateStore(), "workload-credential.revoke", "workload-credential", tokenId);
        ctx.status(204);
    }

    @OpenApi(
            path = "/api/v1/workloads/credentials/instances/{instanceId}",
            methods = {HttpMethod.DELETE},
            operationId = "revokeWorkloadCredentialsForInstance",
            summary = "Revoke all workload credentials for an instance",
            description =
                    "Revokes every workload credential currently bound to the given instance. Returns 404 when no credentials are found for the instance.",
            tags = {"Tokens"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "instanceId", required = true, description = "Instance ID")},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Credentials revoked",
                        content = {@OpenApiContent(from = RevokeInstanceCredentialsResponse.class)}),
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
                        description = "No workload credentials found for this instance",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void revokeWorkloadCredentialsForInstance(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TOKENS_REVOKE);
        String instanceId = ctx.pathParam("instanceId");
        int revoked = controller.clusterState().revokePluginTokensForInstance(instanceId);
        if (revoked == 0) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "No workload credentials found for instance: " + instanceId, 404));
            return;
        }
        audit(ctx, controller.stateStore(), "workload-credential.revoke-instance", "instance", instanceId);
        ctx.json(WorkloadCredentialDtoMapper.revokeInstanceResponse(instanceId, revoked));
    }
}
