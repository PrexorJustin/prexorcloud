package me.prexorjustin.prexorcloud.modules.example.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Config")
class ConfigTest {

    @Test
    @DisplayName("defaults() matches the values documented in example-playtime.yml")
    void defaultsMatchYaml() {
        Config defaults = Config.defaults();

        assertEquals(30, defaults.flushIntervalSeconds());
        assertEquals(25, defaults.topSize());
        assertEquals(30, defaults.retainSessionsDays());
        assertEquals("events", defaults.reportVia());
        assertFalse(defaults.enableDestructiveDemos());
    }

    @Test
    @DisplayName("record constructor preserves all fields")
    void recordRoundTrip() {
        Config cfg = new Config(60, 50, 7, "rest", true);

        assertEquals(60, cfg.flushIntervalSeconds());
        assertEquals(50, cfg.topSize());
        assertEquals(7, cfg.retainSessionsDays());
        assertEquals("rest", cfg.reportVia());
        assertTrue(cfg.enableDestructiveDemos());
    }
}
