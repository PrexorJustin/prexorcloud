package me.prexorjustin.prexorcloud.server.shared;

import java.util.List;

/**
 * JSON payload sent to POST /api/plugin/metrics. Matches the fields accepted by
 * PluginRoutes on the controller.
 */
public final class InstanceMetricsPayload {

    public double tps1m;
    public double tps5m;
    public double tps15m;
    public double msptAvg;

    public long heapUsedMb;
    public long heapMaxMb;
    public long heapCommittedMb;
    public long gcCollections;
    public long gcTimeMs;
    public int threadCount;
    public int daemonThreadCount;

    public int playerCount;
    public int maxPlayers;

    public int worldCount;
    public long totalEntities;
    public long totalChunks;
    public List<WorldSnapshot> worlds;

    public String serverVersion;
    public int pluginCount;
    public long uptimeMs;

    public static final class WorldSnapshot {

        public String name;
        public String environment;
        public int entityCount;
        public int chunkCount;
        public int playerCount;
    }
}
