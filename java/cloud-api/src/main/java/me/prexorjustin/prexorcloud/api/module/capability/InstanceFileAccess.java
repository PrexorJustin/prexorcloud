package me.prexorjustin.prexorcloud.api.module.capability;

import java.util.List;

/**
 * Read-only view over instance working-directory files on remote daemons.
 *
 * <p>Modules that need to inspect files inside running instances (config
 * snapshotting, diagnostics scrapers, audit collectors) consume this
 * capability instead of opening daemon gRPC channels of their own. The
 * controller registers a built-in implementation as a capability handle
 * with id {@link #CAPABILITY_ID}.
 *
 * <h2>Bounds</h2>
 *
 * <ul>
 *   <li>Walks cap at 5 000 entries / 24 directory levels (daemon-side
 *       enforced; see {@code WalkInstanceFiles} in the daemon protocol).
 *   <li>Reads are bounded by the daemon's max-bytes setting (default
 *       64 KiB). Larger files come back with
 *       {@link InstanceFileBytes#truncated()} set to true and
 *       {@link InstanceFileBytes#totalSizeBytes()} reflecting the real
 *       on-disk size. There is no offset/seek — only "first N bytes" or,
 *       when the read passes {@code tail=true}, the last N bytes.
 *   <li>Both calls block up to 20 s per request and never throw —
 *       unreachable daemons, timeouts, and daemon-reported errors all
 *       surface as a populated {@code error} tag on the return value.
 * </ul>
 *
 * <h2>Binary files</h2>
 *
 * <p>The current daemon RPC encodes file content as UTF-8; binary files
 * (Minecraft region files, NBT, world chunks) round-trip lossily and
 * should not be fetched through this capability. A future
 * {@code prexor.instance.snapshot} capability — backed by a daemon-side
 * tar handler — will be the right path for those. Consumers should filter
 * walk results by extension before reading.
 */
public interface InstanceFileAccess {

    String CAPABILITY_ID = "prexor.instance.files";

    /**
     * Walk an instance working directory and return its file tree.
     *
     * @param nodeId     id of the daemon hosting the instance (non-blank)
     * @param group      instance's group name; empty string is acceptable when
     *                   the caller does not have one to hand
     * @param instanceId instance id under the daemon (non-blank)
     */
    InstanceFileTree walk(String nodeId, String group, String instanceId);

    /**
     * Read up to {@code maxBytes} from a single file under the instance
     * directory. Pass {@code maxBytes <= 0} for the daemon default (64 KiB).
     *
     * @param relPath relative path under the instance dir, using forward
     *                slashes (e.g. {@code "config/server.properties"})
     */
    InstanceFileBytes read(String nodeId, String group, String instanceId, String relPath, int maxBytes);

    /** Single entry from {@link #walk}. */
    record InstanceFileEntry(String path, long sizeBytes, boolean isDir, long modifiedAtMs) {}

    /**
     * Result of {@link #walk}. {@code error} is {@code ""} on success,
     * otherwise a tag such as {@code INSTANCE_NOT_FOUND},
     * {@code DAEMON_UNREACHABLE}, or {@code TIMEOUT}.
     */
    record InstanceFileTree(List<InstanceFileEntry> entries, boolean truncated, String error) {

        public boolean ok() {
            return error == null || error.isBlank();
        }
    }

    /**
     * Result of {@link #read}. {@code content} is the daemon's UTF-8
     * encoding of the bytes it read; treat as text. {@code error} is
     * {@code ""} on success.
     */
    record InstanceFileBytes(String content, long totalSizeBytes, boolean truncated, String error) {

        public boolean ok() {
            return error == null || error.isBlank();
        }
    }
}
