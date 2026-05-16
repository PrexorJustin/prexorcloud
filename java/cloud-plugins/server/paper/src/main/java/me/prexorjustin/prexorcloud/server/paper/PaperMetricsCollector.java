package me.prexorjustin.prexorcloud.server.paper;

import me.prexorjustin.prexorcloud.server.shared.InstanceMetricsPayload;
import me.prexorjustin.prexorcloud.server.shared.bukkit.BukkitMetricsCollector;
import me.prexorjustin.prexorcloud.server.shared.bukkit.TickCounter;

import org.bukkit.Bukkit;

/**
 * Extends BukkitMetricsCollector with Paper-native TPS and MSPT APIs, replacing
 * the TickCounter-based fallback.
 *
 * <p>
 * {@link org.bukkit.Server#getTPS()} returns a {@code double[3]} with 1-minute,
 * 5-minute, and 15-minute averages, capped at 20.0 by Paper.
 * {@link org.bukkit.Server#getAverageTickTime()} returns the average
 * milliseconds per tick over the last 100 ticks.
 * </p>
 */
public final class PaperMetricsCollector extends BukkitMetricsCollector {

    public PaperMetricsCollector(TickCounter tickCounter) {
        super(tickCounter);
    }

    @Override
    public InstanceMetricsPayload collect() {
        InstanceMetricsPayload payload = super.collect();

        // Replace fallback TPS with Paper's built-in measurements
        double[] tps = Bukkit.getServer().getTPS();
        payload.tps1m = tps[0];
        payload.tps5m = tps[1];
        payload.tps15m = tps[2];
        payload.msptAvg = Bukkit.getServer().getAverageTickTime();

        return payload;
    }
}
