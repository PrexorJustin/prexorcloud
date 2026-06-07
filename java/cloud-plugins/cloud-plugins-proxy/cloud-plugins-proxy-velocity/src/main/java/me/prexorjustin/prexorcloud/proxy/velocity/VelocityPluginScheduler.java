package me.prexorjustin.prexorcloud.proxy.velocity;

import java.time.Duration;

import me.prexorjustin.prexorcloud.api.ScheduledTask;
import me.prexorjustin.prexorcloud.api.plugin.PluginScheduler;

import com.velocitypowered.api.proxy.ProxyServer;

/**
 * Velocity-flavoured {@link PluginScheduler}. Tasks run on Velocity's async
 * worker pool — Velocity has no main thread for plugins to share.
 */
final class VelocityPluginScheduler implements PluginScheduler {

    private final ProxyServer server;
    private final Object plugin;

    VelocityPluginScheduler(ProxyServer server, Object plugin) {
        this.server = server;
        this.plugin = plugin;
    }

    @Override
    public ScheduledTask runAsync(Runnable task) {
        com.velocitypowered.api.scheduler.ScheduledTask vt =
                server.getScheduler().buildTask(plugin, task).schedule();
        return wrap(vt);
    }

    @Override
    public ScheduledTask runDelayed(Duration delay, Runnable task) {
        com.velocitypowered.api.scheduler.ScheduledTask vt =
                server.getScheduler().buildTask(plugin, task).delay(delay).schedule();
        return wrap(vt);
    }

    @Override
    public ScheduledTask runAtFixedRate(Duration initialDelay, Duration period, Runnable task) {
        com.velocitypowered.api.scheduler.ScheduledTask vt = server.getScheduler()
                .buildTask(plugin, task)
                .delay(initialDelay)
                .repeat(period)
                .schedule();
        return wrap(vt);
    }

    private static ScheduledTask wrap(com.velocitypowered.api.scheduler.ScheduledTask vt) {
        return new ScheduledTask() {

            @Override
            public void cancel() {
                vt.cancel();
            }

            @Override
            public boolean isCancelled() {
                return vt.status() == com.velocitypowered.api.scheduler.TaskStatus.CANCELLED;
            }
        };
    }
}
