package me.prexorjustin.prexorcloud.modules.protocoltap.plugin.folia;

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
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Folia variant of protocol-tap.
 *
 * <p>Folia ships only on Paper 1.20+, so the modern Adventure
 * {@link AsyncChatEvent} is always available — no JAR-split needed on this
 * side. Counterpart to the Paper sibling: tier-2 dispatch is only for cases
 * where API drift forces it, and Folia (at the Paper-API level) doesn't
 * have that problem here.
 */
@CloudPlugin(
        name = "ProtocolTapFolia",
        version = "1.0.0",
        description = "Folia protocol observer — single-version on purpose (modern AsyncChatEvent).",
        authors = {"PrexorCloud"})
public final class ProtocolTapFoliaPlugin extends CloudPluginBase implements Listener {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final AtomicLong chatPacketCount = new AtomicLong();
    private CloudPluginContext ctx;

    @Override
    public void onEnable(CloudPluginContext ctx) {
        this.ctx = ctx;
        Bukkit.getPluginManager().registerEvents(this, getPlugin());
        ctx.logger().info("ProtocolTapFolia enabled on instance " + ctx.self().instanceId());
        ctx.scheduler().runAtFixedRate(Duration.ofSeconds(15), Duration.ofSeconds(15), this::flush);
    }

    @Override
    public void onDisable() {
        flush();
        ctx = null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        chatPacketCount.incrementAndGet();
    }

    private void flush() {
        if (ctx == null) return;
        long count = chatPacketCount.getAndSet(0);
        if (count == 0) return;
        try {
            String body = MAPPER.writeValueAsString(new ObservePayload(PluginEnv.group(), "AsyncChat", count));
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

    private org.bukkit.plugin.java.JavaPlugin getPlugin() {
        return (org.bukkit.plugin.java.JavaPlugin) Bukkit.getPluginManager().getPlugin("ProtocolTapFolia");
    }

    private record ObservePayload(String group, String packetType, long count) {}
}
