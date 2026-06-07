package me.prexorjustin.prexorcloud.server.paper;

import me.prexorjustin.prexorcloud.server.shared.bukkit.AbstractCloudPlugin;
import me.prexorjustin.prexorcloud.server.shared.bukkit.BukkitMetricsCollector;
import me.prexorjustin.prexorcloud.server.shared.bukkit.TickCounter;

/**
 * PrexorCloud Paper plugin.
 *
 * <p>
 * Uses {@link PaperMetricsCollector} to leverage Paper's native
 * {@code Server.getTPS()} and {@code Server.getAverageTickTime()} instead of
 * the TickCounter-based fallback.
 * </p>
 */
public final class PrexorCloudPaper extends AbstractCloudPlugin {

    @Override
    protected BukkitMetricsCollector createMetricsCollector(TickCounter tickCounter) {
        return new PaperMetricsCollector(tickCounter);
    }
}
