package me.prexorjustin.prexorcloud.controller.observability.telemetry;

import java.util.List;
import java.util.function.Function;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;

/**
 * Per-request server span for inbound HTTP (northstar-plan Track D.1 "Javalin-HTTP" + the inbound
 * half of D.3). Extracts an incoming W3C {@code traceparent} from the request headers so a trace
 * started elsewhere (the dashboard, an external client, a future daemon→controller call) continues
 * through the controller instead of starting a fresh root. The span is made <em>current</em> for
 * the request thread, so domain spans created inside the handler ({@code auth.login}, …) nest
 * underneath it.
 *
 * <p>Only meaningful with telemetry enabled; the caller gates installation on that, and on
 * skipping long-lived streaming endpoints (an SSE span would never end).
 */
public final class HttpServerTracing {

    /** Reads header values from a {@code name -> value} lookup (the Javalin {@code Context::header}). */
    private static final TextMapGetter<Function<String, String>> HEADER_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Function<String, String> carrier) {
            return List.of(); // extraction only reads known keys (traceparent/tracestate)
        }

        @Override
        public String get(Function<String, String> carrier, String key) {
            return carrier == null ? null : carrier.apply(key);
        }
    };

    /** Open span + the scope keeping it current; handed back to {@link #end} to close in order. */
    public record Inflight(Span span, Scope scope) {}

    private final Tracer tracer;
    private final TextMapPropagator propagator;

    public HttpServerTracing(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("prexorcloud-controller");
        this.propagator = openTelemetry.getPropagators().getTextMapPropagator();
    }

    /** Start a SERVER span as a child of any inbound trace context, and make it current. */
    public Inflight start(String method, String route, Function<String, String> headerLookup) {
        Context parent = propagator.extract(Context.current(), headerLookup, HEADER_GETTER);
        Span span = tracer.spanBuilder("HTTP " + method)
                .setParent(parent)
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("http.request.method", method)
                .setAttribute("url.path", route)
                .startSpan();
        return new Inflight(span, span.makeCurrent());
    }

    /**
     * The trace id of an in-flight request, or {@code ""} when the span context is invalid (e.g. a
     * non-recording no-op span). Surfaced as the {@code X-Trace-Id} response header so the dashboard
     * can deep-link to the trace (Track D.3).
     */
    public String traceId(Inflight inflight) {
        if (inflight == null || !inflight.span().getSpanContext().isValid()) {
            return "";
        }
        return inflight.span().getSpanContext().getTraceId();
    }

    /** Close the scope and end the span, tagging the response status (5xx → ERROR). */
    public void end(Inflight inflight, int statusCode) {
        if (inflight == null) {
            return;
        }
        Span span = inflight.span();
        span.setAttribute("http.response.status_code", (long) statusCode);
        if (statusCode >= 500) {
            span.setStatus(StatusCode.ERROR);
        }
        inflight.scope().close();
        span.end();
    }
}
