package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.audit;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import me.prexorjustin.prexorcloud.common.util.VersionInfo;
import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.recovery.BackupCreator;
import me.prexorjustin.prexorcloud.controller.recovery.BackupManifest;
import me.prexorjustin.prexorcloud.controller.recovery.BackupScope;
import me.prexorjustin.prexorcloud.controller.recovery.BackupServices;
import me.prexorjustin.prexorcloud.controller.recovery.RestoreExecutor;
import me.prexorjustin.prexorcloud.controller.recovery.RestoreExecutor.DataRestoreReport;
import me.prexorjustin.prexorcloud.controller.recovery.RestoreExecutor.RestoreMode;
import me.prexorjustin.prexorcloud.controller.recovery.RestoreExecutor.RestoreRejectedException;
import me.prexorjustin.prexorcloud.controller.recovery.RestoreExecutor.RestoreReport;
import me.prexorjustin.prexorcloud.controller.recovery.RestoreValidator;
import me.prexorjustin.prexorcloud.controller.rest.RestServer;
import me.prexorjustin.prexorcloud.controller.rest.dto.ErrorResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.RestoreRequest;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class BackupRoutes {

    // Substring anchors for scripts/check-openapi-routes.sh — paths registered
    // in nested path() blocks aren't composed by the audit script.
    @SuppressWarnings("unused")
    private static final String P_BACKUP_BY_ID = "/api/v1/backups/{id}";

    @SuppressWarnings("unused")
    private static final String P_BACKUP_VERIFY = "/api/v1/backups/{id}/verify";

    private final PrexorController controller;
    private final BackupServices services;

    public BackupRoutes(PrexorController controller, BackupServices services) {
        this.controller = controller;
        this.services = services;
    }

    public void register() {
        path("/api/v1/backups", () -> {
            get(this::listBackups);
            post(this::createBackup);
            path("/{id}", () -> {
                get(this::getBackup);
                delete(this::deleteBackup);
                post("/verify", this::verifyBackup);
            });
            post("/prune", this::pruneBackups);
        });

        path("/api/v1/restore", () -> post(this::applyRestore));
    }

    @OpenApi(
            path = "/api/v1/backups",
            methods = {HttpMethod.GET},
            operationId = "listBackups",
            summary = "List backups",
            tags = {"Backups"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {@OpenApiResponse(status = "200", description = "Backup list")})
    private void listBackups(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.BACKUPS_VIEW);
        ctx.json(Map.of(
                "directory", services.catalog().root().toString(),
                "retentionCount", controller.config().backup().retentionCount(),
                "items", services.catalog().list()));
    }

    @OpenApi(
            path = "/api/v1/backups",
            methods = {HttpMethod.POST},
            operationId = "createBackup",
            summary = "Create a backup bundle",
            tags = {"Backups"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {
                @OpenApiResponse(
                        status = "201",
                        description = "Backup created",
                        content = {@OpenApiContent(from = BackupManifest.class)})
            })
    private void createBackup(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.BACKUPS_MANAGE);
        BackupScope scope = BackupScope.from(controller.config());
        var creator = new BackupCreator();
        String id = creator.generateId();
        Path bundle = services.catalog().bundleRoot(id);
        BackupManifest manifest = creator.create(
                scope,
                bundle,
                services.workingDirectory(),
                services.mongoDatabase(),
                null,
                controller.config().uuid(),
                VersionInfo.get().version());
        audit(
                ctx,
                controller.stateStore(),
                "BACKUP_CREATE",
                "backup",
                manifest.id(),
                Map.of("sizeBytes", manifest.sizeBytes(), "mongoDocs", manifest.mongoDocumentCount()));
        pruneSilently(controller, services);
        ctx.status(201);
        ctx.json(manifest);
    }

    @OpenApi(
            path = "/api/v1/backups/{id}",
            methods = {HttpMethod.GET},
            operationId = "getBackup",
            summary = "Get backup manifest",
            tags = {"Backups"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Manifest",
                        content = {@OpenApiContent(from = BackupManifest.class)}),
                @OpenApiResponse(status = "404", description = "Backup not found")
            })
    private void getBackup(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.BACKUPS_VIEW);
        ctx.json(loadOr404(services, ctx.pathParam("id")));
    }

    @OpenApi(
            path = "/api/v1/backups/{id}",
            methods = {HttpMethod.DELETE},
            operationId = "deleteBackup",
            summary = "Delete a backup",
            tags = {"Backups"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {
                @OpenApiResponse(status = "204", description = "No Content"),
                @OpenApiResponse(
                        status = "404",
                        description = "Backup not found",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void deleteBackup(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.BACKUPS_MANAGE);
        String id = ctx.pathParam("id");
        if (!services.catalog().delete(id)) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Backup not found: " + id, 404));
            return;
        }
        audit(ctx, controller.stateStore(), "BACKUP_DELETE", "backup", id);
        ctx.status(204);
    }

    @OpenApi(
            path = "/api/v1/backups/{id}/verify",
            methods = {HttpMethod.POST},
            operationId = "verifyBackup",
            summary = "Verify backup integrity",
            tags = {"Backups"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "id", required = true)},
            responses = {@OpenApiResponse(status = "200", description = "Validation report")})
    private void verifyBackup(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.BACKUPS_VIEW);
        String id = ctx.pathParam("id");
        BackupManifest manifest = loadOr404(services, id);
        BackupScope scope = BackupScope.from(controller.config());
        var validation =
                new RestoreValidator().validate(scope, services.catalog().bundleRoot(id));
        ctx.json(Map.of(
                "id", manifest.id(),
                "valid", validation.valid(),
                "missingFiles",
                        validation.filesystem().missingFiles().stream()
                                .map(Path::toString)
                                .toList(),
                "missingDirectories",
                        validation.filesystem().missingDirectories().stream()
                                .map(Path::toString)
                                .toList(),
                "missingMongoCollections", validation.missingMongoCollections(),
                "missingMongoCollectionPrefixes", validation.missingMongoCollectionPrefixes(),
                "missingRedisPrefixes", validation.missingRedisPrefixes(),
                "emptyRequiredFiles",
                        validation.emptyRequiredFiles().stream()
                                .map(Path::toString)
                                .toList()));
    }

    @OpenApi(
            path = "/api/v1/backups/prune",
            methods = {HttpMethod.POST},
            operationId = "pruneBackups",
            summary = "Prune old backups",
            tags = {"Backups"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            queryParams = {@OpenApiParam(name = "keep", type = Integer.class, description = "Number to retain")},
            responses = {@OpenApiResponse(status = "200", description = "Pruned IDs")})
    private void pruneBackups(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.BACKUPS_MANAGE);
        int retain =
                parseInt(ctx.queryParam("keep"), controller.config().backup().retentionCount());
        var pruned = services.catalog().prune(retain);
        audit(ctx, controller.stateStore(), "BACKUP_PRUNE", "backup", "*", Map.of("removed", pruned.size()));
        ctx.json(Map.of("removed", pruned.stream().map(BackupManifest::id).toList()));
    }

    @OpenApi(
            path = "/api/v1/restore",
            methods = {HttpMethod.POST},
            operationId = "applyRestore",
            summary = "Apply or dry-run a restore",
            tags = {"Backups"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = RestoreRequest.class)}),
            responses = {
                @OpenApiResponse(status = "200", description = "Restore report"),
                @OpenApiResponse(
                        status = "400",
                        description = "Missing id",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "422",
                        description = "Backup failed validation",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    @SuppressWarnings("unchecked")
    private void applyRestore(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.BACKUPS_RESTORE);
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String id = stringOr(body, "id", null);
        if (id == null) {
            ctx.status(400);
            ctx.json(errorResponse("VALIDATION_ERROR", "id is required", 400));
            return;
        }
        boolean dryRun = booleanOr(body, "dryRun", false);
        boolean filesystem = booleanOr(body, "filesystem", true);
        boolean datastores = booleanOr(body, "datastores", true);
        BackupManifest manifest = loadOr404(services, id);
        BackupScope scope = BackupScope.from(controller.config());
        Path bundle = services.catalog().bundleRoot(id);
        RestoreMode mode = dryRun ? RestoreMode.DRY_RUN : RestoreMode.APPLY;

        try {
            var executor = new RestoreExecutor();
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("id", manifest.id());
            response.put("dryRun", dryRun);
            if (filesystem) {
                RestoreReport report = executor.restoreFilesystem(scope, bundle, services.workingDirectory(), mode);
                // rollbackRoot is null on a dry run; Map.of rejects null values, so use a
                // null-tolerant map for the filesystem report.
                Map<String, Object> filesystemReport = new java.util.LinkedHashMap<>();
                filesystemReport.put("applied", report.applied());
                filesystemReport.put("entryCount", report.entries().size());
                filesystemReport.put(
                        "rollbackRoot",
                        report.rollbackRoot() == null
                                ? null
                                : report.rollbackRoot().toString());
                response.put("filesystem", filesystemReport);
            }
            if (datastores) {
                DataRestoreReport report =
                        executor.restoreDatastores(scope, bundle, services.mongoDatabase(), null, mode);
                response.put(
                        "datastores",
                        Map.of(
                                "applied", report.applied(),
                                "mongoCollections", report.mongoImports().size(),
                                "mongoPrefixGroups", report.mongoPrefixImports().size(),
                                "redisPrefixes", report.redisImports().size()));
            }
            audit(
                    ctx,
                    controller.stateStore(),
                    dryRun ? "RESTORE_DRY_RUN" : "RESTORE_APPLY",
                    "backup",
                    id,
                    Map.of("filesystem", filesystem, "datastores", datastores));
            ctx.json(response);
        } catch (RestoreRejectedException _) {
            ctx.status(422);
            ctx.json(errorResponse(
                    "RESTORE_REJECTED", "Backup failed validation; run /api/v1/backups/" + id + "/verify", 422));
        }
    }

    private static BackupManifest loadOr404(BackupServices services, String id) throws IOException {
        return services.catalog()
                .load(id)
                .orElseThrow(() -> new RestServer.NotFoundException("Backup not found: " + id));
    }

    private static void pruneSilently(PrexorController controller, BackupServices services) {
        try {
            services.catalog().prune(controller.config().backup().retentionCount());
        } catch (IOException _) {
        }
    }

    private static String stringOr(Map<String, Object> body, String key, String def) {
        Object v = body == null ? null : body.get(key);
        return v == null ? def : v.toString();
    }

    private static boolean booleanOr(Map<String, Object> body, String key, boolean def) {
        Object v = body == null ? null : body.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return def;
    }

    private static int parseInt(String raw, int def) {
        if (raw == null || raw.isBlank()) return def;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException _) {
            return def;
        }
    }
}
