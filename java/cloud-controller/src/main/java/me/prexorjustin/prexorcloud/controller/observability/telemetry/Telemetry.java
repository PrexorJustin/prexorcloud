package me.prexorjustin.prexorcloud.controller.observability.telemetry;

import java.time.Duration;

import me.prexorjustin.prexorcloud.controller.config.TelemetryConfig;

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
 * Owns the controller's OpenTelemetry tracing pipeline (northstar-plan Track D).
 *
 * <p>The instrument name {@code "prexorcloud-controller"} groups every span the controller emits.
 * When {@link TelemetryConfig#enabled()} is false this is a no-op shell — {@link #tracer()} returns
 * a tracer from {@link OpenTelemetry#noop()}, no SDK is started, and {@link #close()} does nothing —
 * so spans created at instrumentation sites cost effectively nothing and never leave the process.
 * When enabled, spans batch-export over OTLP/gRPC to the configured collector endpoint.
 *
 * <p>{@code service.name} is set on the resource directly (rather than via the semconv artifact) to
 * avoid pulling in an extra dependency for a single attribute key.
 */
public final class Telemetry implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(Telemetry.class);
    private static final String INSTRUMENTATION_SCOPE = "prexorcloud-controller";
    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");

    private final OpenTelemetry openTelemetry;
    private final OpenTelemetrySdk sdk; // null when disabled / no-op
    private final Tracer tracer;
    private final boolean enabled;

    private Telemetry(OpenTelemetry openTelemetry, OpenTelemetrySdk sdk, boolean enabled) {
        this.openTelemetry = openTelemetry;
        this.sdk = sdk;
        this.enabled = enabled;
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
    }

    /** A no-op telemetry instance: tracer never records, nothing is exported. */
    public static Telemetry disabled() {
        return new Telemetry(OpenTelemetry.noop(), null, false);
    }

    /**
     * Build from config. Returns {@link #disabled()} when tracing is off; otherwise stands up an
     * SDK exporting over OTLP/gRPC to {@link TelemetryConfig#otlpEndpoint()}.
     */
    public static Telemetry create(TelemetryConfig config) {
        if (!config.enabled()) {
            return disabled();
        }
        SpanExporter exporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(config.otlpEndpoint())
                .setTimeout(Duration.ofSeconds(10))
                .build();
        Telemetry telemetry = fromExporter(config, exporter);
        logger.info(
                "OpenTelemetry tracing enabled (endpoint={}, service={}, sampleRatio={})",
                config.otlpEndpoint(),
                config.serviceName(),
                config.samplerRatio());
        return telemetry;
    }

    /**
     * Build an enabled instance against a caller-supplied {@link SpanExporter}. The production path
     * uses OTLP; tests inject an in-memory exporter to assert spans are recorded.
     */
    public static Telemetry fromExporter(TelemetryConfig config, SpanExporter exporter) {
        Resource resource =
                Resource.getDefault().merge(Resource.create(Attributes.of(SERVICE_NAME, config.serviceName())));
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(config.samplerRatio())))
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        return new Telemetry(sdk, sdk, true);
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
            sdk.getSdkTracerProvider().forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);
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
