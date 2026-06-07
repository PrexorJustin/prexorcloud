package me.prexorjustin.prexorcloud.controller.module.resource;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import me.prexorjustin.prexorcloud.controller.config.ModuleQuota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-module soft-limit enforcement (northstar-plan Track C.2, stage 2 — "Soft-Limits").
 *
 * <p>Reads the cumulative {@link ModuleResourceTracker.Snapshot} written by stage 1 and derives
 * a <em>per-minute rate</em> for CPU and allocation by differencing consecutive readings. Those
 * rates — plus the instantaneous live-thread count — are compared against the operator-configured
 * {@link ModuleQuota} for each module. A breach is purely advisory: it raises a WARN log (only on
 * the transition <em>into</em> breach, so a sustained overshoot doesn't spam the log every minute)
 * and feeds the {@code prexorcloud.module.quota.exceeded} counter through the {@link BreachSink}.
 * Nothing is throttled or killed — hard isolation (stage 3) is what enforces.
 *
 * <p>The enforcer runs its own minute-cadence ticker rather than piggy-backing on the tracker's
 * 10-second sampler: quotas are expressed per minute, so a one-minute differencing window keeps the
 * derived rate stable and directly comparable to the configured limit. The first tick for a module
 * only records a baseline (no prior reading to diff against), so evaluation begins on the second tick.
 */
public final class ModuleQuotaEnforcer implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ModuleQuotaEnforcer.class);
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final long MILLIS_PER_MINUTE = 60_000L;
    /** Below this elapsed window a derived per-minute rate is too noisy to trust; skip the diff. */
    private static final long MIN_ELAPSED_MILLIS = 1_000L;

    /** The three quota dimensions, used as the {@code resource} metric tag. */
    public enum Resource {
        CPU("cpu"),
        ALLOCATION("allocation"),
        THREADS("threads");

        private final String tag;

        Resource(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    /** Sink for breach events. {@code MetricsCollector::recordModuleQuotaExceeded} is the production binding. */
    @FunctionalInterface
    public interface BreachSink {
        void onBreach(String moduleId, Resource resource);
    }

    /** A prior reading kept to difference cumulative totals into a per-minute rate. */
    private record Reading(long cpuMillis, long allocatedBytes, Instant at) {}

    /** Latest computed evaluation per module — exposed read-only for REST / dashboard. */
    public record Evaluation(
            String moduleId,
            ModuleQuota quota,
            long cpuMillisPerMinute,
            long allocatedMbPerMinute,
            int liveThreads,
            boolean cpuExceeded,
            boolean allocationExceeded,
            boolean threadsExceeded,
            Instant evaluatedAt) {
        public boolean anyExceeded() {
            return cpuExceeded || allocationExceeded || threadsExceeded;
        }
    }

    private final Function<String, ModuleResourceTracker.Snapshot> snapshotSource;
    private final Map<String, ModuleQuota> quotas;
    private final BreachSink sink;
    private final long evaluationIntervalMillis;
    private final Clock clock;

    private final Map<String, Reading> lastReadings = new ConcurrentHashMap<>();
    private final Map<String, Evaluation> evaluations = new ConcurrentHashMap<>();
    // module-id + resource keys currently in breach, so WARNs fire only on the rising edge.
    private final Map<String, Boolean> breaching = new ConcurrentHashMap<>();

    private ScheduledExecutorService ticker;

    public ModuleQuotaEnforcer(ModuleResourceTracker tracker, Map<String, ModuleQuota> quotas, BreachSink sink) {
        this(tracker::snapshot, quotas, sink, MILLIS_PER_MINUTE, Clock.systemUTC());
    }

    /**
     * Primary constructor. {@code snapshotSource} maps a module id to its latest cumulative
     * snapshot — in production it's {@code ModuleResourceTracker::snapshot}; tests supply a
     * controllable source paired with a stepped {@link Clock} to drive deterministic rates.
     */
    public ModuleQuotaEnforcer(
            Function<String, ModuleResourceTracker.Snapshot> snapshotSource,
            Map<String, ModuleQuota> quotas,
            BreachSink sink,
            long evaluationIntervalMillis,
            Clock clock) {
        this.snapshotSource = snapshotSource;
        this.quotas = Map.copyOf(quotas);
        this.sink = sink != null ? sink : (m, r) -> {};
        this.evaluationIntervalMillis = Math.max(MIN_ELAPSED_MILLIS, evaluationIntervalMillis);
        this.clock = clock;
    }

    /** True if any module has at least one enforceable limit — otherwise there's nothing to run. */
    public boolean hasEnforceableQuotas() {
        return quotas.values().stream().anyMatch(ModuleQuota::enforcesAnything);
    }

    /** Start the periodic evaluation ticker. Idempotent; a no-op if no quota enforces anything. */
    public synchronized void start() {
        if (ticker != null || !hasEnforceableQuotas()) {
            return;
        }
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "module-quota-enforcer");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleAtFixedRate(
                this::evaluateQuietly, evaluationIntervalMillis, evaluationIntervalMillis, TimeUnit.MILLISECONDS);
    }

    private void evaluateQuietly() {
        try {
            evaluate();
        } catch (RuntimeException e) {
            logger.warn("module quota evaluation failed: {}", e.getMessage(), e);
        }
    }

    /** Visible for tests — runs one evaluation pass synchronously. */
    public void evaluate() {
        Instant now = clock.instant();
        for (Map.Entry<String, ModuleQuota> entry : quotas.entrySet()) {
            String moduleId = entry.getKey();
            ModuleQuota quota = entry.getValue();
            if (!quota.enforcesAnything()) {
                continue;
            }
            ModuleResourceTracker.Snapshot snapshot = snapshotSource.apply(moduleId);
            Reading previous =
                    lastReadings.put(moduleId, new Reading(snapshot.cpuMillis(), snapshot.allocatedBytes(), now));
            if (previous == null) {
                continue; // first observation — only establishes the baseline.
            }
            long elapsedMillis = now.toEpochMilli() - previous.at().toEpochMilli();
            if (elapsedMillis < MIN_ELAPSED_MILLIS) {
                continue;
            }
            evaluations.put(moduleId, evaluateModule(moduleId, quota, snapshot, previous, elapsedMillis, now));
        }
    }

    private Evaluation evaluateModule(
            String moduleId,
            ModuleQuota quota,
            ModuleResourceTracker.Snapshot snapshot,
            Reading previous,
            long elapsedMillis,
            Instant now) {
        long cpuPerMin = ratePerMinute(snapshot.cpuMillis() - previous.cpuMillis(), elapsedMillis);
        long allocBytesPerMin = ratePerMinute(snapshot.allocatedBytes() - previous.allocatedBytes(), elapsedMillis);
        long allocMbPerMin = allocBytesPerMin / BYTES_PER_MB;
        int liveThreads = snapshot.liveThreads();

        boolean cpuExceeded = quota.limitsCpu() && cpuPerMin > quota.maxCpuMillisPerMinute();
        boolean allocExceeded = quota.limitsAllocation() && allocMbPerMin > quota.maxAllocatedMbPerMinute();
        boolean threadsExceeded = quota.limitsThreads() && liveThreads > quota.maxThreads();

        recordBreach(moduleId, Resource.CPU, cpuExceeded, cpuPerMin, quota.maxCpuMillisPerMinute(), "ms-cpu/min");
        recordBreach(
                moduleId, Resource.ALLOCATION, allocExceeded, allocMbPerMin, quota.maxAllocatedMbPerMinute(), "MB/min");
        recordBreach(moduleId, Resource.THREADS, threadsExceeded, liveThreads, quota.maxThreads(), "threads");

        return new Evaluation(
                moduleId,
                quota,
                cpuPerMin,
                allocMbPerMin,
                liveThreads,
                cpuExceeded,
                allocExceeded,
                threadsExceeded,
                now);
    }

    private void recordBreach(
            String moduleId, Resource resource, boolean exceeded, long observed, long limit, String unit) {
        String key = moduleId + ':' + resource.tag();
        boolean wasBreaching = breaching.getOrDefault(key, false);
        if (exceeded) {
            sink.onBreach(moduleId, resource);
            if (!wasBreaching) {
                logger.warn(
                        "module '{}' exceeded its {} quota: {} {} (limit {})",
                        moduleId,
                        resource.tag(),
                        observed,
                        unit,
                        limit);
                breaching.put(key, true);
            }
        } else if (wasBreaching) {
            logger.info(
                    "module '{}' is back within its {} quota: {} {} (limit {})",
                    moduleId,
                    resource.tag(),
                    observed,
                    unit,
                    limit);
            breaching.put(key, false);
        }
    }

    private static long ratePerMinute(long delta, long elapsedMillis) {
        if (delta <= 0 || elapsedMillis <= 0) {
            return 0;
        }
        return delta * MILLIS_PER_MINUTE / elapsedMillis;
    }

    /** Latest evaluation for a module, if it has been evaluated at least once. */
    public Optional<Evaluation> evaluation(String moduleId) {
        return Optional.ofNullable(evaluations.get(moduleId));
    }

    /** Snapshot of all current evaluations, keyed by module id. */
    public Map<String, Evaluation> evaluations() {
        return new LinkedHashMap<>(evaluations);
    }

    /** The configured quota for a module, if any. */
    public Optional<ModuleQuota> quotaFor(String moduleId) {
        return Optional.ofNullable(quotas.get(moduleId));
    }

    @Override
    public synchronized void close() {
        if (ticker != null) {
            ticker.shutdownNow();
            ticker = null;
        }
    }
}
