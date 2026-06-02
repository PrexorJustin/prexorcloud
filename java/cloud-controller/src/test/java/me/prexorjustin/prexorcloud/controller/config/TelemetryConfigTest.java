package me.prexorjustin.prexorcloud.controller.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TelemetryConfig")
class TelemetryConfigTest {

    @Test
    @DisplayName("defaults to disabled with sensible OTLP endpoint, service name and full sampling")
    void defaults() {
        var config = new TelemetryConfig();
        assertFalse(config.enabled());
        assertEquals("http://localhost:4317", config.otlpEndpoint());
        assertEquals("prexorcloud-controller", config.serviceName());
        assertEquals(1.0, config.samplerRatio());
        assertEquals("", config.traceUiTemplate());
    }

    @Test
    @DisplayName("trace-UI template defaults to empty and is otherwise passed through")
    void traceUiTemplate() {
        assertEquals("", new TelemetryConfig(true, "x", "y", 1.0, null).traceUiTemplate());
        assertEquals(
                "http://localhost:16686/trace/{traceId}",
                new TelemetryConfig(true, "x", "y", 1.0, "http://localhost:16686/trace/{traceId}").traceUiTemplate());
        // The back-compat 4-arg constructor yields no deep link.
        assertEquals("", new TelemetryConfig(true, "x", "y", 1.0).traceUiTemplate());
    }

    @Test
    @DisplayName("null/blank fields fall back to defaults")
    void blankFieldsDefault() {
        var config = new TelemetryConfig(true, "  ", "", null);
        assertEquals("http://localhost:4317", config.otlpEndpoint());
        assertEquals("prexorcloud-controller", config.serviceName());
        assertEquals(1.0, config.samplerRatio());
    }

    @Test
    @DisplayName("sampler ratio is clamped to [0,1]")
    void clampsSamplerRatio() {
        assertEquals(0.0, new TelemetryConfig(true, "x", "y", -2.0).samplerRatio());
        assertEquals(1.0, new TelemetryConfig(true, "x", "y", 5.0).samplerRatio());
        assertEquals(0.25, new TelemetryConfig(true, "x", "y", 0.25).samplerRatio());
    }
}
