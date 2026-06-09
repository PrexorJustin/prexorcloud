package me.prexorjustin.prexorcloud.server.fabric;

import java.util.concurrent.atomic.AtomicLong;

import me.prexorjustin.prexorcloud.api.client.env.PluginEnv;
import me.prexorjustin.prexorcloud.server.shared.ServerControllerClient;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric dedicated-server entry point. Mirrors the Bukkit {@code AbstractCloudPlugin} lifecycle —
 * registers with the controller, reports player join/leave, and pushes a metrics snapshot every
 * {@value #METRICS_INTERVAL_TICKS} ticks (~10s) — but over Fabric's event API instead of Bukkit's,
 * reusing the platform-agnostic {@link ServerControllerClient}. Servers not managed by the cloud
 * (no {@code CLOUD_INSTANCE_ID}) are detected and left untouched.
 */
public final class PrexorCloudFabric implements DedicatedServerModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("PrexorCloud");
    private static final long METRICS_INTERVAL_TICKS = 200L;

    private ServerControllerClient client;
    private FabricMetricsCollector metricsCollector;
    private final AtomicLong tick = new AtomicLong();

    @Override
    public void onInitializeServer() {
        if (!PluginEnv.isCloudManaged()) {
            LOGGER.warn("PrexorCloud: CLOUD_INSTANCE_ID not set -- running in standalone mode");
            return;
        }
        client = new ServerControllerClient(PluginEnv.controllerUrl(), PluginEnv.pluginToken());

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            metricsCollector = new FabricMetricsCollector(server);
            client.reportReady();
            LOGGER.info("PrexorCloud connected (instanceId={}, group={})", PluginEnv.instanceId(), PluginEnv.group());
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            client.reportPlayerJoin(player.getUuid(), player.getGameProfile().getName(), PluginEnv.group());
        });

        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> client.reportPlayerLeave(handler.getPlayer().getUuid()));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (metricsCollector == null) return;
            if (tick.incrementAndGet() % METRICS_INTERVAL_TICKS != 0) return;
            client.reportMetrics(metricsCollector.collect());
        });
    }
}
