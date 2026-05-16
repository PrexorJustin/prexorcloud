package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.requireFound;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.deployment.DeploymentRecord;
import me.prexorjustin.prexorcloud.controller.deployment.DeploymentRolloutConfig;
import me.prexorjustin.prexorcloud.controller.rest.ApiResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.ActionDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.dto.ErrorResponse;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class DeploymentRoutes {

    // Substring anchors for scripts/check-openapi-routes.sh
    @SuppressWarnings("unused")
    private static final String P_DEPLOYMENTS = "/api/v1/groups/{name}/deployments";

    @SuppressWarnings("unused")
    private static final String P_DEPLOYMENT_BY_REV = "/api/v1/groups/{name}/deployments/{rev}";

    @SuppressWarnings("unused")
    private static final String P_DEPLOY = "/api/v1/groups/{name}/deploy";

    @SuppressWarnings("unused")
    private static final String P_DEPLOYMENT_PAUSE = "/api/v1/groups/{name}/deployments/{rev}/pause";

    @SuppressWarnings("unused")
    private static final String P_DEPLOYMENT_RESUME = "/api/v1/groups/{name}/deployments/{rev}/resume";

    @SuppressWarnings("unused")
    private static final String P_DEPLOYMENT_ROLLBACK = "/api/v1/groups/{name}/deployments/{rev}/rollback";

    private final PrexorController controller;

    public DeploymentRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        get("/{name}/deployments", this::listDeployments);
        get("/{name}/deployments/{rev}", this::getDeployment);
        post("/{name}/deploy", this::createDeployment);
        post("/{name}/deployments/{rev}/pause", this::pauseDeployment);
        post("/{name}/deployments/{rev}/resume", this::resumeDeployment);
        post("/{name}/deployments/{rev}/rollback", this::rollbackDeployment);
    }

    @OpenApi(
            path = "/api/v1/groups/{name}/deployments",
            methods = {HttpMethod.GET},
            operationId = "listDeployments",
            summary = "List deployment history",
            tags = {"Deployments"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true)},
            queryParams = {
                @OpenApiParam(name = "page", type = Integer.class),
                @OpenApiParam(name = "pageSize", type = Integer.class)
            },
            responses = {
                @OpenApiResponse(status = "200", description = "Deployment list"),
                @OpenApiResponse(
                        status = "404",
                        description = "Group not found",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void listDeployments(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.GROUPS_VIEW);
        String name = ctx.pathParam("name");
        if (!controller.groupManager().exists(name)) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Group not found: " + name, 404));
            return;
        }
        DeploymentPageRequest request = resolveDeploymentPageRequest(
                ctx.queryParam("page") != null
                        ? ctx.queryParamAsClass("page", Integer.class).getOrDefault(1)
                        : null,
                ctx.queryParam("pageSize") != null
                        ? ctx.queryParamAsClass("pageSize", Integer.class).getOrDefault(50)
                        : null,
                100);
        var deployments = controller.stateStore().getDeployments(name, request.limit(), request.offset()).stream()
                .map(DeploymentRoutes::deploymentToJson)
                .toList();
        ApiResponse.paginated(
                ctx, deployments, controller.stateStore().countDeployments(name), request.page(), request.pageSize());
    }

    @OpenApi(
            path = "/api/v1/groups/{name}/deployments/{rev}",
            methods = {HttpMethod.GET},
            operationId = "getDeployment",
            summary = "Get deployment detail",
            tags = {"Deployments"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {
                @OpenApiParam(name = "name", required = true),
                @OpenApiParam(name = "rev", required = true, type = Integer.class)
            },
            responses = {
                @OpenApiResponse(status = "200", description = "Deployment"),
                @OpenApiResponse(status = "400", description = "Invalid revision"),
                @OpenApiResponse(status = "404", description = "Deployment not found")
            })
    private void getDeployment(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.GROUPS_VIEW);
        String name = ctx.pathParam("name");
        var revOpt = parseRevision(ctx);
        if (revOpt.isEmpty()) return;
        ctx.json(deploymentToJson(requireFound(
                controller.stateStore().getDeployment(name, revOpt.getAsInt()),
                "Deployment",
                name + "/" + revOpt.getAsInt())));
    }

    @OpenApi(
            path = "/api/v1/groups/{name}/deploy",
            methods = {HttpMethod.POST},
            operationId = "createDeployment",
            summary = "Trigger manual deployment",
            tags = {"Deployments"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true)},
            responses = {
                @OpenApiResponse(status = "202", description = "Deployment accepted"),
                @OpenApiResponse(
                        status = "400",
                        description = "Invalid request body",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(status = "404", description = "Group not found")
            })
    private void createDeployment(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.GROUPS_UPDATE);
        String name = ctx.pathParam("name");
        var group = requireFound(controller.groupManager().get(name), "Group", name);

        var recent = controller.stateStore().getDeployments(name, 1, 0);
        int nextRevision = recent.isEmpty() ? 1 : recent.getFirst().revision() + 1;

        int runningCount = (int) controller.clusterState().getInstancesByGroup(name).stream()
                .filter(i -> i.state() == InstanceState.RUNNING)
                .count();
        DeploymentTriggerOptions triggerOptions;
        try {
            Map<?, ?> body = ctx.body().isBlank() ? Map.of() : ctx.bodyAsClass(java.util.HashMap.class);
            triggerOptions = resolveTriggerOptions(body, group.updateStrategy(), runningCount);
        } catch (IllegalArgumentException e) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", e.getMessage(), 400));
            return;
        } catch (Exception _) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Invalid deployment request body", 400));
            return;
        }

        var record = new DeploymentRecord(
                0,
                name,
                nextRevision,
                "manual",
                triggerOptions.strategy(),
                "IN_PROGRESS",
                buildTemplateSnapshot(controller, group.templates()),
                buildConfigSnapshot(
                        group.name(),
                        triggerOptions.strategy(),
                        triggerOptions.batchSize(),
                        triggerOptions.canaryInstances(),
                        triggerOptions.canaryPercent(),
                        triggerOptions.healthGateEnabled(),
                        triggerOptions.autoRollbackOnFailure(),
                        triggerOptions.promotionTimeoutSeconds(),
                        triggerOptions.minHealthySeconds(),
                        runningCount),
                runningCount,
                0,
                Instant.now().toString(),
                null,
                null);
        var saved = controller.stateStore().createDeployment(record);

        Thread.ofVirtual()
                .name("deploy-" + name + "-r" + nextRevision)
                .start(() -> controller.scheduler().rollingRestart(saved));

        ctx.status(202);
        ctx.json(deploymentToJson(saved));
    }

    @OpenApi(
            path = "/api/v1/groups/{name}/deployments/{rev}/pause",
            methods = {HttpMethod.POST},
            operationId = "pauseDeployment",
            summary = "Pause deployment",
            tags = {"Deployments"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {
                @OpenApiParam(name = "name", required = true),
                @OpenApiParam(name = "rev", required = true, type = Integer.class)
            },
            responses = {
                @OpenApiResponse(status = "200", description = "Paused"),
                @OpenApiResponse(status = "404", description = "Deployment not found")
            })
    private void pauseDeployment(Context ctx) {
        applyStateAction(ctx, "PAUSED", "paused");
    }

    @OpenApi(
            path = "/api/v1/groups/{name}/deployments/{rev}/resume",
            methods = {HttpMethod.POST},
            operationId = "resumeDeployment",
            summary = "Resume deployment",
            tags = {"Deployments"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {
                @OpenApiParam(name = "name", required = true),
                @OpenApiParam(name = "rev", required = true, type = Integer.class)
            },
            responses = {
                @OpenApiResponse(status = "200", description = "Resumed"),
                @OpenApiResponse(status = "404", description = "Deployment not found")
            })
    private void resumeDeployment(Context ctx) {
        applyStateAction(ctx, "IN_PROGRESS", "resumed");
    }

    @OpenApi(
            path = "/api/v1/groups/{name}/deployments/{rev}/rollback",
            methods = {HttpMethod.POST},
            operationId = "rollbackDeployment",
            summary = "Rollback deployment",
            tags = {"Deployments"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {
                @OpenApiParam(name = "name", required = true),
                @OpenApiParam(name = "rev", required = true, type = Integer.class)
            },
            responses = {
                @OpenApiResponse(status = "200", description = "Rolled back"),
                @OpenApiResponse(status = "404", description = "Deployment not found")
            })
    private void rollbackDeployment(Context ctx) {
        applyStateAction(ctx, "ROLLED_BACK", "rolled_back");
    }

    private void applyStateAction(Context ctx, String newState, String statusKey) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.GROUPS_UPDATE);
        String name = ctx.pathParam("name");
        var revOpt = parseRevision(ctx);
        if (revOpt.isEmpty()) return;
        var deployment = requireFound(
                controller.stateStore().getDeployment(name, revOpt.getAsInt()),
                "Deployment",
                name + "/" + revOpt.getAsInt());
        controller.stateStore().updateDeploymentState(deployment.id(), newState);
        if ("IN_PROGRESS".equals(newState)) {
            Thread.ofVirtual()
                    .name("resume-deploy-" + deployment.groupName() + "-r" + deployment.revision())
                    .start(() -> controller.scheduler().rollingRestart(deployment));
        }
        ctx.json(ActionDtoMapper.statusResponse(statusKey));
    }

    private static OptionalInt parseRevision(Context ctx) {
        try {
            return OptionalInt.of(Integer.parseInt(ctx.pathParam("rev")));
        } catch (NumberFormatException _) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Invalid revision number: " + ctx.pathParam("rev"), 400));
            return OptionalInt.empty();
        }
    }

    private static String buildTemplateSnapshot(PrexorController controller, java.util.List<String> templateNames) {
        var map = new LinkedHashMap<String, String>();
        for (String tpl : templateNames) {
            controller.templateManager().get(tpl).ifPresent(t -> map.put(t.name(), t.hash()));
        }
        try {
            return new ObjectMapper().writeValueAsString(map);
        } catch (JsonProcessingException _) {
            return "{}";
        }
    }

    static Map<String, Object> deploymentToJson(DeploymentRecord deployment) {
        var dto = new LinkedHashMap<String, Object>();
        dto.put("id", deployment.id());
        dto.put("groupName", deployment.groupName());
        dto.put("revision", deployment.revision());
        dto.put("trigger", deployment.trigger());
        dto.put("strategy", deployment.strategy());
        dto.put("state", deployment.state());
        dto.put("templateSnapshot", deployment.templateSnapshot());
        dto.put("configSnapshot", deployment.configSnapshot());
        dto.put("totalInstances", deployment.totalInstances());
        dto.put("updatedInstances", deployment.updatedInstances());
        dto.put("createdAt", deployment.createdAt());
        dto.put("completedAt", deployment.completedAt());
        dto.put("rollbackOf", deployment.rollbackOf());
        dto.put("rollout", rolloutToJson(deployment.configSnapshot(), deployment.totalInstances()));
        return dto;
    }

    static Map<String, Object> rolloutToJson(String configSnapshot, int totalInstances) {
        var rollout = DeploymentRolloutConfig.fromConfigSnapshot(configSnapshot, totalInstances);
        var dto = new LinkedHashMap<String, Object>();
        dto.put("batchSize", rollout.batchSize());
        dto.put("canaryInstances", rollout.canaryInstances());
        dto.put("healthGateEnabled", rollout.healthGateEnabled());
        dto.put("autoRollbackOnFailure", rollout.autoRollbackOnFailure());
        dto.put("promotionTimeoutSeconds", rollout.promotionTimeoutSeconds());
        dto.put("minHealthySeconds", rollout.minHealthySeconds());
        return dto;
    }

    static DeploymentTriggerOptions resolveTriggerOptions(Map<?, ?> body, String defaultStrategy, int totalInstances) {
        String strategy = stringValue(body.get("strategy")).orElse(defaultStrategy);
        Integer batchSize = intValue(body.get("batchSize"));
        Integer canaryInstances = intValue(body.get("canaryInstances"));
        Integer canaryPercent = intValue(body.get("canaryPercent"));
        Boolean healthGateEnabled = booleanValue(body.get("healthGateEnabled"));
        Boolean autoRollbackOnFailure = booleanValue(body.get("autoRollbackOnFailure"));
        Long promotionTimeoutSeconds = longValue(body.get("promotionTimeoutSeconds"));
        Long minHealthySeconds = longValue(body.get("minHealthySeconds"));

        if (batchSize != null && batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be >= 1");
        }
        if (canaryInstances != null && canaryInstances < 0) {
            throw new IllegalArgumentException("canaryInstances must be >= 0");
        }
        if (canaryPercent != null && (canaryPercent < 0 || canaryPercent > 100)) {
            throw new IllegalArgumentException("canaryPercent must be between 0 and 100");
        }
        if (canaryInstances != null && canaryPercent != null) {
            throw new IllegalArgumentException("Specify either canaryInstances or canaryPercent, not both");
        }
        if (promotionTimeoutSeconds != null && promotionTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("promotionTimeoutSeconds must be >= 1");
        }
        if (minHealthySeconds != null && minHealthySeconds < 0) {
            throw new IllegalArgumentException("minHealthySeconds must be >= 0");
        }

        return new DeploymentTriggerOptions(
                strategy,
                batchSize,
                DeploymentRolloutConfig.resolveCanaryInstances(totalInstances, canaryInstances, canaryPercent),
                canaryPercent,
                healthGateEnabled,
                autoRollbackOnFailure,
                promotionTimeoutSeconds,
                minHealthySeconds);
    }

    static String buildConfigSnapshot(
            String groupName,
            String strategy,
            Integer batchSize,
            Integer canaryInstances,
            Integer canaryPercent,
            Boolean healthGateEnabled,
            Boolean autoRollbackOnFailure,
            Long promotionTimeoutSeconds,
            Long minHealthySeconds,
            int totalInstances) {
        try {
            var snapshot = new LinkedHashMap<String, Object>();
            snapshot.put("group", groupName);
            snapshot.put("strategy", strategy);
            if (batchSize != null) {
                snapshot.put("batchSize", Math.max(1, batchSize));
            }
            int resolvedCanary =
                    DeploymentRolloutConfig.resolveCanaryInstances(totalInstances, canaryInstances, canaryPercent);
            if (resolvedCanary > 0) {
                snapshot.put("canaryInstances", resolvedCanary);
            }
            if (canaryPercent != null) {
                snapshot.put("canaryPercent", canaryPercent);
            }
            if (healthGateEnabled != null) {
                snapshot.put("healthGateEnabled", healthGateEnabled);
            }
            if (autoRollbackOnFailure != null) {
                snapshot.put("autoRollbackOnFailure", autoRollbackOnFailure);
            }
            if (promotionTimeoutSeconds != null) {
                snapshot.put("promotionTimeoutSeconds", promotionTimeoutSeconds);
            }
            if (minHealthySeconds != null) {
                snapshot.put("minHealthySeconds", Math.max(0L, minHealthySeconds));
            }
            return new ObjectMapper().writeValueAsString(snapshot);
        } catch (JsonProcessingException _) {
            return "{}";
        }
    }

    private static java.util.Optional<String> stringValue(Object value) {
        if (value instanceof String string && !string.isBlank()) {
            return java.util.Optional.of(string);
        }
        return java.util.Optional.empty();
    }

    private static Integer intValue(Object value) {
        if (value instanceof Integer integer) {
            return integer;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            return Integer.parseInt(string);
        }
        return null;
    }

    private static Long longValue(Object value) {
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            return Long.parseLong(string);
        }
        return null;
    }

    private static Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string && !string.isBlank()) {
            return Boolean.parseBoolean(string);
        }
        return null;
    }

    static DeploymentPageRequest resolveDeploymentPageRequest(
            Integer pageParam, Integer pageSizeParam, int maxPageSize) {
        int page = Math.max(1, pageParam != null ? pageParam : 1);
        int pageSize = Math.clamp(pageSizeParam != null ? pageSizeParam : 50, 1, maxPageSize);
        return new DeploymentPageRequest(page, pageSize, (page - 1) * pageSize, pageSize);
    }

    record DeploymentTriggerOptions(
            String strategy,
            Integer batchSize,
            Integer canaryInstances,
            Integer canaryPercent,
            Boolean healthGateEnabled,
            Boolean autoRollbackOnFailure,
            Long promotionTimeoutSeconds,
            Long minHealthySeconds) {}

    record DeploymentPageRequest(int page, int pageSize, int offset, int limit) {}
}
