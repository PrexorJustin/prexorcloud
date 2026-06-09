package me.prexorjustin.prexorcloud.server.shared.bukkit;

import me.prexorjustin.prexorcloud.api.client.env.PluginEnv;
import me.prexorjustin.prexorcloud.server.shared.ServerCloudPlayerManager;
import me.prexorjustin.prexorcloud.server.shared.ServerControllerClient;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Reports player join and quit events to the controller in real time. Shared
 * by all Bukkit-based platforms — player events are dispatched globally on
 * Folia as well, so the same listener works there.
 */
public final class BukkitPlayerListener implements Listener {

    private final ServerControllerClient client;
    private final ServerCloudPlayerManager playerManager;

    public BukkitPlayerListener(ServerControllerClient client, ServerCloudPlayerManager playerManager) {
        this.client = client;
        this.playerManager = playerManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        playerManager.addLocalPlayer(player.getUniqueId(), player.getName(), PluginEnv.instanceId(), PluginEnv.group());
        client.reportPlayerJoin(player.getUniqueId(), player.getName(), PluginEnv.group());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        var uuid = event.getPlayer().getUniqueId();
        playerManager.removeLocalPlayer(uuid);
        client.reportPlayerLeave(uuid);
    }
}
