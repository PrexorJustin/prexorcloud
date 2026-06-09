package me.prexorjustin.prexorcloud.modules.example.plugin.bedrockgeyser;

import java.time.Instant;
import java.util.Map;

import me.prexorjustin.prexorcloud.api.event.CustomCloudEvent;
import me.prexorjustin.prexorcloud.api.plugin.CloudPluginBase;
import me.prexorjustin.prexorcloud.api.plugin.CloudPluginContext;
import me.prexorjustin.prexorcloud.api.plugin.annotation.CloudPlugin;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;

/**
 * Bedrock-side variant of the example-playtime module.
 *
 * <p>Runs inside Geyser as an Extension. Geyser sits between Bedrock clients
 * and the Java server, so this variant sees only Bedrock players; Java
 * players are handled by the Paper/Folia variants. Both pathways emit into
 * the same {@code PLAYTIME:*} event family so dashboards can render
 * cross-platform totals without splitting by client kind.
 *
 * <p>The {@code @Subscribe} methods below are auto-registered against the
 * generated {@code *GeyserBridge} via its {@code eventBus().register} call
 * during post-init — mirroring how the Velocity variant attaches to the
 * proxy's event manager.
 */
@CloudPlugin(
        name = "ExamplePlaytimeBedrockGeyser",
        version = "0.0.1",
        description = "Geyser-side Bedrock playtime tracker for the example module.",
        authors = {"PrexorCloud"})
public final class ExamplePlaytimeBedrockGeyserPlugin extends CloudPluginBase {

    private static final String BEDROCK_JOIN = "PLAYTIME:BEDROCK_JOIN";
    private static final String BEDROCK_DISCONNECT = "PLAYTIME:BEDROCK_DISCONNECT";

    private CloudPluginContext ctx;

    @Override
    public void onEnable(CloudPluginContext ctx) {
        this.ctx = ctx;
        ctx.logger()
                .info("ExamplePlaytimeBedrockGeyserPlugin enabled on Geyser " + "(instance: "
                        + ctx.self().instanceId() + ")");
    }

    @Override
    public void onDisable() {
        ctx = null;
    }

    @Subscribe
    public void onSessionJoin(SessionJoinEvent event) {
        if (ctx == null) return;
        var conn = event.connection();
        ctx.events()
                .publish(new CustomCloudEvent(
                        BEDROCK_JOIN,
                        ctx.self().instanceId(),
                        Map.of(
                                "xuid", conn.xuid(),
                                "playerName", conn.name(),
                                "joinAddress", conn.joinAddress(),
                                "joinAt", Instant.now().toString())));
    }

    @Subscribe
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        if (ctx == null) return;
        var conn = event.connection();
        ctx.events()
                .publish(new CustomCloudEvent(
                        BEDROCK_DISCONNECT,
                        ctx.self().instanceId(),
                        Map.of(
                                "xuid", conn.xuid(),
                                "disconnectAt", Instant.now().toString(),
                                "reason", event.disconnectReason())));
    }
}
