package me.prexorjustin.prexorcloud.modules.discord.platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;
import me.prexorjustin.prexorcloud.api.event.EventBus;
import me.prexorjustin.prexorcloud.api.event.EventSubscription;
import me.prexorjustin.prexorcloud.api.event.events.DeploymentCompletedEvent;
import me.prexorjustin.prexorcloud.api.event.events.DeploymentCreatedEvent;
import me.prexorjustin.prexorcloud.api.event.events.GroupCrashLoopEvent;
import me.prexorjustin.prexorcloud.api.event.events.InstanceCrashedEvent;
import me.prexorjustin.prexorcloud.api.event.events.InstanceStateChangedEvent;
import me.prexorjustin.prexorcloud.api.event.events.NodeConnectedEvent;
import me.prexorjustin.prexorcloud.api.event.events.NodeDisconnectedEvent;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleContext;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModule;
import me.prexorjustin.prexorcloud.modules.discord.data.DiscordTarget;
import me.prexorjustin.prexorcloud.modules.discord.data.DiscordTargetRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

/**
 * Forwards alertable cloud events to Discord channels as rich embeds via Discord incoming webhooks.
 * Sibling to {@code webhook-alerts}: same event sources, but Discord-specific embed formatting
 * (colour-coded by severity, structured fields) instead of a generic JSON body.
 *
 * <p>Scope: outbound only (controller events → Discord). The bidirectional half — Discord
 * slash-commands and MC-chat ↔ Discord — needs a gateway bot connection (JDA) and is a separate
 * follow-up; this module deliberately stays a stateless webhook poster.
 */
public final class DiscordBridgeModule implements PlatformModule {

    /** Event-class → stable wire name (matches webhook-alerts so target filters are portable). */
    private static final Map<Class<? extends CloudEvent>, String> EVENT_TYPES = Map.ofEntries(
            Map.entry(NodeConnectedEvent.class, "node_connected"),
            Map.entry(NodeDisconnectedEvent.class, "node_disconnected"),
            Map.entry(InstanceStateChangedEvent.class, "instance_state_changed"),
            Map.entry(InstanceCrashedEvent.class, "instance_crashed"),
            Map.entry(GroupCrashLoopEvent.class, "crash_loop"),
            Map.entry(DeploymentCreatedEvent.class, "deployment_created"),
            Map.entry(DeploymentCompletedEvent.class, "deployment_completed"));

    private DiscordTargetRepository repository;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;
    private Logger logger;
    private List<EventSubscription> subscriptions = List.of();

    @Override
    public void onLoad(ModuleContext context) {
        this.repository = new DiscordTargetRepository(context.requireMongoStorage());
        this.httpClient = context.httpClient();
        this.objectMapper = context.json();
        this.logger = context.logger();
    }

    @Override
    public void onStart(ModuleContext context) {
        EventBus bus = context.events();
        List<EventSubscription> subs = new ArrayList<>();
        for (Class<? extends CloudEvent> type : EVENT_TYPES.keySet()) {
            subs.add(bus.subscribe(type, this::onEvent));
        }
        this.subscriptions = List.copyOf(subs);
        logger.debug(
                "Discord bridge active ({} target(s) configured)",
                repository.findAll().size());
    }

    @Override
    public void onStop(ModuleContext context) {
        for (EventSubscription sub : subscriptions) {
            sub.unsubscribe();
        }
        subscriptions = List.of();
    }

    @Override
    public void onUnload(ModuleContext context) {
        repository = null;
        httpClient = null;
        objectMapper = null;
        logger = null;
    }

    private void onEvent(CloudEvent event) {
        if (repository == null) return;
        String wireName =
                EVENT_TYPES.getOrDefault(event.getClass(), event.getClass().getSimpleName());
        String timestamp = Instant.now().toString();
        for (DiscordTarget target : repository.findAll()) {
            if (!shouldFire(target, wireName)) continue;
            Map<String, Object> payload = DiscordEmbeds.payload(target.username(), wireName, event, timestamp);
            fire(target.url(), payload);
        }
    }

    private static boolean shouldFire(DiscordTarget target, String wireName) {
        return target.events().isEmpty() || target.events().contains(wireName);
    }

    private void fire(String url, Map<String, Object> payload) {
        try {
            String body = objectMapper.writeValueAsString(payload);
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "PrexorCloud-DiscordBridge/1.0")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            httpClient
                    .sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((resp, err) -> {
                        if (err != null) {
                            logger.warn("Discord webhook failed for {}: {}", url, err.getMessage());
                        } else if (resp.statusCode() >= 400) {
                            logger.warn("Discord webhook {} returned status {}", url, resp.statusCode());
                        }
                    });
        } catch (Exception e) {
            logger.warn("Failed to send Discord webhook to {}: {}", url, e.getMessage());
        }
    }
}
