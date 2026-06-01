package me.prexorjustin.prexorcloud.controller.observability.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import me.prexorjustin.prexorcloud.controller.config.TelemetryConfig;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Telemetry")
class TelemetryTest {

    @Test
    @DisplayName("disabled config yields a no-op tracer that records nothing")
    void disabledIsNoop() {
        try (Telemetry telemetry = Telemetry.create(new TelemetryConfig())) {
            assertFalse(telemetry.isEnabled());
            // The no-op tracer's spans are never sampled/recorded.
            assertFalse(telemetry.tracer().spanBuilder("noop").startSpan().isRecording());
        }
    }

    @Test
    @DisplayName("enabled pipeline records and exports a span with the service.name resource")
    void enabledExportsSpans() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        var config = new TelemetryConfig(true, "http://collector:4317", "prexorcloud-controller", 1.0);
        Telemetry telemetry = Telemetry.fromExporter(config, exporter);

        assertTrue(telemetry.isEnabled());
        telemetry.tracer().spanBuilder("scheduler.tick").startSpan().end();
        telemetry.flush(); // force the BatchSpanProcessor to export now

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        assertEquals("scheduler.tick", spans.get(0).getName());
        assertEquals(
                "prexorcloud-controller",
                spans.get(0).getResource().getAttribute(AttributeKey.stringKey("service.name")));
        telemetry.close();
    }
}
