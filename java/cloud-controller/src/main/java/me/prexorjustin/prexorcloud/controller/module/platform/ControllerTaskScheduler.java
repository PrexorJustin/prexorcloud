package me.prexorjustin.prexorcloud.controller.module.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.api.ScheduledTask;
import me.prexorjustin.prexorcloud.api.module.scheduling.TaskScheduler;

/**
 * Controller-side {@link TaskScheduler} implementation. Wraps a
 * {@link ScheduledExecutorService} owned by the controller process; tasks
 * scheduled here are subject to the controller's lifecycle (shut down with
 * the bus when the controller stops).
 */
public final class ControllerTaskScheduler implements TaskScheduler {

    private final ScheduledExecutorService executor;

    public ControllerTaskScheduler(ScheduledExecutorService executor) {
        this.executor = executor;
    }

    @Override
    public ScheduledTask schedule(Runnable task) {
        var future = executor.submit(task);
        return wrap(future);
    }

    @Override
    public ScheduledTask scheduleDelayed(Duration delay, Runnable task) {
        var future = executor.schedule(task, Math.max(0L, delay.toMillis()), TimeUnit.MILLISECONDS);
        return wrap(future);
    }

    @Override
    public ScheduledTask scheduleAtFixedRate(Duration initialDelay, Duration period, Runnable task) {
        var future = executor.scheduleAtFixedRate(
                task, Math.max(0L, initialDelay.toMillis()), Math.max(1L, period.toMillis()), TimeUnit.MILLISECONDS);
        return wrap(future);
    }

    @Override
    public ScheduledTask scheduleAt(Instant when, Runnable task) {
        long delayMs = Math.max(0L, when.toEpochMilli() - System.currentTimeMillis());
        var future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        return wrap(future);
    }

    @Override
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        var promise = new CompletableFuture<T>();
        executor.submit(() -> {
            try {
                promise.complete(task.call());
            } catch (Throwable t) {
                promise.completeExceptionally(t);
            }
        });
        return promise;
    }

    private static ScheduledTask wrap(java.util.concurrent.Future<?> future) {
        return new ScheduledTask() {

            @Override
            public void cancel() {
                future.cancel(false);
            }

            @Override
            public boolean isCancelled() {
                return future.isCancelled();
            }
        };
    }

    private static ScheduledTask wrap(ScheduledFuture<?> future) {
        return new ScheduledTask() {

            @Override
            public void cancel() {
                future.cancel(false);
            }

            @Override
            public boolean isCancelled() {
                return future.isCancelled();
            }
        };
    }
}
