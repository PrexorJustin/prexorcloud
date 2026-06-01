package me.prexorjustin.prexorcloud.controller.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Distributed-tracing configuration (northstar-plan Track D — OpenTelemetry).
 *
 * <p>Disabled by default: with {@code enabled=false} the controller installs a no-op tracer and
 * the OpenTelemetry SDK is never started, so there is zero runtime cost. When enabled, spans are
 * exported over OTLP to {@code otlpEndpoint} (any OTLP-compatible collector — Jaeger, Tempo,
 * Honeycomb, Datadog). {@code samplerRatio} is a parent-based head sampler ratio in {@code [0,1]}.
 */
public record TelemetryConfig(
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("otlpEndpoint") String otlpEndpoint,
        @JsonProperty("serviceName") String serviceName,
        @JsonProperty("samplerRatio") Double samplerRatio) {

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
    }

    public TelemetryConfig() {
        this(false, null, null, null);
    }
}
