package me.prexorjustin.prexorcloud.controller.observability.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Map;

import me.prexorjustin.prexorcloud.controller.config.TelemetryConfig;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HttpServerTracing")
class HttpServerTracingTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-b7ad6b7169203331-01";

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private Telemetry telemetry;
    private HttpServerTracing tracing;

    @BeforeEach
    void setup() {
        telemetry = Telemetry.fromExporter(new TelemetryConfig(true, "x", "svc", 1.0), exporter);
        tracing = new HttpServerTracing(telemetry.openTelemetry());
    }

    @AfterEach
    void tearDown() {
        telemetry.close();
    }

    @Test
    @DisplayName("continues an inbound W3C trace and records a SERVER span with request attributes")
    void continuesInboundTrace() {
        var headers = Map.of("traceparent", TRACEPARENT);
        var inflight = tracing.start("GET", "/api/v1/nodes", headers::get);
        // The new span inherits the inbound trace id.
        assertEquals(TRACE_ID, inflight.span().getSpanContext().getTraceId());
        tracing.end(inflight, 200);
        telemetry.flush();

        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertEquals("HTTP GET", span.getName());
        assertEquals(SpanKind.SERVER, span.getKind());
        assertEquals(TRACE_ID, span.getTraceId());
        assertEquals("GET", span.getAttributes().get(AttributeKey.stringKey("http.request.method")));
        assertEquals("/api/v1/nodes", span.getAttributes().get(AttributeKey.stringKey("url.path")));
        assertEquals(200L, span.getAttributes().get(AttributeKey.longKey("http.response.status_code")));
        assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
    }

    @Test
    @DisplayName("starts a fresh root trace with no inbound header and marks 5xx as ERROR")
    void freshRootAndErrorStatus() {
        var inflight = tracing.start("POST", "/api/v1/groups", name -> null);
        assertNotEquals(TRACE_ID, inflight.span().getSpanContext().getTraceId());
        tracing.end(inflight, 503);
        telemetry.flush();

        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals(503L, span.getAttributes().get(AttributeKey.longKey("http.response.status_code")));
    }
}
