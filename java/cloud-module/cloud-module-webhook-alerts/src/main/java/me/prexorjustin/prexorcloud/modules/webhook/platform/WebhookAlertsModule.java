package me.prexorjustin.prexorcloud.modules.webhook.platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import me.prexorjustin.prexorcloud.modules.webhook.data.WebhookConfig;
import me.prexorjustin.prexorcloud.modules.webhook.data.WebhookRepository;
import org.slf4j.Logger;

/**
 * Subscribes to alertable cloud events and POSTs JSON payloads to webhook
 * endpoints persisted in the module's Mongo storage.
 */
public final class WebhookAlertsModule implements PlatformModule {

    /** Event-class → stable wire name surfaced in the {@code "event"} field of the JSON payload. */
    private static final Map<Class<? extends CloudEvent>, String> EVENT_TYPES = Map.ofEntries(
            Map.entry(NodeConnectedEvent.class, "node_connected"),
            Map.entry(NodeDisconnectedEvent.class, "node_disconnected"),
            Map.entry(InstanceStateChangedEvent.class, "instance_state_changed"),
            Map.entry(InstanceCrashedEvent.class, "instance_crashed"),
            Map.entry(GroupCrashLoopEvent.class, "crash_loop"),
            Map.entry(DeploymentCreatedEvent.class, "deployment_created"),
            Map.entry(DeploymentCompletedEvent.class, "deployment_completed"));

    private WebhookRepository repository;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;
    private Logger logger;
    private List<EventSubscription> subscriptions = List.of();

    @Override
    public void onLoad(ModuleContext context) {
        this.repository = new WebhookRepository(context.requireMongoStorage());
        this.httpClient = context.httpClient();
        this.objectMapper = context.json();
        this.logger = context.logger();
    }

    @Override
    public void onStart(ModuleContext context) {
        EventBus bus = context.events();
        List<EventSubscription> subs = new ArrayList<>();
        subs.add(bus.subscribe(NodeConnectedEvent.class, this::onEvent));
        subs.add(bus.subscribe(NodeDisconnectedEvent.class, this::onEvent));
        subs.add(bus.subscribe(InstanceStateChangedEvent.class, this::onEvent));
        subs.add(bus.subscribe(InstanceCrashedEvent.class, this::onEvent));
        subs.add(bus.subscribe(GroupCrashLoopEvent.class, this::onEvent));
        subs.add(bus.subscribe(DeploymentCreatedEvent.class, this::onEvent));
        subs.add(bus.subscribe(DeploymentCompletedEvent.class, this::onEvent));
        this.subscriptions = List.copyOf(subs);
        logger.debug("Webhook alerting active ({} configured)", repository.findAll().size());
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
        String wireName = EVENT_TYPES.getOrDefault(
                event.getClass(), event.getClass().getSimpleName());
        Map<String, Object> payload = buildPayload(wireName, event);
        for (WebhookConfig webhook : repository.findAll()) {
            if (!shouldFire(webhook, wireName)) continue;
            fire(webhook.url(), payload);
        }
    }

    private static boolean shouldFire(WebhookConfig webhook, String wireName) {
        return webhook.events().isEmpty() || webhook.events().contains(wireName);
    }

    private Map<String, Object> buildPayload(String eventName, CloudEvent event) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("event", eventName);
        payload.put("timestamp", Instant.now().toString());

        switch (event) {
            case NodeConnectedEvent e -> payload.put("data", Map.of("nodeId", e.nodeId(), "sessionId", e.sessionId()));
            case NodeDisconnectedEvent e -> payload.put("data", Map.of("nodeId", e.nodeId(), "reason", e.reason()));
            case InstanceStateChangedEvent e ->
                payload.put(
                        "data",
                        Map.of(
                                "instanceId",
                                e.instanceId(),
                                "group",
                                e.group(),
                                "nodeId",
                                e.nodeId(),
                                "oldState",
                                e.oldState().name(),
                                "newState",
                                e.newState().name()));
            case InstanceCrashedEvent e ->
                payload.put(
                        "data",
                        Map.of(
                                "instanceId",
                                e.instanceId(),
                                "group",
                                e.group(),
                                "nodeId",
                                e.nodeId(),
                                "exitCode",
                                e.exitCode(),
                                "classification",
                                e.classification(),
                                "uptimeMs",
                                e.uptimeMs()));
            case GroupCrashLoopEvent e ->
                payload.put(
                        "data",
                        Map.of(
                                "group",
                                e.group(),
                                "crashCount",
                                e.crashCount(),
                                "windowStart",
                                e.windowStart().toString()));
            case DeploymentCreatedEvent e ->
                payload.put("data", Map.of("group", e.groupName(), "revision", e.revision(), "strategy", e.strategy()));
            case DeploymentCompletedEvent e ->
                payload.put("data", Map.of("group", e.groupName(), "revision", e.revision(), "outcome", e.outcome()));
            default -> payload.put("data", Map.of());
        }
        return payload;
    }

    private void fire(String url, Map<String, Object> payload) {
        try {
            String body = objectMapper.writeValueAsString(payload);
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "PrexorCloud-Webhook/1.0")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            httpClient
                    .sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((resp, err) -> {
                        if (err != null) {
                            logger.warn("Webhook failed for {}: {}", url, err.getMessage());
                        } else if (resp.statusCode() >= 400) {
                            logger.warn("Webhook {} returned status {}", url, resp.statusCode());
                        }
                    });
        } catch (Exception e) {
            logger.warn("Failed to send webhook to {}: {}", url, e.getMessage());
        }
    }
}
