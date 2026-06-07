package me.prexorjustin.prexorcloud.controller.observability.telemetry;

import java.util.HashMap;
import java.util.Map;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;

/**
 * Serializes the current trace context into a W3C {@code traceparent} string for carrying over the
 * gRPC daemon stream (northstar-plan Track D.3, Controller → Daemon hop). The controller is the gRPC
 * <em>server</em> and pushes commands down a long-lived response stream, so trace context rides in
 * the {@code ControllerMessage} payload rather than in request metadata.
 *
 * <p>Uses the stateless global {@link W3CTraceContextPropagator}, so it needs no SDK reference and
 * works the same whether or not telemetry is enabled: with telemetry off (or no active span) the
 * current span context is invalid and {@link #currentTraceparent()} returns {@code ""}, so nothing
 * is stamped and there is no behavioural change.
 */
public final class TraceContextWire {

    private static final TextMapPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();
    private static final TextMapSetter<Map<String, String>> SETTER = (carrier, key, value) -> {
        if (carrier != null) {
            carrier.put(key, value);
        }
    };
    private static final String TRACEPARENT = "traceparent";

    private TraceContextWire() {}

    /** The current span's {@code traceparent}, or {@code ""} when there is no valid active span. */
    public static String currentTraceparent() {
        if (!Span.current().getSpanContext().isValid()) {
            return "";
        }
        Map<String, String> carrier = new HashMap<>(2);
        PROPAGATOR.inject(Context.current(), carrier, SETTER);
        return carrier.getOrDefault(TRACEPARENT, "");
    }
}
