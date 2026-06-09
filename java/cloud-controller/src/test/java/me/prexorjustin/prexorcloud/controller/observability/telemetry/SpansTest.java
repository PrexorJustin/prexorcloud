package me.prexorjustin.prexorcloud.controller.observability.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import me.prexorjustin.prexorcloud.controller.config.TelemetryConfig;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Spans")
class SpansTest {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private Telemetry telemetry;
    private Tracer tracer;

    @BeforeEach
    void setup() {
        telemetry = Telemetry.fromExporter(new TelemetryConfig(true, "x", "svc", 1.0), exporter);
        tracer = telemetry.tracer();
    }

    @AfterEach
    void tearDown() {
        telemetry.close();
    }

    @Test
    @DisplayName("call records a span and returns the body's value")
    void callRecordsAndReturns() {
        int result = Spans.call(tracer, "placement.evaluate", () -> 42);
        telemetry.flush();
        assertEquals(42, result);
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        assertEquals("placement.evaluate", spans.get(0).getName());
        assertEquals(StatusCode.UNSET, spans.get(0).getStatus().getStatusCode());
    }

    @Test
    @DisplayName("run records a span around a void body")
    void runRecords() {
        var ran = new boolean[1];
        Spans.run(tracer, "deployment.reconcile", () -> ran[0] = true);
        telemetry.flush();
        assertTrue(ran[0]);
        assertEquals(
                "deployment.reconcile", exporter.getFinishedSpanItems().get(0).getName());
    }

    @Test
    @DisplayName("a throwing body marks the span ERROR, records the exception, and re-throws")
    void throwingBodyMarksErrorAndRethrows() {
        var ex = assertThrows(
                IllegalStateException.class,
                () -> Spans.run(tracer, "auth.login", () -> {
                    throw new IllegalStateException("boom");
                }));
        assertEquals("boom", ex.getMessage());
        telemetry.flush();
        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals(1, span.getEvents().size()); // recorded exception event
    }
}
