package me.prexorjustin.prexorcloud.controller.diagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import me.prexorjustin.prexorcloud.common.util.VersionInfo;
import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.health.ControllerReadinessProbe;
import me.prexorjustin.prexorcloud.controller.observability.ControllerConfigRedactor;
import me.prexorjustin.prexorcloud.controller.observability.ControllerLogBuffer;
import me.prexorjustin.prexorcloud.controller.recovery.BackupScope;
import me.prexorjustin.prexorcloud.controller.redis.DistributedLeaseManager;
import me.prexorjustin.prexorcloud.controller.redis.RedisKeyspaceInspector;
import me.prexorjustin.prexorcloud.controller.rest.dto.SystemDtoMapper;
import me.prexorjustin.prexorcloud.controller.runtime.RuntimeServices;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles the controller diagnostics bundle. Replaces the inline
 * {@code Map.of(...)} body in {@code SystemRoutes#getDiagnostics} — that
 * literal had hit Map.of's 10-pair ceiling, blocking the expanded sections
 * (nodes, instances, groups, templates, instanceFiles).
 *
 * <p>
 * Used by both the {@code GET /api/v1/system/diagnostics} JSON endpoint and
 * the {@code POST /api/v1/system/diagnostics/share} paste-share endpoint via
 * {@link DiagnosticsSnapshot#toTextDocument()}.
 * </p>
 */
public final class DiagnosticsCollector {

    private static final Logger logger = LoggerFactory.getLogger(DiagnosticsCollector.class);

    /** Bound on the templates filetree walk — keeps the snapshot small even with large templates. */
    static final int TEMPLATE_WALK_MAX_ENTRIES = 1000;

    static final int TEMPLATE_WALK_MAX_DEPTH = 8;

    private final PrexorController controller;
    private final RuntimeServices runtime;
    private final ControllerReadinessProbe readinessProbe;

    public DiagnosticsCollector(
            PrexorController controller, RuntimeServices runtime, ControllerReadinessProbe readinessProbe) {
        this.controller = controller;
        this.runtime = runtime;
        this.readinessProbe = readinessProbe;
    }

    public DiagnosticsSnapshot collect() {
        VersionInfo v = VersionInfo.get();
        var snapshot = readinessProbe.snapshot();
        Map<String, Object> readiness = Map.of("ready", snapshot.ready(), "details", readinessProbe.readinessBody());
        Map<String, Object> overview = Map.of(
                "nodes", controller.clusterState().nodeCount(),
                "instances", controller.clusterState().instanceCount(),
                "players", controller.clusterState().playerCount(),
                "groups", controller.groupManager().getAll().size());
        Map<String, Object> settings = SystemDtoMapper.settingsDto(
                controller.clusterState().nodeCount(),
                controller.clusterState().instanceCount(),
                controller.clusterState().playerCount(),
                controller.config().scheduler().evaluationIntervalSeconds(),
                controller.config().heartbeat().intervalMs(),
                controller.config().metrics().enabled(),
                controller.config().share().enabled());

        Object redisKeyspace = runtime.coordinationEnabled()
                ? new RedisKeyspaceInspector(runtime.redisCommands()).inspect(BackupScope.defaultRedisKeyPrefixes())
                : Map.of("enabled", false);
        List<DistributedLeaseManager.LeaseSnapshot> leases = runtime.coordinationEnabled()
                ? DistributedLeaseManager.scanAllLeases(runtime.redisCommands())
                : List.of();

        ControllerLogBuffer logs = controller.logBuffer();
        Map<String, Object> logBufferStats = logs == null
                ? Map.of("size", 0, "capacity", 0)
                : Map.of("size", logs.size(), "capacity", logs.capacity());

        return DiagnosticsSnapshot.builder()
                .put("generatedAtMs", System.currentTimeMillis())
                .put("controllerId", controller.config().uuid())
                .put("version", SystemDtoMapper.versionDto(v))
                .put("readiness", readiness)
                .put("overview", overview)
                .put("settings", settings)
                .put("redactedConfig", ControllerConfigRedactor.redact(controller.config()))
                .put("redisKeyspace", redisKeyspace)
                .put("leases", leases)
                .put("logBuffer", logBufferStats)
                .put("nodes", controller.clusterState().getAllNodes())
                .put("instances", controller.clusterState().getAllInstances())
                .put("groups", collectGroups())
                .put("templates", collectTemplates())
                .put("instanceFiles", collectInstanceFiles())
                .build();
    }

    private List<Map<String, Object>> collectGroups() {
        var groups = new ArrayList<Map<String, Object>>();
        for (var group : controller.groupManager().getAll()) {
            try {
                var resolved = controller.groupManager().resolveGroup(group.name());
                groups.add(Map.of("name", group.name(), "resolved", resolved));
            } catch (Exception e) {
                groups.add(Map.of("name", group.name(), "error", String.valueOf(e.getMessage())));
            }
        }
        return groups;
    }

    private List<Map<String, Object>> collectTemplates() {
        var templates = new ArrayList<Map<String, Object>>();
        for (var template : controller.templateManager().getAll()) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("config", template);
            try {
                Path dir = controller.templateManager().getTemplateFilesDir(template.name());
                entry.put("filetree", walkLocal(dir, TEMPLATE_WALK_MAX_ENTRIES, TEMPLATE_WALK_MAX_DEPTH));
            } catch (Exception e) {
                entry.put("filetreeError", String.valueOf(e.getMessage()));
            }
            templates.add(entry);
        }
        return templates;
    }

    private Map<String, Object> collectInstanceFiles() {
        var service = controller.instanceFileTreeService();
        if (service == null) return Map.of("unavailable", "InstanceFileTreeService not initialised");
        var byInstance = new LinkedHashMap<String, Object>();
        Collection<InstanceInfo> instances = controller.clusterState().getAllInstances();
        for (InstanceInfo instance : instances) {
            try {
                // Tighter caps for diagnostics: the snapshot embeds one filetree per instance,
                // so on a large cluster the default 5000/24 would produce a multi-MB bundle.
                // Match the template-walk caps for a consistent diagnostics ceiling.
                InstanceFileTreeResult result = service.walkInstanceFiles(
                        instance.nodeId(),
                        instance.group(),
                        instance.id(),
                        TEMPLATE_WALK_MAX_ENTRIES,
                        TEMPLATE_WALK_MAX_DEPTH);
                byInstance.put(instance.id(), result);
            } catch (Exception e) {
                logger.debug("instanceFiles walk failed for {}: {}", instance.id(), e.getMessage());
                byInstance.put(instance.id(), InstanceFileTreeResult.unavailable("ERROR"));
            }
        }
        return byInstance;
    }

    /**
     * Bounded local walk of a controller-side directory. Used for the templates
     * filetree section — kept simple (no summarisation) because templates are
     * orders of magnitude smaller than instance working directories.
     */
    static List<Map<String, Object>> walkLocal(Path dir, int maxEntries, int maxDepth) {
        if (dir == null || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return List.of();
        var out = new ArrayList<Map<String, Object>>();
        boolean[] truncated = {false};
        walkLocalRec(dir, dir, 0, maxDepth, maxEntries, out, truncated);
        if (truncated[0]) {
            out.add(Map.of("path", "[truncated]", "truncated", true));
        }
        return out;
    }

    private static void walkLocalRec(
            Path root,
            Path dir,
            int depth,
            int maxDepth,
            int maxEntries,
            List<Map<String, Object>> out,
            boolean[] truncated) {
        if (out.size() >= maxEntries) {
            truncated[0] = true;
            return;
        }
        List<Path> children;
        try (Stream<Path> stream = Files.list(dir)) {
            children = stream.sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return;
        }
        for (Path child : children) {
            if (out.size() >= maxEntries) {
                truncated[0] = true;
                return;
            }
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                continue;
            }
            boolean isDir = attrs.isDirectory() && !attrs.isSymbolicLink();
            String rel = root.relativize(child).toString().replace('\\', '/');
            var entry = new LinkedHashMap<String, Object>();
            entry.put("path", rel);
            entry.put("sizeBytes", attrs.isRegularFile() ? attrs.size() : 0L);
            entry.put("isDir", isDir);
            entry.put("modifiedAtMs", attrs.lastModifiedTime().toMillis());
            out.add(entry);
            if (isDir && depth + 1 <= maxDepth) {
                walkLocalRec(root, child, depth + 1, maxDepth, maxEntries, out, truncated);
            }
        }
    }
}
