package me.prexorjustin.prexorcloud.daemon.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Daemon-side distributed-tracing configuration (northstar-plan Track D — OpenTelemetry).
 *
 * <p>Mirrors the controller's {@code TelemetryConfig}: disabled by default, so with
 * {@code enabled=false} the daemon installs a no-op tracer and never starts the OpenTelemetry SDK
 * (zero runtime cost). When enabled, spans export over OTLP to {@code otlpEndpoint} (Jaeger, Tempo,
 * Honeycomb, Datadog, …). {@code samplerRatio} is a parent-based head sampler ratio in {@code [0,1]}
 * — keep it aligned with the controller so a sampled controller trace keeps being sampled when it
 * reaches the daemon.
 */
public record TelemetryDaemonConfig(
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("otlpEndpoint") String otlpEndpoint,
        @JsonProperty("serviceName") String serviceName,
        @JsonProperty("samplerRatio") Double samplerRatio) {

    public TelemetryDaemonConfig {
        if (otlpEndpoint == null || otlpEndpoint.isBlank()) {
            otlpEndpoint = "http://localhost:4317";
        }
        if (serviceName == null || serviceName.isBlank()) {
            serviceName = "prexorcloud-daemon";
        }
        if (samplerRatio == null) {
            samplerRatio = 1.0;
        }
        samplerRatio = Math.max(0.0, Math.min(1.0, samplerRatio));
    }

    public TelemetryDaemonConfig() {
        this(false, null, null, null);
    }
}
