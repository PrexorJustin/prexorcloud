package me.prexorjustin.prexorcloud.proxy.bungeecord;

import me.prexorjustin.prexorcloud.api.plugin.CloudPluginContext;
import me.prexorjustin.prexorcloud.api.plugin.command.Arg;
import me.prexorjustin.prexorcloud.plugin.common.AbstractCloudApi;
import me.prexorjustin.prexorcloud.plugin.common.CloudStateCache;

import net.md_5.bungee.api.plugin.Plugin;

public final class BungeeCloudApi extends AbstractCloudApi {

    private final BungeeCloudPlayerManager playerManager;

    public BungeeCloudApi(CloudStateCache stateCache, Plugin plugin) {
        super(stateCache, new BungeeCommandRegistry(plugin));
        this.playerManager = new BungeeCloudPlayerManager(stateCache);
        Arg.registerPlayerConverter((raw, ctx) -> playerManager.getPlayer(raw).orElse(null));
    }

    public BungeeCloudPlayerManager bungeePlayerManager() {
        return playerManager;
    }

    @Override
    protected CloudPluginContext createPluginContext(Object platformPlugin) {
        throw new UnsupportedOperationException("CloudPluginBase is not supported on the proxy side");
    }
}
