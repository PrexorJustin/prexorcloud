package me.prexorjustin.prexorcloud.api.module.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import me.prexorjustin.prexorcloud.api.ScheduledTask;

/** Controller-level async task scheduler for modules. */
public interface TaskScheduler {

    /** Submits {@code task} for immediate async execution. */
    ScheduledTask schedule(Runnable task);

    ScheduledTask scheduleDelayed(Duration delay, Runnable task);

    ScheduledTask scheduleAtFixedRate(Duration initialDelay, Duration period, Runnable task);

    ScheduledTask scheduleAt(Instant when, Runnable task);

    <T> CompletableFuture<T> submit(Callable<T> task);

    // ── Legacy aliases used by modules ──────────────────────────────────

    default ScheduledTask runAsync(Runnable task) {
        return schedule(task);
    }

    default ScheduledTask runDelayed(Duration delay, Runnable task) {
        return scheduleDelayed(delay, task);
    }

    default ScheduledTask runAtFixedRate(Duration initialDelay, Duration period, Runnable task) {
        return scheduleAtFixedRate(initialDelay, period, task);
    }
}
