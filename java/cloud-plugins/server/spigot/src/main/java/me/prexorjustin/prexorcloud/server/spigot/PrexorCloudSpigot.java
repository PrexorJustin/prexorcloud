package me.prexorjustin.prexorcloud.server.spigot;

import me.prexorjustin.prexorcloud.server.shared.bukkit.AbstractCloudPlugin;
import me.prexorjustin.prexorcloud.server.shared.bukkit.BukkitMetricsCollector;
import me.prexorjustin.prexorcloud.server.shared.bukkit.TickCounter;

/**
 * PrexorCloud Spigot plugin.
 *
 * <p>
 * Uses the TickCounter-based {@link BukkitMetricsCollector} for TPS measurement
 * since Spigot does not expose native TPS/MSPT APIs.
 * </p>
 */
public final class PrexorCloudSpigot extends AbstractCloudPlugin {

    @Override
    protected BukkitMetricsCollector createMetricsCollector(TickCounter tickCounter) {
        return new BukkitMetricsCollector(tickCounter);
    }
}
