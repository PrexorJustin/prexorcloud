package me.prexorjustin.prexorcloud.server.neoforge;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;

import me.prexorjustin.prexorcloud.server.shared.InstanceMetricsPayload;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

/**
 * Collects an {@link InstanceMetricsPayload} from a NeoForge dedicated server. The JVM section is
 * identical to the Bukkit/Fabric collectors (pure JMX); the rest is read from {@link MinecraftServer}
 * via Mojang's official mappings.
 *
 * <p>Per-world entity counts are not reported (no cheap aggregate); chunk and player counts are.
 */
final class NeoForgeMetricsCollector {

    private final MinecraftServer server;
    private final long startTimeMs = System.currentTimeMillis();

    NeoForgeMetricsCollector(MinecraftServer server) {
        this.server = server;
    }

    InstanceMetricsPayload collect() {
        var payload = new InstanceMetricsPayload();

        // --- Performance ---
        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
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
        payload.playerCount = server.getPlayerCount();
        payload.maxPlayers = server.getMaxPlayers();

        // --- Worlds ---
        var worldSnapshots = new ArrayList<InstanceMetricsPayload.WorldSnapshot>();
        int worldCount = 0;
        long totalChunks = 0;
        for (ServerLevel world : server.getAllLevels()) {
            worldCount++;
            int chunks = world.getChunkSource().getLoadedChunksCount();
            int players = world.players().size();
            totalChunks += chunks;

            var snapshot = new InstanceMetricsPayload.WorldSnapshot();
            snapshot.name = world.dimension().location().toString();
            snapshot.environment = world.dimension().location().getPath();
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
        payload.serverVersion = "NeoForge " + server.getServerVersion();
        payload.pluginCount = ModList.get().size();
        payload.uptimeMs = System.currentTimeMillis() - startTimeMs;

        return payload;
    }
}
