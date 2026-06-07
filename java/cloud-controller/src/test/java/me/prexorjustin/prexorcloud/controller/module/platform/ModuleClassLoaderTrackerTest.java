package me.prexorjustin.prexorcloud.controller.module.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModuleClassLoaderTrackerTest {

    private ModuleClassLoaderTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ModuleClassLoaderTracker(Duration.ofMillis(100), Duration.ofMillis(20));
    }

    @AfterEach
    void tearDown() {
        if (tracker != null) {
            tracker.close();
        }
    }

    @Test
    void collectsLoaderWhenReleased() throws Exception {
        // Use a generous leak threshold here — the assertion below requires
        // GC to collect the loader before the leak window expires. On slow
        // CI runners the default 100ms in setUp() is not enough and the
        // loader is reported as a leak before the GC hint takes effect.
        try (ModuleClassLoaderTracker localTracker =
                new ModuleClassLoaderTracker(Duration.ofSeconds(30), Duration.ofMillis(20))) {
            URLClassLoader loader = new URLClassLoader(new URL[0]);
            localTracker.track("alpha", "1.0.0", loader);
            loader.close();
            loader = null;

            long deadline = System.currentTimeMillis() + 5_000;
            while (localTracker.totalCollected() == 0 && System.currentTimeMillis() < deadline) {
                localTracker.forceCollectionHintForTesting();
                Thread.sleep(50);
            }

            assertEquals(0, localTracker.pendingCount(), "pending should be 0 after collection");
            assertEquals(1, localTracker.totalTracked());
            assertTrue(localTracker.totalCollected() >= 1, "at least one collection event");
            assertEquals(0, localTracker.totalLeaks(), "no leak when loader is reachable for GC");
        }
    }

    @Test
    void emitsLeakReportWhenReferenceIsPinned() throws Exception {
        URLClassLoader loader = new URLClassLoader(new URL[0]);
        List<URLClassLoader> pin = new ArrayList<>();
        pin.add(loader);

        List<ModuleClassLoaderTracker.LeakReport> reports = new CopyOnWriteArrayList<>();
        tracker.addListener(reports::add);
        tracker.track("beta", "2.0.0", loader);

        long deadline = System.currentTimeMillis() + 2_000;
        while (reports.isEmpty() && System.currentTimeMillis() < deadline) {
            tracker.forceCollectionHintForTesting();
            Thread.sleep(50);
        }
        assertFalse(reports.isEmpty(), "leak listener should have fired");
        ModuleClassLoaderTracker.LeakReport report = reports.get(0);
        assertEquals("beta", report.moduleId());
        assertEquals("2.0.0", report.moduleVersion());
        assertNotNull(report.classLoaderClassName());
        assertTrue(report.age().toMillis() >= 100, "age must reach the leak threshold");
        assertTrue(tracker.totalLeaks() >= 1);

        pin.clear();
        loader.close();
    }

    @Test
    void requestForcedCleanupBumpsCounterAndDrainsCollected() throws Exception {
        URLClassLoader loader = new URLClassLoader(new URL[0]);
        tracker.track("gamma", "3.0.0", loader);
        loader.close();
        loader = null;

        long before = tracker.totalForcedCleanupHints();
        long deadline = System.currentTimeMillis() + 5_000;
        while (tracker.totalCollected() == 0 && System.currentTimeMillis() < deadline) {
            tracker.requestForcedCleanup();
            Thread.sleep(50);
        }

        assertTrue(tracker.totalForcedCleanupHints() > before, "forced-cleanup counter should advance");
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    void snapshotPendingReturnsLiveReports() {
        URLClassLoader loader = new URLClassLoader(new URL[0]);
        List<URLClassLoader> pin = new ArrayList<>();
        pin.add(loader);
        tracker.track("delta", "4.0.0", loader);

        var reports = tracker.snapshotPending();
        assertEquals(1, reports.size());
        assertEquals("delta", reports.get(0).moduleId());
        assertEquals("4.0.0", reports.get(0).moduleVersion());

        pin.clear();
    }

    @Test
    void closeStopsScheduledPolling() {
        tracker.close();
        // second close is a no-op
        tracker.close();
    }

    @Test
    void rejectsInvalidConstructorArgs() {
        try {
            new ModuleClassLoaderTracker(Duration.ZERO, Duration.ofSeconds(1)).close();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException _) {
        }
        try {
            new ModuleClassLoaderTracker(Duration.ofSeconds(1), Duration.ZERO).close();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException _) {
        }
    }
}
