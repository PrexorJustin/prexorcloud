package me.prexorjustin.prexorcloud.proxy.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

@Plugin(
        id = "prexorcloud",
        name = "PrexorCloud",
        version = "1.0.0",
        description = "PrexorCloud proxy integration",
        authors = {"PrexorJustin"})
public final class PrexorCloudVelocity {

    private final VelocityCloudCore core;

    @Inject
    public PrexorCloudVelocity(ProxyServer proxyServer, Logger logger) {
        this.core = new VelocityCloudCore(proxyServer, logger, this);
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        core.start();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        core.stop();
    }
}
