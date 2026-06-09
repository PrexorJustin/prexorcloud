package me.prexorjustin.prexorcloud.server.folia;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import me.prexorjustin.prexorcloud.api.plugin.PluginScheduler;
import me.prexorjustin.prexorcloud.server.shared.InstanceMetricsPayload;
import me.prexorjustin.prexorcloud.server.shared.bukkit.AbstractCloudPlugin;
import me.prexorjustin.prexorcloud.server.shared.bukkit.BukkitMetricsCollector;
import me.prexorjustin.prexorcloud.server.shared.bukkit.TickCounter;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * PrexorCloud Folia plugin.
 *
 * <p>
 * Overrides scheduler + metrics collector + scheduling hook so that no
 * {@code BukkitRunnable} runs against the legacy main-thread scheduler — Folia
 * requires {@code Bukkit.getAsyncScheduler()} for plugin-managed tasks.
 * </p>
 */
public final class PrexorCloudFolia extends AbstractCloudPlugin {

    private static final long METRICS_INTERVAL_MS = 10_000L;

    @Override
    protected boolean usesTickCounter() {
        return false;
    }

    @Override
    protected PluginScheduler createScheduler(JavaPlugin plugin) {
        return new FoliaPluginScheduler(plugin);
    }

    @Override
    protected BukkitMetricsCollector createMetricsCollector(TickCounter tickCounter) {
        return new FoliaMetricsCollector();
    }

    @Override
    protected void scheduleMetricsReporting(
            BukkitMetricsCollector collector, Consumer<InstanceMetricsPayload> reporter) {
        Bukkit.getAsyncScheduler()
                .runAtFixedRate(
                        this,
                        t -> reporter.accept(collector.collect()),
                        METRICS_INTERVAL_MS,
                        METRICS_INTERVAL_MS,
                        TimeUnit.MILLISECONDS);
    }
}
