package me.prexorjustin.prexorcloud.api.event;

/**
 * Base interface for all cloud events. Every event has a type string used for
 * dynamic dispatch and SSE streaming.
 *
 * <p>
 * First-class events are declared as records in the {@code events} sub-package.
 * For runtime-defined or module-specific events, use {@link CustomCloudEvent}.
 * </p>
 */
public interface CloudEvent {

    /**
     * Event type identifier (e.g. {@code "PLAYER_CONNECTED"},
     * {@code "INSTANCE_CRASHED"}). Built-in events use SCREAMING_SNAKE_CASE. Custom
     * module events use {@code "MODULE:ACTION"} format.
     */
    String type();
}
