package me.prexorjustin.prexorcloud.server.neoforge;

import java.util.concurrent.atomic.AtomicLong;

import me.prexorjustin.prexorcloud.api.client.env.PluginEnv;
import me.prexorjustin.prexorcloud.server.shared.ServerControllerClient;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge dedicated-server entry point. Mirrors the Fabric mod and the Bukkit
 * {@code AbstractCloudPlugin} lifecycle — registers with the controller, reports player join/leave,
 * and pushes a metrics snapshot every {@value #METRICS_INTERVAL_TICKS} ticks (~10s) — but over
 * NeoForge's event bus, reusing the platform-agnostic {@link ServerControllerClient}. Servers not
 * managed by the cloud (no {@code CLOUD_INSTANCE_ID}) are detected and left untouched.
 */
@Mod("prexorcloud")
public final class PrexorCloudNeoForge {

    private static final Logger LOGGER = LoggerFactory.getLogger("PrexorCloud");
    private static final long METRICS_INTERVAL_TICKS = 200L;

    private final ServerControllerClient client;
    private NeoForgeMetricsCollector metricsCollector;
    private final AtomicLong tick = new AtomicLong();

    // NeoForge injects the mod event bus, but the lifecycle/player/tick events we need fire on the
    // game bus (NeoForge.EVENT_BUS), so that is where we listen.
    public PrexorCloudNeoForge(IEventBus modEventBus) {
        if (!PluginEnv.isCloudManaged()) {
            this.client = null;
            LOGGER.warn("PrexorCloud: CLOUD_INSTANCE_ID not set -- running in standalone mode");
            return;
        }
        this.client = new ServerControllerClient(PluginEnv.controllerUrl(), PluginEnv.pluginToken());

        IEventBus gameBus = NeoForge.EVENT_BUS;
        gameBus.addListener(ServerStartedEvent.class, this::onServerStarted);
        gameBus.addListener(PlayerEvent.PlayerLoggedInEvent.class, this::onPlayerJoin);
        gameBus.addListener(PlayerEvent.PlayerLoggedOutEvent.class, this::onPlayerLeave);
        gameBus.addListener(ServerTickEvent.Post.class, this::onServerTick);
    }

    private void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        metricsCollector = new NeoForgeMetricsCollector(server);
        client.reportReady();
        LOGGER.info("PrexorCloud connected (instanceId={}, group={})", PluginEnv.instanceId(), PluginEnv.group());
    }

    private void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        client.reportPlayerJoin(player.getUUID(), player.getGameProfile().getName(), PluginEnv.group());
    }

    private void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        client.reportPlayerLeave(event.getEntity().getUUID());
    }

    private void onServerTick(ServerTickEvent.Post event) {
        if (metricsCollector == null) return;
        if (tick.incrementAndGet() % METRICS_INTERVAL_TICKS != 0) return;
        client.reportMetrics(metricsCollector.collect());
    }
}
