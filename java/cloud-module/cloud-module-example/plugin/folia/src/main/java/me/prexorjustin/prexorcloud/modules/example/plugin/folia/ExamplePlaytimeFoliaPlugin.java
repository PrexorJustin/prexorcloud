package me.prexorjustin.prexorcloud.modules.example.plugin.folia;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import me.prexorjustin.prexorcloud.api.client.version.ForVersion;
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
 * STEP F1 — Folia-side half of the example-playtime module.
 *
 * <p>Semantically identical to the Paper variant: translate join/quit events
 * into cluster-wide {@code PLAYTIME:SESSION_*} events on the cloud bus.
 *
 * <h3>Multi-version strategy: {@code @ForVersion} (intra-JAR dispatch)</h3>
 *
 * <p>This plugin showcases the <strong>second</strong> multi-version mechanism
 * PrexorCloud's ModuleSystem supports: runtime dispatch via nested classes
 * annotated {@link ForVersion}. See {@link FoliaSchedulerAdapter} and its two
 * nested implementations ({@link V1_20}, {@link V1_21}). At load time the
 * generated bridge creates a {@code VersionDispatcher} from
 * {@code Bukkit.getServer().getBukkitVersion()} and the {@link #adapt(Class)}
 * call in {@link #onEnable(CloudPluginContext)} picks the best-matching nested
 * class for the running server.
 *
 * <p><strong>When to use this vs. the JAR-level approach</strong>
 * (which the planned {@code cloud-module-protocol-tap} reference module will
 * demonstrate with {@code paperweight-userdev} + per-MC-version subprojects):
 * <ul>
 *   <li>Use {@code @ForVersion} when the API drift between versions is small
 *       enough to hide behind a plain Java interface. One jar, one copy of
 *       every class, one entry in {@code META-INF/plugins/server/folia/}.
 *       Cheaper for the ModuleSystem (one extraction bucket, one synthetic
 *       template).</li>
 *   <li>Use the JAR-level approach (separate subprojects) when the versions
 *       need genuinely incompatible binaries — real NMS mappings, different
 *       compiled API levels, mutually incompatible deps. In that case the
 *       "other" version's classes would fail to load, so nested-class
 *       dispatch isn't even an option at compile time.</li>
 * </ul>
 *
 * <p>The annotation processor picks up {@code -Acloud.platform=folia} (wired
 * in the {@code prexorcloud.plugin-folia} convention plugin) and generates
 * {@code ExamplePlaytimeFoliaPluginFoliaBridge} + a legacy {@code plugin.yml}
 * with {@code folia-supported: true}.
 */
@CloudPlugin(
        name = "ExamplePlaytimeFolia",
        version = "0.0.1",
        description = "Publishes player session events for the example-playtime module (Folia).",
        authors = {"PrexorCloud"})
public final class ExamplePlaytimeFoliaPlugin extends CloudPluginBase implements Listener {

    private static final String SESSION_START = "PLAYTIME:SESSION_START";
    private static final String SESSION_END = "PLAYTIME:SESSION_END";

    private final Map<UUID, OpenSession> openSessions = new ConcurrentHashMap<>();

    private volatile CloudPluginContext ctx;

    @Override
    public void onEnable(CloudPluginContext ctx) {
        this.ctx = ctx;
        // Tier-1 version dispatch: VersionDispatcher reflects on this class's
        // nested types and picks the best @ForVersion match for the running
        // server. The result is logged so operators can confirm which adapter
        // the ModuleSystem's runtime dispatcher chose.
        FoliaSchedulerAdapter adapter = adapt(FoliaSchedulerAdapter.class, ExamplePlaytimeFoliaPlugin.class);
        ctx.logger()
                .info("ExamplePlaytimeFoliaPlugin enabled on instance "
                        + ctx.self().instanceId()
                        + " — @ForVersion adapter: "
                        + adapter.describeScheduler());
    }

    @Override
    public void onDisable() {
        CloudPluginContext localCtx = ctx;
        if (localCtx != null) {
            Instant now = Instant.now();
            for (Map.Entry<UUID, OpenSession> e : openSessions.entrySet()) {
                OpenSession s = e.getValue();
                publishSessionEnd(localCtx, s.sessionId, now, now.toEpochMilli() - s.joinAt.toEpochMilli());
            }
        }
        openSessions.clear();
        ctx = null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        CloudPluginContext localCtx = ctx;
        if (localCtx == null) return;
        Player player = event.getPlayer();
        UUID sessionId = UUID.randomUUID();
        Instant joinAt = Instant.now();
        openSessions.put(player.getUniqueId(), new OpenSession(sessionId, joinAt));

        localCtx.events()
                .publish(new CustomCloudEvent(
                        SESSION_START,
                        localCtx.self().instanceId(),
                        Map.of(
                                "playerId", player.getUniqueId().toString(),
                                "sessionId", sessionId.toString(),
                                "serverName", localCtx.self().instanceId(),
                                "joinAt", joinAt.toString())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        CloudPluginContext localCtx = ctx;
        if (localCtx == null) return;
        OpenSession session = openSessions.remove(event.getPlayer().getUniqueId());
        if (session == null) return;
        Instant quitAt = Instant.now();
        publishSessionEnd(localCtx, session.sessionId, quitAt, quitAt.toEpochMilli() - session.joinAt.toEpochMilli());
    }

    private void publishSessionEnd(CloudPluginContext localCtx, UUID sessionId, Instant quitAt, long durationMs) {
        localCtx.events()
                .publish(new CustomCloudEvent(
                        SESSION_END,
                        localCtx.self().instanceId(),
                        Map.of(
                                "sessionId", sessionId.toString(),
                                "quitAt", quitAt.toString(),
                                "durationMs", durationMs)));
    }

    private record OpenSession(UUID sessionId, Instant joinAt) {}

    /**
     * Minimal adapter surface for Folia scheduler differences between
     * supported Minecraft versions. In a real plugin this would expose the
     * version-specific calls you actually need (region-aware vs. global
     * scheduling hints, async dispatch semantics, etc.). For this reference
     * module it just returns a human-readable description so the runtime
     * dispatch decision is visible in the server log.
     */
    public interface FoliaSchedulerAdapter {
        String describeScheduler();
    }

    /**
     * Adapter for Folia 1.20.x. {@link me.prexorjustin.prexorcloud.api.client.version.VersionDispatcher VersionDispatcher} picks this class
     * when {@code Bukkit.getBukkitVersion()} parses to a 1.20 line.
     */
    @ForVersion(min = "1.20", max = "1.20.6")
    public static final class V1_20 implements FoliaSchedulerAdapter {
        @Override
        public String describeScheduler() {
            return "Folia 1.20 — GlobalRegionScheduler + legacy runTaskAsynchronously hints";
        }
    }

    /**
     * Adapter for Folia 1.21+. Wins over {@link V1_20} on any 1.21.x server
     * because {@link me.prexorjustin.prexorcloud.api.client.version.VersionDispatcher VersionDispatcher} greedily picks the highest {@code min}
     * whose range covers the running version.
     */
    @ForVersion(min = "1.21")
    public static final class V1_21 implements FoliaSchedulerAdapter {
        @Override
        public String describeScheduler() {
            return "Folia 1.21+ — AsyncScheduler with region-aware dispatch";
        }
    }
}
