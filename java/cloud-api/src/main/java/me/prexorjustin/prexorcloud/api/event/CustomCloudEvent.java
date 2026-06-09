package me.prexorjustin.prexorcloud.api.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Dynamic event type for custom module and plugin events. Enables modules to
 * define their own event types at runtime without modifying the core API.
 *
 * <p>
 * Convention: use {@code "MODULE:ACTION"} format for type strings (e.g.
 * {@code "CHAT:MESSAGE"}, {@code "VOTIFIER:VOTE"}).
 * </p>
 *
 * @param type
 *            event type identifier (use {@code "MODULE:ACTION"} format)
 * @param source
 *            originator of the event (instance ID, module name, etc.)
 * @param payload
 *            arbitrary key-value data
 * @param timestamp
 *            when the event was created
 */
public record CustomCloudEvent(String type, String source, Map<String, Object> payload, Instant timestamp)
        implements CloudEvent {

    public CustomCloudEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        if (payload == null) payload = Map.of();
        if (timestamp == null) timestamp = Instant.now();
    }

    public CustomCloudEvent(String type, String source, Map<String, Object> payload) {
        this(type, source, payload, Instant.now());
    }
}
