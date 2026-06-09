package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.auditDiff;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.requireFound;

import java.time.Instant;
import java.util.Map;

import me.prexorjustin.prexorcloud.common.util.InputValidator;
import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.deployment.DeploymentRecord;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.rest.ApiResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.ActionDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.dto.GroupDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.dto.StartGroupRequest;
import me.prexorjustin.prexorcloud.controller.rest.dto.StatusCountResponse;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class GroupRoutes {

    private static final ObjectMapper SHARED_MAPPER = new ObjectMapper();
    private final PrexorController controller;

    public GroupRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        path("/api/v1/groups", () -> {
            get(this::listGroups);
            post(this::createGroup);
            get("/{name}", this::getGroup);
            get("/{name}/resolved", this::getGroupResolved);
            patch("/{name}", this::updateGroup);
            delete("/{name}", this::deleteGroup);
            post("/{name}/start", this::startGroupInstances);
            post("/{name}/restart", this::restartGroup);
            new DeploymentRoutes(controller).register();
        });
    }

    @OpenApi(
            path = "/api/v1/groups",
            methods = {HttpMethod.GET},
            operationId = "listGroups",
            summary = "List all groups",
            description = "Returns every configured group with computed running instance and player counts.",
            tags = {"Groups"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            queryParams = {
                @OpenApiParam(name = "page", type = Integer.class),
                @OpenApiParam(name = "pageSize", type = Integer.class)
            },
            responses = {
                @OpenApiResponse(status = "200", description = "OK"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden")
            })
    private void listGroups(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.GROUPS_VIEW);
        var groups = controller.groupManager().getAll().stream()
                .map(g -> GroupDtoMapper.toDto(g, controller.clusterState(), controller.catalogStore()))
                .toList();
        ApiResponse.writeList(ctx, groups, 500);
    }

    @OpenApi(
            path = "/api/v1/groups",
            methods = {HttpMethod.POST},
            operationId = "createGroup",
            summary = "Create group",
            description =
                    "Creates a new group. The name is validated via InputValidator.requireSafeName (alphanumeric, hyphens, underscores, 1-64 chars).",
            tags = {"Groups"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = GroupConfig.class)}),
            responses = {
                @OpenApiResponse(status = "201", description = "Created"),
                @OpenApiResponse(status = "400", description = "Invalid name or configuration"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "409", description = "Group already exists")
            })
    private void createGroup(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.GROUPS_CREATE);
        var config = ctx.bodyAsClass(GroupConfig.class);
        InputValidator.requireSafeName(config.name(), "Group name");
        controller.groupManager().create(config);
        controller.groupStore().save(config);
        auditDiff(ctx, controller.stateStore(), "group.create", "group", config.name(), null, config);
        ctx.status(201);
        ctx.json(GroupDtoMapper.toDto(config, controller.clusterState(), controller.catalogStore()));
    }

    @OpenApi(
            path = "/api/v1/groups/{name}",
            methods = {HttpMethod.GET},
            operationId = "getGroup",
            summary = "Get group",
            description = "Returns a single group by name.",
            tags = {"Groups"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true, description = "Group name")},
            responses = {
                @OpenApiResponse(status = "200", description = "OK"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Group not found")
            })
    private void getGroup(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.GROUPS_VIEW);
        String name = ctx.pathParam("name");
        ctx.json(GroupDtoMapper.toDto(
                requireFound(controller.groupManager().get(name), "Group", name),
                controller.clusterState(),
                controller.catalogStore()));
    }

    @OpenApi(
            path = "/api/v1/groups/{name}/resolved",
            methods = {HttpMethod.GET},
            operationId = "getGroupResolved",
            summary = "Get group with inheritance resolved",
            description = "Returns the group with all inherited fields resolved from the parent chain.",
            tags = {"Groups"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true, description = "Group name")},
            responses = {
                @OpenApiResponse(status = "200", description = "OK"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Group not found")
            })
    private void getGroupResolved(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.GROUPS_VIEW);
        String name = ctx.pathParam("name");
        requireFound(controller.groupManager().get(name), "Group", name);
        ctx.json(GroupDtoMapper.toDto(
                controller.groupManager().resolveGroup(name), controller.clusterState(), controller.catalogStore()));
    }

    @OpenApi(
            path = "/api/v1/groups/{name}",
            methods = {HttpMethod.PATCH},
            operationId = "updateGroup",
            summary = "Partial update group",
            description =
                    "Merges the supplied fields into the existing group configuration. Only fields present in the request body are updated; omitted fields are left unchanged.",
            tags = {"Groups"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true, description = "Group name")},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = GroupConfig.class)}),
            responses = {
                @OpenApiResponse(status = "200", description = "OK"),
                @OpenApiResponse(status = "400", description = "Invalid configuration"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Group not found")
            })
    private void updateGroup(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.GROUPS_UPDATE);
        String name = ctx.pathParam("name");
        var before = requireFound(controller.groupManager().get(name), "Group", name);
        Map<String, Object> rawFields = SHARED_MAPPER.readValue(ctx.body(), new TypeReference<>() {});
        var sent = rawFields.keySet();
        var update = ctx.bodyAsClass(GroupConfig.class);

        var merged = controller.groupManager().patch(name, update, sent);
        controller.groupStore().save(merged);
        auditDiff(ctx, controller.stateStore(), "group.update", "group", name, before, merged);
        ctx.json(GroupDtoMapper.toDto(merged, controller.clusterState(), controller.catalogStore()));
    }

    @OpenApi(
            path = "/api/v1/groups/{name}",
            methods = {HttpMethod.DELETE},
            operationId = "deleteGroup",
            summary = "Delete group",
            description = "Deletes a group by name.",
            tags = {"Groups"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true, description = "Group name")},
            responses = {
                @OpenApiResponse(status = "204", description = "No Content"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Group not found")
            })
    private void deleteGroup(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.GROUPS_DELETE);
        String name = ctx.pathParam("name");
        var before = requireFound(controller.groupManager().get(name), "Group", name);
        controller.groupManager().delete(name);
        controller.groupStore().delete(name);
        auditDiff(ctx, controller.stateStore(), "group.delete", "group", name, before, null);
        ctx.status(204);
    }

    @OpenApi(
            path = "/api/v1/groups/{name}/start",
            methods = {HttpMethod.POST},
            operationId = "startGroupInstances",
            summary = "Schedule new instances",
            description = "Schedules one or more new instances for the group.",
            tags = {"Groups"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true, description = "Group name")},
            queryParams = {
                @OpenApiParam(
                        name = "count",
                        type = Integer.class,
                        deprecated = true,
                        description =
                                "Number of instances to schedule (1-50). Deprecated: send `count` in the request body instead.")
            },
            requestBody =
                    @OpenApiRequestBody(
                            required = false,
                            content = {@OpenApiContent(from = StartGroupRequest.class)}),
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Instances scheduled",
                        content = {@OpenApiContent(from = StatusCountResponse.class)}),
                @OpenApiResponse(status = "400", description = "Invalid count"),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Group not found")
            })
    private void startGroupInstances(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.GROUPS_START);
        String name = ctx.pathParam("name");
        requireFound(controller.groupManager().get(name), "Group", name);

        Integer bodyCount = null;
        String body = ctx.body();
        if (body != null && !body.isBlank()) {
            try {
                var parsed = ctx.bodyAsClass(StartGroupRequest.class);
                if (parsed != null) bodyCount = parsed.count();
            } catch (Exception ignored) {
                // Tolerate malformed JSON: fall through to query-param path.
            }
        }
        Integer queryCount = ctx.queryParamAsClass("count", Integer.class).getOrDefault(null);
        if (bodyCount == null && queryCount != null) {
            ctx.header("Deprecation", "true");
            ctx.header(
                    "Warning",
                    "299 - \"count query parameter is deprecated; send {\\\"count\\\": N} in the request body\"");
        }
        int count = Math.clamp(bodyCount != null ? bodyCount : (queryCount != null ? queryCount : 1), 1, 50);

        for (int i = 0; i < count; i++) {
            controller.scheduler().scheduleOne(name);
        }
        ctx.json(ActionDtoMapper.statusCountResponse("scheduled", count));
    }

    @OpenApi(
            path = "/api/v1/groups/{name}/restart",
            methods = {HttpMethod.POST},
            operationId = "restartGroup",
            summary = "Rolling restart",
            description = "Triggers a rolling restart of all instances in the group.",
            tags = {"Groups"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true, description = "Group name")},
            responses = {
                @OpenApiResponse(
                        status = "202",
                        description = "Accepted",
                        content = {@OpenApiContent(from = DeploymentRecord.class)}),
                @OpenApiResponse(status = "401", description = "Unauthorized"),
                @OpenApiResponse(status = "403", description = "Forbidden"),
                @OpenApiResponse(status = "404", description = "Group not found")
            })
    private void restartGroup(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.GROUPS_UPDATE);
        String name = ctx.pathParam("name");
        var group = requireFound(controller.groupManager().get(name), "Group", name);

        var recent = controller.stateStore().getDeployments(name, 1, 0);
        int nextRevision = recent.isEmpty() ? 1 : recent.getFirst().revision() + 1;

        int runningCount = (int) controller.clusterState().getInstancesByGroup(name).stream()
                .filter(i -> i.state() == InstanceState.RUNNING)
                .count();

        var record = new DeploymentRecord(
                0,
                name,
                nextRevision,
                "rolling_restart",
                group.updateStrategy(),
                "IN_PROGRESS",
                "{}",
                DeploymentRoutes.buildConfigSnapshot(
                        name, group.updateStrategy(), null, null, null, null, null, null, null, runningCount),
                runningCount,
                0,
                Instant.now().toString(),
                null,
                null);
        var saved = controller.stateStore().createDeployment(record);

        final DeploymentRecord deployment = saved;
        Thread.ofVirtual()
                .name("restart-" + name + "-r" + nextRevision)
                .start(() -> controller.scheduler().rollingRestart(deployment));

        ctx.status(202);
        ctx.json(saved);
    }
}
