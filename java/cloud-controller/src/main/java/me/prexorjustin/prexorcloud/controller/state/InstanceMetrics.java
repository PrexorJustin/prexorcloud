package me.prexorjustin.prexorcloud.controller.state;

import java.time.Instant;
import java.util.List;

/**
 * Latest metrics snapshot reported by a game server plugin via POST
 * /api/plugin/metrics. Immutable -- replaced on every report.
 */
public record InstanceMetrics(
        String instanceId,

        // Performance
        double tps1m,
        double tps5m,
        double tps15m,
        double msptAvg,

        // JVM
        long heapUsedMb,
        long heapMaxMb,
        long heapCommittedMb,
        long gcCollections,
        long gcTimeMs,
        int threadCount,
        int daemonThreadCount,

        // Players
        int playerCount,
        int maxPlayers,

        // World aggregates
        int worldCount,
        long totalEntities,
        long totalChunks,
        List<WorldSnapshot> worlds,

        // Meta
        String serverVersion,
        int pluginCount,
        long uptimeMs,

        Instant collectedAt) {

    public record WorldSnapshot(String name, String environment, int entityCount, int chunkCount, int playerCount) {}
}
