package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.*;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.audit;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.auditDiff;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableValidator;
import me.prexorjustin.prexorcloud.controller.rest.ApiResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.ErrorResponse;
import me.prexorjustin.prexorcloud.controller.rest.dto.TemplateDtoMapper;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;
import me.prexorjustin.prexorcloud.controller.template.TemplateConfig;
import me.prexorjustin.prexorcloud.controller.template.TemplateVariableProcessor;
import me.prexorjustin.prexorcloud.controller.template.ops.ArchiveSearcher;
import me.prexorjustin.prexorcloud.controller.template.ops.SnapshotExtractor;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

public final class TemplateRoutes {

    public record RenameRequest(String from, String to) {}

    public record SaveContentRequest(String content) {}

    public record RollbackRequest(String hash) {}

    private final PrexorController controller;

    public TemplateRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        path("/api/v1/templates", () -> {
            get(this::listTemplates);
            post(this::createTemplate);
            get("/{name}", this::getTemplate);
            patch("/{name}", this::updateTemplate);
            delete("/{name}", this::deleteTemplate);
            get("/{name}/versions", this::listTemplateVersions);
            delete("/{name}/versions/{hash}", this::deleteTemplateVersion);
            post("/{name}/rollback", this::rollbackTemplate);
            post("/{name}/files/mkdir", this::mkdirTemplateFile);
            get("/{name}/files", this::listTemplateFiles);
            get("/{name}/files/content", this::getTemplateFileContent);
            get("/{name}/files/download", this::downloadTemplateFile);
            post("/{name}/files/rename", this::renameTemplateFile);
            delete("/{name}/files", this::deleteTemplateFile);
            post("/{name}/files/upload", this::uploadTemplateFile);
            post("/{name}/files/extract", this::extractTemplateArchive);
            put("/{name}/files/content", this::saveTemplateFileContent);
            post("/{name}/rehash", this::rehashTemplate);
            get("/{name}/variables", this::getTemplateVariables);
            put("/{name}/variables", this::updateTemplateVariables);
            get("/{name}/variables/scan", this::scanTemplateVariables);
            get("/{name}/inheritance", this::getTemplateInheritance);
            get("/{name}/search", this::searchTemplate);
            get("/{name}/export", this::exportTemplate);
            post("/import", this::importTemplate);
        });
    }

    @OpenApi(
            path = "/api/v1/templates",
            methods = {HttpMethod.GET},
            operationId = "listTemplates",
            summary = "List templates",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {@OpenApiResponse(status = "200", description = "Template list")})
    private void listTemplates(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_VIEW);
        var templates = controller.templateManager().getAll().stream()
                .filter(t -> !t.name().startsWith("_"))
                .map(TemplateDtoMapper::toDto)
                .toList();
        ApiResponse.writeList(ctx, templates, 200);
    }

    @OpenApi(
            path = "/api/v1/templates",
            methods = {HttpMethod.POST},
            operationId = "createTemplate",
            summary = "Create template",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = TemplateConfig.class)}),
            responses = {@OpenApiResponse(status = "201", description = "Created")})
    private void createTemplate(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_CREATE);
        var body = ctx.bodyAsClass(TemplateConfig.class);
        controller.templateManager().save(body);
        var saved = controller.templateManager().get(body.name());
        auditDiff(
                ctx,
                controller.stateStore(),
                "template.create",
                "template",
                body.name(),
                null,
                TemplateDtoMapper.toDto(saved.orElse(body)));
        ctx.status(201);
        ctx.json(TemplateDtoMapper.toDto(saved.orElse(body)));
    }

    @OpenApi(
            path = "/api/v1/templates/{name}",
            methods = {HttpMethod.GET},
            operationId = "getTemplate",
            summary = "Get template",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true)},
            responses = {
                @OpenApiResponse(status = "200", description = "Template"),
                @OpenApiResponse(status = "404", description = "Template not found")
            })
    private void getTemplate(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_VIEW);
        String name = ctx.pathParam("name");
        var template = controller.templateManager().get(name);
        if (template.isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Template not found: " + name, 404));
            return;
        }
        ctx.json(TemplateDtoMapper.toDto(template.get()));
    }

    @OpenApi(
            path = "/api/v1/templates/{name}",
            methods = {HttpMethod.PATCH},
            operationId = "updateTemplate",
            summary = "Update template metadata",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true)},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = TemplateConfig.class)}),
            responses = {
                @OpenApiResponse(status = "200", description = "Updated"),
                @OpenApiResponse(status = "404", description = "Template not found")
            })
    private void updateTemplate(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_UPDATE);
        String name = ctx.pathParam("name");
        var existing = controller.templateManager().get(name);
        if (existing.isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Template not found: " + name, 404));
            return;
        }
        var body = ctx.bodyAsClass(TemplateConfig.class);
        String description = body.description().isEmpty() ? existing.get().description() : body.description();
        String platform = body.platform().isEmpty() ? existing.get().platform() : body.platform();
        var updated = new TemplateConfig(
                name,
                description,
                platform,
                existing.get().hash(),
                existing.get().sizeBytes());
        controller.templateManager().save(updated);
        auditDiff(
                ctx,
                controller.stateStore(),
                "template.update",
                "template",
                name,
                TemplateDtoMapper.toDto(existing.get()),
                TemplateDtoMapper.toDto(updated));
        ctx.json(TemplateDtoMapper.toDto(updated));
    }

    @OpenApi(
            path = "/api/v1/templates/{name}",
            methods = {HttpMethod.DELETE},
            operationId = "deleteTemplate",
            summary = "Delete template",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true)},
            responses = {
                @OpenApiResponse(status = "204", description = "No Content"),
                @OpenApiResponse(
                        status = "400",
                        description = "Cannot delete a synthetic template",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(status = "404", description = "Template not found")
            })
    private void deleteTemplate(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_DELETE);
        String name = ctx.pathParam("name");
        if (name.startsWith("_module-plugins-")) {
            ctx.status(400);
            ctx.json(errorResponse(
                    "PROTECTED",
                    "Synthetic module-plugin templates are managed automatically and cannot be deleted",
                    400));
            return;
        }
        if (!controller.templateManager().exists(name)) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Template not found: " + name, 404));
            return;
        }
        var beforeTemplate = controller.templateManager().get(name);
        controller.templateManager().delete(name);
        auditDiff(
                ctx,
                controller.stateStore(),
                "template.delete",
                "template",
                name,
                beforeTemplate.map(TemplateDtoMapper::toDto).orElse(null),
                null);
        ctx.status(204);
    }

    @OpenApi(
            path = "/api/v1/templates/{name}/versions",
            methods = {HttpMethod.GET},
            operationId = "listTemplateVersions",
            summary = "List template version snapshots",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true)},
            responses = {@OpenApiResponse(status = "200", description = "Versions")})
    private void listTemplateVersions(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_VIEW);
        String name = ctx.pathParam("name");
        if (!controller.templateManager().exists(name)) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Template not found: " + name, 404));
            return;
        }
        var versions = controller.stateStore().getTemplateVersions(name).stream()
                .map(TemplateDtoMapper::toVersionDto)
                .toList();
        ctx.json(versions);
    }

    @OpenApi(
            path = "/api/v1/templates/{name}/versions/{hash}",
            methods = {HttpMethod.DELETE},
            operationId = "deleteTemplateVersion",
            summary = "Delete a template version snapshot",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true), @OpenApiParam(name = "hash", required = true)},
            responses = {
                @OpenApiResponse(status = "200", description = "Deleted"),
                @OpenApiResponse(status = "400", description = "Cannot delete current version"),
                @OpenApiResponse(status = "404", description = "Not found")
            })
    private void deleteTemplateVersion(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_DELETE);
        String name = ctx.pathParam("name");
        String hash = ctx.pathParam("hash");
        if (!controller.templateManager().exists(name)) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Template not found: " + name, 404));
            return;
        }
        var current = controller.templateManager().get(name);
        if (current.isPresent() && current.get().hash().equals(hash)) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Cannot delete the current active version", 400));
            return;
        }
        try {
            controller.templateManager().deleteSnapshot(name, hash);
        } catch (IllegalArgumentException e) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", e.getMessage(), 404));
            return;
        }
        audit(ctx, controller.stateStore(), "template.version.delete", "template", name, Map.of("hash", hash));
        ctx.json(TemplateDtoMapper.statusResponse("deleted"));
    }

    @OpenApi(
            path = "/api/v1/templates/{name}/rollback",
            methods = {HttpMethod.POST},
            operationId = "rollbackTemplate",
            summary = "Rollback to previous hash",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true)},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = RollbackRequest.class)}),
            responses = {
                @OpenApiResponse(status = "200", description = "Rolled back"),
                @OpenApiResponse(status = "404", description = "Template or hash not found"),
                @OpenApiResponse(status = "422", description = "No snapshot for the requested hash")
            })
    private void rollbackTemplate(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_UPDATE);
        String name = ctx.pathParam("name");
        if (!controller.templateManager().exists(name)) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Template not found: " + name, 404));
            return;
        }
        var body = ctx.bodyAsClass(java.util.HashMap.class);
        String targetHash = body == null ? null : (String) body.get("hash");
        if (targetHash == null || targetHash.isBlank()) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Missing 'hash' in request body", 400));
            return;
        }
        boolean hashExists = controller.stateStore().getTemplateVersions(name).stream()
                .anyMatch(v -> v.hash().equals(targetHash));
        if (!hashExists) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "No recorded version with hash: " + targetHash, 404));
            return;
        }
        try {
            controller.templateManager().restoreSnapshot(name, targetHash);
        } catch (IllegalArgumentException e) {
            ctx.status(422);
            ctx.json(errorResponse("NO_SNAPSHOT", e.getMessage(), 422));
            return;
        }
        var updated = controller.templateManager().get(name);
        audit(ctx, controller.stateStore(), "template.rollback", "template", name, Map.of("targetHash", targetHash));
        ctx.json(TemplateDtoMapper.rollbackResponse(updated.map(t -> t.hash()).orElse(targetHash)));
    }

    @OpenApi(
            path = "/api/v1/templates/{name}/files/mkdir",
            methods = {HttpMethod.POST},
            operationId = "mkdirTemplateFile",
            summary = "Create directory in template",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true)},
            queryParams = {@OpenApiParam(name = "path", required = true)},
            responses = {@OpenApiResponse(status = "200", description = "Created")})
    private void mkdirTemplateFile(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_UPDATE);
        String name = ctx.pathParam("name");
        String dirPath = ctx.queryParam("path");
        if (dirPath == null || dirPath.isBlank()) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Missing 'path' query param", 400));
            return;
        }
        Path base = controller.templateManager().getTemplateFilesDir(name);
        Path target = base.resolve(dirPath).normalize();
        if (!target.startsWith(base)) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Invalid path", 400));
            return;
        }
        Files.createDirectories(target);
        ctx.json(TemplateDtoMapper.statusResponse("created"));
    }

    @OpenApi(
            path = "/api/v1/templates/{name}/files",
            methods = {HttpMethod.GET},
            operationId = "listTemplateFiles",
            summary = "Browse template files",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true)},
            queryParams = {
                @OpenApiParam(name = "path", description = "Subpath to list"),
                @OpenApiParam(name = "version", description = "Snapshot hash to browse")
            },
            responses = {@OpenApiResponse(status = "200", description = "File list")})
    private void listTemplateFiles(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_VIEW);
        String name = ctx.pathParam("name");
        String subPath = ctx.queryParam("path");
        String versionHash = ctx.queryParam("version");

        if (versionHash != null && !versionHash.isBlank()) {
            if (!versionHash.matches("[a-f0-9]{16,128}")) {
                ctx.status(400);
                ctx.json(errorResponse("BAD_REQUEST", "Invalid version hash format", 400));
                return;
            }
            try {
                var files = controller.templateManager().listSnapshotFiles(name, versionHash, subPath);
                ctx.json(files);
            } catch (IllegalArgumentException e) {
                ctx.status(404);
                ctx.json(errorResponse("NOT_FOUND", e.getMessage(), 404));
            }
            return;
        }

        Path baseDir = controller.templateManager().getTemplateFilesDir(name);
        Path dir = subPath != null ? baseDir.resolve(subPath).normalize() : baseDir;

        if (!dir.startsWith(baseDir) || !Files.exists(dir)) {
            ctx.json(List.of());
            return;
        }
        try (Stream<Path> stream = Files.walk(dir, 1)) {
            var files = stream.filter(p -> !p.equals(dir))
                    .map(p -> Map.of(
                            "name",
                            p.getFileName().toString(),
                            "isDirectory",
                            Files.isDirectory(p),
                            "size",
                            fileSize(p)))
                    .toList();
            ctx.json(files);
        }
    }

    @OpenApi(
            path = "/api/v1/templates/{name}/files/content",
            methods = {HttpMethod.GET},
            operationId = "getTemplateFileContent",
            summary = "Read template file content",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true)},
            queryParams = {
                @OpenApiParam(name = "path", required = true),
                @OpenApiParam(name = "version", description = "Snapshot hash")
            },
            responses = {
                @OpenApiResponse(status = "200", description = "File content"),
                @OpenApiResponse(status = "404", description = "File not found"),
                @OpenApiResponse(status = "415", description = "Binary file")
            })
    private void getTemplateFileContent(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_VIEW);
        String name = ctx.pathParam("name");
        String filePath = ctx.queryParam("path");
        if (filePath == null) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Missing 'path' query param", 400));
            return;
        }

        String versionHash = ctx.queryParam("version");
        if (versionHash != null && !versionHash.isBlank()) {
            if (!versionHash.matches("[a-f0-9]{16,128}")) {
                ctx.status(400);
                ctx.json(errorResponse("BAD_REQUEST", "Invalid version hash format", 400));
                return;
            }
            try {
                String content = controller.templateManager().readSnapshotFileContent(name, versionHash, filePath);
                if (content == null) {
                    ctx.status(404);
                    ctx.json(errorResponse("NOT_FOUND", "File not found in snapshot", 404));
                    return;
                }
                ctx.result(content);
            } catch (IllegalArgumentException e) {
                ctx.status(404);
                ctx.json(errorResponse("NOT_FOUND", e.getMessage(), 404));
            }
            return;
        }

        Path dir = controller.templateManager().getTemplateFilesDir(name);
        Path target = dir.resolve(filePath).normalize();
        if (!target.startsWith(dir) || !Files.exists(target)) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "File not found", 404));
            return;
        }
        try {
            ctx.result(Files.readString(target));
        } catch (java.nio.charset.MalformedInputException _) {
            ctx.status(415);
            ctx.json(errorResponse("BINARY_FILE", "File is binary and cannot be displayed as text", 415));
        }
    }

    @OpenApi(
            path = "/api/v1/templates/{name}/files/download",
            methods = {HttpMethod.GET},
            operationId = "downloadTemplateFile",
            summary = "Download template file",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true)},
            queryParams = {@OpenApiParam(name = "path", required = true)},
            responses = {
                @OpenApiResponse(status = "200", description = "File"),
                @OpenApiResponse(status = "404", description = "Not found")
            })
    private void downloadTemplateFile(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_VIEW);
        String name = ctx.pathParam("name");
        String filePath = ctx.queryParam("path");
        if (filePath == null || filePath.isBlank()) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Missing 'path' query param", 400));
            return;
        }
        Path base = controller.templateManager().getTemplateFilesDir(name);
        Path target = base.resolve(filePath).normalize();
        if (!target.startsWith(base)) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Invalid path", 400));
            return;
        }
        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "File not found: " + filePath, 404));
            return;
        }
        String contentType = Files.probeContentType(target);
        if (contentType == null) contentType = "application/octet-stream";
        ctx.contentType(contentType);
        ctx.header(
                "Content-Disposition",
                "attachment; filename=\"" + target.getFileName().toString() + "\"");
        ctx.result(Files.newInputStream(target));
    }

    @OpenApi(
            path = "/api/v1/templates/{name}/files/rename",
            methods = {HttpMethod.POST},
            operationId = "renameTemplateFile",
            summary = "Rename template file",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true)},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = RenameRequest.class)}),
            responses = {
                @OpenApiResponse(status = "200", description = "Renamed"),
                @OpenApiResponse(status = "404", description = "Source not found"),
                @OpenApiResponse(status = "409", description = "Destination exists")
            })
    private void renameTemplateFile(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_UPDATE);
        String name = ctx.pathParam("name");
        var body = ctx.bodyAsClass(RenameRequest.class);
        if (body.from() == null
                || body.from().isBlank()
                || body.to() == null
                || body.to().isBlank()) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Both 'from' and 'to' fields are required", 400));
            return;
        }
        Path base = controller.templateManager().getTemplateFilesDir(name);
        Path source = base.resolve(body.from()).normalize();
        Path destination = base.resolve(body.to()).normalize();
        if (!source.startsWith(base) || !destination.startsWith(base)) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Invalid path", 400));
            return;
        }
        if (!Files.exists(source)) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Source file not found: " + body.from(), 404));
            return;
        }
        if (Files.exists(destination)) {
            ctx.status(409);
            ctx.json(errorResponse("CONFLICT", "Destination already exists: " + body.to(), 409));
            return;
        }
        boolean skipRehash = "false".equalsIgnoreCase(ctx.queryParam("rehash"));
        if (skipRehash) controller.templateManager().suppressWatcher(name);
        Files.move(source, destination);
        if (!skipRehash) controller.templateManager().rehash(name);
        audit(
                ctx,
                controller.stateStore(),
                "template.file.rename",
                "template",
                name,
                Map.of("from", body.from(), "to", body.to()));
        ctx.json(TemplateDtoMapper.statusResponse("renamed"));
    }

    @OpenApi(
            path = "/api/v1/templates/{name}/files",
            methods = {HttpMethod.DELETE},
            operationId = "deleteTemplateFile",
            summary = "Delete template file or directory",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true)},
            queryParams = {@OpenApiParam(name = "path", required = true)},
            responses = {
                @OpenApiResponse(status = "204", description = "Deleted"),
                @OpenApiResponse(status = "404", description = "Not found")
            })
    private void deleteTemplateFile(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_UPDATE);
        String name = ctx.pathParam("name");
        String filePath = ctx.queryParam("path");
        if (filePath == null || filePath.isBlank()) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Missing 'path' query param", 400));
            return;
        }
        Path base = controller.templateManager().getTemplateFilesDir(name);
        Path target = base.resolve(filePath).normalize();
        if (!target.startsWith(base)) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Invalid path", 400));
            return;
        }
        if (!Files.exists(target)) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "File not found: " + filePath, 404));
            return;
        }
        boolean skipRehash = "false".equalsIgnoreCase(ctx.queryParam("rehash"));
        if (skipRehash) controller.templateManager().suppressWatcher(name);
        if (Files.isDirectory(target)) {
            try (var walk = Files.walk(target)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException _) {
                    }
                });
            }
        } else {
            Files.delete(target);
        }
        if (!skipRehash) controller.templateManager().rehash(name);
        audit(ctx, controller.stateStore(), "template.file.delete", "template", name, Map.of("path", filePath));
        ctx.status(204);
    }

    @OpenApi(
            path = "/api/v1/templates/{name}/files/upload",
            methods = {HttpMethod.POST},
            operationId = "uploadTemplateFile",
            summary = "Upload file(s) into template",
            description = "Multipart form. ZIP archives may be auto-extracted with `extract=true`.",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true)},
            queryParams = {
                @OpenApiParam(name = "path", description = "Target subdirectory"),
                @OpenApiParam(name = "extract", description = "Extract ZIPs in place")
            },
            responses = {
                @OpenApiResponse(status = "200", description = "Uploaded"),
                @OpenApiResponse(status = "404", description = "Template not found")
            })
    private void uploadTemplateFile(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_UPDATE);
        String name = ctx.pathParam("name");
        if (!controller.templateManager().exists(name)) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Template not found: " + name, 404));
            return;
        }

        Path baseDir = controller.templateManager().getTemplateFilesDir(name);
        String targetPath = ctx.queryParam("path");
        Path targetDir = (targetPath != null && !targetPath.isBlank())
                ? baseDir.resolve(targetPath).normalize()
                : baseDir;

        if (!targetDir.startsWith(baseDir)) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Invalid path", 400));
            return;
        }

        var uploadedFiles = ctx.uploadedFiles("file");
        if (uploadedFiles.isEmpty()) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "No file uploaded", 400));
            return;
        }

        boolean extract = "true".equalsIgnoreCase(ctx.queryParam("extract"));
        boolean skipRehash = "false".equalsIgnoreCase(ctx.queryParam("rehash"));
        if (skipRehash) controller.templateManager().suppressWatcher(name);
        int fileCount = 0;

        for (var uploaded : uploadedFiles) {
            String fileName = uploaded.filename();
            boolean isZip = fileName.toLowerCase().endsWith(".zip");

            if (isZip && extract) {
                try (InputStream is = uploaded.content()) {
                    fileCount += SnapshotExtractor.extract(is, targetDir, targetDir);
                }
            } else {
                Path target = targetDir.resolve(fileName).normalize();
                if (!target.startsWith(baseDir)) continue;
                Files.createDirectories(target.getParent());
                try (InputStream is = uploaded.content()) {
                    Files.copy(is, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                fileCount++;
            }
        }

        if (!skipRehash) controller.templateManager().rehash(name);
        audit(
                ctx,
                controller.stateStore(),
                "template.file.upload",
                "template",
                name,
                Map.of("path", targetPath != null ? targetPath : "/", "files", fileCount));
        ctx.json(TemplateDtoMapper.statusWithFilesResponse("uploaded", fileCount));
    }

    @OpenApi(
            path = "/api/v1/templates/{name}/files/extract",
            methods = {HttpMethod.POST},
            operationId = "extractTemplateArchive",
            summary = "Extract a ZIP already in the template",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true)},
            queryParams = {
                @OpenApiParam(name = "path", required = true),
                @OpenApiParam(name = "delete", description = "Delete the source ZIP after extraction")
            },
            responses = {
                @OpenApiResponse(status = "200", description = "Extracted"),
                @OpenApiResponse(status = "400", description = "Not a ZIP / invalid path")
            })
    private void extractTemplateArchive(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_UPDATE);
        String name = ctx.pathParam("name");
        if (!controller.templateManager().exists(name)) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Template not found: " + name, 404));
            return;
        }

        String filePath = ctx.queryParam("path");
        if (filePath == null || filePath.isBlank()) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Missing 'path' query param", 400));
            return;
        }

        Path baseDir = controller.templateManager().getTemplateFilesDir(name);
        Path zipFile = baseDir.resolve(filePath).normalize();
        if (!zipFile.startsWith(baseDir)) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Invalid path", 400));
            return;
        }
        if (!Files.isRegularFile(zipFile) || !zipFile.toString().toLowerCase().endsWith(".zip")) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Not a ZIP file", 400));
            return;
        }

        Path targetDir = zipFile.getParent();
        int fileCount;
        try (InputStream is = Files.newInputStream(zipFile)) {
            fileCount = SnapshotExtractor.extract(is, targetDir, baseDir);
        }

        boolean deleteZip = !"false".equalsIgnoreCase(ctx.queryParam("delete"));
        if (deleteZip) Files.deleteIfExists(zipFile);

        controller.templateManager().suppressWatcher(name);
        audit(
                ctx,
                controller.stateStore(),
                "template.file.extract",
                "template",
                name,
                Map.of("path", filePath, "files", fileCount));
        ctx.json(TemplateDtoMapper.statusWithFilesResponse("extracted", fileCount));
    }

    @OpenApi(
            path = "/api/v1/templates/{name}/files/content",
            methods = {HttpMethod.PUT},
            operationId = "saveTemplateFileContent",
            summary = "Save template file content",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true)},
            queryParams = {@OpenApiParam(name = "path", required = true)},
            requestBody =
                    @OpenApiRequestBody(
                            required = true,
                            content = {@OpenApiContent(from = SaveContentRequest.class)}),
            responses = {@OpenApiResponse(status = "200", description = "Saved")})
    private void saveTemplateFileContent(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_UPDATE);
        String name = ctx.pathParam("name");
        String filePath = ctx.queryParam("path");
        if (filePath == null) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Missing 'path' query param", 400));
            return;
        }
        Path dir = controller.templateManager().getTemplateFilesDir(name);
        Path target = dir.resolve(filePath).normalize();
        if (!target.startsWith(dir)) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Invalid path", 400));
            return;
        }
        var body = ctx.bodyAsClass(SaveContentRequest.class);
        boolean skipRehash = "false".equalsIgnoreCase(ctx.queryParam("rehash"));
        if (skipRehash) controller.templateManager().suppressWatcher(name);
        Files.createDirectories(target.getParent());
        Files.writeString(target, body.content() != null ? body.content() : "");
        if (!skipRehash) controller.templateManager().rehash(name);
        ctx.json(TemplateDtoMapper.statusResponse("saved"));
    }

    @OpenApi(
            path = "/api/v1/templates/{name}/rehash",
            methods = {HttpMethod.POST},
            operationId = "rehashTemplate",
            summary = "Recompute template hash",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true)},
            responses = {@OpenApiResponse(status = "200", description = "Rehashed")})
    private void rehashTemplate(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_UPDATE);
        String name = ctx.pathParam("name");
        controller.templateManager().unsuppressWatcher(name);
        controller.templateManager().rehash(name);
        ctx.json(TemplateDtoMapper.statusResponse("rehashed"));
    }

    @OpenApi(
            path = "/api/v1/templates/{name}/variables",
            methods = {HttpMethod.GET},
            operationId = "getTemplateVariables",
            summary = "Get template variable definitions (typed)",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true)},
            responses = {
                @OpenApiResponse(
                        status = "200",
                        description = "Variable definitions",
                        content = {@OpenApiContent(from = VariableDef[].class)})
            })
    private void getTemplateVariables(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_VIEW);
        String name = ctx.pathParam("name");
        if (!controller.templateManager().exists(name)) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Template not found: " + name, 404));
            return;
        }
        ctx.json(controller.stateStore().getTemplateVariableDefs(name));
    }

    @OpenApi(
            path = "/api/v1/templates/{name}/variables",
            methods = {HttpMethod.PUT},
            operationId = "updateTemplateVariables",
            summary = "Replace template variable definitions (typed, validate-on-set)",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true)},
            requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = VariableDef[].class)}),
            responses = {
                @OpenApiResponse(status = "200", description = "Saved"),
                @OpenApiResponse(status = "422", description = "Invalid variable definition")
            })
    private void updateTemplateVariables(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_UPDATE);
        String name = ctx.pathParam("name");
        if (!controller.templateManager().exists(name)) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Template not found: " + name, 404));
            return;
        }
        var defs = List.of(ctx.bodyAsClass(VariableDef[].class));
        var errors = VariableValidator.validateDefinitions(defs);
        if (!errors.isEmpty()) {
            ctx.status(422);
            ctx.json(errorResponse("VARIABLE_DEFINITION_INVALID", String.join("; ", errors), 422));
            return;
        }
        controller.stateStore().saveTemplateVariableDefs(name, defs);
        audit(ctx, controller.stateStore(), "template.variables.update", "template", name);
        ctx.json(TemplateDtoMapper.statusResponse("saved"));
    }

    @OpenApi(
            path = "/api/v1/templates/{name}/variables/scan",
            methods = {HttpMethod.GET},
            operationId = "scanTemplateVariables",
            summary = "Scan template files for {{var}} placeholders",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true)},
            responses = {@OpenApiResponse(status = "200", description = "Discovered placeholders")})
    private void scanTemplateVariables(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_VIEW);
        String name = ctx.pathParam("name");
        if (!controller.templateManager().exists(name)) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Template not found: " + name, 404));
            return;
        }
        var found = TemplateVariableProcessor.scanDirectory(
                controller.templateManager().getTemplateFilesDir(name));
        ctx.json(found);
    }

    @OpenApi(
            path = "/api/v1/templates/{name}/inheritance",
            methods = {HttpMethod.GET},
            operationId = "getTemplateInheritance",
            summary = "Get template inheritance chain",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true)},
            responses = {@OpenApiResponse(status = "200", description = "Inheritance chain")})
    private void getTemplateInheritance(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_VIEW);
        String name = ctx.pathParam("name");
        var template = controller.templateManager().get(name);
        if (template.isEmpty()) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Template not found: " + name, 404));
            return;
        }
        var chain = new ArrayList<Map<String, Object>>();
        chain.add(Map.of("name", "base", "exists", controller.templateManager().exists("base")));
        String platform = template.get().platform().toLowerCase();
        if (!platform.isEmpty()) {
            String pt = "base-" + platform;
            if (controller.templateManager().exists(pt)) {
                chain.add(Map.of("name", pt, "exists", true));
            }
        }
        if (!name.equals("base") && !name.equals("base-" + platform)) {
            chain.add(Map.of("name", name, "exists", true));
        }
        ctx.json(chain);
    }

    @OpenApi(
            path = "/api/v1/templates/{name}/search",
            methods = {HttpMethod.GET},
            operationId = "searchTemplate",
            summary = "Search within template files",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true)},
            queryParams = {
                @OpenApiParam(name = "q", required = true, description = "Search query"),
                @OpenApiParam(name = "maxResults", type = Integer.class, description = "Max matches (clamped to 200)")
            },
            responses = {@OpenApiResponse(status = "200", description = "Matches")})
    private void searchTemplate(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_VIEW);
        String name = ctx.pathParam("name");
        if (!controller.templateManager().exists(name)) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Template not found: " + name, 404));
            return;
        }
        String query = ctx.queryParam("q");
        if (query == null || query.isBlank()) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Missing 'q' query param", 400));
            return;
        }
        int maxResults;
        try {
            maxResults = ctx.queryParam("maxResults") != null
                    ? Math.min(Integer.parseInt(ctx.queryParam("maxResults")), 200)
                    : 50;
        } catch (NumberFormatException _) {
            maxResults = 50;
        }

        Path baseDir = controller.templateManager().getTemplateFilesDir(name);
        var matches = ArchiveSearcher.search(baseDir, query, maxResults).stream()
                .map(ArchiveSearcher.Match::toJson)
                .toList();
        ctx.json(matches);
    }

    @OpenApi(
            path = "/api/v1/templates/{name}/export",
            methods = {HttpMethod.GET},
            operationId = "exportTemplate",
            summary = "Export template as tar.gz",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            pathParams = {@OpenApiParam(name = "name", required = true)},
            responses = {@OpenApiResponse(status = "200", description = "tar.gz stream")})
    private void exportTemplate(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_VIEW);
        String name = ctx.pathParam("name");
        if (!controller.templateManager().exists(name)) {
            ctx.status(404);
            ctx.json(errorResponse("NOT_FOUND", "Template not found: " + name, 404));
            return;
        }
        var result = controller.templateMerger().packageTemplate(name);
        ctx.contentType("application/gzip");
        ctx.header("Content-Disposition", "attachment; filename=\"" + name + ".tar.gz\"");
        ctx.result(result.tarGz());
        audit(ctx, controller.stateStore(), "template.export", "template", name);
    }

    @OpenApi(
            path = "/api/v1/templates/import",
            methods = {HttpMethod.POST},
            operationId = "importTemplate",
            summary = "Import template from uploaded tar.gz",
            description = "Multipart form: name, description, platform fields plus a tar.gz file field.",
            tags = {"Templates"},
            security = {@OpenApiSecurity(name = "bearerAuth")},
            responses = {
                @OpenApiResponse(status = "201", description = "Created"),
                @OpenApiResponse(
                        status = "400",
                        description = "Validation error",
                        content = {@OpenApiContent(from = ErrorResponse.class)}),
                @OpenApiResponse(
                        status = "409",
                        description = "Template already exists",
                        content = {@OpenApiContent(from = ErrorResponse.class)})
            })
    private void importTemplate(Context ctx) throws Exception {
        JwtAuthMiddleware.requirePermission(ctx, Permission.TEMPLATES_CREATE);
        String name = ctx.formParam("name");
        String description = ctx.formParam("description");
        String platform = ctx.formParam("platform");

        if (name == null || name.isBlank()) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Missing 'name' form field", 400));
            return;
        }
        if (platform == null || platform.isBlank()) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "Missing 'platform' form field", 400));
            return;
        }
        if (!name.matches("[a-z0-9_][a-z0-9_-]*") || name.length() > 32) {
            ctx.status(400);
            ctx.json(errorResponse(
                    "BAD_REQUEST",
                    "Invalid template name: " + name + " (must match [a-z0-9_][a-z0-9_-]*, max 32 chars)",
                    400));
            return;
        }
        if (controller.templateManager().exists(name)) {
            ctx.status(409);
            ctx.json(errorResponse("CONFLICT", "Template already exists: " + name, 409));
            return;
        }

        var uploadedFiles = ctx.uploadedFiles("file");
        if (uploadedFiles.isEmpty()) {
            ctx.status(400);
            ctx.json(errorResponse("BAD_REQUEST", "No file uploaded", 400));
            return;
        }

        controller
                .templateManager()
                .save(new TemplateConfig(name, description != null ? description : "", platform, "", 0));

        Path targetDir = controller.templateManager().getTemplateFilesDir(name);
        Files.createDirectories(targetDir);

        var uploaded = uploadedFiles.getFirst();
        try (InputStream is = uploaded.content();
                var gzip = new GzipCompressorInputStream(is);
                var tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName()).normalize();
                if (!entryPath.startsWith(targetDir)) continue;
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(tar, entryPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        controller.templateManager().rehash(name);
        audit(ctx, controller.stateStore(), "template.import", "template", name);
        var created = controller.templateManager().get(name);
        ctx.status(201);
        ctx.json(TemplateDtoMapper.toDto(
                created.orElse(new TemplateConfig(name, description != null ? description : "", platform, "", 0))));
    }

    private static long fileSize(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0;
        } catch (IOException _) {
            return 0;
        }
    }
}
