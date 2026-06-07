package me.prexorjustin.prexorcloud.controller.module.capability;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import me.prexorjustin.prexorcloud.api.module.capability.InstanceFileAccess;
import me.prexorjustin.prexorcloud.controller.diagnostics.InstanceFileContentResult;
import me.prexorjustin.prexorcloud.controller.diagnostics.InstanceFileContentService;
import me.prexorjustin.prexorcloud.controller.diagnostics.InstanceFileTreeResult;
import me.prexorjustin.prexorcloud.controller.diagnostics.InstanceFileTreeService;

/**
 * Built-in provider for the {@link InstanceFileAccess} capability. Translates
 * the controller-internal {@link InstanceFileTreeService} /
 * {@link InstanceFileContentService} results into the cloud-api record shapes
 * so modules consume a stable interface without depending on controller
 * internals.
 *
 * <p>Registered as a built-in handle in {@code PrexorCloudBootstrap} after the
 * two underlying services come up and before stored modules are loaded.
 */
public final class ControllerInstanceFileAccess implements InstanceFileAccess {

    private final InstanceFileTreeService treeService;
    private final InstanceFileContentService contentService;

    public ControllerInstanceFileAccess(
            InstanceFileTreeService treeService, InstanceFileContentService contentService) {
        this.treeService = Objects.requireNonNull(treeService, "treeService");
        this.contentService = Objects.requireNonNull(contentService, "contentService");
    }

    @Override
    public InstanceFileTree walk(String nodeId, String group, String instanceId) {
        InstanceFileTreeResult res = treeService.walkInstanceFiles(nodeId, group, instanceId);
        List<InstanceFileEntry> entries = new ArrayList<>(res.entries().size());
        for (InstanceFileTreeResult.Entry e : res.entries()) {
            // Skip "summary" entries — they're not real files, just markers
            // the daemon emits when SUMMARIZE_THRESHOLD trims a directory.
            // Backup callers only want concrete paths they can subsequently read.
            if (e.summary()) continue;
            entries.add(new InstanceFileEntry(e.path(), e.sizeBytes(), e.isDir(), e.modifiedAtMs()));
        }
        return new InstanceFileTree(List.copyOf(entries), res.truncated(), res.error() == null ? "" : res.error());
    }

    @Override
    public InstanceFileBytes read(String nodeId, String group, String instanceId, String relPath, int maxBytes) {
        int effective = maxBytes <= 0 ? InstanceFileContentService.DEFAULT_MAX_BYTES : maxBytes;
        InstanceFileContentResult res = contentService.read(nodeId, group, instanceId, relPath, effective, false);
        return new InstanceFileBytes(
                res.content() == null ? "" : res.content(),
                res.totalSizeBytes(),
                res.truncated(),
                res.error() == null ? "" : res.error());
    }
}
