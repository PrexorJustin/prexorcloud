package me.prexorjustin.prexorcloud.api;

/**
 * Handle to a scheduled task. Shared by {@link plugin.PluginScheduler} and
 * {@link module.scheduling.TaskScheduler}.
 */
public interface ScheduledTask {

    /** Cancel this task. No-op if already cancelled or completed. */
    void cancel();

    /** Returns {@code true} if this task has been cancelled. */
    boolean isCancelled();
}
