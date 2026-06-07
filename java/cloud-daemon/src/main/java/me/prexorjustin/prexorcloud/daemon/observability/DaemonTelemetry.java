package me.prexorjustin.prexorcloud.daemon.observability;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.daemon.config.TelemetryDaemonConfig;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the daemon's OpenTelemetry tracing pipeline (northstar-plan Track D — the daemon hop of the
 * Controller → Daemon → MC-Plugin trace path). Mirrors the controller's {@code Telemetry}: when
 * {@link TelemetryDaemonConfig#enabled()} is false this is a no-op shell — {@link #tracer()} returns
 * a tracer from {@link OpenTelemetry#noop()}, no SDK is started, and {@link #close()} does nothing —
 * so spans created at instrumentation sites cost effectively nothing. When enabled, spans batch-export
 * over OTLP to the configured collector.
 *
 * <p>W3C propagation is registered so the daemon can continue an inbound trace started by the
 * controller (once {@code ControllerMessage} carries a {@code traceparent}) and propagate it onward
 * to launched MC plugins. The {@code node.id} resource attribute attributes every span to its host.
 */
public final class DaemonTelemetry implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(DaemonTelemetry.class);
    private static final String INSTRUMENTATION_SCOPE = "prexorcloud-daemon";
    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
    private static final AttributeKey<String> NODE_ID = AttributeKey.stringKey("node.id");

    private final OpenTelemetry openTelemetry;
    private final OpenTelemetrySdk sdk; // null when disabled / no-op
    private final Tracer tracer;
    private final boolean enabled;

    private DaemonTelemetry(OpenTelemetry openTelemetry, OpenTelemetrySdk sdk, boolean enabled) {
        this.openTelemetry = openTelemetry;
        this.sdk = sdk;
        this.enabled = enabled;
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
    }

    /** A no-op telemetry instance: tracer never records, nothing is exported. */
    public static DaemonTelemetry disabled() {
        return new DaemonTelemetry(OpenTelemetry.noop(), null, false);
    }

    /**
     * Build from config. Returns {@link #disabled()} when tracing is off; otherwise stands up an SDK
     * exporting over OTLP to {@link TelemetryDaemonConfig#otlpEndpoint()}.
     */
    public static DaemonTelemetry create(TelemetryDaemonConfig config, String nodeId) {
        if (!config.enabled()) {
            return disabled();
        }
        SpanExporter exporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(config.otlpEndpoint())
                .setTimeout(Duration.ofSeconds(10))
                .build();
        DaemonTelemetry telemetry = fromExporter(config, nodeId, exporter);
        logger.info(
                "OpenTelemetry tracing enabled (endpoint={}, service={}, node={}, sampleRatio={})",
                config.otlpEndpoint(),
                config.serviceName(),
                nodeId,
                config.samplerRatio());
        return telemetry;
    }

    /**
     * Build an enabled instance against a caller-supplied {@link SpanExporter}. The production path
     * uses OTLP; tests inject an in-memory exporter to assert spans are recorded.
     */
    public static DaemonTelemetry fromExporter(TelemetryDaemonConfig config, String nodeId, SpanExporter exporter) {
        Attributes attrs = nodeId == null || nodeId.isBlank()
                ? Attributes.of(SERVICE_NAME, config.serviceName())
                : Attributes.of(SERVICE_NAME, config.serviceName(), NODE_ID, nodeId);
        Resource resource = Resource.getDefault().merge(Resource.create(attrs));
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(config.samplerRatio())))
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        return new DaemonTelemetry(sdk, sdk, true);
    }

    public Tracer tracer() {
        return tracer;
    }

    public OpenTelemetry openTelemetry() {
        return openTelemetry;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Force any buffered spans to export now (no-op when disabled). Blocks up to 5s. */
    public void flush() {
        if (sdk != null) {
            sdk.getSdkTracerProvider().forceFlush().join(5, TimeUnit.SECONDS);
        }
    }

    /** Flush and shut down the SDK (no-op when disabled). Blocks briefly on the final export. */
    @Override
    public void close() {
        if (sdk != null) {
            sdk.close();
        }
    }
}
