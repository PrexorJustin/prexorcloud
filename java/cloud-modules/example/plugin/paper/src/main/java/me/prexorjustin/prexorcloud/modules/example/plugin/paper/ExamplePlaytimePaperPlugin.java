package me.prexorjustin.prexorcloud.modules.example.plugin.paper;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.api.event.CustomCloudEvent;
import me.prexorjustin.prexorcloud.api.plugin.CloudPluginBase;
import me.prexorjustin.prexorcloud.api.plugin.CloudPluginContext;
import me.prexorjustin.prexorcloud.api.plugin.annotation.CloudPlugin;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Paper variant of the example-playtime plugin.
 *
 * <p>Single-version reference plugin: compiled against {@code paperApi120}
 * (Paper 1.20.4) but the manifest entry uses {@code mcVersionRange: "*"} so
 * {@code ModulePluginManager} ships it to any Paper instance the module is
 * attached to. The plugin itself only touches the long-stable Bukkit API
 * surface (Player events, the cloud-api), so the single-version cut is
 * honest.
 *
 * <p><strong>Multi-version stories live elsewhere</strong>:
 * <ul>
 *   <li>The sibling Folia plugin demonstrates {@code @ForVersion} intra-jar
 *       dispatch.</li>
 *   <li>{@code cloud-module-tablist} (planned) spans MC 1.18 → 1.21 with
 *       {@code @ForVersion} adapters around real API drift.</li>
 *   <li>{@code cloud-module-protocol-tap} (planned) uses the JAR-split
 *       mechanism with {@code paperweight-userdev} for NMS access.</li>
 * </ul>
 * Adding a JAR-split here would have been theatre — example-playtime doesn't
 * touch any API that drifts.
 */
@CloudPlugin(
        name = "ExamplePlaytimePaper",
        version = "0.0.1",
        description = "Paper variant of example-playtime.",
        authors = {"PrexorCloud"})
public final class ExamplePlaytimePaperPlugin extends CloudPluginBase implements Listener {

    private static final String SESSION_START = "PLAYTIME:SESSION_START";
    private static final String SESSION_END = "PLAYTIME:SESSION_END";

    private final Map<UUID, OpenSession> openSessions = new HashMap<>();
    private CloudPluginContext ctx;

    @Override
    public void onEnable(CloudPluginContext ctx) {
        this.ctx = ctx;
        ctx.logger()
                .info("ExamplePlaytimePaper enabled on instance " + ctx.self().instanceId());
    }

    @Override
    public void onDisable() {
        if (ctx != null) {
            Instant now = Instant.now();
            for (Map.Entry<UUID, OpenSession> e : openSessions.entrySet()) {
                OpenSession s = e.getValue();
                publishSessionEnd(s.sessionId, now, now.toEpochMilli() - s.joinAt.toEpochMilli());
            }
        }
        openSessions.clear();
        ctx = null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        if (ctx == null) return;
        Player player = event.getPlayer();
        UUID sessionId = UUID.randomUUID();
        Instant joinAt = Instant.now();
        openSessions.put(player.getUniqueId(), new OpenSession(sessionId, joinAt));

        ctx.events()
                .publish(new CustomCloudEvent(
                        SESSION_START,
                        ctx.self().instanceId(),
                        Map.of(
                                "playerId", player.getUniqueId().toString(),
                                "sessionId", sessionId.toString(),
                                "serverName", ctx.self().instanceId(),
                                "joinAt", joinAt.toString())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (ctx == null) return;
        OpenSession session = openSessions.remove(event.getPlayer().getUniqueId());
        if (session == null) return;
        Instant quitAt = Instant.now();
        publishSessionEnd(session.sessionId, quitAt, quitAt.toEpochMilli() - session.joinAt.toEpochMilli());
    }

    private void publishSessionEnd(UUID sessionId, Instant quitAt, long durationMs) {
        ctx.events()
                .publish(new CustomCloudEvent(
                        SESSION_END,
                        ctx.self().instanceId(),
                        Map.of(
                                "sessionId", sessionId.toString(),
                                "quitAt", quitAt.toString(),
                                "durationMs", durationMs)));
    }

    private record OpenSession(UUID sessionId, Instant joinAt) {}
}
