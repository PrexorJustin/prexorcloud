package me.prexorjustin.prexorcloud.proxy.bungeecord;

import java.util.List;
import java.util.Optional;

import me.prexorjustin.prexorcloud.api.client.env.PluginEnv;
import me.prexorjustin.prexorcloud.api.domain.InstanceState;
import me.prexorjustin.prexorcloud.api.domain.InstanceView;
import me.prexorjustin.prexorcloud.api.domain.PlayerEdition;
import me.prexorjustin.prexorcloud.api.event.EventBus;
import me.prexorjustin.prexorcloud.api.event.events.PlayerConnectedEvent;
import me.prexorjustin.prexorcloud.api.event.events.PlayerDisconnectedEvent;
import me.prexorjustin.prexorcloud.plugin.common.CloudStateCache;
import me.prexorjustin.prexorcloud.plugin.common.NetworkRouter;
import me.prexorjustin.prexorcloud.proxy.shared.ProxyControllerClient;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.event.ServerKickEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BungeePlayerListener implements Listener {

    private static final Logger logger = LoggerFactory.getLogger(BungeePlayerListener.class);
    private static final String DEFAULT_KICK_MESSAGE = "No backend server is currently available.";

    private final ProxyControllerClient controllerClient;
    private final EventBus eventBus;
    private final CloudStateCache stateCache;
    private final NetworkRouter router;
    private final ProxyServer proxyServer;

    public BungeePlayerListener(
            ProxyControllerClient controllerClient,
            EventBus eventBus,
            CloudStateCache stateCache,
            ProxyServer proxyServer) {
        this.controllerClient = controllerClient;
        this.eventBus = eventBus;
        this.stateCache = stateCache;
        this.router = new NetworkRouter(stateCache, PluginEnv.group());
        this.proxyServer = proxyServer;
    }

    @EventHandler
    public void onServerConnect(ServerConnectEvent event) {
        if (event.getReason() != ServerConnectEvent.Reason.JOIN_PROXY) {
            return;
        }
        String edition = PlayerEdition.detect(event.getPlayer().getUniqueId());
        List<String> chain = router.fallbackChain(null, edition);
        if (chain.isEmpty()) {
            logger.warn(
                    "No network or default group configured -- cannot route player {}",
                    event.getPlayer().getName());
            return;
        }
        Optional<ServerInfo> target = pickFromChain(chain);
        if (target.isPresent()) {
            event.setTarget(target.get());
            logger.info(
                    "Routing {} to {}",
                    event.getPlayer().getName(),
                    target.get().getName());
        } else {
            logger.warn(
                    "No routable RUNNING instance found in {} for {}",
                    chain,
                    event.getPlayer().getName());
        }
    }

    @EventHandler
    public void onServerKick(ServerKickEvent event) {
        if (event.getKickedFrom() == null) return;
        String sourceServerName = event.getKickedFrom().getName();
        String sourceGroup = stateCache
                .getInstance(sourceServerName)
                .map(InstanceView::group)
                .orElse(null);

        String edition = PlayerEdition.detect(event.getPlayer().getUniqueId());
        List<String> chain = router.fallbackChain(sourceGroup, edition);
        Optional<ServerInfo> target = pickFromChain(chain);
        if (target.isPresent()) {
            event.setCancelled(true);
            event.setCancelServer(target.get());
            logger.info(
                    "Failover: routing {} from {} to {}",
                    event.getPlayer().getName(),
                    sourceServerName,
                    target.get().getName());
        } else {
            event.setKickReasonComponent(new net.md_5.bungee.api.chat.BaseComponent[] {
                new TextComponent(router.kickMessage(DEFAULT_KICK_MESSAGE))
            });
        }
    }

    private Optional<ServerInfo> pickFromChain(List<String> groups) {
        for (String group : groups) {
            for (InstanceView instance : stateCache.getInstancesByGroup(group)) {
                if (instance.state() != InstanceState.RUNNING) continue;
                ServerInfo server = proxyServer.getServerInfo(instance.instanceId());
                if (server != null) {
                    return Optional.of(server);
                }
                logger.warn("Instance {} is RUNNING but not registered with BungeeCord", instance.instanceId());
            }
        }
        return Optional.empty();
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        var player = event.getPlayer();
        controllerClient.reportPlayerJoin(
                player.getUniqueId(), player.getName(), PluginEnv.instanceId(), PluginEnv.group());
        eventBus.publish(new PlayerConnectedEvent(
                player.getUniqueId(), player.getName(), PluginEnv.instanceId(), PluginEnv.group()));
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        var player = event.getPlayer();
        controllerClient.reportPlayerLeave(player.getUniqueId());
        eventBus.publish(new PlayerDisconnectedEvent(
                player.getUniqueId(), player.getName(), PluginEnv.instanceId(), PluginEnv.group()));
    }
}
