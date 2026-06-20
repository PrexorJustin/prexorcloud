package me.prexorjustin.prexorcloud.controller;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import me.prexorjustin.prexorcloud.common.config.YamlConfigLoader;
import me.prexorjustin.prexorcloud.controller.auth.AuthManager;
import me.prexorjustin.prexorcloud.controller.auth.JwtRevocationStore;
import me.prexorjustin.prexorcloud.controller.auth.MongoRoleStore;
import me.prexorjustin.prexorcloud.controller.catalog.CatalogStore;
import me.prexorjustin.prexorcloud.controller.config.ControllerConfig;
import me.prexorjustin.prexorcloud.controller.console.ConsoleBuffer;
import me.prexorjustin.prexorcloud.controller.crash.CrashLoopDetector;
import me.prexorjustin.prexorcloud.controller.crash.CrashStore;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.event_choreography.EventChoreographer;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.group.MongoGroupStore;
import me.prexorjustin.prexorcloud.controller.metrics.MetricsCollector;
import me.prexorjustin.prexorcloud.controller.module.ModuleRegistry;
import me.prexorjustin.prexorcloud.controller.network.MongoNetworkStore;
import me.prexorjustin.prexorcloud.controller.network.NetworkManager;
import me.prexorjustin.prexorcloud.controller.observability.ControllerLogBuffer;
import me.prexorjustin.prexorcloud.controller.observability.DaemonLogStore;
import me.prexorjustin.prexorcloud.controller.scheduler.Scheduler;
import me.prexorjustin.prexorcloud.controller.session.NodeSession;
import me.prexorjustin.prexorcloud.controller.session.NodeSessionManager;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.MetricsTimeseries;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.controller.state.WorkflowStateStore;
import me.prexorjustin.prexorcloud.controller.template.BaseTemplateGenerator;
import me.prexorjustin.prexorcloud.controller.template.TemplateManager;
import me.prexorjustin.prexorcloud.controller.template.TemplateMerger;
import me.prexorjustin.prexorcloud.security.ca.CertificateAuthority;
import me.prexorjustin.prexorcloud.security.jwt.JwtManager;
import me.prexorjustin.prexorcloud.security.token.JoinTokenStore;

import org.jetbrains.annotations.Nullable;

/**
 * Central service registry of the controller.
 *
 * <p>
 * This class exposes all core subsystems but does not contain initialization
 * logic. It is fully constructed by the bootstrap.
 * </p>
 */
public final class PrexorController {

    // --- Nested service group records ---

    public record CoreServices(
            EventBus eventBus,
            ClusterState clusterState,
            WorkflowStateStore workflowStateStore,
            NodeSessionManager sessionManager,
            ConsoleBuffer consoleBuffer,
            ControllerLogBuffer logBuffer) {}

    public record SecurityServices(
            CertificateAuthority ca, JwtManager jwtManager, JoinTokenStore joinTokenStore, char[] caPassword) {}

    public record AuthServices(
            StateStore stateStore,
            AuthManager authManager,
            MongoRoleStore roleStore,
            JwtRevocationStore revocationStore) {}

    public record TemplateServices(
            TemplateManager templateManager,
            TemplateMerger templateMerger,
            MongoGroupStore groupStore,
            GroupManager groupManager,
            CatalogStore catalogStore,
            BaseTemplateGenerator baseTemplateGenerator) {}

    public record CrashServices(CrashStore crashStore, CrashLoopDetector crashLoopDetector) {}

    public record NetworkServices(MongoNetworkStore networkStore, NetworkManager networkManager) {}

    public record ObservabilityServices(MetricsCollector metricsCollector) {}

    // --- Fields ---

    private static final Path CONFIG_PATH = Path.of("config", "controller.yml");
    private final AtomicReference<ControllerConfig> config;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    private final EventBus eventBus;
    private final ClusterState clusterState;
    private final WorkflowStateStore workflowStateStore;
    private final NodeSessionManager sessionManager;
    private final ConsoleBuffer consoleBuffer;
    private final ControllerLogBuffer logBuffer;

    private final CertificateAuthority ca;
    private final JwtManager jwtManager;
    private final JoinTokenStore joinTokenStore;

    private final StateStore stateStore;
    private final AuthManager authManager;
    private final MongoRoleStore roleStore;
    private final JwtRevocationStore revocationStore;

    private final TemplateManager templateManager;
    private final TemplateMerger templateMerger;
    private final MongoGroupStore groupStore;
    private final GroupManager groupManager;
    private final CatalogStore catalogStore;
    private final BaseTemplateGenerator baseTemplateGenerator;

