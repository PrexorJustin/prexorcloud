package me.prexorjustin.prexorcloud.controller.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Predicate;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;
import me.prexorjustin.prexorcloud.api.event.CustomCloudEvent;
import me.prexorjustin.prexorcloud.api.event.EventHandler;
import me.prexorjustin.prexorcloud.api.event.EventSubscription;
import me.prexorjustin.prexorcloud.api.event.EventSubscriptionBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller-side {@link me.prexorjustin.prexorcloud.api.event.EventBus} implementation
 * with three dispatch mechanisms:
 * <ol>
 * <li><b>Class-based</b> — {@link #subscribe(Class, EventHandler)} for typed
 * events</li>
 * <li><b>Type-string</b> — {@link #subscribeByType(String, EventHandler)} for
 * dynamic event types</li>
 * <li><b>Wildcard</b> — {@link #subscribeAll(EventHandler)} for all events
 * (SSE, logging)</li>
 * </ol>
 * All handlers run on virtual threads using Structured Concurrency (JEP 505).
 *
 * <p>
 * This is the single in-process implementation of the cloud-api event-bus
 * contract: plugin-side, module-side, and controller-internal code all subscribe
 * through the same {@link me.prexorjustin.prexorcloud.api.event.EventBus} interface.
 * </p>
 */
public final class EventBus implements me.prexorjustin.prexorcloud.api.event.EventBus {

    private static final Logger logger = LoggerFactory.getLogger(EventBus.class);

    private final Map<Class<? extends CloudEvent>, List<EventHandler<?>>> classHandlers = new ConcurrentHashMap<>();
    private final Map<String, List<EventHandler<CloudEvent>>> typeHandlers = new ConcurrentHashMap<>();
    private final List<EventHandler<CloudEvent>> wildcardHandlers = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public <T extends CloudEvent> EventSubscription subscribe(Class<T> eventType, EventHandler<T> handler) {
        var list = classHandlers.computeIfAbsent(eventType, _ -> new CopyOnWriteArrayList<>());
        list.add(handler);
        return () -> list.remove(handler);
    }

    /** @deprecated callers should hold the {@link EventSubscription} from {@link #subscribe} and call {@link EventSubscription#unsubscribe()}. Retained for the legacy {@code ConsoleStreamer} caller until its rewrite. */
    @Deprecated
    public <T extends CloudEvent> void unsubscribe(Class<T> eventType, EventHandler<T> handler) {
        var list = classHandlers.get(eventType);
        if (list != null) list.remove(handler);
    }

    @Override
    public EventSubscription subscribeByType(String type, EventHandler<CustomCloudEvent> handler) {
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
        wildcardHandlers.add(handler);
        return () -> wildcardHandlers.remove(handler);
    }

    @Override
    public <T extends CloudEvent> EventSubscriptionBuilder<T> on(Class<T> eventType) {
        return new Builder<>(eventType);
    }

    /** Shut down the event-bus executor. Should be called during controller shutdown. */
    public void shutdown() {
        executor.shutdown();
    }

    @SuppressWarnings("unchecked")
    @Override
    public void publish(CloudEvent event) {
        var byClass = classHandlers.get(event.getClass());
        var byType = typeHandlers.get(event.type());

        boolean hasClassHandlers = byClass != null && !byClass.isEmpty();
        boolean hasTypeHandlers = byType != null && !byType.isEmpty();
        boolean hasWildcard = !wildcardHandlers.isEmpty();

        if (!hasClassHandlers && !hasTypeHandlers && !hasWildcard) return;

        executor.execute(() -> {
            try (var scope = StructuredTaskScope.open()) {
                if (hasClassHandlers) {
                    for (var handler : byClass) {
                        scope.fork(() -> {
                            try {
                                ((EventHandler<CloudEvent>) handler).handle(event);
                            } catch (Throwable e) {
                                logger.error("Event handler failed for {}: {}", event.type(), e.getMessage(), e);
                            }
                            return null;
                        });
                    }
                }
                if (hasTypeHandlers) {
                    for (var handler : byType) {
                        scope.fork(() -> {
                            try {
                                handler.handle(event);
                            } catch (Throwable e) {
                                logger.error("Type handler failed for {}: {}", event.type(), e.getMessage(), e);
                            }
                            return null;
                        });
                    }
                }
                if (hasWildcard) {
                    for (var handler : wildcardHandlers) {
                        scope.fork(() -> {
                            try {
                                handler.handle(event);
                            } catch (Throwable e) {
                                logger.error("Wildcard handler failed for {}: {}", event.type(), e.getMessage(), e);
                            }
                            return null;
                        });
                    }
                }
                scope.join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                logger.error("EventBus publish failed for {}: {}", event.type(), e.getMessage(), e);
            }
        });
    }

    private final class Builder<T extends CloudEvent> implements EventSubscriptionBuilder<T> {

        private final Class<T> type;
        private Predicate<T> filter;

        Builder(Class<T> type) {
            this.type = type;
        }

        @Override
        public EventSubscriptionBuilder<T> filter(Predicate<T> predicate) {
            this.filter = filter == null ? predicate : filter.and(predicate);
            return this;
        }

        @Override
        public EventSubscription subscribe(EventHandler<T> handler) {
            EventHandler<T> wrapped = filter == null
                    ? handler
                    : event -> {
                        if (filter.test(event)) handler.handle(event);
                    };
            return EventBus.this.subscribe(type, wrapped);
        }
    }
}
