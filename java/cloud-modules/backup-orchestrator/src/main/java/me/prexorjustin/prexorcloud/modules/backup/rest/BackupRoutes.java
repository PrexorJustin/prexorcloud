package me.prexorjustin.prexorcloud.modules.backup.rest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.api.module.rest.RouteRegistrar;
import me.prexorjustin.prexorcloud.modules.backup.data.SnapshotMetadata;
import me.prexorjustin.prexorcloud.modules.backup.data.SnapshotRepository;
import me.prexorjustin.prexorcloud.modules.backup.service.SnapshotService;

/**
 * REST surface for backup-orchestrator. Mounted under
 * {@code /api/v1/modules/backup-orchestrator/} by the controller-side
 * module-route dispatcher.
 *
 * <pre>
 *   GET    /snapshots                            list recent snapshots (?instance=&amp;limit=)
 *   POST   /snapshots                            trigger a snapshot {nodeId,instanceId,group?,patterns?}
 *   GET    /snapshots/{id}                       fetch a single snapshot record
 *   DELETE /snapshots/{id}                       delete archive + metadata
 * </pre>
 */
public final class BackupRoutes {

    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 500;

    private final SnapshotService service;
    private final SnapshotRepository repository;

    public BackupRoutes(SnapshotService service, SnapshotRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    public void register(RouteRegistrar registrar) {
        registrar.get("/snapshots", (req, res) -> {
            String instance = req.queryParam("instance").orElse(null);
            int limit = parseLimit(req.queryParam("limit").orElse(null));
            List<SnapshotMetadata> rows = (instance == null || instance.isBlank())
                    ? repository.findAll(limit)
                    : repository.findForInstance(instance, limit);
            res.json(Map.of("snapshots", rows));
        });

        registrar.post("/snapshots", TriggerSnapshotRequest.class, (req, body, res) -> {
            if (body == null || body.instanceId() == null || body.instanceId().isBlank()) {
                res.status(400).json(error("instanceId is required"));
                return;
            }
            if (body.nodeId() == null || body.nodeId().isBlank()) {
                res.status(400).json(error("nodeId is required"));
                return;
            }
            SnapshotMetadata metadata =
                    service.snapshotInstance(body.nodeId(), body.group(), body.instanceId(), body.patterns());
            res.status(metadata.ok() ? 201 : 502).json(metadata);
        });

        registrar.get("/snapshots/{id}", (req, res) -> {
            String id = req.pathParam("id");
            repository
                    .findById(id)
                    .ifPresentOrElse(
                            snapshot -> res.json(snapshot),
                            () -> res.status(404).json(error("snapshot not found: " + id)));
        });

        registrar.delete("/snapshots/{id}", (req, res) -> {
            String id = req.pathParam("id");
            if (service.deleteSnapshot(id)) {
                res.status(204);
            } else {
                res.status(404).json(error("snapshot not found: " + id));
            }
        });
    }

    private static int parseLimit(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_LIST_LIMIT;
        try {
            int n = Integer.parseInt(raw);
            if (n <= 0) return DEFAULT_LIST_LIMIT;
            return Math.min(MAX_LIST_LIMIT, n);
        } catch (NumberFormatException nfe) {
            return DEFAULT_LIST_LIMIT;
        }
    }

    private static Map<String, Object> error(String message) {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("error", message);
        return envelope;
    }

    /** Body shape for {@code POST /snapshots}. */
    public record TriggerSnapshotRequest(String nodeId, String group, String instanceId, List<String> patterns) {}
}
