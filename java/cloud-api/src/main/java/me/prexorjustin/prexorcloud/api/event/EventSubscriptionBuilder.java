package me.prexorjustin.prexorcloud.api.event;

import java.util.function.Predicate;

/**
 * Fluent builder returned by {@link EventBus#on(Class)} for attaching optional
 * filters before subscribing.
 *
 * <pre>{@code
 * events.on(PlayerConnectedEvent.class).filter(e -> e.group().equals("lobby"))
 *         .subscribe(e -> log.info("{} joined", e.name()));
 * }</pre>
 *
 * @param <T>
 *            the event type
 */
public interface EventSubscriptionBuilder<T extends CloudEvent> {

    /**
     * Attach a predicate filter. Only events matching the predicate will be
     * delivered to the subscriber. Multiple calls to {@code filter} are ANDed.
     *
     * @param predicate
     *            filter condition
     * @return this builder for chaining
     */
    EventSubscriptionBuilder<T> filter(Predicate<T> predicate);

    /**
     * Complete the subscription and start receiving events.
     *
     * @param handler
     *            callback invoked for each matching event
     * @return a handle that can be used to cancel the subscription
     */
    EventSubscription subscribe(EventHandler<T> handler);
}
