package me.prexorjustin.prexorcloud.api.event;

/**
 * Handle returned when subscribing to an event. Call {@link #unsubscribe()} to
 * stop receiving events.
 */
public interface EventSubscription {

    /** Removes this handler from the event bus. */
    void unsubscribe();
}
