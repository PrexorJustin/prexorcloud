package me.prexorjustin.prexorcloud.proxy.velocity;

import me.prexorjustin.prexorcloud.api.client.env.PluginEnv;
import me.prexorjustin.prexorcloud.plugin.common.CloudStateCache;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;

public final class VelocityPingListener {

    private final CloudStateCache stateCache;

    public VelocityPingListener(CloudStateCache stateCache) {
        this.stateCache = stateCache;
    }

    @Subscribe
    public void onProxyPing(ProxyPingEvent event) {
        stateCache.getGroupMotd(PluginEnv.group()).ifPresent(motd -> {
            var motds = motd.motds();
            if (motds == null || motds.isEmpty()) return;

            String raw;
            String mode = motd.motdMode();
            if ("SEQUENTIAL".equalsIgnoreCase(mode)) {
                int interval = motd.motdIntervalSeconds() > 0 ? motd.motdIntervalSeconds() : 30;
                int idx = (int) ((System.currentTimeMillis() / (interval * 1000L)) % motds.size());
                raw = motds.get(idx);
            } else if ("RANDOM".equalsIgnoreCase(mode)) {
                raw = motds.get((int) (Math.random() * motds.size()));
            } else {
                raw = motds.get(0);
            }

            var motdComponent = MiniMessage.miniMessage().deserialize(raw);
            event.setPing(event.getPing().asBuilder().description(motdComponent).build());
        });
    }
}
