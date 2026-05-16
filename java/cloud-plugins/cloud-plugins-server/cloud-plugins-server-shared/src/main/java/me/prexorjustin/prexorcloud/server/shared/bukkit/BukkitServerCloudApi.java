package me.prexorjustin.prexorcloud.server.shared.bukkit;

import java.util.function.Function;

import me.prexorjustin.prexorcloud.api.plugin.CloudPluginContext;
import me.prexorjustin.prexorcloud.api.plugin.PluginScheduler;
import me.prexorjustin.prexorcloud.server.shared.ServerCloudApi;
import me.prexorjustin.prexorcloud.server.shared.ServerControllerClient;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Concrete {@link ServerCloudApi} for Bukkit-based platforms (Spigot, Paper,
 * Folia). The scheduler factory is injected so each platform can supply its
 * own region-thread-safe scheduler implementation.
 */
public final class BukkitServerCloudApi extends ServerCloudApi {

    private final JavaPlugin platform;
    private final Function<JavaPlugin, PluginScheduler> schedulerFactory;

    public BukkitServerCloudApi(
            ServerControllerClient client,
            BukkitCommandRegistry commandRegistry,
            JavaPlugin platform,
            Function<JavaPlugin, PluginScheduler> schedulerFactory) {
        super(client, commandRegistry);
        this.platform = platform;
        this.schedulerFactory = schedulerFactory;
    }

    @Override
    protected CloudPluginContext createPluginContext(Object platformPlugin) {
        JavaPlugin plugin = platformPlugin instanceof JavaPlugin jp ? jp : platform;
        return new BukkitPluginContext(plugin, this, schedulerFactory.apply(plugin));
    }

    /** Exposed for {@link BukkitPluginContext} construction. */
    public ServerControllerClient controllerClient() {
        return client;
    }
}
