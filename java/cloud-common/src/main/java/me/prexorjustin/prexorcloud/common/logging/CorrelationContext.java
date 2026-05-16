package me.prexorjustin.prexorcloud.common.logging;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.MDC;

/**
 * Small MDC helper for cross-cutting request/workflow correlation fields.
 */
public final class CorrelationContext {

    public static final String CORRELATION_ID = "correlationId";
    public static final String REQUEST_ID = "requestId";

    private CorrelationContext() {}

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static String sanitize(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            return newId();
        }
        return value.replaceAll("[^A-Za-z0-9_.:-]", "_");
    }

    public static Scope open(String key, String value) {
        return open(Map.of(key, value));
    }

    public static Scope open(Map<String, String> fields) {
        return new Scope(fields);
    }

    public static final class Scope implements AutoCloseable {

        private final Map<String, String> previous = new LinkedHashMap<>();

        private Scope(Map<String, String> fields) {
            fields.forEach((key, value) -> {
                previous.put(key, MDC.get(key));
                if (value == null || value.isBlank()) {
                    MDC.remove(key);
                } else {
                    MDC.put(key, value);
                }
            });
        }

        @Override
        public void close() {
            previous.forEach((key, value) -> {
                if (value == null) {
                    MDC.remove(key);
                } else {
                    MDC.put(key, value);
                }
            });
        }
    }
}
