package me.prexorjustin.prexorcloud.api.event.events;

import java.util.List;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired periodically when an instance reports its performance metrics. */
public record InstanceMetricsUpdatedEvent(
        String instanceId,
        String group,
        double tps1m,
        double tps5m,
        double tps15m,
        double msptAvg,
        long heapUsedMb,
        long heapMaxMb,
        long gcCollections,
        long gcTimeMs,
        int threadCount,
        int playerCount,
        int maxPlayers,
        int worldCount,
        long totalEntities,
        long totalChunks,
        List<WorldSnapshot> worlds,
        String serverVersion,
        int pluginCount)
        implements CloudEvent {

    public record WorldSnapshot(String name, String environment, int entityCount, int chunkCount, int playerCount) {}

    @Override
    public String type() {
        return "INSTANCE_METRICS";
    }
}
