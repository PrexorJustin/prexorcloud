package me.prexorjustin.prexorcloud.plugin.common;

import java.util.logging.Logger;

import me.prexorjustin.prexorcloud.api.client.CloudClient;
import me.prexorjustin.prexorcloud.api.client.env.PluginEnv;
import me.prexorjustin.prexorcloud.api.domain.InstanceView;
import me.prexorjustin.prexorcloud.api.event.EventBus;
import me.prexorjustin.prexorcloud.api.plugin.CloudPluginContext;
import me.prexorjustin.prexorcloud.api.plugin.InstanceContext;
import me.prexorjustin.prexorcloud.api.plugin.PluginScheduler;
import me.prexorjustin.prexorcloud.api.plugin.command.CloudCommandRegistry;
import me.prexorjustin.prexorcloud.api.plugin.player.PlayerManager;

/**
 * Shared base for {@link CloudPluginContext} implementations across all plugin
 * platforms (Bukkit/Spigot/Paper/Folia, Velocity, BungeeCord).
 *
 * <p>
 * Owns the {@link InstanceContext} construction, every getter that is uniform
 * across platforms, and the field-based plumbing for events / commands /
 * players / scheduler / client / logger. Subclasses supply only the
 * platform-specific {@link CloudClient} and {@link PluginScheduler}; the rest
 * is delegated to the {@link AbstractCloudApi} the subclass already owns.
 * </p>
 *
 * <p>
 * Symmetric with controller/daemon-side {@code ModuleContext} on the
 * primitives both sides need: events, scheduler, client, logger.
 * </p>
 */
public abstract class AbstractPluginContext implements CloudPluginContext {

    protected final AbstractCloudApi cloudApi;
    protected final PluginScheduler scheduler;
    protected final CloudClient cloudClient;
    protected final Logger logger;
    protected final PlayerManager players;

    protected AbstractPluginContext(
            AbstractCloudApi cloudApi,
            PluginScheduler scheduler,
            CloudClient cloudClient,
            Logger logger,
            PlayerManager players) {
        this.cloudApi = cloudApi;
        this.scheduler = scheduler;
        this.cloudClient = cloudClient;
        this.logger = logger;
        this.players = players;
    }

    @Override
    public InstanceContext self() {
        CloudStateCache cache = cloudApi.exposedStateCache();
        CloudClient client = cloudClient;
        return new InstanceContext() {

            @Override
            public String instanceId() {
                return PluginEnv.instanceId();
            }

            @Override
            public String group() {
                return PluginEnv.group();
            }

            @Override
            public String nodeId() {
                return PluginEnv.nodeId();
            }

            @Override
            public int port() {
                return 0;
            }

            @Override
            public InstanceView snapshot() {
                return cache.getInstance(PluginEnv.instanceId()).orElse(null);
            }

            @Override
            public CloudClient client() {
                return client;
            }
        };
    }

    @Override
    public EventBus events() {
        return cloudApi.events();
    }

    @Override
    public CloudCommandRegistry commands() {
        return cloudApi.exposedCommandRegistry();
    }

    @Override
    public PlayerManager players() {
        return players;
    }

    @Override
    public PluginScheduler scheduler() {
        return scheduler;
    }

    @Override
    public CloudClient client() {
        return cloudClient;
    }

    @Override
    public Logger logger() {
        return logger;
    }
}
