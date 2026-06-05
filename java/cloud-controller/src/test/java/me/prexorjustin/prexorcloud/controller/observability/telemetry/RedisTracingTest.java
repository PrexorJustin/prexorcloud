package me.prexorjustin.prexorcloud.controller.observability.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import me.prexorjustin.prexorcloud.controller.config.TelemetryConfig;

import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.tracing.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RedisTracing")
class RedisTracingTest {

    private static Tracer.Span span(RedisTracing tracing) {
        return tracing.getTracerProvider().getTracer().nextSpan();
    }

    @Test
    @DisplayName("a command yields a CLIENT span named after the operation")
    void recordsSpanForCommand() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        Telemetry telemetry = Telemetry.fromExporter(new TelemetryConfig(true, "x", "svc", 1.0), exporter);
        RedisTracing tracing = new RedisTracing(telemetry.tracer());

        span(tracing).start(new Command<>(CommandType.GET, null)).finish();

        telemetry.flush();
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        SpanData s = spans.get(0);
        assertEquals("redis GET", s.getName());
        assertEquals("redis", s.getAttributes().get(AttributeKey.stringKey("db.system")));
        assertEquals("GET", s.getAttributes().get(AttributeKey.stringKey("db.operation")));
        assertEquals(StatusCode.UNSET, s.getStatus().getStatusCode());
    }

    @Test
    @DisplayName("error() marks the span ERROR and records the exception")
    void recordsError() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        Telemetry telemetry = Telemetry.fromExporter(new TelemetryConfig(true, "x", "svc", 1.0), exporter);
        RedisTracing tracing = new RedisTracing(telemetry.tracer());

        span(tracing)
                .start(new Command<>(CommandType.SET, null))
                .error(new RuntimeException("down"))
                .finish();

        telemetry.flush();
        SpanData s = exporter.getFinishedSpanItems().get(0);
        assertEquals(StatusCode.ERROR, s.getStatus().getStatusCode());
        assertTrue(s.getEvents().stream().anyMatch(e -> "exception".equals(e.getName())));
    }

    @Test
    @DisplayName("the adapter declares itself enabled and excludes command args from tags")
    void declaresEnabledAndArgsExcluded() {
        RedisTracing tracing = new RedisTracing(Telemetry.disabled().tracer());
        assertTrue(tracing.isEnabled());
        assertFalse(tracing.includeCommandArgsInSpanTags());
    }
}
