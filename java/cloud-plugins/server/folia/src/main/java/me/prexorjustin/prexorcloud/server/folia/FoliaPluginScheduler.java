package me.prexorjustin.prexorcloud.server.folia;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import me.prexorjustin.prexorcloud.api.ScheduledTask;
import me.prexorjustin.prexorcloud.api.plugin.PluginScheduler;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Folia-safe {@link PluginScheduler} using Bukkit's {@code AsyncScheduler}.
 * Does NOT use {@code BukkitRunnable} or the legacy Bukkit scheduler — those
 * are not supported in Folia.
 */
public final class FoliaPluginScheduler implements PluginScheduler {

    private final JavaPlugin plugin;

    public FoliaPluginScheduler(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public ScheduledTask runAsync(Runnable task) {
        var ft = Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
        return wrap(ft);
    }

    @Override
    public ScheduledTask runDelayed(Duration delay, Runnable task) {
        long ms = Math.max(0L, delay.toMillis());
        var ft = Bukkit.getAsyncScheduler().runDelayed(plugin, t -> task.run(), ms, TimeUnit.MILLISECONDS);
        return wrap(ft);
    }

    @Override
    public ScheduledTask runAtFixedRate(Duration initialDelay, Duration period, Runnable task) {
        long initMs = Math.max(0L, initialDelay.toMillis());
        long periodMs = Math.max(1L, period.toMillis());
        var ft = Bukkit.getAsyncScheduler()
                .runAtFixedRate(plugin, t -> task.run(), initMs, periodMs, TimeUnit.MILLISECONDS);
        return wrap(ft);
    }

    private static ScheduledTask wrap(io.papermc.paper.threadedregions.scheduler.ScheduledTask ft) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        return new ScheduledTask() {

            @Override
            public void cancel() {
                ft.cancel();
                cancelled.set(true);
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get() || ft.isCancelled();
            }
        };
    }
}