    private final CrashStore crashStore;
    private final CrashLoopDetector crashLoopDetector;

    private final MongoNetworkStore networkStore;
    private final NetworkManager networkManager;

    private final ModuleRegistry moduleRegistry;
    private final MetricsCollector metricsCollector;
    private final MetricsTimeseries metricsTimeseries;
    private final PreWarmService preWarmService;
    private final DaemonLogStore daemonLogStore;
    private final EventChoreographer eventChoreographer;

    private Scheduler scheduler;
    private volatile me.prexorjustin.prexorcloud.controller.rest.middleware.CorsAllowList corsAllowList;
    private volatile me.prexorjustin.prexorcloud.controller.rest.middleware.AllowedSubnetsList allowedSubnetsList;
    private me.prexorjustin.prexorcloud.controller.auth.passwordreset.PasswordResetManager passwordResetManager;
    private me.prexorjustin.prexorcloud.controller.share.ShareService shareService;
    private me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlPlane clusterControlPlane;
    private me.prexorjustin.prexorcloud.controller.cluster.ClusterReadView clusterReadView;
    private me.prexorjustin.prexorcloud.controller.module.resource.ModuleResourceTracker moduleResourceTracker;
    private me.prexorjustin.prexorcloud.controller.module.resource.ModuleQuotaEnforcer moduleQuotaEnforcer;
    private me.prexorjustin.prexorcloud.controller.module.health.ModuleHealthMonitor moduleHealthMonitor;
    private me.prexorjustin.prexorcloud.controller.observability.telemetry.Telemetry telemetry;
    private me.prexorjustin.prexorcloud.controller.diagnostics.InstanceFileTreeService instanceFileTreeService;
    private me.prexorjustin.prexorcloud.controller.diagnostics.InstanceFileContentService instanceFileContentService;

    public PrexorController(
            ControllerConfig config,
            CoreServices core,
            SecurityServices security,
            AuthServices auth,
            TemplateServices templates,
            CrashServices crash,
            NetworkServices network,
            ModuleRegistry modules,
            ObservabilityServices obs) {
        this.config = new AtomicReference<>(config);
        this.eventBus = core.eventBus();
        this.clusterState = core.clusterState();
        this.workflowStateStore = core.workflowStateStore();
        this.sessionManager = core.sessionManager();
        this.consoleBuffer = core.consoleBuffer();
        this.logBuffer = core.logBuffer();
        this.ca = security.ca();
        this.jwtManager = security.jwtManager();
        this.joinTokenStore = security.joinTokenStore();
        this.stateStore = auth.stateStore();
        this.authManager = auth.authManager();
        this.roleStore = auth.roleStore();
        this.revocationStore = auth.revocationStore();
        this.templateManager = templates.templateManager();
        this.templateMerger = templates.templateMerger();
        this.groupStore = templates.groupStore();
        this.groupManager = templates.groupManager();
        this.catalogStore = templates.catalogStore();
        this.baseTemplateGenerator = templates.baseTemplateGenerator();
        this.crashStore = crash.crashStore();
        this.crashLoopDetector = crash.crashLoopDetector();
        this.networkStore = network.networkStore();
        this.networkManager = network.networkManager();
        this.moduleRegistry = modules;
        this.metricsCollector = obs.metricsCollector();
        this.metricsTimeseries = new MetricsTimeseries(
                config.metrics().collectionIntervalSeconds(), config.metrics().retentionHours());
        this.preWarmService = new PreWarmService(groupManager, catalogStore);
        this.daemonLogStore = new DaemonLogStore();
        this.eventChoreographer = new EventChoreographer(config.events(), eventBus);
    }

