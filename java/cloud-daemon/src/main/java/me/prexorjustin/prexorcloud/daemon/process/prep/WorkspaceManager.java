package me.prexorjustin.prexorcloud.daemon.process.prep;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Staging-area lifecycle for instance directories. Owns the
 * {@code .staging/} and {@code .quarantine/} layout under the daemon's
 * instances root.
 *
 * <p>Two responsibilities:
 *
 * <ul>
 *   <li>Per-start preparation workspaces — fresh ephemeral working dirs,
 *       optional backup of an existing static instance, atomic promotion
 *       into the live dir, restore-on-failure.
 *   <li>Filesystem helpers — directory move/copy/delete/quarantine that
 *       work around platform quirks (atomic-move unsupported, partially
 *       locked files, etc.).
 * </ul>
 *
 * <p>Sibling to {@link ArtifactProvisioner} and {@link TemplatePreparation}.
 * Pure I/O — no preparation-stage tagging; callers translate failures into
 * {@link StartPreparationException} themselves where needed.
 */
public final class WorkspaceManager {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceManager.class);

    private final Path instancesDir;

    public WorkspaceManager(Path instancesDir) {
        this.instancesDir = instancesDir;
    }

    /**
     * Working dir + optional pre-promotion backup of an existing static
     * instance's content. {@code backupDir} is {@code null} for non-static
     * (ephemeral) instances.
     */
    public record PreparationWorkspace(Path workingDir, Path backupDir) {}

    // ──────────────────────────────────────────────────────────────────
    // Per-start preparation lifecycle
    // ──────────────────────────────────────────────────────────────────

    /**
     * Create a unique ephemeral working dir under
     * {@code .staging/<group>/<instanceId>-<nanoTime>}.
     */
    public Path createPreparationDirectory(String group, String instanceId) throws IOException {
        Path stagingDir = instancesDir.resolve(".staging").resolve(group).resolve(instanceId + "-" + System.nanoTime());
        Files.createDirectories(stagingDir);
        return stagingDir;
    }

    /**
     * Stage a fresh working dir for the start, optionally backing up the
     * existing static-instance content so a failed preparation can roll
     * back. Ephemeral (non-static) instances skip the backup step.
     */
    public PreparationWorkspace prepareWorkspace(
            boolean staticInstance, String group, String instanceId, Path instanceDir) throws IOException {
        Path workingDir = createPreparationDirectory(group, instanceId);
        if (!staticInstance || !Files.isDirectory(instanceDir)) {
            return new PreparationWorkspace(workingDir, null);
        }

        Path backupDir = createBackupDirectory(group, instanceId);
        stageStaticWorkspace(instanceDir, workingDir, backupDir);
        logger.debug("Static instance {} staged with backup {}", instanceId, backupDir);
        return new PreparationWorkspace(workingDir, backupDir);
    }

    /**
     * Sibling of {@link #createPreparationDirectory(String, String)} that
     * names the dir as the backup target (parent only — the move into it
     * is performed by {@link #stageStaticWorkspace(Path, Path, Path)}).
     */
    public Path createBackupDirectory(String group, String instanceId) throws IOException {
        Path backupDir =
                instancesDir.resolve(".staging").resolve(group).resolve(instanceId + "-backup-" + System.nanoTime());
        Files.createDirectories(backupDir.getParent());
        return backupDir;
    }

    /**
     * Delete a staging dir best-effort; quarantine it if the tree can't
     * be fully deleted (e.g. files held open by a child process). Logs +
     * swallows on any IO error — caller is already on a failure path.
     */
    public void cleanupPreparationDirectory(Path path, String group, String instanceId) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            if (!deleteDirectoryTree(path)) {
                quarantineDirectory(path, group + "_" + instanceId + "_failed-start");
            }
        } catch (IOException e) {
            logger.warn("Failed to clean preparation directory {}: {}", path, e.getMessage());
        }
    }

    /**
     * Restore a static instance's content from its pre-preparation backup
     * after a failed start. On restore failure the backup itself is
     * quarantined so it isn't left dangling under {@code .staging/}.
     */
    public void restoreBackupDirectory(Path backupDir, Path instanceDir, String group, String instanceId) {
        if (backupDir == null || !Files.exists(backupDir)) {
            return;
        }
        try {
            replaceDirectory(backupDir, instanceDir);
            logger.info("Restored static instance {} from backup after failed preparation", instanceId);
        } catch (IOException e) {
            logger.error(
                    "Failed to restore backup for static instance {}/{}: {}", group, instanceId, e.getMessage(), e);
            cleanupPreparationDirectory(backupDir, group, instanceId + "_restore");
        }
    }

    /**
     * Move {@code source} into {@code .quarantine/<prefix>_<epochMillis>}.
     * Returns the new path so the caller can surface it in a log line.
     */
    public Path quarantineDirectory(Path source, String prefix) throws IOException {
        Path quarantineDir = instancesDir.resolve(".quarantine");
        Path target = quarantineDir.resolve(prefix + "_" + System.currentTimeMillis());
        Files.createDirectories(quarantineDir);
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    // ──────────────────────────────────────────────────────────────────
    // Stateless filesystem helpers (no instancesDir dependency)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Atomic-by-rename swap of {@code sourceDir} → {@code targetDir} with
     * a sibling-backup rollback on failure. Used to promote a prepared
     * staging dir into the live instance dir.
     */
    public static void replaceDirectory(Path sourceDir, Path targetDir) throws IOException {
        Files.createDirectories(targetDir.getParent());
        Path backupDir = null;
        if (Files.exists(targetDir)) {
            backupDir = targetDir.resolveSibling(targetDir.getFileName() + ".backup-" + System.nanoTime());
            moveDirectory(targetDir, backupDir);
        }
        try {
            moveDirectory(sourceDir, targetDir);
        } catch (IOException moveError) {
            if (backupDir != null && Files.exists(backupDir) && !Files.exists(targetDir)) {
                try {
                    moveDirectory(backupDir, targetDir);
                } catch (IOException restoreError) {
                    moveError.addSuppressed(restoreError);
                }
            }
            throw moveError;
        }

        if (backupDir != null) {
            deleteDirectoryTree(backupDir);
        }
    }

    /**
     * Walk + delete in reverse order so directories empty before they're
     * removed. Returns {@code false} when at least one file resisted
     * deletion (open handle, EACCES, etc.) so the caller can quarantine.
     */
    public static boolean deleteDirectoryTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return true;
        }
        boolean allDeleted = true;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException _) {
                    allDeleted = false;
                }
            }
        }
        return allDeleted;
    }

    /**
     * Stage a static instance: move the live dir aside into {@code backupDir},
     * then clone its content into the fresh {@code workingDir}. On copy
     * failure the partial staging dir is removed and the live dir is
     * restored from the backup.
     */
    public static void stageStaticWorkspace(Path instanceDir, Path workingDir, Path backupDir) throws IOException {
        moveDirectory(instanceDir, backupDir);
        try {
            copyDirectoryTree(backupDir, workingDir);
        } catch (IOException copyError) {
            IOException failure = copyError;
            try {
                deleteDirectoryTree(workingDir);
            } catch (IOException cleanupError) {
                failure.addSuppressed(cleanupError);
            }
            if (!Files.exists(instanceDir) && Files.exists(backupDir)) {
                try {
                    moveDirectory(backupDir, instanceDir);
                } catch (IOException restoreError) {
                    failure.addSuppressed(restoreError);
                }
            }
            throw failure;
        }
    }

    /** Recursive copy with attribute preservation; idempotent on existing dirs. */
    public static void copyDirectoryTree(Path sourceDir, Path targetDir) throws IOException {
        if (!Files.exists(sourceDir)) {
            return;
        }
        Files.createDirectories(targetDir);
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            for (Path source : walk.toList()) {
                Path relative = sourceDir.relativize(source);
                Path target = relative.toString().isEmpty() ? targetDir : targetDir.resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void moveDirectory(Path sourceDir, Path targetDir) throws IOException {
        try {
            Files.move(sourceDir, targetDir, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException _) {
            Files.move(sourceDir, targetDir, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
