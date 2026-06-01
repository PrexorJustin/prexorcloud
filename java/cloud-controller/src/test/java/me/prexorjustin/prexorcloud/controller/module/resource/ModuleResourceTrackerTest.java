package me.prexorjustin.prexorcloud.controller.module.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import me.prexorjustin.prexorcloud.controller.module.platform.ControllerTaskScheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ModuleResourceTracker")
class ModuleResourceTrackerTest {

    private final AtomicReference<Set<String>> active = new AtomicReference<>(Set.of());
    private ModuleResourceTracker tracker;

    private ModuleResourceTracker tracker() {
        tracker = new ModuleResourceTracker(active::get, 1000L);
        return tracker;
    }

    @AfterEach
    void tearDown() {
        if (tracker != null) {
            tracker.close();
        }
    }

    /** Run a CPU+allocation-burning task on the module's scheduler and wait for it to finish. */
    private String runWorkload(ControllerTaskScheduler scheduler) throws InterruptedException {
        var threadName = new AtomicReference<String>();
        var done = new CountDownLatch(1);
        scheduler.schedule(() -> {
            threadName.set(Thread.currentThread().getName());
            long acc = 0;
            for (int i = 0; i < 2_000_000; i++) {
                acc += (i * 31L) ^ acc;
                if ((i & 0xFFFF) == 0) {
                    acc += new byte[256].length; // touch the allocator
                }
            }
            if (acc == 42) {
                threadName.set(threadName.get() + acc); // defeat dead-code elimination
            }
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS), "workload should finish");
        return threadName.get();
    }

    @Test
    @DisplayName("names module threads and produces a non-negative snapshot after sampling")
    void samplesNamedModuleThreads() throws Exception {
        active.set(Set.of("alpha"));
        ControllerTaskScheduler scheduler = tracker().schedulerFor("alpha");
        String threadName = runWorkload(scheduler);

        assertTrue(threadName.startsWith("module-alpha-sched-"), "thread named per module, got: " + threadName);

        tracker.sample();
        var snapshot = tracker.snapshot("alpha");
        assertEquals("alpha", snapshot.moduleId());
        assertTrue(snapshot.liveThreads() >= 1, "the worker thread should be counted");
        assertTrue(snapshot.cpuMillis() >= 0);
        assertTrue(snapshot.allocatedBytes() >= 0);
    }

    @Test
    @DisplayName("reconcile releases schedulers for modules no longer installed")
    void reconcileReleasesUninstalled() {
        active.set(Set.of("beta"));
        ControllerTaskScheduler scheduler = tracker().schedulerFor("beta");

        // Module gets uninstalled -> drops out of the active set.
        active.set(Set.of());
        tracker.sample();

        // Snapshot is gone (empty) and the executor was shut down.
        assertEquals(0, tracker.snapshot("beta").liveThreads());
        assertThrows(RejectedExecutionException.class, () -> scheduler.schedule(() -> {}));
    }

    @Test
    @DisplayName("schedulerFor replaces (shuts down) a prior scheduler for the same module on reload")
    void schedulerForReplacesOnReload() {
        active.set(Set.of("gamma"));
        ControllerTaskScheduler first = tracker().schedulerFor("gamma");
        ControllerTaskScheduler second = tracker.schedulerFor("gamma");

        assertThrows(RejectedExecutionException.class, () -> first.schedule(() -> {}));
        // The replacement is live.
        second.schedule(() -> {});
        assertFalse(tracker.snapshots().containsKey("gamma")); // not sampled yet
    }
}
