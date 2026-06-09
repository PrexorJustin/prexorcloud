package me.prexorjustin.prexorcloud.api.event;

/**
 * Event bus for subscribing to and publishing cloud events. Used by plugin
 * developers via {@link me.prexorjustin.prexorcloud.api.plugin.CloudPluginContext}
 * and by platform modules through controller-provided module bridges.
 *
 * <h3>Fluent subscription (recommended)</h3>
 *
 * <pre>{@code
 * events.on(PlayerConnectedEvent.class).filter(e -> e.group().equals("lobby"))
 *         .subscribe(e -> log.info("{} joined lobby", e.name()));
 * }</pre>
 *
 * <h3>Direct subscription</h3>
 *
 * <pre>{@code
 * EventSubscription sub = events.subscribe(PlayerConnectedEvent.class, e -> { ... });
 * // later:
 * sub.unsubscribe();
 * }</pre>
 */
public interface EventBus {

    /**
     * Begin a fluent subscription builder for the given event type.
     *
     * @param eventType
     *            the event class to subscribe to
     * @return a builder that allows attaching filters before subscribing
     */
    <T extends CloudEvent> EventSubscriptionBuilder<T> on(Class<T> eventType);

    /**
     * Subscribe to events of the given type without a filter.
     *
     * @param eventType
     *            the event class to subscribe to
     * @param handler
     *            callback invoked for each event
     * @return a subscription handle for cancellation
     */
    <T extends CloudEvent> EventSubscription subscribe(Class<T> eventType, EventHandler<T> handler);

    /**
     * Subscribe to custom events matching a specific type string. Useful for
     * dynamic event types not known at compile time.
     *
     * @param type
     *            event type string (e.g. {@code "CHAT:MESSAGE"})
     * @param handler
     *            callback invoked for each matching custom event
     * @return a subscription handle for cancellation
     */
    EventSubscription subscribeByType(String type, EventHandler<CustomCloudEvent> handler);

    /**
     * Subscribe to all events regardless of type (catch-all).
     *
     * @param handler
     *            callback invoked for every event
     * @return a subscription handle for cancellation
     */
    EventSubscription subscribeAll(EventHandler<CloudEvent> handler);

    /**
     * Publish an event to all matching subscribers.
     *
     * @param event
     *            the event to publish
     */
    void publish(CloudEvent event);
}
