package me.prexorjustin.prexorcloud.modules.backup.data;

import java.time.Instant;
import java.util.List;

/**
 * Persisted record describing a single backup snapshot of an instance's
 * config files.
 *
 * <p>The {@code archivePath} is controller-local; consumers off-host (S3,
 * Restic, rclone) read from there. {@code truncatedFiles} is the subset of
 * files whose on-disk size exceeded the daemon's per-file read cap and were
 * therefore captured only partially — see {@code SnapshotService} for the
 * cap and the reasoning.
 */
public record SnapshotMetadata(
        String id,
        String instanceId,
        String group,
        String nodeId,
        Instant createdAt,
        long archiveSizeBytes,
        String archivePath,
        int fileCount,
        List<String> truncatedFiles,
        List<String> patterns,
        String error) {

    public boolean ok() {
        return error == null || error.isBlank();
    }
}
