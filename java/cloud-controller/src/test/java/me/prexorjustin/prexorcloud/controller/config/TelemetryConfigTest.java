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
