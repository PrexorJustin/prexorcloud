package me.prexorjustin.prexorcloud.controller.module.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import me.prexorjustin.prexorcloud.api.module.platform.ModuleHealth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ModuleHealthMonitor")
class ModuleHealthMonitorTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneOffset.UTC);

    private final ModuleHealthMonitor monitor = new ModuleHealthMonitor(FIXED);

    private static Map<String, ModuleHealth> poll(Object... pairs) {
        Map<String, ModuleHealth> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], (ModuleHealth) pairs[i + 1]);
        }
        return out;
    }

    @Test
    @DisplayName("records each module's status, detail and check timestamp")
    void recordsSnapshots() {
        monitor.record(poll(
                "alpha", ModuleHealth.healthy(),
                "beta", ModuleHealth.degraded("redis fallback")));

        var alpha = monitor.snapshot("alpha").orElseThrow();
        assertEquals(ModuleHealth.Status.HEALTHY, alpha.status());
        assertEquals(FIXED.instant(), alpha.checkedAt());

        var beta = monitor.snapshot("beta").orElseThrow();
        assertEquals(ModuleHealth.Status.DEGRADED, beta.status());
        assertEquals("redis fallback", beta.detail());
    }

    @Test
    @DisplayName("counts modules by status for the metric gauge")
    void countsByStatus() {
        monitor.record(poll(
                "a", ModuleHealth.healthy(),
                "b", ModuleHealth.healthy(),
                "c", ModuleHealth.unhealthy("db down"),
                "d", ModuleHealth.unknown()));

        assertEquals(2, monitor.countByStatus(ModuleHealth.Status.HEALTHY));
        assertEquals(1, monitor.countByStatus(ModuleHealth.Status.UNHEALTHY));
        assertEquals(1, monitor.countByStatus(ModuleHealth.Status.UNKNOWN));
        assertEquals(0, monitor.countByStatus(ModuleHealth.Status.DEGRADED));
    }

    @Test
    @DisplayName("a module absent from a later poll is dropped (no stale health for gone modules)")
    void dropsModulesAbsentFromLaterPoll() {
        monitor.record(poll(
                "alpha", ModuleHealth.healthy(),
                "beta", ModuleHealth.healthy()));
        assertTrue(monitor.snapshot("beta").isPresent());

        // beta is no longer ACTIVE → not in this poll.
        monitor.record(poll("alpha", ModuleHealth.unhealthy("degraded")));

        assertTrue(monitor.snapshot("alpha").isPresent());
        assertFalse(monitor.snapshot("beta").isPresent(), "beta should be dropped, not kept stale");
        assertEquals(
                ModuleHealth.Status.UNHEALTHY,
                monitor.snapshot("alpha").orElseThrow().status());
        assertEquals(1, monitor.snapshots().size());
    }

    @Test
    @DisplayName("snapshot is empty for an unknown module")
    void emptyForUnknownModule() {
        assertTrue(monitor.snapshot("nope").isEmpty());
    }
}
