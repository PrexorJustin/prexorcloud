package me.prexorjustin.prexorcloud.proxy.velocity;

import java.util.List;
import java.util.Optional;

import me.prexorjustin.prexorcloud.api.client.env.PluginEnv;
import me.prexorjustin.prexorcloud.api.domain.InstanceState;
import me.prexorjustin.prexorcloud.api.domain.InstanceView;
import me.prexorjustin.prexorcloud.api.event.EventBus;
import me.prexorjustin.prexorcloud.api.event.events.PlayerConnectedEvent;
import me.prexorjustin.prexorcloud.api.event.events.PlayerDisconnectedEvent;
import me.prexorjustin.prexorcloud.plugin.common.CloudStateCache;
import me.prexorjustin.prexorcloud.plugin.common.NetworkRouter;
import me.prexorjustin.prexorcloud.proxy.shared.ProxyControllerClient;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VelocityPlayerListener {

    private static final Logger logger = LoggerFactory.getLogger(VelocityPlayerListener.class);
    private static final String DEFAULT_KICK_MESSAGE = "No backend server is currently available.";

    private final ProxyServer proxyServer;
    private final ProxyControllerClient controllerClient;
    private final CloudStateCache stateCache;
    private final NetworkRouter router;
    private final EventBus eventBus;

    public VelocityPlayerListener(
            ProxyServer proxyServer,
            ProxyControllerClient controllerClient,
            CloudStateCache stateCache,
            EventBus eventBus) {
        this.proxyServer = proxyServer;
        this.controllerClient = controllerClient;
        this.stateCache = stateCache;
        this.router = new NetworkRouter(stateCache, PluginEnv.group());
        this.eventBus = eventBus;
    }

    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        List<String> chain = router.fallbackChain(null);
        if (chain.isEmpty()) {
            logger.warn(
                    "No network or default group configured -- cannot route player {}",
                    event.getPlayer().getUsername());
            return;
        }
        Optional<RegisteredServer> target = pickFromChain(chain);
        if (target.isPresent()) {
            event.setInitialServer(target.get());
            logger.info(
                    "Routing {} to {}",
                    event.getPlayer().getUsername(),
                    target.get().getServerInfo().getName());
            return;
        }
        logger.warn(
                "No routable RUNNING instance found in {} for {}",
                chain,
                event.getPlayer().getUsername());
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        String sourceServerName = event.getServer().getServerInfo().getName();
        String sourceGroup = stateCache
                .getInstance(sourceServerName)
                .map(InstanceView::group)
                .orElse(null);

        List<String> chain = router.fallbackChain(sourceGroup);
        Optional<RegisteredServer> target = pickFromChain(chain);
        if (target.isPresent()) {
            event.setResult(KickedFromServerEvent.RedirectPlayer.create(target.get()));
            logger.info(
                    "Failover: routing {} from {} to {}",
                    event.getPlayer().getUsername(),
                    sourceServerName,
                    target.get().getServerInfo().getName());
            return;
        }
        Component reason = Component.text(router.kickMessage(DEFAULT_KICK_MESSAGE));
        event.setResult(KickedFromServerEvent.DisconnectPlayer.create(reason));
    }

    private Optional<RegisteredServer> pickFromChain(List<String> groups) {
        for (String group : groups) {
            for (InstanceView instance : stateCache.getInstancesByGroup(group)) {
                if (instance.state() != InstanceState.RUNNING) continue;
                Optional<RegisteredServer> server = proxyServer.getServer(instance.instanceId());
                if (server.isPresent()) {
                    return server;
                }
                logger.warn("Instance {} is RUNNING but not registered with Velocity", instance.instanceId());
            }
        }
        return Optional.empty();
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        var player = event.getPlayer();
        controllerClient.reportPlayerJoin(
                player.getUniqueId(), player.getUsername(), PluginEnv.instanceId(), PluginEnv.group());
        eventBus.publish(new PlayerConnectedEvent(
                player.getUniqueId(), player.getUsername(), PluginEnv.instanceId(), PluginEnv.group()));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        var player = event.getPlayer();
        controllerClient.reportPlayerLeave(player.getUniqueId());
        eventBus.publish(new PlayerDisconnectedEvent(
                player.getUniqueId(), player.getUsername(), PluginEnv.instanceId(), PluginEnv.group()));
    }
}
