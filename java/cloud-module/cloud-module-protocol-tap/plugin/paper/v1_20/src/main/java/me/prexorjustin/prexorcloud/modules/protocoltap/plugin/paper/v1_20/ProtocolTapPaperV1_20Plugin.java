package me.prexorjustin.prexorcloud.modules.protocoltap.plugin.paper.v1_20;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import me.prexorjustin.prexorcloud.api.client.env.PluginEnv;
import me.prexorjustin.prexorcloud.api.plugin.CloudPluginBase;
import me.prexorjustin.prexorcloud.api.plugin.CloudPluginContext;
import me.prexorjustin.prexorcloud.api.plugin.annotation.CloudPlugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Paper 1.20 variant of protocol-tap.
 *
 * <h3>Why two subprojects (tier-2 multi-version)?</h3>
 * Paper's chat-event API drifted between 1.20 and 1.21:
 * <ul>
 *   <li>1.20.x still ships the legacy {@link AsyncPlayerChatEvent} (deprecated
 *       but present).</li>
 *   <li>1.21.x phased it out; the v1_21 sibling listens to
 *       {@code io.papermc.paper.event.player.AsyncChatEvent} instead.</li>
 * </ul>
 * That cross-version delta is small but real: an {@code @ForVersion}
 * adapter would have to import both event types in one source set, and
 * whichever one isn't on the running server's classpath would resolve to a
 * NoClassDefFoundError when the JVM tries to load the adapter. JAR-split
 * sidesteps that — each subproject sees only its own world.
 *
 * <p><strong>Phase E upgrade (real NMS):</strong> swap this plugin's
 * subscription for a packet-listener hook on
 * {@code net.minecraft.server.network.ServerGamePacketListenerImpl} via
 * paperweight-userdev. The same pattern (per-version subproject, distinct
 * imports) carries over — the version delta just becomes much larger and
 * paperweight's mappings are what make it tractable.
 */
@CloudPlugin(
        name = "ProtocolTapPaperV1_20",
        version = "1.0.0",
        description = "Paper 1.20 protocol observer — JAR-split tier-2 demo (legacy AsyncPlayerChatEvent).",
        authors = {"PrexorCloud"})
public final class ProtocolTapPaperV1_20Plugin extends CloudPluginBase implements Listener {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final AtomicLong chatPacketCount = new AtomicLong();
    private CloudPluginContext ctx;

    @Override
    public void onEnable(CloudPluginContext ctx) {
        this.ctx = ctx;
        Bukkit.getPluginManager().registerEvents(this, getPlugin());
        ctx.logger()
                .info("ProtocolTapPaperV1_20 enabled — observing legacy AsyncPlayerChatEvent on instance "
                        + ctx.self().instanceId());
        ctx.scheduler().runAtFixedRate(Duration.ofSeconds(15), Duration.ofSeconds(15), this::flush);
    }

    @Override
    public void onDisable() {
        flush();
        ctx = null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @SuppressWarnings("deprecation") // legacy 1.20 event is the whole teaching point
    public void onChat(AsyncPlayerChatEvent event) {
        chatPacketCount.incrementAndGet();
    }

    private void flush() {
        if (ctx == null) return;
        long count = chatPacketCount.getAndSet(0);
        if (count == 0) return;
        try {
            String body = MAPPER.writeValueAsString(new ObservePayload(PluginEnv.group(), "AsyncPlayerChat", count));
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(PluginEnv.controllerUrl() + "/api/v1/modules/protocol-tap/observe"))
                    .header("Authorization", "Bearer " + PluginEnv.pluginToken())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                ctx.logger().warning("protocol-tap flush: HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            ctx.logger().warning("protocol-tap flush failed: " + e.getMessage());
        }
    }

    /** {@link CloudPluginBase#getPlugin()} returns the underlying JavaPlugin handle for event registration. */
    private org.bukkit.plugin.java.JavaPlugin getPlugin() {
        return (org.bukkit.plugin.java.JavaPlugin) Bukkit.getPluginManager().getPlugin("ProtocolTapPaperV1_20");
    }

    private record ObservePayload(String group, String packetType, long count) {}
}
