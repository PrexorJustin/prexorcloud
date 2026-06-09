package me.prexorjustin.prexorcloud.server.shared.bukkit;

import me.prexorjustin.prexorcloud.api.plugin.PluginScheduler;
import me.prexorjustin.prexorcloud.plugin.common.AbstractPluginContext;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bukkit-based {@link me.prexorjustin.prexorcloud.api.plugin.CloudPluginContext}.
 * The scheduler is injected so Folia can supply its own
 * {@code FoliaPluginScheduler} (region-thread-safe) while Spigot and Paper use
 * the standard {@link BukkitPluginScheduler}.
 *
 * <p>
 * All structural plumbing (events, commands, players, scheduler, client,
 * logger, instance snapshot) is inherited from {@link AbstractPluginContext}.
 * </p>
 */
public final class BukkitPluginContext extends AbstractPluginContext {

    public BukkitPluginContext(JavaPlugin platform, BukkitServerCloudApi cloudApi, PluginScheduler scheduler) {
        super(
                cloudApi,
                scheduler,
                new BukkitCloudClient(cloudApi.controllerClient()),
                platform.getLogger(),
                cloudApi.serverPlayerManager());
    }
}
