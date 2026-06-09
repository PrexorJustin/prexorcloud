package me.prexorjustin.prexorcloud.server.shared;

import me.prexorjustin.prexorcloud.api.plugin.command.Arg;
import me.prexorjustin.prexorcloud.plugin.common.AbstractCloudApi;
import me.prexorjustin.prexorcloud.plugin.common.AbstractCommandRegistry;
import me.prexorjustin.prexorcloud.plugin.common.CloudStateCache;

/**
 * CloudApi implementation for game server plugins (Spigot/Paper/Folia).
 * Provides a server-side player manager; platform-specific modules provide the
 * command registry and plugin context factory.
 */
public abstract class ServerCloudApi extends AbstractCloudApi {

    protected final ServerControllerClient client;
    protected final ServerCloudPlayerManager playerManager;

    protected ServerCloudApi(ServerControllerClient client, AbstractCommandRegistry commandRegistry) {
        super(new CloudStateCache(client, 10), commandRegistry);
        this.client = client;
        this.playerManager = new ServerCloudPlayerManager(client);
        Arg.registerPlayerConverter((raw, ctx) -> playerManager.getPlayer(raw).orElse(null));
    }

    public ServerCloudPlayerManager serverPlayerManager() {
        return playerManager;
    }
}
