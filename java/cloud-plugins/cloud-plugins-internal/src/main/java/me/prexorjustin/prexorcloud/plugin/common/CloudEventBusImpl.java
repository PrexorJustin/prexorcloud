package me.prexorjustin.prexorcloud.plugin.common;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;
import me.prexorjustin.prexorcloud.api.event.CustomCloudEvent;
import me.prexorjustin.prexorcloud.api.event.EventBus;
import me.prexorjustin.prexorcloud.api.event.EventHandler;
import me.prexorjustin.prexorcloud.api.event.EventSubscription;
import me.prexorjustin.prexorcloud.api.event.EventSubscriptionBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe {@link EventBus} implementation used by all platform adapters.
 */
public final class CloudEventBusImpl implements EventBus {

    private static final Logger logger = LoggerFactory.getLogger(CloudEventBusImpl.class);

    @SuppressWarnings("rawtypes")
    private final Map<Class<? extends CloudEvent>, List<HandlerEntry>> typedHandlers = new ConcurrentHashMap<>();

    private final Map<String, List<HandlerEntry<CustomCloudEvent>>> stringHandlers = new ConcurrentHashMap<>();
    private final List<HandlerEntry<CloudEvent>> catchAllHandlers = new CopyOnWriteArrayList<>();

    @Override
    public <T extends CloudEvent> EventSubscriptionBuilder<T> on(Class<T> eventType) {
        return new SubscriptionBuilderImpl<>(eventType);
    }

    @Override
    public <T extends CloudEvent> EventSubscription subscribe(Class<T> eventType, EventHandler<T> handler) {
        return on(eventType).subscribe(handler);
    }

    @Override
    public EventSubscription subscribeByType(String type, EventHandler<CustomCloudEvent> handler) {
        HandlerEntry<CustomCloudEvent> entry = new HandlerEntry<>(handler, null);
        stringHandlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(entry);
        return () -> {
            List<HandlerEntry<CustomCloudEvent>> list = stringHandlers.get(type);
            if (list != null) list.remove(entry);
        };
    }

    @Override
    public EventSubscription subscribeAll(EventHandler<CloudEvent> handler) {
        HandlerEntry<CloudEvent> entry = new HandlerEntry<>(handler, null);
        catchAllHandlers.add(entry);
        return () -> catchAllHandlers.remove(entry);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void publish(CloudEvent event) {
        // Type-based dispatch
        List<HandlerEntry> list = typedHandlers.get(event.getClass());
        if (list != null) {
            for (HandlerEntry entry : list) {
                invokeEntry(entry, event);
            }
        }
        // String-type dispatch — only for CustomCloudEvent
        if (event instanceof CustomCloudEvent customEvent) {
            List<HandlerEntry<CustomCloudEvent>> stringList = stringHandlers.get(event.type());
            if (stringList != null) {
                for (HandlerEntry<CustomCloudEvent> entry : stringList) {
                    invokeEntry(entry, customEvent);
                }
            }
        }
        // Catch-all
        for (HandlerEntry<CloudEvent> entry : catchAllHandlers) {
            invokeEntry(entry, event);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void invokeEntry(HandlerEntry entry, CloudEvent event) {
        if (entry.cancelled.get()) return;
        try {
            if (entry.filter == null || entry.filter.test(event)) {
                entry.handler.handle(event);
            }
        } catch (Exception e) {
            logger.warn(
                    "Exception in cloud event handler for {}: {}",
                    event.getClass().getSimpleName(),
                    e.getMessage(),
                    e);
        }
    }

    // ── Inner types ────────────────────────────────────────────────────────────

    private static final class HandlerEntry<T extends CloudEvent> {

        final EventHandler<T> handler;
        final Predicate<T> filter;
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        HandlerEntry(EventHandler<T> handler, Predicate<T> filter) {
            this.handler = handler;
            this.filter = filter;
        }
    }

    private final class SubscriptionBuilderImpl<T extends CloudEvent> implements EventSubscriptionBuilder<T> {

        private final Class<T> eventType;
        private Predicate<T> filter;

        SubscriptionBuilderImpl(Class<T> eventType) {
            this.eventType = eventType;
        }

        @Override
        public EventSubscriptionBuilder<T> filter(Predicate<T> predicate) {
            this.filter = (this.filter == null) ? predicate : this.filter.and(predicate);
            return this;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public EventSubscription subscribe(EventHandler<T> handler) {
            HandlerEntry<T> entry = new HandlerEntry<>(handler, filter);
            typedHandlers
                    .computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                    .add((HandlerEntry) entry);
            return () -> {
                entry.cancelled.set(true);
                List<HandlerEntry> list = typedHandlers.get(eventType);
                if (list != null) list.remove(entry);
            };
        }
    }
}
