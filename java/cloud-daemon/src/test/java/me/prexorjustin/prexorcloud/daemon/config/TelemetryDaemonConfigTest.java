package me.prexorjustin.prexorcloud.daemon.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TelemetryDaemonConfig")
class TelemetryDaemonConfigTest {

    @Test
    @DisplayName("no-arg default is disabled with daemon defaults")
    void defaults() {
        var config = new TelemetryDaemonConfig();
        assertFalse(config.enabled());
        assertEquals("http://localhost:4317", config.otlpEndpoint());
        assertEquals("prexorcloud-daemon", config.serviceName());
        assertEquals(1.0, config.samplerRatio());
    }

    @Test
    @DisplayName("blank endpoint/service fall back to defaults")
    void blanksFallBack() {
        var config = new TelemetryDaemonConfig(true, "  ", "", null);
        assertEquals("http://localhost:4317", config.otlpEndpoint());
        assertEquals("prexorcloud-daemon", config.serviceName());
    }

    @Test
    @DisplayName("sampler ratio is clamped to [0,1]")
    void clampsSamplerRatio() {
        assertEquals(0.0, new TelemetryDaemonConfig(true, null, null, -3.0).samplerRatio());
        assertEquals(1.0, new TelemetryDaemonConfig(true, null, null, 4.0).samplerRatio());
        assertEquals(0.25, new TelemetryDaemonConfig(true, null, null, 0.25).samplerRatio());
    }
}
