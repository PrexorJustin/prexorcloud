package me.prexorjustin.prexorcloud.daemon.event;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;
import me.prexorjustin.prexorcloud.api.event.CustomCloudEvent;
import me.prexorjustin.prexorcloud.api.event.EventBus;
import me.prexorjustin.prexorcloud.api.event.EventHandler;
import me.prexorjustin.prexorcloud.api.event.EventSubscription;
import me.prexorjustin.prexorcloud.api.event.EventSubscriptionBuilder;
import me.prexorjustin.prexorcloud.common.io.ObjectMappers;
import me.prexorjustin.prexorcloud.daemon.grpc.DaemonGrpcClient;
import me.prexorjustin.prexorcloud.protocol.DaemonMessage;
import me.prexorjustin.prexorcloud.protocol.EventSubscribe;
import me.prexorjustin.prexorcloud.protocol.EventUnsubscribe;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Daemon-side {@link EventBus} with subscribe-registration to the controller.
 *
 * <p>Local pub/sub mirrors the controller-side EventBus shape. The novel piece is
 * the controller-side registration: when a daemon module subscribes to a
 * {@code Class<? extends CloudEvent>} and that class has no other local subscribers
 * yet, the bus sends an {@code EventSubscribe} to the controller so future events
 * of that type are forwarded back as {@code ModuleEvent}. When the last subscriber
 * for that class unsubscribes, the bus sends {@code EventUnsubscribe} so the
 * controller-side EventBus stops forwarding events nobody on this daemon cares about.
 *
 * <p>The bus also re-registers the full set of currently-subscribed event types on
 * every reconnect via {@link #onReconnect()} so a brief gRPC blip does not leave the
 * controller out of sync.
 */
public final class DaemonEventBus implements EventBus {

    private static final Logger logger = LoggerFactory.getLogger(DaemonEventBus.class);

    private final DaemonGrpcClient client;
    private final ObjectMapper json;

    private final Map<Class<? extends CloudEvent>, List<EventHandler<?>>> classHandlers = new ConcurrentHashMap<>();
    private final Map<Class<? extends CloudEvent>, AtomicInteger> classRefcount = new ConcurrentHashMap<>();
    private final Map<String, List<EventHandler<CloudEvent>>> typeHandlers = new ConcurrentHashMap<>();
    private final List<EventHandler<CloudEvent>> wildcardHandlers = new CopyOnWriteArrayList<>();

    public DaemonEventBus(DaemonGrpcClient client) {
        this.client = Objects.requireNonNull(client, "client");
        this.json = ObjectMappers.standard();
    }

    @Override
    public <T extends CloudEvent> EventSubscriptionBuilder<T> on(Class<T> eventType) {
        return new Builder<>(eventType);
    }

    @Override
    public <T extends CloudEvent> EventSubscription subscribe(Class<T> eventType, EventHandler<T> handler) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(handler, "handler");
        var list = classHandlers.computeIfAbsent(eventType, _ -> new CopyOnWriteArrayList<>());
        list.add(handler);
        AtomicInteger count = classRefcount.computeIfAbsent(eventType, _ -> new AtomicInteger(0));
        if (count.getAndIncrement() == 0) {
            sendSubscribe(eventType.getName());
        }
        return () -> removeClassSubscriber(eventType, handler);
    }

    @Override
    public EventSubscription subscribeByType(String type, EventHandler<CustomCloudEvent> handler) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        @SuppressWarnings("unchecked")
        EventHandler<CloudEvent> wrapper = e -> {
            if (e instanceof CustomCloudEvent custom) {
                handler.handle(custom);
            }
        };
        var list = typeHandlers.computeIfAbsent(type, _ -> new CopyOnWriteArrayList<>());
        list.add(wrapper);
        return () -> list.remove(wrapper);
    }

    @Override
    public EventSubscription subscribeAll(EventHandler<CloudEvent> handler) {
        Objects.requireNonNull(handler, "handler");
        wildcardHandlers.add(handler);
        return () -> wildcardHandlers.remove(handler);
    }

    @Override
    public void publish(CloudEvent event) {
        Objects.requireNonNull(event, "event");
        dispatch(event);
    }

    /**
     * Decode a {@code ModuleEvent} payload pushed from the controller and publish it to
     * matching local subscribers. Called by the daemon's {@code MessageDispatcher} on every
     * inbound {@code ModuleEvent}.
     */
    public void publishFromController(String eventType, byte[] payloadJson) {
        if (eventType == null || eventType.isBlank() || payloadJson == null) {
            return;
        }
        Class<? extends CloudEvent> clazz = resolveEventClass(eventType);
        if (clazz == null) {
            return;
        }
        CloudEvent event;
        try {
            event = json.readValue(payloadJson, clazz);
        } catch (Exception e) {
            logger.warn("failed to deserialize controller event {}: {}", eventType, e.getMessage());
            return;
        }
        dispatch(event);
    }

    /**
     * Reconnect listener: re-send {@code EventSubscribe} for every currently-subscribed
     * class so the controller-side {@code DaemonEventForwarder} rebuilds its per-daemon
     * subscription map after a stream loss.
     */
    public void onReconnect() {
        Set<String> currentTypes = new LinkedHashSet<>();
        for (Map.Entry<Class<? extends CloudEvent>, AtomicInteger> entry : classRefcount.entrySet()) {
            if (entry.getValue().get() > 0) {
                currentTypes.add(entry.getKey().getName());
            }
        }
        if (currentTypes.isEmpty()) {
            return;
        }
        client.sendMessage(DaemonMessage.newBuilder()
                .setEventSubscribe(EventSubscribe.newBuilder().addAllEventTypes(currentTypes))
                .build());
        logger.debug("re-registered {} controller-bus event subscriptions on reconnect", currentTypes.size());
    }

    private void dispatch(CloudEvent event) {
        Class<? extends CloudEvent> eventClass = event.getClass();
        var handlers = classHandlers.get(eventClass);
        if (handlers != null) {
            for (EventHandler<?> handler : handlers) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                EventHandler<CloudEvent> typed = (EventHandler) handler;
                runHandler(typed, event);
            }
        }
        if (event instanceof CustomCloudEvent custom) {
            var byType = typeHandlers.get(custom.type());
            if (byType != null) {
                for (EventHandler<CloudEvent> handler : byType) {
                    runHandler(handler, event);
                }
            }
        }
        for (EventHandler<CloudEvent> handler : wildcardHandlers) {
            runHandler(handler, event);
        }
    }

    private static void runHandler(EventHandler<CloudEvent> handler, CloudEvent event) {
        Thread.startVirtualThread(() -> {
            try {
                handler.handle(event);
            } catch (Exception e) {
                logger.warn("event handler threw for {}: {}", event.getClass().getName(), e.getMessage());
            }
        });
    }

    private void removeClassSubscriber(Class<? extends CloudEvent> eventType, EventHandler<?> handler) {
        var list = classHandlers.get(eventType);
        if (list == null) {
            return;
        }
        if (!list.remove(handler)) {
            return;
        }
        AtomicInteger count = classRefcount.get(eventType);
        if (count != null && count.decrementAndGet() <= 0) {
            sendUnsubscribe(eventType.getName());
        }
    }

    private void sendSubscribe(String eventType) {
        client.sendMessage(DaemonMessage.newBuilder()
                .setEventSubscribe(EventSubscribe.newBuilder().addEventTypes(eventType))
                .build());
    }

    private void sendUnsubscribe(String eventType) {
        client.sendMessage(DaemonMessage.newBuilder()
                .setEventUnsubscribe(EventUnsubscribe.newBuilder().addEventTypes(eventType))
                .build());
    }

    @SuppressWarnings("unchecked")
    private Class<? extends CloudEvent> resolveEventClass(String eventType) {
        try {
            // Use the bus's classloader so cloud-api types and any module-shipped CloudEvent
            // subclasses (resolvable via the daemon's app classloader) are visible.
            Class<?> clazz = Class.forName(eventType, false, DaemonEventBus.class.getClassLoader());
            if (!CloudEvent.class.isAssignableFrom(clazz)) {
                logger.warn("inbound event type {} does not implement CloudEvent", eventType);
                return null;
            }
            return (Class<? extends CloudEvent>) clazz;
        } catch (ClassNotFoundException _) {
            logger.warn("inbound event type {} is not on the daemon's classpath", eventType);
            return null;
        }
    }

    private final class Builder<T extends CloudEvent> implements EventSubscriptionBuilder<T> {

        private final Class<T> eventType;
        private Predicate<T> predicate = _ -> true;

        Builder(Class<T> eventType) {
            this.eventType = eventType;
        }

        @Override
        public EventSubscriptionBuilder<T> filter(Predicate<T> next) {
            Predicate<T> current = predicate;
            this.predicate = e -> current.test(e) && next.test(e);
            return this;
        }

        @Override
        public EventSubscription subscribe(EventHandler<T> handler) {
            Predicate<T> filter = predicate;
            return DaemonEventBus.this.subscribe(eventType, e -> {
                if (filter.test(e)) {
                    handler.handle(e);
                }
            });
        }
    }
}
