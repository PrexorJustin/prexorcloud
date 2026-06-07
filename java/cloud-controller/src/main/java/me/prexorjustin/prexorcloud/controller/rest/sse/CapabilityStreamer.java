package me.prexorjustin.prexorcloud.controller.rest.sse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;
import me.prexorjustin.prexorcloud.api.event.EventSubscription;
import me.prexorjustin.prexorcloud.api.event.events.CapabilityProviderChangedEvent;
import me.prexorjustin.prexorcloud.api.event.events.CapabilityRegisteredEvent;
import me.prexorjustin.prexorcloud.api.event.events.CapabilityUnregisteredEvent;
import me.prexorjustin.prexorcloud.controller.event.EventBus;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.config.RoutesConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dedicated SSE endpoint at {@code GET /api/v1/modules/platform/capabilities/stream}
 * that forwards only the three capability lifecycle events. The dashboard's
 * {@code useCapability} composable subscribes here so it doesn't have to
 * filter the firehose at {@code /api/v1/events/stream}.
 *
 * <p>Replay/sequence machinery is intentionally absent — capability changes
 * are infrequent and the dashboard refetches the registry on (re)connect via
 * the regular {@code GET /api/v1/modules/platform/capabilities} endpoint.
 */
public final class CapabilityStreamer {

    private static final Logger logger = LoggerFactory.getLogger(CapabilityStreamer.class);
    private static final List<Class<? extends CloudEvent>> EVENT_TYPES = List.of(
            CapabilityRegisteredEvent.class, CapabilityUnregisteredEvent.class, CapabilityProviderChangedEvent.class);

    private final EventBus eventBus;
    private final SseTicketManager ticketManager;
    private final ObjectMapper objectMapper;

    public CapabilityStreamer(EventBus eventBus, SseTicketManager ticketManager, ObjectMapper objectMapper) {
        this.eventBus = eventBus;
        this.ticketManager = ticketManager;
        this.objectMapper = objectMapper;
    }

    public void register(RoutesConfig routes) {
        routes.sse("/api/v1/modules/platform/capabilities/stream", client -> {
            String ticket = client.ctx().queryParam("ticket");
            if (ticketManager.validate(ticket) == null) {
                client.sendEvent("error", "{\"message\":\"Unauthorized\"}");
                client.close();
                return;
            }

            client.sendEvent("connected", "{\"message\":\"Connected to capability stream\"}");

            List<EventSubscription> subscriptions = new java.util.ArrayList<>(EVENT_TYPES.size());
            for (Class<? extends CloudEvent> type : EVENT_TYPES) {
                subscriptions.add(subscribe(client, type));
            }

            client.keepAlive();
            client.onClose(() -> subscriptions.forEach(EventSubscription::unsubscribe));
        });
    }

    private <E extends CloudEvent> EventSubscription subscribe(io.javalin.http.sse.SseClient client, Class<E> type) {
        return eventBus.subscribe(type, event -> {
            try {
                Map<String, Object> envelope = new LinkedHashMap<>(
                        objectMapper.convertValue(event, new TypeReference<Map<String, Object>>() {}));
                envelope.put("type", event.type());
                client.sendEvent("message", envelope);
            } catch (Exception e) {
                logger.debug("Capability SSE send failed for {}: {}", event.type(), e.getMessage());
                client.close();
            }
        });
    }
}
