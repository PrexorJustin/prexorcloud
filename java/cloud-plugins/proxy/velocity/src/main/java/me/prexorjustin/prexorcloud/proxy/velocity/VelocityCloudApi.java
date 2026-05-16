package me.prexorjustin.prexorcloud.proxy.velocity;

import me.prexorjustin.prexorcloud.api.plugin.CloudPluginContext;
import me.prexorjustin.prexorcloud.api.plugin.command.Arg;
import me.prexorjustin.prexorcloud.plugin.common.AbstractCloudApi;
import me.prexorjustin.prexorcloud.plugin.common.CloudStateCache;
import me.prexorjustin.prexorcloud.proxy.shared.ProxyControllerClient;

import com.velocitypowered.api.proxy.ProxyServer;

public final class VelocityCloudApi extends AbstractCloudApi {

    private final ProxyServer proxyServer;
    private final ProxyControllerClient controllerClient;
    private final VelocityCloudPlayerManager playerManager;

    public VelocityCloudApi(
            ProxyServer proxyServer, CloudStateCache stateCache, ProxyControllerClient controllerClient) {
        super(stateCache, new VelocityCommandRegistry(proxyServer.getCommandManager(), proxyServer));
        this.proxyServer = proxyServer;
        this.controllerClient = controllerClient;
        this.playerManager = new VelocityCloudPlayerManager(proxyServer, stateCache);
        Arg.registerPlayerConverter((raw, ctx) -> playerManager.getPlayer(raw).orElse(null));
    }

    public VelocityCloudPlayerManager velocityPlayerManager() {
        return playerManager;
    }

    @Override
    protected CloudPluginContext createPluginContext(Object platformPlugin) {
        return new VelocityPluginContext(platformPlugin, this, proxyServer, controllerClient, stateCache);
    }
}
