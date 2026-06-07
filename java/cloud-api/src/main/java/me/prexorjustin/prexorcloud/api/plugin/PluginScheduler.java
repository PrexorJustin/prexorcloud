package me.prexorjustin.prexorcloud.api.plugin;

import java.time.Duration;

import me.prexorjustin.prexorcloud.api.ScheduledTask;

/**
 * Platform-agnostic task scheduler for plugins.
 * <p>
 * Implementations differ by platform (Bukkit, Folia, Velocity, BungeeCord) but
 * share this interface — plugins never need to import platform scheduler
 * classes.
 */
public interface PluginScheduler {

    /** Schedules {@code task} to run asynchronously as soon as possible. */
    ScheduledTask runAsync(Runnable task);

    /** Schedules {@code task} to run asynchronously after {@code delay}. */
    ScheduledTask runDelayed(Duration delay, Runnable task);

    /** Schedules {@code task} to run asynchronously at a fixed rate. */
    ScheduledTask runAtFixedRate(Duration initialDelay, Duration period, Runnable task);
}
