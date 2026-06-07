package me.prexorjustin.prexorcloud.server.shared.bukkit;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;

import me.prexorjustin.prexorcloud.server.shared.InstanceMetricsPayload;

import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * Collects instance metrics using the standard Bukkit API and JMX. Subclassed
 * by {@code PaperMetricsCollector} to replace TPS/MSPT with native Paper APIs,
 * and by {@code FoliaMetricsCollector} to skip region-thread-only data.
 *
 * <p>
 * The {@code tickCounter} parameter is optional — Folia passes {@code null}
 * because {@code BukkitRunnable}-based tick counting is not safe there. Subclasses
 * that pass {@code null} must override {@link #collect()} so the fallback TPS
 * branch is never executed.
 * </p>
 */
public class BukkitMetricsCollector {

    private final TickCounter tickCounter;
    private final long startTimeMs = System.currentTimeMillis();

    public BukkitMetricsCollector(TickCounter tickCounter) {
        this.tickCounter = tickCounter;
    }

    public InstanceMetricsPayload collect() {
        var payload = new InstanceMetricsPayload();

        // --- Performance (fallback via TickCounter) ---
        payload.tps1m = tickCounter.tps1m();
        payload.tps5m = tickCounter.tps5m();
        payload.tps15m = tickCounter.tps15m();
        payload.msptAvg = 0.0; // not available on plain Spigot

        // --- JVM ---
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        var heap = memBean.getHeapMemoryUsage();
        payload.heapUsedMb = heap.getUsed() / (1024 * 1024);
        payload.heapMaxMb = heap.getMax() / (1024 * 1024);
        payload.heapCommittedMb = heap.getCommitted() / (1024 * 1024);

        long gcCollections = 0;
        long gcTimeMs = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gc.getCollectionCount();
            long time = gc.getCollectionTime();
            if (count > 0) gcCollections += count;
            if (time > 0) gcTimeMs += time;
        }
        payload.gcCollections = gcCollections;
        payload.gcTimeMs = gcTimeMs;

        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        payload.threadCount = threadBean.getThreadCount();
        payload.daemonThreadCount = threadBean.getDaemonThreadCount();

        // --- Players ---
        payload.playerCount = Bukkit.getOnlinePlayers().size();
        payload.maxPlayers = Bukkit.getMaxPlayers();

        // --- Worlds ---
        List<World> worlds = Bukkit.getWorlds();
        payload.worldCount = worlds.size();

        long totalEntities = 0;
        long totalChunks = 0;
        List<InstanceMetricsPayload.WorldSnapshot> worldSnapshots = new ArrayList<>(worlds.size());

        for (World world : worlds) {
            int entities = world.getEntities().size();
            int chunks = world.getLoadedChunks().length;
            int players = world.getPlayers().size();

            totalEntities += entities;
            totalChunks += chunks;

            var snapshot = new InstanceMetricsPayload.WorldSnapshot();
            snapshot.name = world.getName();
            snapshot.environment = world.getEnvironment().name();
            snapshot.entityCount = entities;
            snapshot.chunkCount = chunks;
            snapshot.playerCount = players;
            worldSnapshots.add(snapshot);
        }

        payload.totalEntities = totalEntities;
        payload.totalChunks = totalChunks;
        payload.worlds = worldSnapshots;

        // --- Meta ---
        payload.serverVersion = Bukkit.getVersion();
        payload.pluginCount = Bukkit.getPluginManager().getPlugins().length;
        payload.uptimeMs = System.currentTimeMillis() - startTimeMs;

        return payload;
    }

    protected long startTimeMs() {
        return startTimeMs;
    }
}
