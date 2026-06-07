package me.prexorjustin.prexorcloud.controller.diagnostics;

import java.util.List;

/**
 * Controller-side, JSON-friendly view of a {@link me.prexorjustin.prexorcloud.protocol.InstanceFileTree}
 * reply. Embedded into the diagnostics snapshot under {@code instanceFiles[instanceId]}.
 *
 * @param entries   filetree entries; empty when {@code error} is non-blank
 * @param truncated true when the daemon hit max_entries / max_depth
 * @param error     "" on success; otherwise an error tag like
 *                  {@code INSTANCE_NOT_FOUND}, {@code DIR_UNREADABLE},
 *                  {@code DAEMON_UNREACHABLE}, {@code TIMEOUT}
 */
public record InstanceFileTreeResult(List<Entry> entries, boolean truncated, String error) {

    public record Entry(
            String path, long sizeBytes, boolean isDir, long modifiedAtMs, boolean summary, int childCount) {}

    public static InstanceFileTreeResult unavailable(String error) {
        return new InstanceFileTreeResult(List.of(), false, error);
    }
}
