package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.audit;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarFile;

import me.prexorjustin.prexorcloud.api.event.events.ModuleFrontendReloadedEvent;
import me.prexorjustin.prexorcloud.api.event.events.ModuleLoadedEvent;
import me.prexorjustin.prexorcloud.api.event.events.ModuleUnloadedEvent;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleManifest;
import me.prexorjustin.prexorcloud.api.module.platform.RuntimeTarget;
import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.module.ModuleFrontendManager;
import me.prexorjustin.prexorcloud.controller.module.compat.GroupCompatibilityResult;
import me.prexorjustin.prexorcloud.controller.module.compat.ModuleCompatibilityChecker;
import me.prexorjustin.prexorcloud.controller.module.compat.PlatformCompatibilityConflictException;
import me.prexorjustin.prexorcloud.controller.module.compat.PlatformCompatibilityReport;
import me.prexorjustin.prexorcloud.controller.module.platform.ExtensionRegistry;
import me.prexorjustin.prexorcloud.controller.module.platform.ExtensionRegistryException;
import me.prexorjustin.prexorcloud.controller.module.platform.ModuleClassLoaderTracker;
import me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleManager;
import me.prexorjustin.prexorcloud.controller.module.registry.ModuleRegistryClient;
import me.prexorjustin.prexorcloud.controller.module.registry.ModuleRegistryException;
import me.prexorjustin.prexorcloud.controller.module.registry.RegistryFetcher;
import me.prexorjustin.prexorcloud.controller.rest.ApiResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.ErrorResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.ModuleDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;
import me.prexorjustin.prexorcloud.modules.runtime.CapabilityRegistry;
import me.prexorjustin.prexorcloud.modules.runtime.PlatformModuleManifestException;
import me.prexorjustin.prexorcloud.modules.runtime.PlatformModuleManifestParser;
import me.prexorjustin.prexorcloud.security.signing.PlatformModuleSignatureVerifier;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class ModuleRoutes {

    // Substring anchors for scripts/check-openapi-routes.sh — greedy-segment
    // paths are normalised to `{…}` in OpenAPI.
    @SuppressWarnings("unused")
    private static final String P_MODULE_ARTIFACTS = "/api/v1/modules/platform/{moduleId}/artifacts/{artifactPath}";

    @SuppressWarnings("unused")
    private static final String P_MODULE_FRONTEND = "/api/v1/modules/{name}/frontend/{filepath}";

    private static final Map<String, String> MIME_TYPES = Map.of(
            ".js", "application/javascript",
            ".css", "text/css",
            ".json", "application/json",
            ".svg", "image/svg+xml",
            ".png", "image/png",
            ".woff2", "font/woff2",
            ".woff", "font/woff",
            ".map", "application/json");

    private static final com.fasterxml.jackson.databind.ObjectMapper REGISTRY_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final PrexorController controller;
    private final ModuleFrontendManager frontendManager;
    private final PlatformModuleManager platformManager;
    private final RegistryFetcher registryFetcher;

    public ModuleRoutes(PrexorController controller) {
        this(controller, RegistryFetcher.httpDefault());
    }

    /** Test seam — inject an in-memory registry fetcher instead of the real HTTP one. */
    public ModuleRoutes(PrexorController controller, RegistryFetcher registryFetcher) {
        this.controller = controller;
        var modules = controller.moduleRegistry();
        this.frontendManager = modules.frontendManager();
        this.platformManager = modules.platformManager();
        this.registryFetcher = registryFetcher;
    }

    /** Built per request so it always reflects the currently-configured registry URLs. */
    private ModuleRegistryClient registryClient() {
        return new ModuleRegistryClient(
                controller.config().modules().registries(), registryFetcher, REGISTRY_MAPPER);
    }

    public void register() {
        path("/api/v1/modules", () -> {
            get(this::listModules);

            path("/platform", () -> {
                get("/{moduleId}/artifacts/<artifactPath>", this::getPlatformModuleArtifact);
                get("", this::listPlatformModules);
                get("/manifests", this::listPlatformModuleManifests);
                get("/registry", this::listRegistry);
                post("/registry/install", this::installFromRegistry);
                post("/upload", this::uploadPlatformModule);
                get("/{moduleId}/resources", this::getPlatformModuleResources);
                post("/{moduleId}/upgrade", this::upgradePlatformModule);
                post("/{moduleId}/frontend/reload", this::reloadPlatformModuleFrontend);
                delete("/{moduleId}", this::deletePlatformModule);
                delete("/{moduleId}/storage", this::dropPlatformModuleStorage);
                get("/capabilities", this::listPlatformCapabilities);
                get("/extensions", this::listPlatformExtensions);
                get("/extensions/resolve", this::resolvePlatformExtensions);
                get("/leaked-classloaders", this::listLeakedClassLoaders);
                post("/leaked-classloaders/force-cleanup", this::forceClassLoaderCleanup);
            });

            get("/{name}/frontend/<filepath>", this::getModuleFrontendAsset);
        });
    }

    @OpenApi(
            path = "/api/v1/modules",
            methods = {HttpMethod.GET},
            operationId = "listModules",
            summary = "List modules with frontend metadata",
            tags = {"Modules"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {@OpenApiResponse(status = "200", description = "Module list")})
    private void listModules(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.MODULES_VIEW);

        List<Map<String, Object>> result = new ArrayList<>();
        for (var frontend : frontendManager.allFrontends()) {
            result.add(ModuleDtoMapper.toDto(frontend));
        }

        ApiResponse.writeList(ctx, result, 100);
    }

    @OpenApi(
            path = "/api/v1/modules/platform/{moduleId}/artifacts/{artifactPath}",
            methods = {HttpMethod.GET},
            operationId = "getPlatformModuleArtifact",
            summary = "Stream a platform module artifact",
            tags = {"Modules"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {
                @OpenApiParam(name = "moduleId", required = true),
                @OpenApiParam(name = "artifactPath", required = true)
            },
            responses = {
                @OpenApiResponse(status = "200", description = "Artifact bytes"),
                @OpenApiResponse(status = "404", description = "Artifact not found")
            })
    private void getPlatformModuleArtifact(Context ctx) {
        String moduleId = ctx.pathParam("moduleId");
        String artifactPath = ctx.pathParam("artifactPath");

        Optional<me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleStore.ArtifactContent> artifact =
                platformManager.readArtifact(moduleId, artifactPath);
        if (artifact.isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Platform module artifact not found", 404));
            return;
        }

        ctx.contentType("application/octet-stream");
        ctx.header(
                "Content-Disposition",
                "attachment; filename=\"" + artifact.get().fileName().replace("\"", "") + "\"");
        ctx.result(new java.io.ByteArrayInputStream(artifact.get().bytes()));
    }

    @OpenApi(
            path = "/api/v1/modules/platform",
            methods = {HttpMethod.GET},
            operationId = "listPlatformModules",
            summary = "List installed platform modules",
            tags = {"Modules"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {@OpenApiResponse(status = "200", description = "Module list + capability metrics")})
    private void listPlatformModules(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.MODULES_VIEW);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(
                "modules",
                platformManager.listModules().stream()
                        .map(ModuleRoutes::platformManagedModuleToJson)
                        .toList());
        body.put("capabilityMetrics", platformCapabilityMetricsToJson(platformManager.capabilityRegistry()));
        ctx.json(body);
    }

    @OpenApi(
            path = "/api/v1/modules/platform/manifests",
            methods = {HttpMethod.GET},
            operationId = "listPlatformModuleManifests",
            summary = "List platform module manifests",
            tags = {"Modules"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {@OpenApiResponse(status = "200", description = "Manifests")})
    private void listPlatformModuleManifests(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.MODULES_VIEW);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(
                "modules",
                platformManager.listModules().stream()
                        .map(ModuleRoutes::platformManagedModuleToJson)
                        .toList());
        ctx.json(body);
    }

    @OpenApi(
            path = "/api/v1/modules/platform/upload",
            methods = {HttpMethod.POST},
            operationId = "uploadPlatformModule",
            summary = "Install a new platform module from JAR",
            tags = {"Modules"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {
                @OpenApiResponse(status = "201", description = "Module installed"),
                @OpenApiResponse(
                        status = "400",
                        description = "Missing or invalid upload",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "409",
                        description = "Install conflict",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "422",
                        description = "Validation or signature failure",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void uploadPlatformModule(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.MODULES_MANAGE);
        handlePlatformMutationUpload(
                ctx,
                controller,
                platformManager,
                frontendManager,
                null,
                "platform-module.upload",
                "PLATFORM_INSTALL_FAILED",
                201);
    }

    @OpenApi(
            path = "/api/v1/modules/platform/{moduleId}/upgrade",
            methods = {HttpMethod.POST},
            operationId = "upgradePlatformModule",
            summary = "Upgrade an installed platform module",
            tags = {"Modules"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "moduleId", required = true)},
            responses = {
                @OpenApiResponse(status = "200", description = "Module upgraded"),
                @OpenApiResponse(status = "404", description = "Module not found"),
                @OpenApiResponse(status = "409", description = "Upgrade conflict"),
                @OpenApiResponse(status = "422", description = "Validation/signature failure")
            })
    private void upgradePlatformModule(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.MODULES_MANAGE);
        String moduleId = ctx.pathParam("moduleId");
        if (platformManager.snapshot(moduleId).isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Platform module not found: " + moduleId, 404));
            return;
        }

        handlePlatformMutationUpload(
                ctx,
                controller,
                platformManager,
                frontendManager,
                moduleId,
                "platform-module.upgrade",
                "PLATFORM_UPGRADE_FAILED",
                200);
    }

    @OpenApi(
            path = "/api/v1/modules/platform/{moduleId}/frontend/reload",
            methods = {HttpMethod.POST},
            operationId = "reloadPlatformModuleFrontend",
            summary = "Hot-reload a module's frontend bundle without touching its classloader",
            description = "Accepts a zipped frontend bundle (same structure as META-INF/frontend/ inside a module jar) "
                    + "and re-stages it on disk. Publishes MODULE_FRONTEND_RELOADED so the dashboard re-imports "
                    + "the bundle. The platform module's REST routes, capabilities, and module-data stay live — "
                    + "only the dashboard-facing assets are swapped. Used by `prexorctl module dev` for fast "
                    + "frontend iteration.",
            tags = {"Modules"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "moduleId", required = true)},
            responses = {
                @OpenApiResponse(status = "200", description = "Frontend reloaded; body carries the new contentHash"),
                @OpenApiResponse(status = "400", description = "Missing or malformed frontend bundle"),
                @OpenApiResponse(status = "404", description = "Module not installed"),
                @OpenApiResponse(status = "422", description = "Bundle missing module-frontend.json manifest")
            })
    private void reloadPlatformModuleFrontend(Context ctx) throws IOException {
        JwtAuthMiddleware.requirePermission(ctx, Permission.MODULES_MANAGE);
        String moduleId = ctx.pathParam("moduleId");

        Optional<PlatformModuleManager.ManagedPlatformModule> existing = platformManager.snapshot(moduleId);
        if (existing.isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Platform module not found: " + moduleId, 404));
            return;
        }

        var uploadedFile = ctx.uploadedFile("frontend");
        if (uploadedFile == null) {
            ctx.status(400);
            ctx.json(errorResponse("MISSING_BUNDLE", "Multipart field 'frontend' is required", 400));
            return;
        }

        // The bundle is a zip with the same internal layout as META-INF/frontend/
        // inside a module jar — including module-frontend.json at the root.
        // ModuleFrontendManager.extractFrontend treats its input as a JarFile,
        // which transparently reads any zip, so the same code path that handles
        // install also handles hot-reload.
        Path tempZip = Files.createTempFile("prexor-frontend-reload-", ".zip");
        try (var input = uploadedFile.content()) {
            Files.copy(input, tempZip, StandardCopyOption.REPLACE_EXISTING);

            boolean extracted = frontendManager.extractFrontend(moduleId, tempZip);
            if (!extracted) {
                ctx.status(422);
                ctx.json(errorResponse(
                        "FRONTEND_MISSING", "Bundle did not contain META-INF/frontend/module-frontend.json", 422));
                return;
            }

            var loaded = frontendManager
                    .getFrontend(moduleId)
                    .orElseThrow(() ->
                            new IllegalStateException("frontend extracted but missing from registry: " + moduleId));
            audit(ctx, controller.stateStore(), "platform-module.frontend.reload", "module", moduleId);
            controller.eventBus().publish(new ModuleFrontendReloadedEvent(moduleId, loaded.contentHash()));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("moduleId", moduleId);
            body.put("contentHash", loaded.contentHash());
            ctx.status(200);
            ctx.json(body);
        } finally {
            Files.deleteIfExists(tempZip);
        }
    }

    @OpenApi(
            path = "/api/v1/modules/platform/{moduleId}",
            methods = {HttpMethod.DELETE},
            operationId = "deletePlatformModule",
            summary = "Uninstall a platform module",
            tags = {"Modules"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "moduleId", required = true)},
            responses = {
                @OpenApiResponse(status = "204", description = "Uninstalled"),
                @OpenApiResponse(status = "404", description = "Module not found"),
                @OpenApiResponse(status = "409", description = "Uninstall conflict")
            })
    private void deletePlatformModule(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.MODULES_MANAGE);
        String moduleId = ctx.pathParam("moduleId");
        Optional<PlatformModuleManager.ManagedPlatformModule> existing = platformManager.snapshot(moduleId);
        if (existing.isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Platform module not found: " + moduleId, 404));
            return;
        }

        try {
            platformManager.uninstall(moduleId);
            frontendManager.removeFrontend(moduleId);
            audit(ctx, controller.stateStore(), "platform-module.delete", "module", moduleId);
            controller.eventBus().publish(new ModuleUnloadedEvent(moduleId));
            ctx.status(204);
        } catch (IllegalStateException e) {
            ctx.status(409);
            ctx.json(errorResponse("PLATFORM_UNINSTALL_FAILED", e.getMessage(), 409));
        }
    }

    @OpenApi(
            path = "/api/v1/modules/platform/{moduleId}/storage",
            methods = {HttpMethod.DELETE},
            operationId = "dropPlatformModuleStorage",
            summary = "Drop a module's Mongo/Redis storage",
            tags = {"Modules"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "moduleId", required = true)},
            responses = {
                @OpenApiResponse(status = "200", description = "Storage dropped"),
                @OpenApiResponse(status = "409", description = "Drop failed")
            })
    private void dropPlatformModuleStorage(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.MODULES_MANAGE);
        String moduleId = ctx.pathParam("moduleId");
        try {
            var dropped = platformManager.dropStorage(moduleId);
            audit(ctx, controller.stateStore(), "platform-module.storage.drop", "module", moduleId);
            ctx.json(ModuleDtoMapper.storageDropResponse(dropped));
        } catch (IllegalStateException e) {
            ctx.status(409);
            ctx.json(errorResponse("PLATFORM_STORAGE_DROP_FAILED", e.getMessage(), 409));
        }
    }

    @OpenApi(
            path = "/api/v1/modules/platform/capabilities",
            methods = {HttpMethod.GET},
            operationId = "listPlatformCapabilities",
            summary = "List platform-module capability bindings",
            tags = {"Modules"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {@OpenApiResponse(status = "200", description = "Capability bindings + metrics")})
    private void listPlatformCapabilities(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.MODULES_VIEW);

        CapabilityRegistry capabilityRegistry = platformManager.capabilityRegistry();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(
                "modules",
                platformManager.listModules().stream()
                        .map(module -> Map.of(
                                "moduleId",
                                module.moduleId(),
                                "state",
                                module.state().name(),
                                "provides",
                                module.manifest().capabilities().provides().stream()
                                        .map(provides -> Map.of(
                                                "id", provides.id(),
                                                "version", provides.version(),
                                                "active",
                                                        capabilityRegistry
                                                                .find(provides.id())
                                                                .map(binding -> binding.moduleId()
                                                                        .equals(module.moduleId()))
                                                                .orElse(false)))
                                        .toList(),
                                "requires",
                                module.manifest().capabilities().requires().stream()
                                        .map(requires -> {
                                            Map<String, Object> required = new LinkedHashMap<>();
                                            required.put("id", requires.id());
                                            required.put("versionRange", requires.versionRange());
                                            required.put(
                                                    "binding",
                                                    capabilityRegistry
                                                            .find(requires.id())
                                                            .map(binding -> Map.of(
                                                                    "moduleId", binding.moduleId(),
                                                                    "version",
                                                                            binding.version()
                                                                                    .toString()))
                                                            .orElse(null));
                                            return required;
                                        })
                                        .toList(),
                                "unresolvedRequirements",
                                module.unresolvedRequirements().stream()
                                        .map(unresolved -> Map.of(
                                                "capabilityId", unresolved.capabilityId(),
                                                "versionRange", unresolved.versionRange(),
                                                "reason", unresolved.reason()))
                                        .toList()))
                        .toList());
        body.put(
                "bindings",
                capabilityRegistry.activeBindings().stream()
                        .map(binding -> Map.of(
                                "capabilityId", binding.capabilityId(),
                                "version", binding.version().toString(),
                                "moduleId", binding.moduleId()))
                        .toList());
        body.put("metrics", platformCapabilityMetricsToJson(capabilityRegistry));
        ctx.json(body);
    }

    @OpenApi(
            path = "/api/v1/modules/platform/extensions",
            methods = {HttpMethod.GET},
            operationId = "listPlatformExtensions",
            summary = "List platform-module extensions",
            tags = {"Modules"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            queryParams = {@OpenApiParam(name = "target", description = "Filter by runtime target")},
            responses = {
                @OpenApiResponse(status = "200", description = "Extensions"),
                @OpenApiResponse(status = "409", description = "Extension index failed")
            })
    private void listPlatformExtensions(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.MODULES_VIEW);
        RuntimeTarget runtimeTarget = optionalRuntimeTarget(ctx.queryParam("target"));

        try {
            ExtensionRegistry registry = platformManager.extensionRegistry();
            List<ExtensionRegistry.RegisteredExtension> extensions =
                    runtimeTarget == null ? registry.listExtensions() : registry.listExtensions(runtimeTarget);

            Map<String, Object> body = new LinkedHashMap<>();
            if (runtimeTarget != null) {
                body.put("target", runtimeTarget.wireValue());
            }
            body.put(
                    "extensions",
                    extensions.stream()
                            .map(ModuleRoutes::platformExtensionToJson)
                            .toList());
            ctx.json(body);
        } catch (ExtensionRegistryException e) {
            ctx.status(409);
            ctx.json(errorResponse("EXTENSION_INDEX_FAILED", e.getMessage(), 409));
        }
    }

    @OpenApi(
            path = "/api/v1/modules/platform/extensions/resolve",
            methods = {HttpMethod.GET},
            operationId = "resolvePlatformExtensions",
            summary = "Resolve extension variants for a runtime",
            tags = {"Modules"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            queryParams = {
                @OpenApiParam(name = "target", required = true, description = "Runtime target"),
                @OpenApiParam(name = "version", required = true, description = "Runtime version"),
                @OpenApiParam(name = "extensionId", description = "Specific extension IDs (repeatable)")
            },
            responses = {
                @OpenApiResponse(status = "200", description = "Resolved variants"),
                @OpenApiResponse(status = "409", description = "Resolution failed")
            })
    private void resolvePlatformExtensions(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.MODULES_VIEW);

        RuntimeTarget runtimeTarget = requireRuntimeTarget(ctx.queryParam("target"));
        String runtimeVersion = requireQuery(ctx.queryParam("version"), "version");

        try {
            ExtensionRegistry registry = platformManager.extensionRegistry();
            List<String> requestedExtensionIds = queryValues(ctx.queryParams("extensionId"));
            List<ExtensionRegistry.ResolvedVariant> resolved = requestedExtensionIds.isEmpty()
                    ? registry.listCompatibleVariants(runtimeTarget, runtimeVersion)
                    : registry.resolveVariants(requestedExtensionIds, runtimeTarget, runtimeVersion);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("target", runtimeTarget.wireValue());
            body.put("runtimeVersion", runtimeVersion);
            body.put(
                    "resolved",
                    resolved.stream()
                            .map(ModuleRoutes::resolvedPlatformExtensionToJson)
                            .toList());
            ctx.json(body);
        } catch (ExtensionRegistryException e) {
            ctx.status(409);
            ctx.json(errorResponse("EXTENSION_RESOLUTION_FAILED", e.getMessage(), 409));
        }
    }

    @OpenApi(
            path = "/api/v1/modules/platform/leaked-classloaders",
            methods = {HttpMethod.GET},
            operationId = "listLeakedClassLoaders",
            summary = "Get module classloader leak status",
            tags = {"Modules"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {@OpenApiResponse(status = "200", description = "Tracker snapshot")})
    private void listLeakedClassLoaders(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.MODULES_MANAGE);
        ModuleClassLoaderTracker tracker = platformManager.classLoaderTracker();
        if (tracker == null) {
            ctx.json(Map.of(
                    "tracking", false,
                    "pending", List.of(),
                    "totals", Map.of()));
            return;
        }
        List<Map<String, Object>> pending = tracker.snapshotPending().stream()
                .map(report -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("moduleId", report.moduleId());
                    entry.put("moduleVersion", report.moduleVersion());
                    entry.put("classLoaderClassName", report.classLoaderClassName());
                    entry.put("trackedAt", report.trackedAt().toString());
                    entry.put("ageMs", report.age().toMillis());
                    entry.put("repeatCount", report.repeatCount());
                    return entry;
                })
                .toList();
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("tracked", tracker.totalTracked());
        totals.put("collected", tracker.totalCollected());
        totals.put("leaks", tracker.totalLeaks());
        totals.put("forcedCleanupHints", tracker.totalForcedCleanupHints());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tracking", true);
        body.put("pending", pending);
        body.put("totals", totals);
        ctx.json(body);
    }

    @OpenApi(
            path = "/api/v1/modules/platform/leaked-classloaders/force-cleanup",
            methods = {HttpMethod.POST},
            operationId = "forceClassLoaderCleanup",
            summary = "Force module classloader cleanup",
            tags = {"Modules"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {
                @OpenApiResponse(status = "200", description = "Cleanup summary"),
                @OpenApiResponse(status = "409", description = "Tracker disabled")
            })
    private void forceClassLoaderCleanup(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.MODULES_MANAGE);
        ModuleClassLoaderTracker tracker = platformManager.classLoaderTracker();
        if (tracker == null) {
            ctx.status(409);
            ctx.json(errorResponse(
                    "CLASSLOADER_TRACKER_DISABLED",
                    "Module classloader tracker is not configured on this controller",
                    409));
            return;
        }
        int pendingBefore = tracker.pendingCount();
        tracker.requestForcedCleanup();
        int pendingAfter = tracker.pendingCount();
        audit(
                ctx,
                controller.stateStore(),
                "platform-module.classloader.force-cleanup",
                "tracker",
                "platform",
                Map.of(
                        "pendingBefore", pendingBefore,
                        "pendingAfter", pendingAfter));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pendingBefore", pendingBefore);
        body.put("pendingAfter", pendingAfter);
        body.put("collected", Math.max(0, pendingBefore - pendingAfter));
        body.put("totalForcedCleanupHints", tracker.totalForcedCleanupHints());
        ctx.json(body);
    }

    @OpenApi(
            path = "/api/v1/modules/{name}/frontend/{filepath}",
            methods = {HttpMethod.GET},
            operationId = "getModuleFrontendAsset",
            summary = "Serve a module frontend asset",
            description =
                    "Public static asset (no auth) — module asset paths are content-hash addressed and loaded via <script>/<link> tags that cannot send Authorization headers.",
            tags = {"Modules"},
            security = {},
            pathParams = {
                @OpenApiParam(name = "name", required = true),
                @OpenApiParam(name = "filepath", required = true)
            },
            responses = {
                @OpenApiResponse(status = "200", description = "Asset stream"),
                @OpenApiResponse(status = "404", description = "Asset not found"),
                @OpenApiResponse(status = "500", description = "Read failure")
            })
    private void getModuleFrontendAsset(Context ctx) {
        String moduleName = ctx.pathParam("name");
        String filepath = ctx.pathParam("filepath");

        Optional<Path> asset = frontendManager.resolveAsset(moduleName, filepath);
        if (asset.isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Frontend asset not found", 404));
            return;
        }

        Path file = asset.get();
        String ext = filepath.contains(".") ? filepath.substring(filepath.lastIndexOf('.')) : "";
        String contentType = MIME_TYPES.getOrDefault(ext.toLowerCase(Locale.ROOT), "application/octet-stream");

        ctx.contentType(contentType);
        ctx.header("Cache-Control", "public, max-age=31536000, immutable");
        try {
            ctx.result(Files.newInputStream(file));
        } catch (IOException _) {
            ctx.status(500);
            ctx.json(errorResponse("INTERNAL", "Failed to read asset", 500));
        }
    }

    private static Map<String, Object> platformManagedModuleToJson(PlatformModuleManager.ManagedPlatformModule module) {
        PlatformModuleManifest manifest = module.manifest();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moduleId", module.moduleId());
        out.put("state", module.state().name());
        out.put("jarFile", module.jarPath().getFileName().toString());
        out.put("jarPath", module.jarPath().toString());
        out.put("sha256", module.sha256());
        out.put("sizeBytes", module.sizeBytes());
        out.put("storedAt", module.storedAt().toString());
        out.put("id", manifest.id());
        out.put("version", manifest.version());
        out.put("manifestVersion", manifest.manifestVersion());
        out.put("lastError", module.lastError());
        Map<String, Object> backendOut = new LinkedHashMap<>();
        if (manifest.backend().controller() != null) {
            backendOut.put(
                    "controller",
                    Map.of("entrypoint", manifest.backend().controller().entrypoint()));
        }
        if (manifest.backend().daemon() != null) {
            backendOut.put(
                    "daemon", Map.of("entrypoint", manifest.backend().daemon().entrypoint()));
        }
        out.put("backend", backendOut);
        out.put(
                "hosts",
                manifest.hosts().stream()
                        .map(h -> h.name().toLowerCase(java.util.Locale.ROOT))
                        .toList());
        out.put(
                "frontend",
                manifest.frontend() == null
                        ? null
                        : Map.of(
                                "sdkVersion", manifest.frontend().sdkVersion(),
                                "entry", manifest.frontend().entry()));
        out.put(
                "capabilities",
                Map.of(
                        "provides",
                                manifest.capabilities().provides().stream()
                                        .map(p -> Map.of("id", p.id(), "version", p.version()))
                                        .toList(),
                        "requires",
                                manifest.capabilities().requires().stream()
                                        .map(r -> Map.of("id", r.id(), "versionRange", r.versionRange()))
                                        .toList()));
        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("mongo", manifest.storage().mongo());
        storage.put("redis", manifest.storage().redis());
        storage.put("mongoDocumentLimit", module.storage().mongoDocumentLimit());
        storage.put("redisKeyLimit", module.storage().redisKeyLimit());
        storage.put("mongoAvailable", module.storage().mongoAvailable());
        storage.put("redisAvailable", module.storage().redisAvailable());
        storage.put("mongoDatabase", module.storage().mongoDatabaseName());
        storage.put("mongoCollectionPrefix", module.storage().mongoCollectionPrefix());
        storage.put("redisKeyPrefix", module.storage().redisKeyPrefix());
        out.put("storage", storage);
        out.put(
                "extensions",
                manifest.extensions().stream()
                        .map(extension -> platformExtensionToJson(
                                new ExtensionRegistry.RegisteredExtension(manifest.id(), extension)))
                        .toList());
        out.put(
                "unresolvedRequirements",
                module.unresolvedRequirements().stream()
                        .map(requirement -> Map.of(
                                "capabilityId", requirement.capabilityId(),
                                "versionRange", requirement.versionRange(),
                                "reason", requirement.reason()))
                        .toList());
        return out;
    }

    private static Map<String, Object> platformCapabilityMetricsToJson(CapabilityRegistry capabilityRegistry) {
        CapabilityRegistry.MetricsSnapshot metrics = capabilityRegistry.metrics();
        return Map.of(
                "resolutionCount",
                metrics.resolutionCount(),
                "unresolvedRequirementCount",
                metrics.unresolvedRequirementCount(),
                "rebindingEventCount",
                metrics.rebindingEventCount(),
                "deprecatedProviderResolutionCount",
                metrics.deprecatedProviderResolutionCount(),
                "lastResolutionLatencyMillis",
                metrics.lastResolutionLatency().toMillis());
    }

    private static Map<String, Object> platformExtensionToJson(ExtensionRegistry.RegisteredExtension extension) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moduleId", extension.moduleId());
        out.put("id", extension.extension().id());
        out.put("target", extension.extension().target().wireValue());
        out.put("activation", extension.extension().activationPolicy().wireValue());
        out.put("conflicts", extension.extension().conflicts());
        out.put(
                "variants",
                extension.extension().variants().stream()
                        .map(variant -> Map.of(
                                "id", variant.id(),
                                "mcVersionRange", variant.mcVersionRange(),
                                "runtimeApiVersion", variant.runtimeApiVersion(),
                                "artifact", variant.artifact(),
                                "sha256", variant.sha256(),
                                "installPath", variant.installPath()))
                        .toList());
        return out;
    }

    private static Map<String, Object> resolvedPlatformExtensionToJson(ExtensionRegistry.ResolvedVariant resolved) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moduleId", resolved.moduleId());
        out.put("extensionId", resolved.extensionId());
        out.put("target", resolved.extension().target().wireValue());
        out.put("activation", resolved.extension().activationPolicy().wireValue());
        out.put("variantId", resolved.variant().id());
        out.put("mcVersionRange", resolved.variant().mcVersionRange());
        out.put("runtimeApiVersion", resolved.variant().runtimeApiVersion());
        out.put("artifact", resolved.variant().artifact());
        out.put("sha256", resolved.variant().sha256());
        out.put("installPath", resolved.variant().installPath());
        return out;
    }

    private void handlePlatformMutationUpload(
            io.javalin.http.Context ctx,
            PrexorController controller,
            PlatformModuleManager platformManager,
            ModuleFrontendManager frontendManager,
            String expectedModuleId,
            String auditAction,
            String conflictCode,
            int successStatus)
            throws IOException {
        var uploadedFile = ctx.uploadedFile("file");
        if (uploadedFile == null) {
            ctx.status(400);
            ctx.json(errorResponse("MISSING_FILE", "No file uploaded. Use multipart form with field 'file'.", 400));
            return;
        }
        if (!uploadedFile.filename().endsWith(".jar")) {
            ctx.status(400);
            ctx.json(errorResponse("INVALID_FILE", "Only .jar files are accepted", 400));
            return;
        }

        Path tempFile = Files.createTempFile("platform-module-upload-", ".jar");
        Path sidecarFile = null;
        try {
            try (var input = uploadedFile.content()) {
                Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            var signaturePart = ctx.uploadedFile("signature");
            if (signaturePart != null) {
                String sigName = signaturePart.filename() == null ? "" : signaturePart.filename();
                String suffix;
                if (sigName.endsWith(".cosign.bundle")) {
                    suffix = ".cosign.bundle";
                } else if (sigName.endsWith(".sig")) {
                    suffix = ".sig";
                } else {
                    ctx.status(400);
                    ctx.json(errorResponse(
                            "INVALID_SIGNATURE", "signature filename must end with .sig or .cosign.bundle", 400));
                    return;
                }
                sidecarFile = tempFile.resolveSibling(tempFile.getFileName() + suffix);
                try (var input = signaturePart.content()) {
                    Files.copy(input, sidecarFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            installPreparedModule(ctx, controller, platformManager, frontendManager, expectedModuleId, tempFile,
                    auditAction, conflictCode, successStatus);
        } finally {
            Files.deleteIfExists(tempFile);
            if (sidecarFile != null) {
                Files.deleteIfExists(sidecarFile);
            }
        }
    }

    /**
     * Shared install pipeline for a JAR already on disk (with its signature sidecar
     * adjacent, discovered by suffix): parse manifest → compatibility check →
     * {@code platformManager.install} (which runs signature verification) → frontend
     * sync, audit, event. Used by both the multipart upload and the registry-pull
     * install so the two paths can never drift in what they verify.
     *
     * <p>Owns its error responses but not the temp-file lifecycle — the caller
     * created the JAR and is responsible for cleaning it up.
     */
    private void installPreparedModule(
            io.javalin.http.Context ctx,
            PrexorController controller,
            PlatformModuleManager platformManager,
            ModuleFrontendManager frontendManager,
            String expectedModuleId,
            Path jarPath,
            String auditAction,
            String conflictCode,
            int successStatus)
            throws IOException {
        try {
            PlatformModuleManifest manifest = readPlatformModuleManifest(jarPath);
            if (expectedModuleId != null && !expectedModuleId.equals(manifest.id())) {
                throw new IllegalArgumentException("module id '" + manifest.id()
                        + "' does not match requested module '" + expectedModuleId + "'");
            }

            PlatformCompatibilityReport compatibilityReport =
                    new ModuleCompatibilityChecker(controller, platformManager).evaluate(manifest);
            if (!compatibilityReport.compatible()) {
                String affectedGroups = compatibilityReport.affectedGroups().stream()
                        .filter(result -> !result.compatible())
                        .map(GroupCompatibilityResult::groupName)
                        .distinct()
                        .sorted()
                        .reduce((left, right) -> left + ", " + right)
                        .orElse("unknown");
                throw new PlatformCompatibilityConflictException(
                        "platform module '%s' is incompatible with existing group runtimes or module policies: %s"
                                .formatted(manifest.id(), affectedGroups),
                        compatibilityReport);
            }

            PlatformModuleManager.ManagedPlatformModule managed = platformManager.install(jarPath);
            syncFrontend(frontendManager, managed);
            audit(ctx, controller.stateStore(), auditAction, "module", managed.moduleId());
            controller
                    .eventBus()
                    .publish(new ModuleLoadedEvent(
                            managed.moduleId(),
                            frontendManager.getFrontend(managed.moduleId()).isPresent()));
            ctx.status(successStatus);
            var response = new LinkedHashMap<>(platformManagedModuleToJson(managed));
            response.put("compatibility", compatibilityReport.toJson());
            ctx.json(response);
        } catch (PlatformModuleManifestException | IllegalArgumentException e) {
            ctx.status(422);
            ctx.json(errorResponse("VALIDATION_ERROR", e.getMessage(), 422));
        } catch (PlatformModuleSignatureVerifier.SignatureVerificationException e) {
            ctx.status(422);
            ctx.json(errorResponse("SIGNATURE_VERIFICATION_FAILED", e.getMessage(), 422));
        } catch (PlatformCompatibilityConflictException e) {
            ctx.status(409);
            var error = new LinkedHashMap<String, Object>();
            error.put("error", Map.of("code", conflictCode, "message", e.getMessage(), "status", 409));
            error.put("compatibility", e.report().toJson());
            ctx.json(error);
        } catch (IllegalStateException e) {
            ctx.status(409);
            ctx.json(errorResponse(conflictCode, e.getMessage(), 409));
        }
    }

    @OpenApi(
            path = "/api/v1/modules/platform/registry",
            methods = {HttpMethod.GET},
            operationId = "listRegistryModules",
            summary = "Browse modules offered by the configured registries",
            description = "Aggregates every configured module registry's index. Optional ?q= filters by "
                    + "case-insensitive substring of moduleId or tags.",
            tags = {"Modules"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            queryParams = {@OpenApiParam(name = "q", description = "Substring filter over moduleId and tags")},
            responses = {@OpenApiResponse(status = "200", description = "Aggregated registry module list")})
    @OpenApi(
            path = "/api/v1/modules/platform/{moduleId}/resources",
            methods = {HttpMethod.GET},
            operationId = "getPlatformModuleResources",
            summary = "Per-module resource usage (CPU, allocation, threads)",
            tags = {"Modules"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "moduleId", required = true)},
            responses = {
                @OpenApiResponse(status = "200", description = "Resource snapshot"),
                @OpenApiResponse(status = "404", description = "Module not found")
            })
    private void getPlatformModuleResources(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.MODULES_VIEW);
        String moduleId = ctx.pathParam("moduleId");
        if (platformManager.snapshot(moduleId).isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Platform module not found: " + moduleId, 404));
            return;
        }
        var tracker = controller.moduleResourceTracker();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("moduleId", moduleId);
        if (tracker == null) {
            body.put("trackingEnabled", false);
            ctx.status(200);
            ctx.json(body);
            return;
        }
        var snapshot = tracker.snapshot(moduleId);
        body.put("trackingEnabled", true);
        body.put("cpuMillis", snapshot.cpuMillis());
        body.put("allocatedBytes", snapshot.allocatedBytes());
        body.put("liveThreads", snapshot.liveThreads());
        body.put("sampledAt", snapshot.sampledAt().toString());
        ctx.status(200);
        ctx.json(body);
    }

    private void listRegistry(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.MODULES_VIEW);
        ModuleRegistryClient client = registryClient();
        String query = ctx.queryParam("q");
        List<ModuleRegistryClient.ResolvedEntry> entries =
                query == null ? client.aggregate() : client.search(query);
        List<Map<String, Object>> rows =
                entries.stream().map(this::registryEntryToJson).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("registries", client.configuredRegistries());
        body.put("modules", rows);
        ctx.status(200);
        ctx.json(body);
    }

    @OpenApi(
            path = "/api/v1/modules/platform/registry/install",
            methods = {HttpMethod.POST},
            operationId = "installModuleFromRegistry",
            summary = "Install a module pulled from a configured registry",
            description = "Resolves {moduleId, version?} against the configured registries, downloads the signed "
                    + "JAR, verifies its sha256 against the index and its signature against the controller trust "
                    + "root, then installs it. Optional registryUrl must be one of the configured registries.",
            tags = {"Modules"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {
                @OpenApiResponse(status = "201", description = "Module installed"),
                @OpenApiResponse(
                        status = "400",
                        description = "Missing moduleId or unconfigured registryUrl",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "404",
                        description = "Module/version not found in any registry",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "409",
                        description = "No registries configured or install conflict",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "422",
                        description = "sha256/signature/validation failure",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "502",
                        description = "Registry fetch/download failure",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void installFromRegistry(Context ctx) throws IOException {
        JwtAuthMiddleware.requirePermission(ctx, Permission.MODULES_MANAGE);
        Map<String, Object> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_BODY", "request body must be a JSON object", 400));
            return;
        }
        String moduleId = stringField(body.get("moduleId"));
        if (moduleId == null) {
            ctx.status(400);
            ctx.json(errorResponse("MISSING_MODULE_ID", "moduleId is required", 400));
            return;
        }
        String version = stringField(body.get("version"));
        String registryUrl = stringField(body.get("registryUrl"));

        ModuleRegistryClient client = registryClient();
        if (client.configuredRegistries().isEmpty()) {
            ctx.status(409);
            ctx.json(errorResponse(
                    "NO_REGISTRIES_CONFIGURED", "no module registries are configured (modules.registries)", 409));
            return;
        }
        if (registryUrl != null && !client.configuredRegistries().contains(registryUrl)) {
            // SSRF guard: never fetch from a registry the operator didn't configure.
            ctx.status(400);
            ctx.json(errorResponse(
                    "UNCONFIGURED_REGISTRY", "registryUrl is not in the configured modules.registries list", 400));
            return;
        }

        Path tempDir = Files.createTempDirectory("module-registry-install-");
        try {
            ModuleRegistryClient.ResolvedEntry resolved = client.resolve(moduleId, version, registryUrl);
            Path jar = client.download(resolved.entry(), tempDir);
            installPreparedModule(
                    ctx,
                    controller,
                    platformManager,
                    frontendManager,
                    moduleId,
                    jar,
                    "platform-module.install-from-registry",
                    "PLATFORM_INSTALL_FAILED",
                    201);
        } catch (ModuleRegistryException e) {
            int status = registryErrorStatus(e.code());
            ctx.status(status);
            ctx.json(errorResponse(e.code(), e.getMessage(), status));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static int registryErrorStatus(String code) {
        return switch (code) {
            case "MODULE_NOT_FOUND" -> 404;
            case "SHA256_MISMATCH", "BAD_URL", "MISSING_JAR_URL", "MISSING_SHA256", "INDEX_PARSE_FAILED" -> 422;
            default -> 502; // FETCH_FAILED, DOWNLOAD_WRITE_FAILED — upstream/network
        };
    }

    private Map<String, Object> registryEntryToJson(ModuleRegistryClient.ResolvedEntry resolved) {
        var entry = resolved.entry();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("registryUrl", resolved.registryUrl());
        out.put("registryName", resolved.registryName());
        out.put("moduleId", entry.moduleId());
        out.put("version", entry.version());
        out.put("sha256", entry.sha256());
        out.put("tags", entry.tags());
        out.put("compatibleControllerVersions", entry.compatibleControllerVersions());
        out.put("readme", entry.readme());
        out.put("signed", entry.hasCosignBundle() || entry.hasSig());
        // "update available" hint for the dashboard: is this module already installed,
        // and at what version?
        platformManager.snapshot(entry.moduleId()).ifPresent(installed -> {
            out.put("installed", true);
            out.put("installedVersion", installed.version());
        });
        out.putIfAbsent("installed", false);
        return out;
    }

    private static String stringField(Object value) {
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            });
        }
    }

    private void syncFrontend(
            ModuleFrontendManager frontendManager, PlatformModuleManager.ManagedPlatformModule managed) {
        if (managed.manifest().frontend() == null) {
            frontendManager.removeFrontend(managed.moduleId());
            return;
        }
        if (!frontendManager.extractFrontend(managed.moduleId(), managed.jarPath())) {
            throw new IllegalStateException(
                    "platform module '%s' declares a frontend but no extractable frontend bundle was found"
                            .formatted(managed.moduleId()));
        }
    }

    private static PlatformModuleManifest readPlatformModuleManifest(Path jarPath) throws IOException {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            var manifestEntry = jarFile.getJarEntry(PlatformModuleManifestParser.FILE_NAME);
            try (InputStream input = manifestEntry == null ? null : jarFile.getInputStream(manifestEntry)) {
                return PlatformModuleManifestParser.parse(
                        input, jarPath.getFileName().toString());
            }
        }
    }

    private static RuntimeTarget optionalRuntimeTarget(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        return RuntimeTarget.parse(rawValue);
    }

    private static RuntimeTarget requireRuntimeTarget(String rawValue) {
        return RuntimeTarget.parse(requireQuery(rawValue, "target"));
    }

    private static String requireQuery(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("query parameter '" + name + "' is required");
        }
        return value;
    }

    private static List<String> queryValues(List<String> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        for (String rawValue : rawValues) {
            if (rawValue == null || rawValue.isBlank()) {
                continue;
            }
            for (String token : rawValue.split(",")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    values.add(trimmed);
                }
            }
        }
        return List.copyOf(values);
    }
}
