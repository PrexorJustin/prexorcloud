package me.prexorjustin.prexorcloud.server.fabric;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;

import me.prexorjustin.prexorcloud.server.shared.InstanceMetricsPayload;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

/**
 * Collects an {@link InstanceMetricsPayload} from a Fabric dedicated server. The JVM section is
 * identical to the Bukkit collector (pure JMX); the rest is read from {@link MinecraftServer}.
 *
 * <p>Per-world entity counts are not reported (Fabric exposes no cheap aggregate); chunk and player
 * counts are.
 */
final class FabricMetricsCollector {

    private final MinecraftServer server;
    private final long startTimeMs = System.currentTimeMillis();

    FabricMetricsCollector(MinecraftServer server) {
        this.server = server;
    }

    InstanceMetricsPayload collect() {
        var payload = new InstanceMetricsPayload();

        // --- Performance ---
        double mspt = server.getAverageTickTime();
        double tps = mspt <= 0.0 ? 20.0 : Math.min(20.0, 1000.0 / mspt);
        payload.tps1m = tps;
        payload.tps5m = tps;
        payload.tps15m = tps;
        payload.msptAvg = mspt;

        // --- JVM (platform-agnostic) ---
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        payload.heapUsedMb = heap.getUsed() / (1024 * 1024);
        payload.heapMaxMb = heap.getMax() / (1024 * 1024);
        payload.heapCommittedMb = heap.getCommitted() / (1024 * 1024);

        long gcCollections = 0;
        long gcTimeMs = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionCount() > 0) gcCollections += gc.getCollectionCount();
            if (gc.getCollectionTime() > 0) gcTimeMs += gc.getCollectionTime();
        }
        payload.gcCollections = gcCollections;
        payload.gcTimeMs = gcTimeMs;

        var threadBean = ManagementFactory.getThreadMXBean();
        payload.threadCount = threadBean.getThreadCount();
        payload.daemonThreadCount = threadBean.getDaemonThreadCount();

        // --- Players ---
        payload.playerCount = server.getCurrentPlayerCount();
        payload.maxPlayers = server.getMaxPlayerCount();

        // --- Worlds ---
        var worldSnapshots = new ArrayList<InstanceMetricsPayload.WorldSnapshot>();
        int worldCount = 0;
        long totalChunks = 0;
        for (ServerWorld world : server.getWorlds()) {
            worldCount++;
            int chunks = world.getChunkManager().getLoadedChunkCount();
            int players = world.getPlayers().size();
            totalChunks += chunks;

            var snapshot = new InstanceMetricsPayload.WorldSnapshot();
            snapshot.name = world.getRegistryKey().getValue().toString();
            snapshot.environment = world.getRegistryKey().getValue().getPath();
            snapshot.entityCount = 0;
            snapshot.chunkCount = chunks;
            snapshot.playerCount = players;
            worldSnapshots.add(snapshot);
        }
        payload.worldCount = worldCount;
        payload.totalEntities = 0;
        payload.totalChunks = totalChunks;
        payload.worlds = worldSnapshots;

        // --- Meta ---
        payload.serverVersion = "Fabric " + server.getVersion();
        payload.pluginCount = FabricLoader.getInstance().getAllMods().size();
        payload.uptimeMs = System.currentTimeMillis() - startTimeMs;

        return payload;
    }
}
