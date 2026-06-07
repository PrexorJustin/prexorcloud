package me.prexorjustin.prexorcloud.daemon.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.prexorjustin.prexorcloud.daemon.config.TelemetryDaemonConfig;
import me.prexorjustin.prexorcloud.daemon.observability.DaemonTelemetry;
import me.prexorjustin.prexorcloud.protocol.ControllerMessage;
import me.prexorjustin.prexorcloud.protocol.ErrorReport;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MessageDispatcher trace continuation")
class MessageDispatcherTracingTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String PARENT_SPAN_ID = "b7ad6b7169203331";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01";

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();

    private MessageDispatcher dispatcherWith(DaemonTelemetry telemetry) {
        var dispatcher = new MessageDispatcher();
        dispatcher.setTracer(telemetry.tracer());
        return dispatcher;
    }

    private DaemonTelemetry telemetry() {
        return DaemonTelemetry.fromExporter(
                new TelemetryDaemonConfig(true, "http://localhost:4317", "svc", 1.0), "node-1", exporter);
    }

    private static ControllerMessage errorReport(String traceparent) {
        var msg = ControllerMessage.newBuilder()
                .setErrorReport(ErrorReport.newBuilder().setErrorCode("X").setErrorMessage("boom"));
        if (traceparent != null) {
            msg.setTraceparent(traceparent);
        }
        return msg.build();
    }

    @Test
    @DisplayName("continues the controller trace when a traceparent is present")
    void continuesInboundTrace() {
        try (DaemonTelemetry telemetry = telemetry()) {
            dispatcherWith(telemetry).dispatch(errorReport(TRACEPARENT));
            telemetry.flush();

            SpanData span = exporter.getFinishedSpanItems().get(0);
            assertEquals("daemon.command", span.getName());
            assertEquals(SpanKind.CONSUMER, span.getKind());
            assertEquals(TRACE_ID, span.getTraceId());
            assertEquals(PARENT_SPAN_ID, span.getParentSpanId());
            assertEquals("ERROR_REPORT", span.getAttributes().get(AttributeKey.stringKey("rpc.command")));
        }
    }

    @Test
    @DisplayName("creates no span for an untraced message (no traceparent)")
    void noSpanWithoutTraceparent() {
        try (DaemonTelemetry telemetry = telemetry()) {
            dispatcherWith(telemetry).dispatch(errorReport(null));
            telemetry.flush();
            assertTrue(exporter.getFinishedSpanItems().isEmpty());
        }
    }
}
