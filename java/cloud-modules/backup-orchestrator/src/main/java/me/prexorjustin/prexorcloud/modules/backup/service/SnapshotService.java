package me.prexorjustin.prexorcloud.modules.backup.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import me.prexorjustin.prexorcloud.api.module.capability.InstanceFileAccess;
import me.prexorjustin.prexorcloud.modules.backup.data.SnapshotMetadata;
import me.prexorjustin.prexorcloud.modules.backup.data.SnapshotRepository;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;

/**
 * Produces config-file snapshots of remote instances by walking the daemon's
 * instance directory through the {@link InstanceFileAccess} capability and
 * packing matching files into a controller-local tar.gz.
 *
 * <h2>Scope and known limit</h2>
 *
 * <p>The daemon's {@code ReadInstanceFile} RPC encodes file content as UTF-8
 * and caps each read at the daemon's {@code maxBytes} setting (default 64
 * KiB). That makes this service usable for <strong>config and small state
 * files</strong> — {@code server.properties}, {@code ops.json}, plugin
 * YAML, whitelist/banlist JSON — but not for binary world data
 * (region files, NBT). World snapshots need a daemon-side tar handler
 * (tracked as a follow-up; see {@code daemon_service.proto} for the
 * eventual {@code SnapshotInstance} message). Files larger than the cap
 * are captured up to the cap and their full path recorded in
 * {@link SnapshotMetadata#truncatedFiles()} so operators can spot them.
 */
public final class SnapshotService {

    /** Default file patterns the snapshot picks up. Conservative on purpose. */
    public static final List<String> DEFAULT_PATTERNS =
            List.of("*.properties", "*.json", "*.yml", "*.yaml", "*.txt", "*.cfg", "*.toml");

    /** Per-file read cap; bumped past the daemon default to capture larger configs. */
    private static final int READ_MAX_BYTES = 256 * 1024;

    private final InstanceFileAccess fileAccess;
    private final SnapshotRepository repository;
    private final Path archiveRoot;
    private final Logger logger;

    public SnapshotService(
            InstanceFileAccess fileAccess, SnapshotRepository repository, Path archiveRoot, Logger logger) {
        this.fileAccess = fileAccess;
        this.repository = repository;
        this.archiveRoot = archiveRoot;
        this.logger = logger;
    }

    /**
     * Snapshot a single instance and persist the metadata. Returns the saved
     * record so callers can surface it (REST replies, scheduled-task logs).
     */
    public SnapshotMetadata snapshotInstance(String nodeId, String group, String instanceId, List<String> patterns) {
        List<String> usePatterns = patterns == null || patterns.isEmpty() ? DEFAULT_PATTERNS : patterns;
        String snapshotId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        InstanceFileAccess.InstanceFileTree tree = fileAccess.walk(nodeId, group, instanceId);
        if (!tree.ok()) {
            return errorMetadata(snapshotId, instanceId, group, nodeId, now, usePatterns, tree.error());
        }

        List<InstanceFileAccess.InstanceFileEntry> matching = filterByPatterns(tree.entries(), usePatterns);
        Path archivePath = archivePath(instanceId, snapshotId, now);
        try {
            Files.createDirectories(archivePath.getParent());
        } catch (IOException io) {
            return errorMetadata(
                    snapshotId, instanceId, group, nodeId, now, usePatterns, "ARCHIVE_DIR: " + io.getMessage());
        }

        List<String> truncated = new ArrayList<>();
        int written = 0;
        try (OutputStream raw = Files.newOutputStream(archivePath);
                GZIPOutputStream gz = new GZIPOutputStream(raw);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(gz)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (InstanceFileAccess.InstanceFileEntry entry : matching) {
                InstanceFileAccess.InstanceFileBytes bytes =
                        fileAccess.read(nodeId, group, instanceId, entry.path(), READ_MAX_BYTES);
                if (!bytes.ok()) {
                    logger.warn("snapshot {}: skip {} ({}): {}", snapshotId, entry.path(), instanceId, bytes.error());
                    continue;
                }
                byte[] data = bytes.content().getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry tarEntry = new TarArchiveEntry(entry.path());
                tarEntry.setSize(data.length);
                tarEntry.setModTime(java.util.Date.from(Instant.ofEpochMilli(entry.modifiedAtMs())));
                tar.putArchiveEntry(tarEntry);
                tar.write(data);
                tar.closeArchiveEntry();
                if (bytes.truncated()) {
                    truncated.add(entry.path());
                }
                written++;
            }
            tar.finish();
        } catch (IOException io) {
            return errorMetadata(
                    snapshotId, instanceId, group, nodeId, now, usePatterns, "TAR_WRITE: " + io.getMessage());
        }

        long archiveSize;
        try {
            archiveSize = Files.size(archivePath);
        } catch (IOException io) {
            archiveSize = 0L;
        }

        SnapshotMetadata metadata = new SnapshotMetadata(
                snapshotId,
                instanceId,
                group == null ? "" : group,
                nodeId,
                now,
                archiveSize,
                archivePath.toString(),
                written,
                List.copyOf(truncated),
                usePatterns,
                "");
        repository.save(metadata);
        logger.info(
                "snapshot {}: instance={} files={} truncated={} size={}B path={}",
                snapshotId,
                instanceId,
                written,
                truncated.size(),
                archiveSize,
                archivePath);
        return metadata;
    }

