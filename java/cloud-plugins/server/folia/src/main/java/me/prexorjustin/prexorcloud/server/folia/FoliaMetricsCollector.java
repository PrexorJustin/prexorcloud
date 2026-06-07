package me.prexorjustin.prexorcloud.server.folia;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;

import me.prexorjustin.prexorcloud.server.shared.InstanceMetricsPayload;
import me.prexorjustin.prexorcloud.server.shared.bukkit.BukkitMetricsCollector;

import org.bukkit.Bukkit;

/**
 * Folia-safe metrics collector.
 *
 * <p>
 * Does NOT use {@code world.getEntities()}, {@code world.getLoadedChunks()}, or
 * {@code BukkitRunnable} — those are unsafe on Folia. TPS is sourced from
 * {@code Bukkit.getServer().getTPS()} (available on Paper/Folia ≥ 1.20).
 * World/entity metrics are omitted because they require region-thread context.
 * </p>
 *
 * <p>
 * Extends {@link BukkitMetricsCollector} so that the shared {@code AbstractCloudPlugin}
 * factory signature is satisfied. {@code super.collect()} is never invoked —
 * Folia passes {@code null} for the {@code TickCounter} since
 * {@code BukkitRunnable} is not safe on Folia.
 * </p>
 */
final class FoliaMetricsCollector extends BukkitMetricsCollector {

    private final long startTimeMs = System.currentTimeMillis();

    FoliaMetricsCollector() {
        super(null);
    }

    @Override
    public InstanceMetricsPayload collect() {
        var payload = new InstanceMetricsPayload();

        // --- TPS via Paper/Folia API (safe from async context) ---
        double[] tps = Bukkit.getServer().getTPS();
        payload.tps1m = tps.length > 0 ? tps[0] : 20.0;
        payload.tps5m = tps.length > 1 ? tps[1] : 20.0;
        payload.tps15m = tps.length > 2 ? tps[2] : 20.0;
        payload.msptAvg = Bukkit.getServer().getAverageTickTime();

        // --- JVM (platform-independent, always safe from async) ---
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

        // --- Players (safe from async) ---
        payload.playerCount = Bukkit.getOnlinePlayers().size();
        payload.maxPlayers = Bukkit.getMaxPlayers();

        // NOTE: world.getEntities()/getLoadedChunks() are NOT safe outside region
        // threads on Folia. Skip world/entity metrics to avoid data races.
        payload.worldCount = 0;

        // --- Meta ---
        payload.serverVersion = Bukkit.getVersion();
        payload.pluginCount = Bukkit.getPluginManager().getPlugins().length;
        payload.uptimeMs = System.currentTimeMillis() - startTimeMs;

        return payload;
    }
}
