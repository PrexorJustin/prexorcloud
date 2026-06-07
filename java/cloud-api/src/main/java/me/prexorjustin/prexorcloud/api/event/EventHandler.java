package me.prexorjustin.prexorcloud.api.event;

/**
 * Handles a specific type of cloud event.
 *
 * @param <T>
 *            the event type
 */
@FunctionalInterface
public interface EventHandler<T extends CloudEvent> {

    void handle(T event);
}
