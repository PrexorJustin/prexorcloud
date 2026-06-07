package me.prexorjustin.prexorcloud.controller.module.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import me.prexorjustin.prexorcloud.controller.config.ModuleQuota;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ModuleQuotaEnforcer")
class ModuleQuotaEnforcerTest {

    /** A clock the test can step forward between evaluation passes. */
    private static final class SteppedClock extends Clock {
        private Instant now = Instant.parse("2026-06-01T00:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private final SteppedClock clock = new SteppedClock();
    private final AtomicReference<ModuleResourceTracker.Snapshot> current = new AtomicReference<>();
    private final List<String> breaches = new CopyOnWriteArrayList<>();

    private ModuleResourceTracker.Snapshot snap(long cpuMillis, long allocBytes, int threads) {
        return new ModuleResourceTracker.Snapshot("alpha", cpuMillis, allocBytes, threads, clock.instant());
    }

    private ModuleQuotaEnforcer enforcer(ModuleQuota quota) {
        return new ModuleQuotaEnforcer(
                id -> current.get(),
                Map.of("alpha", quota),
                (moduleId, resource) -> breaches.add(moduleId + ':' + resource.tag()),
                60_000L,
                clock);
    }

    @Test
    @DisplayName("first pass only establishes a baseline; no evaluation yet")
    void firstPassBaselinesOnly() {
        var enforcer = enforcer(new ModuleQuota(1000, 0, 0));
        current.set(snap(0, 0, 0));
        enforcer.evaluate();
        assertTrue(enforcer.evaluation("alpha").isEmpty(), "no evaluation before a second reading");
        assertTrue(breaches.isEmpty());
    }

    @Test
    @DisplayName("CPU rate over the limit raises a breach and the metric sink fires")
    void cpuOverLimitBreaches() {
        var enforcer = enforcer(new ModuleQuota(2000, 0, 0)); // 2000 ms-cpu/min
        current.set(snap(0, 0, 1));
        enforcer.evaluate(); // baseline at T0

        clock.advance(Duration.ofMinutes(1));
        current.set(snap(5000, 0, 1)); // +5000 ms over 1 min => 5000 ms/min > 2000
        enforcer.evaluate();

        var eval = enforcer.evaluation("alpha").orElseThrow();
        assertEquals(5000, eval.cpuMillisPerMinute());
        assertTrue(eval.cpuExceeded());
        assertTrue(eval.anyExceeded());
        assertEquals(List.of("alpha:cpu"), breaches);
    }

    @Test
    @DisplayName("a sustained breach only logs/records on the rising edge each pass still emits the metric")
    void sustainedBreachEmitsMetricEachPass() {
        var enforcer = enforcer(new ModuleQuota(1000, 0, 0));
        current.set(snap(0, 0, 1));
        enforcer.evaluate();

        clock.advance(Duration.ofMinutes(1));
        current.set(snap(3000, 0, 1)); // 3000/min
        enforcer.evaluate();
        clock.advance(Duration.ofMinutes(1));
        current.set(snap(6000, 0, 1)); // still 3000/min
        enforcer.evaluate();

        // Metric fires on every breaching pass (not deduped) — two breaching passes.
        assertEquals(List.of("alpha:cpu", "alpha:cpu"), breaches);
    }

    @Test
    @DisplayName("allocation rate is computed in MB/min and compared to the limit")
    void allocationOverLimitBreaches() {
        var enforcer = enforcer(new ModuleQuota(0, 10, 0)); // 10 MB/min
        current.set(snap(0, 0, 1));
        enforcer.evaluate();

        clock.advance(Duration.ofMinutes(1));
        current.set(snap(0, 32L * 1024 * 1024, 1)); // 32 MB over 1 min
        enforcer.evaluate();

        var eval = enforcer.evaluation("alpha").orElseThrow();
        assertEquals(32, eval.allocatedMbPerMinute());
        assertTrue(eval.allocationExceeded());
        assertEquals(List.of("alpha:allocation"), breaches);
    }

    @Test
    @DisplayName("thread count is an instantaneous comparison, not a rate")
    void threadsOverLimitBreaches() {
        var enforcer = enforcer(new ModuleQuota(0, 0, 4));
        current.set(snap(0, 0, 8));
        enforcer.evaluate(); // baseline

        clock.advance(Duration.ofMinutes(1));
        current.set(snap(0, 0, 8)); // 8 live threads > 4
        enforcer.evaluate();

        var eval = enforcer.evaluation("alpha").orElseThrow();
        assertTrue(eval.threadsExceeded());
        assertEquals(8, eval.liveThreads());
        assertEquals(List.of("alpha:threads"), breaches);
    }

    @Test
    @DisplayName("staying within limits raises nothing")
    void withinLimitsNoBreach() {
        var enforcer = enforcer(new ModuleQuota(10_000, 100, 16));
        current.set(snap(0, 0, 2));
        enforcer.evaluate();

        clock.advance(Duration.ofMinutes(1));
        current.set(snap(500, 4L * 1024 * 1024, 2)); // 500 ms/min, 4 MB/min, 2 threads
        enforcer.evaluate();

        var eval = enforcer.evaluation("alpha").orElseThrow();
        assertFalse(eval.anyExceeded());
        assertTrue(breaches.isEmpty());
    }

    @Test
    @DisplayName("an all-zero quota enforces nothing and never starts the ticker")
    void emptyQuotaEnforcesNothing() {
        var enforcer = new ModuleQuotaEnforcer(
                id -> current.get(),
                Map.of("alpha", new ModuleQuota(0, 0, 0)),
                (moduleId, resource) -> breaches.add(moduleId),
                60_000L,
                clock);
        assertFalse(enforcer.hasEnforceableQuotas());
        current.set(snap(0, 0, 1));
        enforcer.evaluate();
        clock.advance(Duration.ofMinutes(1));
        current.set(snap(999_999, 0, 1));
        enforcer.evaluate();
        assertTrue(breaches.isEmpty(), "all-zero quota is unlimited on every dimension");
    }
}
