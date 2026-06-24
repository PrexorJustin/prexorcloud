package me.prexorjustin.prexorcloud.controller.metrics;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import me.prexorjustin.prexorcloud.controller.crash.CrashStore;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.module.platform.ExtensionRegistry;
import me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleManager;
import me.prexorjustin.prexorcloud.controller.session.NodeSessionManager;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.WorkflowStateStore;
import me.prexorjustin.prexorcloud.modules.runtime.ModuleLifecycleManager;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * Micrometer-based metrics collector with Prometheus export.
 */
public final class MetricsCollector {

    private final PrometheusMeterRegistry registry;
    private final Counter compositionPlanningFailures;

    private final Timer schedulerTickTimer;
    private final Counter schedulerTickFailures;
    private final DistributionSummary schedulerGroupsEvaluated;
    private final AtomicLong schedulerLastTickEpochMs = new AtomicLong(0);

    private final Counter leaseAcquisitions;
    private final Counter leaseRenewals;
    private final Counter leaseContentions;
    private final Counter jwtRevocations;
    private final DistributionSummary shareBytes;

    // Micrometer registers gauges/function-counters with a WEAK reference to their state object, so a
    // probe whose only other reference is a local in the caller gets GC'd and the gauge then reads NaN.
    // Retain a strong reference to every probe we register here for the collector's lifetime.
    private final java.util.List<Object> retainedGaugeProbes = new java.util.concurrent.CopyOnWriteArrayList<>();

    public MetricsCollector(ClusterState clusterState, GroupManager groupManager, CrashStore crashStore) {
        this(clusterState, groupManager, crashStore, null, null);
    }

    public MetricsCollector(
            ClusterState clusterState,
            GroupManager groupManager,
            CrashStore crashStore,
            WorkflowStateStore workflowStateStore,
            PlatformModuleManager platformModuleManager) {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        this.compositionPlanningFailures = Counter.builder("prexorcloud.composition.planning.failures")
                .description("Total failed instance composition planning attempts")
                .register(registry);

        this.schedulerTickTimer = Timer.builder("prexorcloud.scheduler.tick.duration")
                .description("Duration of a single scheduler evaluation pass")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.schedulerTickFailures = Counter.builder("prexorcloud.scheduler.tick.failures")
                .description("Total scheduler evaluation passes that threw before completion")
                .register(registry);
        this.schedulerGroupsEvaluated = DistributionSummary.builder("prexorcloud.scheduler.groups_evaluated")
                .description("Groups evaluated per scheduler tick")
                .register(registry);
        Gauge.builder("prexorcloud.scheduler.last_tick.lag.millis", schedulerLastTickEpochMs, source -> {
                    long last = source.get();
                    return last == 0 ? 0 : System.currentTimeMillis() - last;
                })
                .description("Milliseconds since the last completed scheduler tick (0 before first tick)")
                .register(registry);

        this.leaseAcquisitions = Counter.builder("prexorcloud.coordination.lease.acquisitions")
                .description("Distributed leases acquired (excluding renewals)")
                .register(registry);
        this.leaseRenewals = Counter.builder("prexorcloud.coordination.lease.renewals")
                .description("Distributed leases renewed by the current holder")
                .register(registry);
        this.leaseContentions = Counter.builder("prexorcloud.coordination.lease.contentions")
                .description("Distributed lease acquisitions that lost the race to another controller")
                .register(registry);
        this.jwtRevocations = Counter.builder("prexorcloud.coordination.jwt.revocations")
                .description("JWT revocation entries written to the coordination store")
                .register(registry);

        this.shareBytes = DistributionSummary.builder("prexorcloud.share.upload_bytes")
                .description("Redacted UTF-8 byte size of each paste-share upload")
                .baseUnit("bytes")
                .register(registry);

        Gauge.builder("prexorcloud.nodes.total", clusterState, ClusterState::nodeCount)
                .description("Total connected nodes")
                .register(registry);

        Gauge.builder("prexorcloud.instances.total", clusterState, ClusterState::instanceCount)
                .description("Total running instances")
                .register(registry);

        Gauge.builder("prexorcloud.players.total", clusterState, ClusterState::playerCount)
                .description("Total online players")
                .register(registry);

        Gauge.builder(
                        "prexorcloud.groups.total",
                        groupManager,
                        gm -> gm.getAll().size())
                .description("Total configured groups")
                .register(registry);

        Gauge.builder("prexorcloud.crashes.total", crashStore, CrashStore::size)
                .description("Total crash records in buffer")
                .register(registry);

        if (workflowStateStore != null) {
            registerWorkflowMetrics(workflowStateStore);
        }

        if (platformModuleManager != null) {
            registerPlatformModuleMetrics(platformModuleManager);
        }
    }

