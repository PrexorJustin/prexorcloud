package me.prexorjustin.prexorcloud.controller.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ControllerReadinessProbeTest {

    @Test
    void reportsReadyWhenAllCriticalSubsystemsAreInitialized() {
        var probe = new ControllerReadinessProbe(() -> true, () -> true, () -> true);

        var snapshot = probe.snapshot();

        assertTrue(snapshot.ready());
        assertEquals("READY", snapshot.status());
        assertEquals(200, snapshot.httpStatus());
        assertEquals(true, snapshot.checks().get("mongo"));
        assertEquals(true, snapshot.checks().get("scheduler"));
        assertEquals(true, snapshot.checks().get("platformModules"));
    }

    @Test
    void reportsNotReadyWhenMongoIsMissing() {
        var probe = new ControllerReadinessProbe(() -> false, () -> true, () -> true);

        var snapshot = probe.snapshot();

        assertFalse(snapshot.ready());
        assertEquals("NOT_READY", snapshot.status());
        assertEquals(503, snapshot.httpStatus());
        assertEquals(false, snapshot.checks().get("mongo"));
    }

    @Test
    void reportsNotReadyWhenSchedulerIsMissing() {
        var probe = new ControllerReadinessProbe(() -> true, () -> false, () -> true);

        var snapshot = probe.snapshot();

        assertFalse(snapshot.ready());
        assertEquals(false, snapshot.checks().get("scheduler"));
    }
}