    /**
     * Sets scheduler after construction (due to circular dependency).
     */
    public void setScheduler(Scheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    /**
     * @return scheduler (never null after startup)
     */
    public Scheduler scheduler() {
        if (scheduler == null) throw new IllegalStateException("Scheduler not initialized");
        return scheduler;
    }

    public boolean hasScheduler() {
        return scheduler != null;
    }

    public void shutdown() {
        shutdownLatch.countDown();
    }

    public void awaitShutdown() throws InterruptedException {
        shutdownLatch.await();
    }

    // --- Getters ---

    public ControllerConfig config() {
        return config.get();
    }

    public void updateConfig(ControllerConfig updated) {
        config.set(updated);
        try {
            YamlConfigLoader.mapper().writeValue(CONFIG_PATH.toFile(), updated);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist controller config", e);
        }
    }

    public EventBus eventBus() {
        return eventBus;
    }

    public ClusterState clusterState() {
        return clusterState;
    }

    public WorkflowStateStore workflowStateStore() {
        return workflowStateStore;
    }

    public NodeSessionManager sessionManager() {
        return sessionManager;
    }

    public ConsoleBuffer consoleBuffer() {
        return consoleBuffer;
    }

    public ControllerLogBuffer logBuffer() {
        return logBuffer;
    }

    public DaemonLogStore daemonLogStore() {
        return daemonLogStore;
    }

    public EventChoreographer eventChoreographer() {
        return eventChoreographer;
    }

    public CertificateAuthority ca() {
        return ca;
    }

    public JwtManager jwtManager() {
        return jwtManager;
    }

    public JoinTokenStore joinTokenStore() {
        return joinTokenStore;
    }

    /**
     * Live CORS allow-list. Lazily initialised from {@code config.http.cors.allowedOrigins}
     * so that admin routes can mutate it at runtime without an app restart.
     */
    public me.prexorjustin.prexorcloud.controller.rest.middleware.CorsAllowList corsAllowList() {
        var ref = corsAllowList;
        if (ref != null) return ref;
        synchronized (this) {
            if (corsAllowList == null) {
                corsAllowList = new me.prexorjustin.prexorcloud.controller.rest.middleware.CorsAllowList(
                        config.get().http().cors().allowedOrigins());
            }
            return corsAllowList;
        }
    }

    /**
     * Live network allow-list (gates inbound REST + gRPC by source IP). Lazily
     * initialised from {@code config.network.allowedSubnets}; the daemon
     * installer auto-registers its source IP into this list during join-token
     * exchange.
     */
    public me.prexorjustin.prexorcloud.controller.rest.middleware.AllowedSubnetsList allowedSubnetsList() {
        var ref = allowedSubnetsList;
        if (ref != null) return ref;
        synchronized (this) {
            if (allowedSubnetsList == null) {
                allowedSubnetsList = new me.prexorjustin.prexorcloud.controller.rest.middleware.AllowedSubnetsList(
                        config.get().network().allowedSubnets());
            }
            return allowedSubnetsList;
        }
    }

    public StateStore stateStore() {
        return stateStore;
    }

    public AuthManager authManager() {
        return authManager;
    }

    public MongoRoleStore roleStore() {
        return roleStore;
    }

    public JwtRevocationStore revocationStore() {
        return revocationStore;
    }

    public TemplateManager templateManager() {
        return templateManager;
    }

    public TemplateMerger templateMerger() {
        return templateMerger;
    }

    public MongoGroupStore groupStore() {
        return groupStore;
    }

    public GroupManager groupManager() {
        return groupManager;
    }

    public CatalogStore catalogStore() {
        return catalogStore;
    }

    public BaseTemplateGenerator baseTemplateGenerator() {
        return baseTemplateGenerator;
    }

    public CrashStore crashStore() {
        return crashStore;
    }

    public CrashLoopDetector crashLoopDetector() {
        return crashLoopDetector;
    }

    public MongoNetworkStore networkStore() {
        return networkStore;
    }

    public NetworkManager networkManager() {
        return networkManager;
    }

    public ModuleRegistry moduleRegistry() {
        return moduleRegistry;
    }

    public MetricsCollector metricsCollector() {
        return metricsCollector;
    }

    public MetricsTimeseries metricsTimeseries() {
        return metricsTimeseries;
    }

    /**
     * Sends pre-warm instructions to a node.
     */
    public void sendPreWarmToNode(NodeSession session) {
        preWarmService.sendTo(session);
    }

    /** Set the password-reset manager. Null when {@code security.passwordReset.enabled=false}. */
    public void setPasswordResetManager(
            @Nullable
                    me.prexorjustin.prexorcloud.controller.auth.passwordreset.PasswordResetManager
                            passwordResetManager) {
        this.passwordResetManager = passwordResetManager;
    }

    /** May be null when password reset is disabled — callers MUST null-check. */
    @Nullable
    public me.prexorjustin.prexorcloud.controller.auth.passwordreset.PasswordResetManager passwordResetManager() {
        return passwordResetManager;
    }

    /** Set the paste-share service. Built once at bootstrap. */
    public void setShareService(me.prexorjustin.prexorcloud.controller.share.ShareService shareService) {
        this.shareService = Objects.requireNonNull(shareService);
    }

    /** Never null after bootstrap — sharing is gated on {@code share.enabled} inside the service. */
    public me.prexorjustin.prexorcloud.controller.share.ShareService shareService() {
        if (shareService == null) throw new IllegalStateException("ShareService not initialized");
        return shareService;
    }

    /** Cluster control plane (Raft facade). Wired by bootstrap right after the service starts. */
    public void setClusterControlPlane(me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlPlane plane) {
        this.clusterControlPlane = Objects.requireNonNull(plane);
    }

    /** Never null after bootstrap completes. */
    public me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlPlane clusterControlPlane() {
        if (clusterControlPlane == null) throw new IllegalStateException("ClusterControlPlane not initialized");
        return clusterControlPlane;
    }

    /**
     * Read-only membership/identity view (Phase-4). Serves from Mongo once {@code clusterStore=mongo}
     * cuts reads over, otherwise from Raft. Wired by bootstrap alongside the control plane; use this
     * for member/identity reads and {@link #clusterControlPlane()} for writes and lease reads.
     */
    public void setClusterReadView(me.prexorjustin.prexorcloud.controller.cluster.ClusterReadView view) {
        this.clusterReadView = Objects.requireNonNull(view);
    }

    /** Never null after bootstrap completes. */
    public me.prexorjustin.prexorcloud.controller.cluster.ClusterReadView clusterReadView() {
        if (clusterReadView == null) throw new IllegalStateException("ClusterReadView not initialized");
        return clusterReadView;
    }

    /** Per-module resource tracker. Wired in bootstrap alongside the module context factory; may be null in tests. */
    public void setModuleResourceTracker(
            me.prexorjustin.prexorcloud.controller.module.resource.ModuleResourceTracker tracker) {
        this.moduleResourceTracker = tracker;
    }

    public me.prexorjustin.prexorcloud.controller.module.resource.ModuleResourceTracker moduleResourceTracker() {
        return moduleResourceTracker;
    }

    /** Per-module soft-quota enforcer. Wired in bootstrap alongside the tracker; may be null in tests or when no quota is configured. */
    public void setModuleQuotaEnforcer(
            me.prexorjustin.prexorcloud.controller.module.resource.ModuleQuotaEnforcer enforcer) {
        this.moduleQuotaEnforcer = enforcer;
    }

    public me.prexorjustin.prexorcloud.controller.module.resource.ModuleQuotaEnforcer moduleQuotaEnforcer() {
        return moduleQuotaEnforcer;
    }

    /** Per-module health monitor. Wired in bootstrap alongside the module poller; may be null in tests. */
    public void setModuleHealthMonitor(
            me.prexorjustin.prexorcloud.controller.module.health.ModuleHealthMonitor monitor) {
        this.moduleHealthMonitor = monitor;
    }

    public me.prexorjustin.prexorcloud.controller.module.health.ModuleHealthMonitor moduleHealthMonitor() {
        return moduleHealthMonitor;
    }

    /** Distributed-tracing pipeline (Track D). Wired in bootstrap; never null (no-op when disabled). */
    public void setTelemetry(me.prexorjustin.prexorcloud.controller.observability.telemetry.Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    public me.prexorjustin.prexorcloud.controller.observability.telemetry.Telemetry telemetry() {
        return telemetry;
    }

    /** Set the instance-filetree service. Wired in bootstrap once the NodeMessageDispatcher exists. */
    public void setInstanceFileTreeService(
            @Nullable me.prexorjustin.prexorcloud.controller.diagnostics.InstanceFileTreeService service) {
        this.instanceFileTreeService = service;
    }

    /** May be null in unit-test harnesses; production bootstrap always wires it. Callers MUST null-check. */
    @Nullable
    public me.prexorjustin.prexorcloud.controller.diagnostics.InstanceFileTreeService instanceFileTreeService() {
        return instanceFileTreeService;
    }

    /** Set the instance-file-content service. Wired in bootstrap once the NodeMessageDispatcher exists. */
    public void setInstanceFileContentService(
            @Nullable me.prexorjustin.prexorcloud.controller.diagnostics.InstanceFileContentService service) {
        this.instanceFileContentService = service;
    }

    /** May be null in unit-test harnesses; production bootstrap always wires it. Callers MUST null-check. */
    @Nullable
    public me.prexorjustin.prexorcloud.controller.diagnostics.InstanceFileContentService instanceFileContentService() {
        return instanceFileContentService;
    }
}