    /**
     * Scrape metrics in Prometheus exposition format.
     */
    public String scrape() {
        return registry.scrape();
    }

    public PrometheusMeterRegistry registry() {
        return registry;
    }

    public void recordCompositionPlanningFailure() {
        compositionPlanningFailures.increment();
    }

    public void recordSchedulerTick(Duration duration, boolean success, int groupsEvaluated) {
        if (duration != null) {
            schedulerTickTimer.record(duration);
        }
        if (success) {
            schedulerLastTickEpochMs.set(System.currentTimeMillis());
        } else {
            schedulerTickFailures.increment();
        }
        if (groupsEvaluated >= 0) {
            schedulerGroupsEvaluated.record(groupsEvaluated);
        }
    }

    /**
     * Register a gauge per health status counting active modules currently reporting it
     * (Track C.3 — Health-Checks). Bounded cardinality: one series per {@code ModuleHealth.Status}.
     * Wired in bootstrap once the health monitor exists.
     */
    public void registerModuleHealthMetrics(
            me.prexorjustin.prexorcloud.controller.module.health.ModuleHealthMonitor monitor) {
        if (monitor == null) return;
        for (me.prexorjustin.prexorcloud.api.module.platform.ModuleHealth.Status status :
                me.prexorjustin.prexorcloud.api.module.platform.ModuleHealth.Status.values()) {
            Gauge.builder("prexorcloud.module.health", monitor, m -> m.countByStatus(status))
                    .description("Active platform modules by self-reported health status")
                    .tag("status", status.name().toLowerCase(Locale.ROOT))
                    .register(registry);
        }
    }

    public void registerDaemonSessionMetrics(NodeSessionManager sessionManager) {
        if (sessionManager == null) return;
        Gauge.builder("prexorcloud.grpc.daemon.sessions.active", sessionManager, NodeSessionManager::sessionCount)
                .description("Active controller↔daemon gRPC streams")
                .register(registry);
    }

    public void recordDaemonInbound(String payloadCase) {
        registry.counter("prexorcloud.grpc.daemon.messages_in", Tags.of("payload_case", normalizeTag(payloadCase)))
                .increment();
    }

    public void recordDaemonOutbound(String payloadCase, boolean delivered) {
        registry.counter(
                        "prexorcloud.grpc.daemon.messages_out",
                        Tags.of(
                                "payload_case",
                                normalizeTag(payloadCase),
                                "outcome",
                                delivered ? "delivered" : "dropped"))
                .increment();
    }

    public void recordLeaseAcquisition() {
        leaseAcquisitions.increment();
    }

    public void recordLeaseRenewal() {
        leaseRenewals.increment();
    }

    public void recordLeaseContention() {
        leaseContentions.increment();
    }

    public void recordJwtRevocation() {
        jwtRevocations.increment();
    }

    /**
     * Record that a platform module overshot one of its soft resource quotas (Track C.2 stage 2).
     * Tagged by module id and the breached {@code resource} dimension (cpu / allocation / threads),
     * both bounded-cardinality. Advisory only — the module keeps running.
     */
    public void recordModuleQuotaExceeded(String moduleId, String resource) {
        registry.counter(
                        "prexorcloud.module.quota.exceeded",
                        Tags.of("module", normalizeTag(moduleId), "resource", normalizeTag(resource)))
                .increment();
    }

    /**
     * Record a paste-share attempt. {@code outcome} is {@code "success"} on a
     * 2xx upload, or {@code "error"} otherwise. {@code kind} comes from
     * {@code ShareKind#name()} so its cardinality is bounded.
     */
    public void recordShareAttempt(String kind, String outcome) {
        registry.counter(
                        "prexorcloud.share.attempts",
                        Tags.of("kind", normalizeTag(kind), "outcome", normalizeTag(outcome)))
                .increment();
    }

