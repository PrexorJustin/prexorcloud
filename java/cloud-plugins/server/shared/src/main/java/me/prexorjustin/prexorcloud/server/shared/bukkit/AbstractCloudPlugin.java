package me.prexorjustin.prexorcloud.server.shared.bukkit;

import java.util.function.Consumer;

import me.prexorjustin.prexorcloud.api.client.env.PluginEnv;
import me.prexorjustin.prexorcloud.api.plugin.PluginScheduler;
import me.prexorjustin.prexorcloud.server.shared.InstanceMetricsPayload;
import me.prexorjustin.prexorcloud.server.shared.ServerControllerClient;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Base plugin for PrexorCloud on Bukkit-based servers. Handles controller
 * connection, CloudAPI initialization, player event reporting, command
 * registration, and periodic metrics collection.
 *
 * <p>
 * Platform-specific subclasses provide their own {@link BukkitMetricsCollector}
 * via {@link #createMetricsCollector}. Folia additionally overrides
 * {@link #createScheduler}, {@link #usesTickCounter}, and
 * {@link #scheduleMetricsReporting} so that no {@code BukkitRunnable}
 * runs against the legacy main-thread scheduler.
 * </p>
 */
public abstract class AbstractCloudPlugin extends JavaPlugin {

    // Metrics report interval in server ticks (20 ticks = 1 second, 200 = 10
    // seconds)
    private static final long METRICS_INTERVAL_TICKS = 200L;

    protected ServerControllerClient client;
    protected BukkitServerCloudApi cloudApi;

    @Override
    public void onEnable() {
        if (!PluginEnv.isCloudManaged()) {
            getLogger().warning("PrexorCloud: CLOUD_INSTANCE_ID not set -- running in standalone mode");
            return;
        }

        client = new ServerControllerClient(PluginEnv.controllerUrl(), PluginEnv.pluginToken());

        cloudApi = new BukkitServerCloudApi(client, new BukkitCommandRegistry(this), this, this::createScheduler);
        cloudApi.start();

        TickCounter tickCounter = null;
        if (usesTickCounter()) {
            tickCounter = new TickCounter();
            tickCounter.start(this);
        }

        var metricsCollector = createMetricsCollector(tickCounter);

        getServer()
                .getPluginManager()
                .registerEvents(new BukkitPlayerListener(client, cloudApi.serverPlayerManager()), this);

        client.reportReady();

        // Renewable readiness: re-assert readiness on every metrics heartbeat (not just once at
        // startup) so a lost one-shot /ready or a cold-leader rebuild self-heals to RUNNING instead
        // of pinning this instance at STARTING. Wrapping the reporter keeps this platform-agnostic —
        // Folia's scheduleMetricsReporting override invokes the same reporter.
        scheduleMetricsReporting(metricsCollector, payload -> {
            client.reportMetrics(payload);
            client.reportReady();
        });

        getLogger()
                .info("PrexorCloud connected (instanceId=" + PluginEnv.instanceId() + ", group=" + PluginEnv.group()
                        + ")");
    }

    @Override
    public void onDisable() {
        if (cloudApi != null) {
            cloudApi.stop();
        }
        getLogger().info("PrexorCloud disconnected");
    }

    /** Whether this platform supports {@link TickCounter}. Folia returns false. */
    protected boolean usesTickCounter() {
        return true;
    }

    /** Creates the per-plugin scheduler used inside {@link BukkitPluginContext}. */
    protected PluginScheduler createScheduler(JavaPlugin plugin) {
        return new BukkitPluginScheduler(plugin);
    }

    /** Creates the platform-specific metrics collector. */
    protected abstract BukkitMetricsCollector createMetricsCollector(TickCounter tickCounter);

    /**
     * Schedules periodic metrics reporting. Default = collect on the Bukkit main
     * thread (entity counts need it), report HTTP off-thread. Folia overrides to
     * run both stages on {@code Bukkit.getAsyncScheduler()}.
     */
    protected void scheduleMetricsReporting(
            BukkitMetricsCollector collector, Consumer<InstanceMetricsPayload> reporter) {
        new BukkitRunnable() {

            @Override
            public void run() {
                var payload = collector.collect();
                getServer()
                        .getScheduler()
                        .runTaskAsynchronously(AbstractCloudPlugin.this, () -> reporter.accept(payload));
            }
        }.runTaskTimer(this, METRICS_INTERVAL_TICKS, METRICS_INTERVAL_TICKS);
    }
}
