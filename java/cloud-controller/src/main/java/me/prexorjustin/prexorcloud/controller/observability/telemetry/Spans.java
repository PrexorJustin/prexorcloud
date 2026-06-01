package me.prexorjustin.prexorcloud.controller.observability.telemetry;

import java.util.function.Supplier;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

/**
 * Small helpers for wrapping a unit of work in a span (northstar-plan Track D.2).
 *
 * <p>Each call starts a span, makes it current for the duration of {@code body} (so anything the
 * body traces nests underneath), records and re-throws any {@link RuntimeException} with an
 * {@code ERROR} status, and always ends the span. With a no-op {@link Tracer} (telemetry disabled)
 * the span is non-recording and the overhead is negligible, so call sites stay unconditional.
 */
public final class Spans {

    private Spans() {}

    public static <T> T call(Tracer tracer, String name, Supplier<T> body) {
        Span span = tracer.spanBuilder(name).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return body.get();
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR);
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    public static void run(Tracer tracer, String name, Runnable body) {
        call(tracer, name, () -> {
            body.run();
            return null;
        });
    }
}