    /**
     * Delete an archive from disk and its metadata record. Returns whether the
     * metadata row existed; the archive file is removed best-effort.
     */
    public boolean deleteSnapshot(String snapshotId) {
        var record = repository.findById(snapshotId).orElse(null);
        if (record == null) return false;
        try {
            Files.deleteIfExists(Path.of(record.archivePath()));
        } catch (IOException io) {
            logger.warn("snapshot {}: archive unlink failed: {}", snapshotId, io.getMessage());
        }
        return repository.deleteById(snapshotId);
    }

    Path archivePath(String instanceId, String snapshotId, Instant when) {
        String safeInstance = instanceId.replaceAll("[^A-Za-z0-9_.-]", "_");
        String name = when.toEpochMilli() + "-" + snapshotId.substring(0, 8) + ".tar.gz";
        return archiveRoot.resolve(safeInstance).resolve(name);
    }

    static List<InstanceFileAccess.InstanceFileEntry> filterByPatterns(
            List<InstanceFileAccess.InstanceFileEntry> entries, List<String> patterns) {
        List<InstanceFileAccess.InstanceFileEntry> out = new ArrayList<>();
        for (var e : entries) {
            if (e.isDir()) continue;
            String base = baseName(e.path());
            for (String pattern : patterns) {
                if (globMatch(pattern, base)) {
                    out.add(e);
                    break;
                }
            }
        }
        return out;
    }

    private static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * Tiny glob matcher: supports {@code *} (any chars) and literal characters.
     * Avoids a full regex compile for the common config-file extension case.
     */
    static boolean globMatch(String pattern, String name) {
        int pi = 0, ni = 0, star = -1, match = 0;
        while (ni < name.length()) {
            if (pi < pattern.length() && (pattern.charAt(pi) == name.charAt(ni))) {
                pi++;
                ni++;
            } else if (pi < pattern.length() && pattern.charAt(pi) == '*') {
                star = pi++;
                match = ni;
            } else if (star != -1) {
                pi = star + 1;
                ni = ++match;
            } else {
                return false;
            }
        }
        while (pi < pattern.length() && pattern.charAt(pi) == '*') pi++;
        return pi == pattern.length();
    }

    private SnapshotMetadata errorMetadata(
            String id,
            String instanceId,
            String group,
            String nodeId,
            Instant when,
            List<String> patterns,
            String error) {
        SnapshotMetadata md = new SnapshotMetadata(
                id, instanceId, group == null ? "" : group, nodeId, when, 0L, "", 0, List.of(), patterns, error);
        repository.save(md);
        logger.warn("snapshot {}: instance={} error={}", id, instanceId, error);
        return md;
    }
}