    /** Record a pste upstream error keyed by HTTP status (or {@code "network"} when no response arrived). */
    public void recordShareUpstreamError(int status) {
        String tag = status > 0 ? Integer.toString(status) : "network";
        registry.counter("prexorcloud.share.upstream_errors", Tags.of("status", tag))
                .increment();
    }

    /** Record the byte size of a successful share upload. */
    public void recordShareUploadBytes(long bytes) {
        if (bytes > 0) shareBytes.record(bytes);
    }

    /** Record a revoke attempt outcome (success / error / missing-token). */
    public void recordShareRevoke(String outcome) {
        registry.counter("prexorcloud.share.revocations", Tags.of("outcome", normalizeTag(outcome)))
                .increment();
    }

    /**
     * Registers gauges that read live state off an SSE event streamer
     * (current client count + replay buffer size).
     */
    public void registerSseStreamerMetrics(SseStreamerProbe probe) {
        if (probe == null) return;
        retainedGaugeProbes.add(probe);
        Gauge.builder("prexorcloud.sse.clients.connected", probe, SseStreamerProbe::clientCount)
                .description("SSE clients currently connected to /api/v1/events/stream")
                .register(registry);
        Gauge.builder("prexorcloud.sse.replay.buffer_size", probe, p -> {
                    long latest = p.latestSequence();
                    long earliest = p.earliestSequence();
                    return latest <= 0 || earliest <= 0 ? 0 : Math.max(0, latest - earliest + 1);
                })
                .description("Events currently retained in the SSE replay buffer")
                .register(registry);
    }

    /**
     * Register the single-writer control-plane observability gauges (Phases 1/2/5): leadership state
     * + fencing epoch + renew age, the post-takeover convergence observation phase, and the reactive
     * change-stream reconcile layer. These replace the Raft-role / lease / pub-sub debug signal the
     * rewrite deletes — without them a failover or a stuck reconcile is invisible. Reads live state
     * off a probe so the collector stays decoupled from the cluster package (mirrors
     * {@link SseStreamerProbe}). Wired in bootstrap once the elector exists.
     */
    public void registerLeadershipMetrics(LeadershipMetricsProbe probe) {
        if (probe == null) return;
        retainedGaugeProbes.add(probe);
        Gauge.builder("prexorcloud.leadership.is_leader", probe, p -> p.isLeader() ? 1d : 0d)
                .description("1 if this controller currently holds the leadership lease, else 0")
                .register(registry);
        Gauge.builder("prexorcloud.leadership.epoch", probe, p -> p.currentEpoch())
                .description("Fencing epoch of the leadership this controller holds (0 if never acquired)")
                .register(registry);
        Gauge.builder("prexorcloud.leadership.renew_age.millis", probe, p -> p.renewAgeMillis())
                .description(
                        "Milliseconds since the last confirmed lease renew (-1 if not leader) — proximity to step-down")
                .register(registry);
        FunctionCounter.builder("prexorcloud.leadership.transitions", probe, p -> p.leadershipTransitions())
                .description("Times this controller transitioned follower→leader")
                .register(registry);

        Gauge.builder("prexorcloud.convergence.observing", probe, p -> p.isObserving() ? 1d : 0d)
                .description("1 while the leader is in the post-takeover observation phase (scale-reconcile deferred)")
                .register(registry);
        Gauge.builder("prexorcloud.convergence.last_observation.millis", probe, p -> p.lastObservationDurationMillis())
                .description("Duration of the most recent convergence observation phase in ms (-1 if none completed)")
                .register(registry);

        Gauge.builder("prexorcloud.changestream.running", probe, p -> p.changeStreamRunning() ? 1d : 0d)
                .description("1 while the reactive change-stream reconcile watcher is running (leader only)")
                .register(registry);
        Gauge.builder("prexorcloud.changestream.last_event_age.millis", probe, p -> p.changeStreamLastEventAgeMillis())
                .description("Milliseconds since the last observed change-stream event (-1 if none) — stream lag proxy")
                .register(registry);
        FunctionCounter.builder("prexorcloud.changestream.changes", probe, p -> p.changeStreamChangesObserved())
                .description("Change-stream events observed (each triggers a reconcile)")
                .register(registry);
        FunctionCounter.builder("prexorcloud.changestream.full_resyncs", probe, p -> p.changeStreamFullResyncs())
                .description("Full resyncs forced by a non-resumable change stream (stale token / rolled oplog)")
                .register(registry);
        FunctionCounter.builder("prexorcloud.changestream.opens", probe, p -> p.changeStreamOpens())
                .description("Times the change-stream cursor was (re)opened")
                .register(registry);
    }

