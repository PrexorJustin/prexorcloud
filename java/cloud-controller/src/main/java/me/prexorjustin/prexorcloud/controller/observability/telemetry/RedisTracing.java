package me.prexorjustin.prexorcloud.controller.observability.telemetry;

import java.net.SocketAddress;

import io.lettuce.core.protocol.RedisCommand;
import io.lettuce.core.tracing.TraceContext;
import io.lettuce.core.tracing.TraceContextProvider;
import io.lettuce.core.tracing.Tracer;
import io.lettuce.core.tracing.TracerProvider;
import io.lettuce.core.tracing.Tracing;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;

/**
 * Adapter from Lettuce's native tracing SPI ({@link Tracing}) onto OpenTelemetry (northstar-plan
 * Track D.1 — manual per-library instrumentation, no Javaagent). One CLIENT span per Redis command,
 * parented to the caller's current context so a {@code SET}/{@code EVAL} nests under the span that
 * issued it.
 *
 * <p>Installed on {@code ClientResources} only when telemetry is enabled — see the controller
 * bootstrap. Lettuce gates all tracing on {@link #isEnabled()}, so when telemetry is off the Redis
 * client is built without this adapter at all and behaves exactly as before. Command arguments are
 * deliberately excluded from span tags ({@link #includeCommandArgsInSpanTags()} is {@code false}) so
 * Redis values (tokens, revocation entries, lease payloads) never leak into traces.
 */
public final class RedisTracing implements Tracing {

    private final TracerProvider tracerProvider;

    public RedisTracing(io.opentelemetry.api.trace.Tracer otelTracer) {
        Tracer tracer = new OtelLettuceTracer(otelTracer);
        this.tracerProvider = () -> tracer;
    }

    @Override
    public TracerProvider getTracerProvider() {
        return tracerProvider;
    }

    @Override
    public TraceContextProvider initialTraceContextProvider() {
        return () -> TraceContext.EMPTY;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean includeCommandArgsInSpanTags() {
        return false;
    }

    @Override
    public Endpoint createEndpoint(SocketAddress socketAddress) {
        return new SocketEndpoint(socketAddress);
    }

    private record SocketEndpoint(SocketAddress address) implements Endpoint {}

    private static final class OtelLettuceTracer extends Tracer {
        private final io.opentelemetry.api.trace.Tracer otel;

        OtelLettuceTracer(io.opentelemetry.api.trace.Tracer otel) {
            this.otel = otel;
        }

        @Override
        public Span nextSpan() {
            return new OtelSpan(otel);
        }

        @Override
        public Span nextSpan(TraceContext traceContext) {
            return new OtelSpan(otel);
        }
    }

    private static final class OtelSpan extends Tracer.Span {
        private final io.opentelemetry.api.trace.Tracer otel;
        private io.opentelemetry.api.trace.Span span;

        OtelSpan(io.opentelemetry.api.trace.Tracer otel) {
            this.otel = otel;
        }

        @Override
        public Tracer.Span start(RedisCommand<?, ?, ?> command) {
            String op = command != null && command.getType() != null ? String.valueOf(command.getType()) : "command";
            this.span = otel.spanBuilder("redis " + op)
                    .setParent(Context.current())
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute("db.system", "redis")
                    .setAttribute("db.operation", op)
                    .startSpan();
            return this;
        }

        @Override
        public Tracer.Span name(String name) {
            if (span != null) {
                span.updateName(name);
            }
            return this;
        }

        @Override
        public Tracer.Span annotate(String value) {
            if (span != null) {
                span.addEvent(value);
            }
            return this;
        }

        @Override
        public Tracer.Span tag(String key, String value) {
            if (span != null) {
                span.setAttribute(key, value);
            }
            return this;
        }

        @Override
        public Tracer.Span error(Throwable throwable) {
            if (span != null) {
                span.setStatus(StatusCode.ERROR, throwable == null ? "" : String.valueOf(throwable.getMessage()));
                if (throwable != null) {
                    span.recordException(throwable);
                }
            }
            return this;
        }

        @Override
        public Tracer.Span remoteEndpoint(Endpoint endpoint) {
            return this;
        }

        @Override
        public void finish() {
            if (span != null) {
                span.end();
            }
        }
    }
}
