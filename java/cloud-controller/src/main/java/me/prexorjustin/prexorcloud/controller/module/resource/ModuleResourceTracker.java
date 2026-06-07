package me.prexorjustin.prexorcloud.controller.module.resource;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import me.prexorjustin.prexorcloud.controller.module.platform.ControllerTaskScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-module resource accounting (northstar-plan Track C.2, stage 1 —
 * "Resource-Tracking"). Each platform module is handed its <em>own</em> named
 * {@link ScheduledExecutorService} ({@code module-&lt;id&gt;-sched-N}) instead of
 * the previous single shared pool, which makes the work a module does on the
 * controller attributable to that module: the threads carry the module id in
 * their name (so JFR/thread-dumps group cleanly) and the tracker periodically
 * samples each thread's CPU time and allocated bytes.
 *
 * <p>CPU time uses {@link ThreadMXBean#getThreadCpuTime(long)}; allocation uses
 * the HotSpot-specific {@code com.sun.management.ThreadMXBean.getThreadAllocatedBytes}
 * when available (it degrades to 0 on JVMs that don't expose it). Because a dead
 * thread can no longer be queried, the tracker remembers each thread's
 * last-known counters and folds them into a per-module "retired" accumulator
 * before pruning, so the reported totals stay monotonic across worker-thread
 * turnover.
 *
 * <p>This is <em>tracking only</em> — no enforcement. Soft limits / quotas are
 * stage 2; the snapshot here is what a quota check (and the dashboard) reads.
 */
public final class ModuleResourceTracker implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ModuleResourceTracker.class);

    /** Immutable per-module sample exposed over REST / metrics. */
    public record Snapshot(String moduleId, long cpuMillis, long allocatedBytes, int liveThreads, Instant sampledAt) {
        public static Snapshot empty(String moduleId, Instant at) {
            return new Snapshot(moduleId, 0, 0, 0, at);
        }
    }

    private static final class Registration {
        final ScheduledExecutorService executor;
        final Set<Long> threadIds = ConcurrentHashMap.newKeySet();
        final Map<Long, long[]> lastSeen = new HashMap<>(); // tid -> {cpuNs, allocBytes}; sampler-thread only
        long retiredCpuNs;
        long retiredAllocBytes;

        Registration(ScheduledExecutorService executor) {
            this.executor = executor;
        }
    }

    private final ThreadMXBean threadBean;
    private final com.sun.management.ThreadMXBean sunThreadBean;
    private final Supplier<Set<String>> activeModuleIds;
    private final long sampleIntervalMillis;
    private final Clock clock;

    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final AtomicInteger threadSeq = new AtomicInteger();

    private ScheduledExecutorService sampler;

    public ModuleResourceTracker(Supplier<Set<String>> activeModuleIds, long sampleIntervalMillis) {
        this(activeModuleIds, sampleIntervalMillis, Clock.systemUTC());
    }

    public ModuleResourceTracker(Supplier<Set<String>> activeModuleIds, long sampleIntervalMillis, Clock clock) {
        this.activeModuleIds = activeModuleIds;
        this.sampleIntervalMillis = Math.max(1000L, sampleIntervalMillis);
        this.clock = clock;
        this.threadBean = ManagementFactory.getThreadMXBean();
        this.sunThreadBean = resolveSunThreadBean(threadBean);
        if (threadBean.isThreadCpuTimeSupported() && !threadBean.isThreadCpuTimeEnabled()) {
            threadBean.setThreadCpuTimeEnabled(true);
        }
    }

    private static com.sun.management.ThreadMXBean resolveSunThreadBean(ThreadMXBean bean) {
        if (bean instanceof com.sun.management.ThreadMXBean sun && sun.isThreadAllocatedMemorySupported()) {
            if (!sun.isThreadAllocatedMemoryEnabled()) {
                sun.setThreadAllocatedMemoryEnabled(true);
            }
            return sun;
        }
        return null;
    }

    /** Start the periodic sampler. Idempotent. */
    public synchronized void start() {
        if (sampler != null) {
            return;
        }
        sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "module-resource-sampler");
            t.setDaemon(true);
            return t;
        });
        sampler.scheduleAtFixedRate(
                this::sampleQuietly, sampleIntervalMillis, sampleIntervalMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Hand a module its own named scheduler. Replaces (and shuts down) any prior
     * scheduler for the same id — a reload calls this again. The returned
     * {@link ControllerTaskScheduler} is what the module sees through its
     * {@code ModuleContext}.
     */
    public ControllerTaskScheduler schedulerFor(String moduleId) {
        release(moduleId);
        Registration registration = new Registration(newModuleExecutor(moduleId));
        registrations.put(moduleId, registration);
        return new ControllerTaskScheduler(registration.executor);
    }

    private ScheduledExecutorService newModuleExecutor(String moduleId) {
        Registration[] holder = new Registration[1];
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "module-" + moduleId + "-sched-" + threadSeq.incrementAndGet());
            t.setDaemon(true);
            Registration reg = registrations.get(moduleId);
            if (reg != null) {
                reg.threadIds.add(t.threadId());
            } else if (holder[0] != null) {
                holder[0].threadIds.add(t.threadId());
            }
            return t;
        };
        ScheduledExecutorService exec = Executors.newScheduledThreadPool(1, factory);
        return exec;
    }

    /** Shut down a module's scheduler and drop its accounting (called on uninstall / reconcile). */
    public void release(String moduleId) {
        Registration registration = registrations.remove(moduleId);
        snapshots.remove(moduleId);
        if (registration != null) {
            registration.executor.shutdownNow();
        }
    }

    /** Latest sample for a module, or an empty snapshot if it hasn't been sampled yet. */
    public Snapshot snapshot(String moduleId) {
        Snapshot snapshot = snapshots.get(moduleId);
        return snapshot != null ? snapshot : Snapshot.empty(moduleId, clock.instant());
    }

    public Map<String, Snapshot> snapshots() {
        return Map.copyOf(snapshots);
    }

    private void sampleQuietly() {
        try {
            sample();
        } catch (RuntimeException e) {
            logger.warn("module resource sampling failed: {}", e.getMessage(), e);
        }
    }

    /** Visible for tests — runs one sampling pass synchronously. */
    public void sample() {
        // Reconcile: drop schedulers for modules that are no longer installed.
        Set<String> active = activeModuleIds.get();
        for (String moduleId : Set.copyOf(registrations.keySet())) {
            if (!active.contains(moduleId)) {
                release(moduleId);
            }
        }
        Instant now = clock.instant();
        for (Map.Entry<String, Registration> entry : registrations.entrySet()) {
            snapshots.put(entry.getKey(), sampleModule(entry.getKey(), entry.getValue(), now));
        }
    }

    private Snapshot sampleModule(String moduleId, Registration registration, Instant now) {
        long liveCpuNs = 0;
        long liveAllocBytes = 0;
        int liveThreads = 0;
        for (Long tid : Set.copyOf(registration.threadIds)) {
            long cpuNs = threadBean.getThreadCpuTime(tid);
            if (cpuNs < 0) {
                // Thread is gone — fold its last-known counters into the retired
                // accumulator so the running total doesn't regress, then prune.
                long[] last = registration.lastSeen.remove(tid);
                if (last != null) {
                    registration.retiredCpuNs += last[0];
                    registration.retiredAllocBytes += last[1];
                }
                registration.threadIds.remove(tid);
                continue;
            }
            long allocBytes = sunThreadBean != null ? Math.max(0, sunThreadBean.getThreadAllocatedBytes(tid)) : 0;
            registration.lastSeen.put(tid, new long[] {cpuNs, allocBytes});
            liveCpuNs += cpuNs;
            liveAllocBytes += allocBytes;
            liveThreads++;
        }
        long totalCpuMillis = (registration.retiredCpuNs + liveCpuNs) / 1_000_000L;
        long totalAllocBytes = registration.retiredAllocBytes + liveAllocBytes;
        return new Snapshot(moduleId, totalCpuMillis, totalAllocBytes, liveThreads, now);
    }

    @Override
    public synchronized void close() {
        if (sampler != null) {
            sampler.shutdownNow();
            sampler = null;
        }
        for (String moduleId : Set.copyOf(registrations.keySet())) {
            release(moduleId);
        }
    }
}
