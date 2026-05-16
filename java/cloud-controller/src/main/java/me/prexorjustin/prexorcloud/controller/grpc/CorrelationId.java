package me.prexorjustin.prexorcloud.controller.grpc;

import java.util.UUID;

/**
 * Composite correlation ID used for daemon round-trips that may be initiated
 * by one controller but answered through a session owned by another. Encoded
 * as {@code "{originatingControllerId}:{uuid}"} so any controller can parse
 * out the originator and forward the reply to it via Redis pubsub when needed.
 */
public final class CorrelationId {

    private CorrelationId() {}

    /** Build a fresh correlation id stamped with {@code controllerId}. */
    public static String mint(String controllerId) {
        return (controllerId == null ? "" : controllerId) + ":" + UUID.randomUUID();
    }

    /**
     * Extract the originating controller id from a correlation id minted by
     * {@link #mint(String)}. Returns the empty string when the id does not
     * carry a controller prefix (legacy/system origin).
     */
    public static String originator(String correlationId) {
        if (correlationId == null) return "";
        int colon = correlationId.indexOf(':');
        if (colon <= 0) return "";
        return correlationId.substring(0, colon);
    }
}
