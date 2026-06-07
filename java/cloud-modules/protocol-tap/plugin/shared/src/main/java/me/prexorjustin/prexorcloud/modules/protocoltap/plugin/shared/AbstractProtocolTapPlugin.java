package me.prexorjustin.prexorcloud.modules.protocoltap.plugin.shared;

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

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared base for all protocol-tap plugin variants. The three siblings (folia,
 * paper v1_20, paper v1_21) only differ in which chat-event API they subscribe
 * to and in their plugin metadata; counter management, periodic flush, and the
 * HTTP send live here.
 *
 * <p>Subclasses:
 * <ol>
 *   <li>Register their event listener inside {@link #onEnable(CloudPluginContext)}
 *       (call {@code super.onEnable(ctx)} first, then attach the platform-specific
 *       {@code Listener}).</li>
 *   <li>Call {@link #recordPacket()} from each {@code @EventHandler} method.</li>
 *   <li>Implement {@link #packetTypeName()} for the observation payload.</li>
 * </ol>
 *
 * <p>The flush uses {@link HttpClient#sendAsync(HttpRequest, HttpResponse.BodyHandler)}
 * so the scheduler tick never blocks on the HTTP round-trip — production-grade
 * modules must not stall the host process's scheduler thread.
 */
public abstract class AbstractProtocolTapPlugin extends CloudPluginBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final AtomicLong packetCount = new AtomicLong();
    protected CloudPluginContext ctx;

    /** Stable display name for the {@code packetType} field of the observation payload. */
    protected abstract String packetTypeName();

    @Override
    public void onEnable(CloudPluginContext ctx) {
        this.ctx = ctx;
        ctx.scheduler().runAtFixedRate(Duration.ofSeconds(15), Duration.ofSeconds(15), this::flush);
    }

    @Override
    public void onDisable() {
        flush();
        ctx = null;
    }

    /** Increment the observation counter. Subclasses call this from their {@code @EventHandler}. */
    protected final void recordPacket() {
        packetCount.incrementAndGet();
    }

    private void flush() {
        if (ctx == null) return;
        long count = packetCount.getAndSet(0);
        if (count == 0) return;
        final CloudPluginContext snapshot = ctx;
        try {
            String body = MAPPER.writeValueAsString(new ObservePayload(PluginEnv.group(), packetTypeName(), count));
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(PluginEnv.controllerUrl() + "/api/v1/modules/protocol-tap/observe"))
                    .header("Authorization", "Bearer " + PluginEnv.pluginToken())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
                if (error != null) {
                    snapshot.logger().warning("protocol-tap flush failed: " + error.getMessage());
                } else if (response.statusCode() / 100 != 2) {
                    snapshot.logger().warning("protocol-tap flush: HTTP " + response.statusCode());
                }
            });
        } catch (Exception e) {
            snapshot.logger().warning("protocol-tap flush failed: " + e.getMessage());
        }
    }

    private record ObservePayload(String group, String packetType, long count) {}
}
