package me.prexorjustin.prexorcloud.controller.observability.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.prexorjustin.prexorcloud.controller.config.TelemetryConfig;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TraceContextWire")
class TraceContextWireTest {

    private Telemetry telemetry;

    @BeforeEach
    void setup() {
        telemetry = Telemetry.fromExporter(new TelemetryConfig(true, "x", "svc", 1.0), InMemorySpanExporter.create());
    }

    @AfterEach
    void tearDown() {
        telemetry.close();
    }

    @Test
    @DisplayName("returns empty when no span is active")
    void emptyWithoutSpan() {
        assertEquals("", TraceContextWire.currentTraceparent());
    }

    @Test
    @DisplayName("emits a W3C traceparent carrying the active span's trace and span id")
    void stampsActiveSpan() {
        Span span = telemetry.tracer().spanBuilder("placement.dispatch").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            String traceparent = TraceContextWire.currentTraceparent();
            // 00-<32 hex trace id>-<16 hex span id>-<2 hex flags>
            assertTrue(traceparent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}"), traceparent);
            assertTrue(traceparent.contains(span.getSpanContext().getTraceId()));
            assertTrue(traceparent.contains(span.getSpanContext().getSpanId()));
        } finally {
            span.end();
        }
    }
}
