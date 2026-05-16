package me.prexorjustin.prexorcloud.proxy.velocity;

import java.util.logging.Logger;

import me.prexorjustin.prexorcloud.plugin.common.AbstractPluginContext;
import me.prexorjustin.prexorcloud.plugin.common.CloudStateCache;
import me.prexorjustin.prexorcloud.proxy.shared.ProxyControllerClient;

import com.velocitypowered.api.proxy.ProxyServer;

/**
 * Velocity-side {@link me.prexorjustin.prexorcloud.api.plugin.CloudPluginContext}.
 * Inherits the structural plumbing from {@link AbstractPluginContext}; supplies
 * the Velocity-specific scheduler and client.
 *
 * <p>
 * The bridge's {@code @Plugin} instance is required as {@code platformPlugin}
 * — it is the cookie Velocity uses for event/scheduler scoping.
 * </p>
 */
final class VelocityPluginContext extends AbstractPluginContext {

    VelocityPluginContext(
            Object platformPlugin,
            VelocityCloudApi cloudApi,
            ProxyServer proxyServer,
            ProxyControllerClient controllerClient,
            CloudStateCache stateCache) {
        super(
                cloudApi,
                new VelocityPluginScheduler(proxyServer, platformPlugin),
                new VelocityCloudClient(proxyServer, stateCache, controllerClient),
                Logger.getLogger(platformPlugin.getClass().getName()),
                cloudApi.velocityPlayerManager());
    }
}
