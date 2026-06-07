package me.prexorjustin.prexorcloud.controller.module.platform;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects platform-module classloader leaks after unload.
 *
 * <p>Each tracked classloader is wrapped in a {@link PhantomReference} associated with a
 * {@link ReferenceQueue}. A scheduled poll drains the queue and considers the loader
 * collected. If a loader is still pending after {@link #leakThreshold()}, it is recorded
 * as a leak: leak counters increment and registered listeners are notified so callers can
 * emit metrics or audit log entries.
 */
public final class ModuleClassLoaderTracker implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModuleClassLoaderTracker.class);

    @FunctionalInterface
    public interface LeakListener {
        void onLeak(LeakReport report);
    }

    public record LeakReport(
            String moduleId,
            String moduleVersion,
            String classLoaderClassName,
            Instant trackedAt,
            Duration age,
            int repeatCount) {}

    private final Duration leakThreshold;
    private final Duration pollInterval;
    private final ScheduledExecutorService executor;
    private final boolean ownsExecutor;
    private final ReferenceQueue<ClassLoader> referenceQueue = new ReferenceQueue<>();
    private final Map<TrackedRef, TrackingState> tracked = new LinkedHashMap<>();
    private final List<LeakListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong totalTracked = new AtomicLong();
    private final AtomicLong totalCollected = new AtomicLong();
    private final AtomicLong totalLeaks = new AtomicLong();
    private final AtomicLong totalForcedHints = new AtomicLong();
    private ScheduledFuture<?> pollHandle;
    private boolean closed;

    public ModuleClassLoaderTracker() {
        this(Duration.ofSeconds(30), Duration.ofSeconds(5), null);
    }

    public ModuleClassLoaderTracker(Duration leakThreshold, Duration pollInterval) {
        this(leakThreshold, pollInterval, null);
    }

    public ModuleClassLoaderTracker(
            Duration leakThreshold, Duration pollInterval, ScheduledExecutorService externalExecutor) {
        if (Objects.requireNonNull(leakThreshold, "leakThreshold").isNegative() || leakThreshold.isZero()) {
            throw new IllegalArgumentException("leakThreshold must be positive");
        }
        if (Objects.requireNonNull(pollInterval, "pollInterval").isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
        this.leakThreshold = leakThreshold;
        this.pollInterval = pollInterval;
        if (externalExecutor != null) {
            this.executor = externalExecutor;
            this.ownsExecutor = false;
        } else {
            this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "platform-module-classloader-tracker");
                thread.setDaemon(true);
                return thread;
            });
            this.ownsExecutor = true;
        }
        this.pollHandle = this.executor.scheduleWithFixedDelay(
                this::pollSafely, pollInterval.toMillis(), pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized void track(String moduleId, String moduleVersion, ClassLoader classLoader) {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(classLoader, "classLoader");
        if (closed) {
            return;
        }
        TrackedRef ref = new TrackedRef(classLoader, referenceQueue);
        tracked.put(
                ref,
                new TrackingState(
                        moduleId, moduleVersion, classLoader.getClass().getName(), Instant.now()));
        totalTracked.incrementAndGet();
    }

    public void addListener(LeakListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public Duration leakThreshold() {
        return leakThreshold;
    }

    public Duration pollInterval() {
        return pollInterval;
    }

    public long totalTracked() {
        return totalTracked.get();
    }

    public long totalCollected() {
        return totalCollected.get();
    }

    public long totalLeaks() {
        return totalLeaks.get();
    }

    public long totalForcedCleanupHints() {
        return totalForcedHints.get();
    }

    public synchronized int pendingCount() {
        return tracked.size();
    }

    public synchronized List<LeakReport> snapshotPending() {
        List<LeakReport> reports = new ArrayList<>(tracked.size());
        Instant now = Instant.now();
        for (TrackingState state : tracked.values()) {
            reports.add(new LeakReport(
                    state.moduleId,
                    state.moduleVersion,
                    state.classLoaderClassName,
                    state.trackedAt,
                    Duration.between(state.trackedAt, now),
                    state.repeatCount));
        }
        return List.copyOf(reports);
    }

    /**
     * Operator-triggered cleanup hint. Increments the forced-hints counter,
     * asks the JVM to collect garbage, and runs an immediate poll. Used by the
     * platform-module leak REST surface as the escalation step when the
     * tracker reports a long-lived pending classloader. Forcing GC perturbs
     * application throughput, so this should be invoked manually rather than
     * on a schedule.
     */
    public void requestForcedCleanup() {
        totalForcedHints.incrementAndGet();
        System.gc();
        pollSafely();
    }

    /**
     * @deprecated kept for tests that predated the operator-facing surface.
     *             Call {@link #requestForcedCleanup()} from new code.
     */
    @Deprecated(forRemoval = false)
    public void forceCollectionHintForTesting() {
        requestForcedCleanup();
    }

    /**
     * Drain queued references and re-evaluate live ones. Public for tests; the scheduled
     * executor invokes this periodically.
     */
    public synchronized void pollNow() {
        Reference<? extends ClassLoader> drained;
        while ((drained = referenceQueue.poll()) != null) {
            TrackingState removed = tracked.remove(drained);
            if (removed != null) {
                totalCollected.incrementAndGet();
            }
        }

        if (tracked.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        Iterator<Map.Entry<TrackedRef, TrackingState>> iterator =
                tracked.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<TrackedRef, TrackingState> entry = iterator.next();
            TrackingState state = entry.getValue();
            Duration age = Duration.between(state.trackedAt, now);
            if (age.compareTo(leakThreshold) < 0) {
                continue;
            }
            state.repeatCount++;
            totalLeaks.incrementAndGet();
            LeakReport report = new LeakReport(
                    state.moduleId,
                    state.moduleVersion,
                    state.classLoaderClassName,
                    state.trackedAt,
                    age,
                    state.repeatCount);
            LOGGER.warn(
                    "module classloader leak detected: moduleId={} version={} loader={} age={}ms repeats={}",
                    report.moduleId(),
                    report.moduleVersion(),
                    report.classLoaderClassName(),
                    report.age().toMillis(),
                    report.repeatCount());
            for (LeakListener listener : listeners) {
                try {
                    listener.onLeak(report);
                } catch (RuntimeException ex) {
                    LOGGER.warn("module classloader leak listener threw", ex);
                }
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (pollHandle != null) {
            pollHandle.cancel(false);
            pollHandle = null;
        }
        if (ownsExecutor) {
            executor.shutdownNow();
        }
        tracked.clear();
        listeners.clear();
    }

    private void pollSafely() {
        try {
            pollNow();
        } catch (RuntimeException ex) {
            LOGGER.warn("module classloader tracker poll failed", ex);
        }
    }

    public Optional<Instant> firstTrackedAt(String moduleId) {
        Objects.requireNonNull(moduleId, "moduleId");
        synchronized (this) {
            for (TrackingState state : tracked.values()) {
                if (state.moduleId.equals(moduleId)) {
                    return Optional.of(state.trackedAt);
                }
            }
        }
        return Optional.empty();
    }

    private static final class TrackedRef extends PhantomReference<ClassLoader> {
        TrackedRef(ClassLoader referent, ReferenceQueue<? super ClassLoader> queue) {
            super(referent, queue);
        }
    }

    private static final class TrackingState {
        final String moduleId;
        final String moduleVersion;
        final String classLoaderClassName;
        final Instant trackedAt;
        int repeatCount;

        TrackingState(String moduleId, String moduleVersion, String classLoaderClassName, Instant trackedAt) {
            this.moduleId = moduleId;
            this.moduleVersion = moduleVersion;
            this.classLoaderClassName = classLoaderClassName;
            this.trackedAt = trackedAt;
        }
    }
}
