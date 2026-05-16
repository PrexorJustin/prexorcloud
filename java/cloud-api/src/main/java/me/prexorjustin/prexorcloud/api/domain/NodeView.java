package me.prexorjustin.prexorcloud.api.domain;

import java.time.Instant;
import java.util.Map;

/**
 * Read-only snapshot of a daemon node's state.
 *
 * @param nodeId
 *            unique node identifier
 * @param status
 *            current connectivity status
 * @param cpuUsage
 *            CPU usage fraction (0.0 – 1.0)
 * @param totalMemoryMb
 *            total available memory in MB
 * @param usedMemoryMb
 *            currently used memory in MB
 * @param freeDiskMb
 *            free disk space in MB
 * @param totalDiskMb
 *            total disk capacity in MB
 * @param instanceCount
 *            number of instances currently running on this node
 * @param labels
 *            key-value labels used for node affinity
 * @param connectedSince
 *            when the node last connected to the controller
 */
public record NodeView(
        String nodeId,
        NodeStatus status,
        double cpuUsage,
        long totalMemoryMb,
        long usedMemoryMb,
        long freeDiskMb,
        long totalDiskMb,
        int instanceCount,
        Map<String, String> labels,
        Instant connectedSince) {

    /** Convenience: returns {@code totalMemoryMb - usedMemoryMb}. */
    public long freeMemoryMb() {
        return totalMemoryMb - usedMemoryMb;
    }
}
