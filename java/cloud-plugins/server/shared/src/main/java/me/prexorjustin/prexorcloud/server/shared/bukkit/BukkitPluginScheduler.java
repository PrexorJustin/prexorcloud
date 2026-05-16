package me.prexorjustin.prexorcloud.server.shared.bukkit;

import java.time.Duration;

import me.prexorjustin.prexorcloud.api.ScheduledTask;
import me.prexorjustin.prexorcloud.api.plugin.PluginScheduler;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/**
 * Bukkit-based {@link PluginScheduler}. All tasks run asynchronously — safe for
 * Spigot and Paper (but NOT Folia; the Folia module provides its own
 * implementation).
 */
public final class BukkitPluginScheduler implements PluginScheduler {

    private static final long TICKS_PER_SECOND = 20L;

    private final JavaPlugin plugin;

    public BukkitPluginScheduler(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public ScheduledTask runAsync(Runnable task) {
        BukkitTask bt = plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        return wrap(bt);
    }

    @Override
    public ScheduledTask runDelayed(Duration delay, Runnable task) {
        long ticks = toTicks(delay);
        BukkitTask bt = plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, task, ticks);
        return wrap(bt);
    }

    @Override
    public ScheduledTask runAtFixedRate(Duration initialDelay, Duration period, Runnable task) {
        long initTicks = toTicks(initialDelay);
        long periodTicks = Math.max(1L, toTicks(period));
        BukkitTask bt = new BukkitRunnable() {

            @Override
            public void run() {
                task.run();
            }
        }.runTaskTimerAsynchronously(plugin, initTicks, periodTicks);
        return wrap(bt);
    }

    private static long toTicks(Duration d) {
        return Math.max(0L, d.toMillis() * TICKS_PER_SECOND / 1000L);
    }

    private static ScheduledTask wrap(BukkitTask bt) {
        return new ScheduledTask() {

            @Override
            public void cancel() {
                bt.cancel();
            }

            @Override
            public boolean isCancelled() {
                return bt.isCancelled();
            }
        };
    }
}
