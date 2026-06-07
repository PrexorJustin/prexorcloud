package me.prexorjustin.prexorcloud.proxy.geyser;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;

/**
 * PrexorCloud Geyser sidecar entry point. Geyser auto-registers an extension's {@code @Subscribe}
 * methods, so the lifecycle + session handlers below are wired without any manual registration.
 *
 * <p>Geyser is a Bedrock↔Java protocol translator, not a server-list proxy: it forwards every
 * Bedrock client to its single configured remote (typically a Java proxy that already performs the
 * edition-aware routing added in Track F.1). This sidecar therefore does not route by server list;
 * its job is to (a) register the Geyser process with the controller as a proxy instance via the
 * shared {@link me.prexorjustin.prexorcloud.proxy.shared.AbstractProxyCloudPlugin} lifecycle and
 * (b) report every Bedrock session as {@code edition=bedrock} — authoritative even when Floodgate
 * isn't in use and the Java UUID looks ordinary.
 */
public final class PrexorCloudGeyser implements Extension {

    private final GeyserCloudCore core = new GeyserCloudCore(this);

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        core.start();
    }

    @Subscribe
    public void onShutdown(GeyserShutdownEvent event) {
        core.stop();
    }

    @Subscribe
    public void onSessionJoin(SessionJoinEvent event) {
        core.onBedrockJoin(event.connection());
    }

    @Subscribe
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        core.onBedrockLeave(event.connection());
    }
}