    /**
     * Surface the single-writer epoch fence. A nonzero, growing counter means a deposed leader's
     * stale write was actually rejected at the store (the #12 fence firing in anger) — an alarm-worthy
     * signal that until now was only a WARN log line. Wired in bootstrap once the state store exists.
     */
    public void registerStateStoreFenceMetrics(me.prexorjustin.prexorcloud.controller.state.StateStore stateStore) {
        if (stateStore == null) return;
        retainedGaugeProbes.add(stateStore);
        FunctionCounter.builder("prexorcloud.statestore.fenced_write_rejections", stateStore, s -> s.fencedWriteRejections())
                .description("Authority-sensitive writes dropped by the epoch fence (deposed-leader stale writes)")
                .register(registry);
    }

    public void recordHttpRequest(String method, int status, Duration duration) {
        String statusClass = httpStatusClass(status);
        Tags tags = Tags.of("method", normalizeTag(method), "status_class", statusClass);
        registry.counter("prexorcloud.http.requests", tags).increment();
        if (duration != null) {
            registry.timer("prexorcloud.http.request.duration", tags).record(duration.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    private static String httpStatusClass(int status) {
        if (status >= 500) return "5xx";
        if (status >= 400) return "4xx";
        if (status >= 300) return "3xx";
        if (status >= 200) return "2xx";
        if (status >= 100) return "1xx";
        return "unknown";
    }

    private static String normalizeTag(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.toLowerCase(Locale.ROOT);
    }

    private void registerWorkflowMetrics(WorkflowStateStore workflowStateStore) {
        Gauge.builder(
                        "prexorcloud.workflows.pending_transfers",
                        workflowStateStore,
                        store -> store.transferIntents().size())
                .description("Durable player transfer intents awaiting acknowledgement")
                .register(registry);
        Gauge.builder(
                        "prexorcloud.workflows.node_drains",
                        workflowStateStore,
                        store -> store.nodeDrains().size())
                .description("Durable node drain intents awaiting completion")
                .register(registry);
        Gauge.builder(
                        "prexorcloud.workflows.healing_actions",
                        workflowStateStore,
                        store -> store.healingActions().size())
                .description("Durable healing actions awaiting reconciliation")
                .register(registry);
        Gauge.builder(
                        "prexorcloud.workflows.start_retries",
                        workflowStateStore,
                        store -> store.startRetries().size())
                .description("Durable start retry intents awaiting reconciliation")
                .register(registry);
    }

    private void registerPlatformModuleMetrics(PlatformModuleManager platformModuleManager) {
        Gauge.builder(
                        "prexorcloud.platform_modules.total",
                        platformModuleManager,
                        manager -> manager.listModules().size())
                .description("Total platform modules known to the controller")
                .register(registry);
        for (ModuleLifecycleManager.ModuleState state : ModuleLifecycleManager.ModuleState.values()) {
            Gauge.builder(
                            "prexorcloud.platform_modules.state",
                            platformModuleManager,
                            manager -> moduleStateCount(manager, state))
                    .description("Platform module count by lifecycle state")
                    .tag("state", state.name().toLowerCase(java.util.Locale.ROOT))
                    .register(registry);
        }
        Gauge.builder("prexorcloud.platform_extensions.total", platformModuleManager, MetricsCollector::extensionCount)
                .description("Total registered workload extensions from non-failed platform modules")
                .register(registry);
        Gauge.builder(
                        "prexorcloud.platform_extension_variants.total",
                        platformModuleManager,
                        MetricsCollector::variantCount)
                .description("Total registered workload extension variants from non-failed platform modules")
                .register(registry);
        FunctionCounter.builder(
                        "prexorcloud.capabilities.resolutions",
                        platformModuleManager,
                        manager -> manager.capabilityRegistry().metrics().resolutionCount())
                .description("Total capability resolution passes")
                .register(registry);
        FunctionCounter.builder(
                        "prexorcloud.capabilities.rebindings",
                        platformModuleManager,
                        manager -> manager.capabilityRegistry().metrics().rebindingEventCount())
                .description("Total capability rebinding events")
                .register(registry);
        Gauge.builder(
                        "prexorcloud.capabilities.unresolved_requirements",
                        platformModuleManager,
                        manager -> manager.capabilityRegistry().metrics().unresolvedRequirementCount())
                .description("Current unresolved platform module capability requirements")
                .register(registry);
        Gauge.builder(
                        "prexorcloud.capabilities.last_resolution_latency_millis",
                        platformModuleManager,
                        manager -> manager.capabilityRegistry()
                                .metrics()
                                .lastResolutionLatency()
                                .toMillis())
                .description("Last capability resolution latency in milliseconds")
                .register(registry);
        FunctionCounter.builder(
                        "prexorcloud.capabilities.deprecated_resolutions",
                        platformModuleManager,
                        manager -> manager.capabilityRegistry().metrics().deprecatedProviderResolutionCount())
                .description("Capability resolutions that landed on a deprecated provider")
                .register(registry);

        registerClassLoaderLeakMetrics(platformModuleManager);
    }

    private void registerClassLoaderLeakMetrics(PlatformModuleManager platformModuleManager) {
        Gauge.builder("prexorcloud.module.classloader.pending", platformModuleManager, manager -> {
                    var tracker = manager.classLoaderTracker();
                    return tracker == null ? 0d : tracker.pendingCount();
                })
                .description("Module classloaders awaiting collection after unload")
                .register(registry);
        FunctionCounter.builder("prexorcloud.module.classloader.tracked.total", platformModuleManager, manager -> {
                    var tracker = manager.classLoaderTracker();
                    return tracker == null ? 0d : tracker.totalTracked();
                })
                .description("Total module classloaders tracked since startup")
                .register(registry);
        FunctionCounter.builder("prexorcloud.module.classloader.collected.total", platformModuleManager, manager -> {
                    var tracker = manager.classLoaderTracker();
                    return tracker == null ? 0d : tracker.totalCollected();
                })
                .description("Total module classloaders observed to be GC'd after unload")
                .register(registry);
        FunctionCounter.builder("prexorcloud.module.classloader.leaked", platformModuleManager, manager -> {
                    var tracker = manager.classLoaderTracker();
                    return tracker == null ? 0d : tracker.totalLeaks();
                })
                .description("Total leak detections (one per poll a loader survives past the threshold)")
                .register(registry);
    }

    private static double moduleStateCount(
            PlatformModuleManager platformModuleManager, ModuleLifecycleManager.ModuleState state) {
        return platformModuleManager.listModules().stream()
                .filter(module -> module.state() == state)
                .count();
    }

    private static double extensionCount(PlatformModuleManager platformModuleManager) {
        return platformModuleManager.extensionRegistry().listExtensions().size();
    }

    private static double variantCount(PlatformModuleManager platformModuleManager) {
        ExtensionRegistry extensionRegistry = platformModuleManager.extensionRegistry();
        return extensionRegistry.listExtensions().stream()
                .mapToLong(extension -> extension.extension().variants().size())
                .sum();
    }

    /**
     * Minimal probe interface so {@link MetricsCollector} can read live SSE
     * streamer state without depending on the rest layer at compile time.
     */
    public interface SseStreamerProbe {
        int clientCount();

        long latestSequence();

        long earliestSequence();
    }

    /**
     * Probe over the single-writer control-plane components (leader elector + convergence gate +
     * change-stream reconciler) so {@link MetricsCollector} can read their live state without a
     * compile-time dependency on the cluster package. Mirrors {@link SseStreamerProbe}.
     */
    public interface LeadershipMetricsProbe {
        // leadership (MongoLeaderElector)
        boolean isLeader();

        long currentEpoch();

        long leadershipTransitions();

        long renewAgeMillis();

        // convergence gate
        boolean isObserving();

        long lastObservationDurationMillis();

        // change-stream reconcile layer
        boolean changeStreamRunning();

        long changeStreamChangesObserved();

        long changeStreamFullResyncs();

        long changeStreamOpens();

        long changeStreamLastEventAgeMillis();
    }
}
