package me.prexorjustin.prexorcloud.modules.example.plugin.velocity;

import java.time.Instant;
import java.util.Map;

import me.prexorjustin.prexorcloud.api.event.CustomCloudEvent;
import me.prexorjustin.prexorcloud.api.plugin.CloudPluginBase;
import me.prexorjustin.prexorcloud.api.plugin.CloudPluginContext;
import me.prexorjustin.prexorcloud.api.plugin.annotation.CloudPlugin;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;

/**
 * Velocity-side variant of the example-playtime module.
 *
 * <p>Distinct from the Paper / Folia variants: the backend plugins emit
 * {@code PLAYTIME:SESSION_*} events scoped to a single backend server. This
 * plugin emits {@code PLAYTIME:PROXY_*} events scoped to the proxy edge —
 * useful for "time spent connected to the network" metrics that span server
 * transfers without double-counting per-server sessions.
 *
 * <p>The {@code @Subscribe} methods below are auto-registered against the
 * generated {@code *VelocityBridge}'s {@code @Plugin} instance — no platform
 * escape hatch needed.
 */
@CloudPlugin(
        name = "ExamplePlaytimeVelocity",
        version = "0.0.1",
        description = "Velocity-side proxy-edge playtime tracker for the example module.",
        authors = {"PrexorCloud"})
public final class ExamplePlaytimeVelocityPlugin extends CloudPluginBase {

    private static final String PROXY_LOGIN = "PLAYTIME:PROXY_LOGIN";
    private static final String PROXY_DISCONNECT = "PLAYTIME:PROXY_DISCONNECT";

    private CloudPluginContext ctx;

    @Override
    public void onEnable(CloudPluginContext ctx) {
        this.ctx = ctx;
        ctx.logger()
                .info("ExamplePlaytimeVelocityPlugin enabled on proxy "
                        + ctx.self().instanceId());
    }

    @Override
    public void onDisable() {
        ctx = null;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        if (ctx == null) return;
        Instant now = Instant.now();
        ctx.events()
                .publish(new CustomCloudEvent(
                        PROXY_LOGIN,
                        ctx.self().instanceId(),
                        Map.of(
                                "playerId",
                                event.getPlayer().getUniqueId().toString(),
                                "playerName",
                                event.getPlayer().getUsername(),
                                "loginAt",
                                now.toString())));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (ctx == null) return;
        Instant now = Instant.now();
        ctx.events()
                .publish(new CustomCloudEvent(
                        PROXY_DISCONNECT,
                        ctx.self().instanceId(),
                        Map.of(
                                "playerId",
                                event.getPlayer().getUniqueId().toString(),
                                "disconnectAt",
                                now.toString())));
    }
}
