package me.prexorjustin.prexorcloud.daemon.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.prexorjustin.prexorcloud.daemon.config.TelemetryDaemonConfig;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DaemonTelemetry")
class DaemonTelemetryTest {

    @Test
    @DisplayName("disabled config yields a no-op tracer that records nothing")
    void disabledIsNoop() {
        try (DaemonTelemetry telemetry = DaemonTelemetry.create(new TelemetryDaemonConfig(), "node-1")) {
            assertFalse(telemetry.isEnabled());
            // A no-op span is not recording, so flush/close are safe and export nothing.
            telemetry.tracer().spanBuilder("noop").startSpan().end();
            telemetry.flush();
        }
    }

    @Test
    @DisplayName("enabled config exports a span carrying the node.id resource attribute")
    void enabledExportsSpanWithNodeId() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        var config = new TelemetryDaemonConfig(true, "http://localhost:4317", "svc", 1.0);
        try (DaemonTelemetry telemetry = DaemonTelemetry.fromExporter(config, "node-7", exporter)) {
            assertTrue(telemetry.isEnabled());
            Span span = telemetry.tracer().spanBuilder("daemon.work").startSpan();
            span.end();
            telemetry.flush();

            SpanData data = exporter.getFinishedSpanItems().get(0);
            assertEquals("daemon.work", data.getName());
            assertEquals("svc", data.getResource().getAttribute(AttributeKey.stringKey("service.name")));
            assertEquals("node-7", data.getResource().getAttribute(AttributeKey.stringKey("node.id")));
        }
    }
}
