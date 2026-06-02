package me.prexorjustin.prexorcloud.controller.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Distributed-tracing configuration (northstar-plan Track D — OpenTelemetry).
 *
 * <p>Disabled by default: with {@code enabled=false} the controller installs a no-op tracer and
 * the OpenTelemetry SDK is never started, so there is zero runtime cost. When enabled, spans are
 * exported over OTLP to {@code otlpEndpoint} (any OTLP-compatible collector — Jaeger, Tempo,
 * Honeycomb, Datadog). {@code samplerRatio} is a parent-based head sampler ratio in {@code [0,1]}.
 *
 * <p>{@code traceUiTemplate} is an optional deep-link template for the operator's trace UI,
 * containing a literal {@code {traceId}} placeholder (e.g.
 * {@code "http://localhost:16686/trace/{traceId}"} for Jaeger). The controller surfaces it to the
 * dashboard so a "view trace" link can be built (Track D.3); empty means no link is offered.
 */
public record TelemetryConfig(
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("otlpEndpoint") String otlpEndpoint,
        @JsonProperty("serviceName") String serviceName,
        @JsonProperty("samplerRatio") Double samplerRatio,
        @JsonProperty("traceUiTemplate") String traceUiTemplate) {

    public TelemetryConfig {
        if (otlpEndpoint == null || otlpEndpoint.isBlank()) {
            otlpEndpoint = "http://localhost:4317";
        }
        if (serviceName == null || serviceName.isBlank()) {
            serviceName = "prexorcloud-controller";
        }
        // Clamp to a valid head-sampling ratio; default to "sample everything".
        if (samplerRatio == null) {
            samplerRatio = 1.0;
        }
        samplerRatio = Math.max(0.0, Math.min(1.0, samplerRatio));
        if (traceUiTemplate == null) {
            traceUiTemplate = "";
        }
    }

    /** Back-compat 4-arg constructor (no trace-UI deep link). */
    public TelemetryConfig(boolean enabled, String otlpEndpoint, String serviceName, Double samplerRatio) {
        this(enabled, otlpEndpoint, serviceName, samplerRatio, null);
    }

    public TelemetryConfig() {
        this(false, null, null, null, null);
    }
}
