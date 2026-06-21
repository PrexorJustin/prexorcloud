package me.prexorjustin.prexorcloud.controller.grpc;

import java.util.*;

import me.prexorjustin.prexorcloud.api.event.events.InstanceConsoleOutputEvent;
import me.prexorjustin.prexorcloud.api.event.events.NodeStatusUpdatedEvent;
import me.prexorjustin.prexorcloud.common.logging.CorrelationContext;
import me.prexorjustin.prexorcloud.common.util.InputValidator;
import me.prexorjustin.prexorcloud.controller.catalog.CatalogStore;
import me.prexorjustin.prexorcloud.controller.console.ConsoleBuffer;
import me.prexorjustin.prexorcloud.controller.crash.CrashLoopDetector;
import me.prexorjustin.prexorcloud.controller.crash.CrashStore;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.metrics.MetricsCollector;
import me.prexorjustin.prexorcloud.controller.observability.DaemonLogStore;
import me.prexorjustin.prexorcloud.controller.scheduler.Scheduler;
import me.prexorjustin.prexorcloud.controller.session.HeartbeatTracker;
import me.prexorjustin.prexorcloud.controller.session.NodeSessionManager;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.controller.template.TemplateManager;
import me.prexorjustin.prexorcloud.controller.template.TemplateMerger;
import me.prexorjustin.prexorcloud.protocol.*;

import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DaemonServiceImpl extends DaemonServiceGrpc.DaemonServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(DaemonServiceImpl.class);

    /**
     * Groups all service-layer dependencies into a single record to avoid a
     * 14-parameter constructor. Scalar config values ({@code heartbeatIntervalMs},
     * {@code controllerApiPort}) are passed separately as they are computed at
     * construction time rather than looked up from the dependency graph.
     */
    public record Deps(
            NodeSessionManager sessionManager,
            ClusterState clusterState,
            HeartbeatTracker heartbeatTracker,
            EventBus eventBus,
            CrashStore crashStore,
            CrashLoopDetector crashLoopDetector,
            TemplateManager templateManager,
            TemplateMerger templateMerger,
            StateStore stateStore,
            ConsoleBuffer consoleBuffer,
            GroupManager groupManager,
            CatalogStore catalogStore,
            PendingRequestRegistry pendingRequests) {} // for async controller↔daemon round-trips

    private final NodeSessionManager sessionManager;
    private final ClusterState clusterState;
    private final HeartbeatTracker heartbeatTracker;
    private final EventBus eventBus;
    private final long heartbeatIntervalMs;
    private final int heartbeatMissedThreshold;
    private final int controllerApiPort;
    private final CrashStore crashStore;
    private final CrashLoopDetector crashLoopDetector;
    private final TemplateManager templateManager;
    private final TemplateMerger templateMerger;
    private final StateStore stateStore;
    private final ConsoleBuffer consoleBuffer;
    private final GroupManager groupManager;
    private final CatalogStore catalogStore;
    private volatile Scheduler scheduler;
    private volatile DaemonLogStore daemonLogStore;
    private volatile MetricsCollector metricsCollector;
    private volatile me.prexorjustin.prexorcloud.controller.module.platform.ModuleDistributor moduleDistributor;
    private volatile DaemonEventForwarder daemonEventForwarder;
    private final DaemonCommandAckHandler commandAckHandler;
    private final DaemonCrashEventReceiver crashEventReceiver;
    private final DaemonCacheStatusReceiver cacheStatusReceiver;
    private final DaemonFileTreeReceiver fileTreeReceiver;
    private final DaemonFileContentReceiver fileContentReceiver;
    private final DaemonTemplateRequestHandler templateRequestHandler;
    private final DaemonConnectionLifecycle connectionLifecycle;
    private final PendingRequestRegistry pendingRequests;

    /**
     * Inject single-writer leadership (Phase 3): the leader stamps its fencing epoch on each
     * {@code HandshakeAck}; a follower redirects the daemon to the leader via the resolver below.
     * Wired in bootstrap once the elector exists; defaults to always-leader so tests are unchanged.
     */
    public void setLeadership(me.prexorjustin.prexorcloud.controller.cluster.Leadership leadership) {
        connectionLifecycle.setLeadership(leadership);
    }

    /** Inject the resolver for the current leader's gRPC address (empty when leader/unknown). */
    public void setLeaderGrpcAddressResolver(java.util.function.Supplier<String> resolver) {
        connectionLifecycle.setLeaderGrpcAddressResolver(resolver);
    }

    /** Inject the resolver that enumerates the cluster's live controller gRPC addresses for daemons. */
    public void setControllerAddrsResolver(java.util.function.Supplier<java.util.List<String>> resolver) {
        connectionLifecycle.setControllerAddrsResolver(resolver);
    }

    public DaemonServiceImpl(Deps deps, long heartbeatIntervalMs, int heartbeatMissedThreshold, int controllerApiPort) {
        this.sessionManager = deps.sessionManager();
        this.clusterState = deps.clusterState();
        this.heartbeatTracker = deps.heartbeatTracker();
        this.eventBus = deps.eventBus();
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.heartbeatMissedThreshold = heartbeatMissedThreshold;
        this.controllerApiPort = controllerApiPort;
        this.crashStore = deps.crashStore();
        this.crashLoopDetector = deps.crashLoopDetector();
        this.templateManager = deps.templateManager();
        this.templateMerger = deps.templateMerger();
        this.stateStore = deps.stateStore();
        this.consoleBuffer = deps.consoleBuffer();
        this.groupManager = deps.groupManager();
        this.catalogStore = deps.catalogStore();
        this.commandAckHandler =
                new DaemonCommandAckHandler(this.clusterState, this.crashLoopDetector, () -> this.scheduler);
        this.crashEventReceiver = new DaemonCrashEventReceiver(
                this.clusterState, this.crashStore, this.crashLoopDetector, this.eventBus, this.stateStore);
        this.cacheStatusReceiver = new DaemonCacheStatusReceiver(this.clusterState);
        this.pendingRequests = deps.pendingRequests();
        this.fileTreeReceiver = new DaemonFileTreeReceiver(this.pendingRequests);
        this.fileContentReceiver = new DaemonFileContentReceiver(this.pendingRequests);
        this.templateRequestHandler = new DaemonTemplateRequestHandler(this.templateManager, this.templateMerger);
        this.connectionLifecycle = new DaemonConnectionLifecycle(
                this.sessionManager,
                this.heartbeatTracker,
                this.eventBus,
                this.clusterState,
                this.stateStore,
                this.groupManager,
                this.catalogStore,
                () -> this.scheduler,
                () -> this.moduleDistributor,
                () -> this.daemonEventForwarder,
                this.pendingRequests,
                this.heartbeatIntervalMs,
                this.controllerApiPort);
    }

    public void attachScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Wires the per-node daemon log ring used by `prexorctl logs daemon`.
     * Optional: when unset (e.g. unit tests) incoming {@code DaemonLogRecord}
     * messages are dropped silently.
     */
    public void attachDaemonLogStore(DaemonLogStore store) {
        this.daemonLogStore = store;
    }

    /**
     * Wires the controller's {@link MetricsCollector} so inbound daemon
     * messages are counted by payload case. Safe to leave unset in unit tests.
     */
    public void attachMetricsCollector(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    /**
     * Wires the platform-module distributor so successful handshakes trigger a reconciliation
     * push of every daemon-host module. Optional: when unset (e.g. unit tests) handshake
     * proceeds without the sync.
     */
    public void attachModuleDistributor(
            me.prexorjustin.prexorcloud.controller.module.platform.ModuleDistributor moduleDistributor) {
        this.moduleDistributor = moduleDistributor;
    }

    /**
     * Wires the daemon event forwarder so {@code EVENT_SUBSCRIBE}/{@code EVENT_UNSUBSCRIBE}
     * messages from daemons are dispatched into the controller's EventBus bridge. Optional
     * for unit tests that don't exercise the event-bridge path.
     */
    public void attachDaemonEventForwarder(DaemonEventForwarder daemonEventForwarder) {
        this.daemonEventForwarder = daemonEventForwarder;
    }

    @Override
    public StreamObserver<DaemonMessage> connect(StreamObserver<ControllerMessage> responseObserver) {
        return new StreamObserver<>() {

            private String sessionId;
            private String nodeId;
            private boolean handshakeComplete;

            @Override
            public void onNext(DaemonMessage message) {
                try (var ignored = CorrelationContext.open(daemonFields(message))) {
                    MetricsCollector mc = metricsCollector;
                    if (mc != null) {
                        mc.recordDaemonInbound(message.getPayloadCase().name());
                    }
                    if (message.getPayloadCase() == DaemonMessage.PayloadCase.HANDSHAKE) {
                        connectionLifecycle
                                .handleHandshake(message.getHandshake(), responseObserver, handshakeComplete)
                                .ifPresent(result -> {
                                    sessionId = result.sessionId();
                                    nodeId = result.nodeId();
                                    handshakeComplete = true;
                                });
                        return;
                    }
                    if (!handshakeComplete) {
                        logger.warn("Ignoring {} before handshake completed", message.getPayloadCase());
                        return;
                    }

                    switch (message.getPayloadCase()) {
                        case NODE_STATUS -> handleNodeStatus(message.getNodeStatus());
                        case INSTANCE_STATUS -> handleInstanceStatus(message.getInstanceStatus());
                        case CONSOLE_OUTPUT -> handleConsoleOutput(message.getConsoleOutput());
                        case CRASH_REPORT -> crashEventReceiver.handleCrashReport(nodeId, message.getCrashReport());
                        case PONG -> handlePong(message.getPong());
                        case TEMPLATE_REQUEST ->
                            templateRequestHandler.handleTemplateRequest(
                                    nodeId, message.getTemplateRequest(), responseObserver);
                        case CACHE_STATUS -> cacheStatusReceiver.handleCacheStatus(nodeId, message.getCacheStatus());
                        case ERROR_REPORT -> crashEventReceiver.handleErrorReport(nodeId, message.getErrorReport());
                        case SHUTDOWN_NODE_ACK ->
                            commandAckHandler.handleShutdownNodeAck(nodeId, message.getShutdownNodeAck());
                        case START_INSTANCE_ACK ->
                            commandAckHandler.handleStartInstanceAck(nodeId, message.getStartInstanceAck());
                        case STOP_INSTANCE_ACK ->
                            commandAckHandler.handleStopInstanceAck(nodeId, message.getStopInstanceAck());
                        case DAEMON_LOG_RECORD -> handleDaemonLogRecord(message.getDaemonLogRecord());
                        case MODULE_STATE_UPDATE -> handleModuleStateUpdate(message.getModuleStateUpdate());
                        case EVENT_SUBSCRIBE -> handleEventSubscribe(message.getEventSubscribe());
                        case EVENT_UNSUBSCRIBE -> handleEventUnsubscribe(message.getEventUnsubscribe());
                        case INSTANCE_FILE_TREE ->
                            fileTreeReceiver.handleInstanceFileTree(nodeId, message.getInstanceFileTree());
                        case INSTANCE_FILE_CONTENT ->
                            fileContentReceiver.handleInstanceFileContent(nodeId, message.getInstanceFileContent());
                        default ->
                            logger.warn("Unknown message type from node {}: {}", nodeId, message.getPayloadCase());
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                logger.warn("Stream error from node {}: {}", nodeId, t.getMessage());
                connectionLifecycle.cleanup(sessionId, nodeId, "stream error: " + t.getMessage());
            }

            @Override
            public void onCompleted() {
                logger.info("Stream completed from node {}", nodeId);
                connectionLifecycle.cleanup(sessionId, nodeId, "stream completed");
                responseObserver.onCompleted();
            }

            private boolean verifyNodeOwnership(String instanceId, String handlerName) {
                var instance = clusterState.getInstance(instanceId);
                if (instance.isEmpty()) {
                    // A peer (the group-lease holder) may have placed this instance on our
                    // node and written it to Redis; status flows only here (the node-owner).
                    // Adopt it from the shared projection rather than dropping the update,
                    // which would otherwise leave the instance wedged until a reconcile tick.
                    if (clusterState.adoptInstanceFromRedis(instanceId, nodeId)) {
                        instance = clusterState.getInstance(instanceId);
                    }
                }
                if (instance.isEmpty()) {
                    logger.warn("{}: unknown instance {}", handlerName, instanceId);
                    return false;
                }
                if (!instance.get().nodeId().equals(nodeId)) {
                    logger.warn(
                            "{}: instance {} belongs to node {}, not {}",
                            handlerName,
                            instanceId,
                            instance.get().nodeId(),
                            nodeId);
                    return false;
                }
                return true;
            }

            private void handleNodeStatus(NodeStatus status) {
                if (nodeId == null) return;
                try {
                    InputValidator.requireRange(status.getCpuUsage(), 0.0, 1.0, "cpuUsage");
                    InputValidator.requireNonNegativeLong(status.getTotalMemoryMb(), "totalMemoryMb");
                    InputValidator.requireNonNegativeLong(status.getUsedMemoryMb(), "usedMemoryMb");
                    if (status.getUsedMemoryMb() > status.getTotalMemoryMb()) {
                        throw new IllegalArgumentException("usedMemoryMb cannot exceed totalMemoryMb");
                    }
                    InputValidator.requireNonNegativeLong(status.getFreeDiskMb(), "freeDiskMb");
                    InputValidator.requireNonNegativeLong(status.getTotalDiskMb(), "totalDiskMb");
                    InputValidator.requireNonNegative(status.getInstanceCount(), "instanceCount");
                    for (int port : status.getUsedPortsList()) {
                        InputValidator.requireValidPort(port, "usedPort");
                    }
                } catch (IllegalArgumentException e) {
                    logger.warn("Invalid handleNodeStatus from node {}: {}", nodeId, e.getMessage());
                    return;
                }
                // Apply as telemetry, not authority: a daemon heartbeat reports only the
                // instances it has already started, so a full overwrite would silently drop
                // the controller's port/memory reservations for instances still in flight
                // (SCHEDULED/PREPARING/STARTING), recycling ports and under-counting memory.
                clusterState.applyNodeTelemetry(
                        nodeId,
                        status.getCpuUsage(),
                        status.getTotalMemoryMb(),
                        status.getUsedMemoryMb(),
                        status.getFreeDiskMb(),
                        status.getTotalDiskMb(),
                        status.getInstanceCount(),
                        new HashSet<>(status.getUsedPortsList()));
                eventBus.publish(new NodeStatusUpdatedEvent(
                        nodeId,
                        status.getCpuUsage(),
                        status.getUsedMemoryMb(),
                        status.getTotalMemoryMb(),
                        clusterState.getNode(nodeId).map(n -> n.lastHeartbeat()).orElse(null)));
            }

            private void handleInstanceStatus(InstanceStatusUpdate status) {
                try {
                    InputValidator.requireSafeName(status.getInstanceId(), "instanceId");
                    if (status.getState() == InstanceState.INSTANCE_STATE_UNSPECIFIED) {
                        throw new IllegalArgumentException("state is required");
                    }
                    InputValidator.requireNonNegative(status.getPlayerCount(), "playerCount");
                    InputValidator.requireNonNegativeLong(status.getUptimeMs(), "uptimeMs");
                } catch (IllegalArgumentException e) {
                    logger.warn("Invalid handleInstanceStatus from node {}: {}", nodeId, e.getMessage());
                    return;
                }
                if (!verifyNodeOwnership(status.getInstanceId(), "handleInstanceStatus")) return;

                clusterState.updateInstanceStatus(
                        status.getInstanceId(), status.getState(), status.getPlayerCount(), status.getUptimeMs());
            }

            private void handleConsoleOutput(ConsoleOutput output) {
                try {
                    InputValidator.requireSafeName(output.getInstanceId(), "instanceId");
                    InputValidator.requireMaxLength(output.getLine(), 65536, "consoleLine");
                } catch (IllegalArgumentException e) {
                    logger.warn("Invalid handleConsoleOutput from node {}: {}", nodeId, e.getMessage());
                    return;
                }
                if (!verifyNodeOwnership(output.getInstanceId(), "handleConsoleOutput")) return;

                var result = consoleBuffer.append(output.getInstanceId(), output.getLine(), output.getTimestampMs());
                java.time.Instant ts = output.getTimestampMs() > 0
                        ? java.time.Instant.ofEpochMilli(output.getTimestampMs())
                        : java.time.Instant.now();
                for (String line : result.acceptedLines()) {
                    eventBus.publish(
                            new InstanceConsoleOutputEvent(output.getInstanceId(), line, output.getTimestampMs()));
                    stateStore.appendConsoleLine(output.getInstanceId(), ts, line);
                }
            }

            private void handlePong(Pong pong) {
                if (pong.getSequence() < 0) {
                    logger.warn("Invalid pong from node {}: sequence must be non-negative", nodeId);
                    return;
                }
                if (sessionId != null) {
                    heartbeatTracker.recordPong(sessionId);
                }
                if (nodeId != null) {
                    clusterState.recordHeartbeat(nodeId);
                }
            }

            private void handleDaemonLogRecord(DaemonLogRecord record) {
                if (nodeId == null) return;
                DaemonLogStore store = daemonLogStore;
                if (store == null) return;
                try {
                    InputValidator.requireNonNegativeLong(record.getTimestampMs(), "timestampMs");
                    InputValidator.requireMaxLength(record.getLevel(), 16, "level");
                    InputValidator.requireMaxLength(record.getLogger(), 256, "logger");
                    InputValidator.requireMaxLength(record.getThread(), 128, "thread");
                    InputValidator.requireMaxLength(record.getMessage(), 8192, "message");
                    InputValidator.requireMaxLength(record.getThrowable(), 32_768, "throwable");
                } catch (IllegalArgumentException e) {
                    logger.warn("Invalid daemon log record from node {}: {}", nodeId, e.getMessage());
                    return;
                }
                String throwable = record.getThrowable();
                store.append(
                        nodeId,
                        record.getTimestampMs() == 0 ? System.currentTimeMillis() : record.getTimestampMs(),
                        record.getLevel(),
                        record.getLogger(),
                        record.getThread(),
                        record.getMessage(),
                        throwable.isEmpty() ? null : throwable,
                        record.getMdcMap());
            }

            private void handleModuleStateUpdate(ModuleStateUpdate update) {
                if (nodeId == null) return;
                try {
                    InputValidator.requireSafeName(update.getModuleId(), "moduleId");
                    InputValidator.requireMaxLength(update.getState(), 32, "state");
                    InputValidator.requireMaxLength(update.getLastError(), 1024, "lastError");
                } catch (IllegalArgumentException e) {
                    logger.warn("Invalid module state update from node {}: {}", nodeId, e.getMessage());
                    return;
                }
                logger.debug(
                        "node {} reports module {} state={} (lastError={})",
                        nodeId,
                        update.getModuleId(),
                        update.getState(),
                        update.getLastError().isEmpty() ? "none" : update.getLastError());
                // Reporting is informational in PR 7b; aggregation/visibility surface lands
                // with PR 7c (DaemonModuleManager). Future hook: store per-(nodeId, moduleId)
                // state for the dashboard's module-status table.
            }

            private void handleEventSubscribe(EventSubscribe subscribe) {
                if (nodeId == null) return;
                DaemonEventForwarder forwarder = daemonEventForwarder;
                if (forwarder == null) {
                    logger.debug("Ignoring EventSubscribe from {}: no forwarder attached", nodeId);
                    return;
                }
                forwarder.onSubscribe(nodeId, subscribe.getEventTypesList());
            }

            private void handleEventUnsubscribe(EventUnsubscribe unsubscribe) {
                if (nodeId == null) return;
                DaemonEventForwarder forwarder = daemonEventForwarder;
                if (forwarder == null) {
                    return;
                }
                forwarder.onUnsubscribe(nodeId, unsubscribe.getEventTypesList());
            }

            private Map<String, String> daemonFields(DaemonMessage message) {
                var fields = new LinkedHashMap<String, String>();
                fields.put("daemonMessage", message.getPayloadCase().name());
                if (nodeId != null) fields.put("nodeId", nodeId);
                if (sessionId != null) fields.put("sessionId", sessionId);
                return fields;
            }
        };
    }

    /**
     * gRPC Context key for the remote peer address, populated by
     * {@link PeerAddressInterceptor}.
     */
    static final io.grpc.Context.Key<String> PEER_ADDRESS_KEY = io.grpc.Context.key("peer-address");

    /**
     * Extracts the remote IP from the gRPC context. Requires
     * {@link PeerAddressInterceptor} to be installed on the server. Returns empty
     * string if unavailable.
     */
    static String extractPeerAddress() {
        String address = PEER_ADDRESS_KEY.get();
        return address != null ? address : "";
    }

    /**
     * Converts a string config format name (e.g. "paper", "velocity") to the proto
     * enum. Returns CONFIG_FORMAT_UNSPECIFIED for null, empty, or unknown values.
     */
    public static ConfigFormat parseConfigFormat(String value) {
        if (value == null || value.isBlank()) return ConfigFormat.CONFIG_FORMAT_UNSPECIFIED;
        return switch (value.toUpperCase()) {
            case "PAPER" -> ConfigFormat.PAPER;
            case "SPIGOT" -> ConfigFormat.SPIGOT;
            case "VELOCITY" -> ConfigFormat.VELOCITY;
            case "BUNGEECORD" -> ConfigFormat.BUNGEECORD;
            case "GEYSER" -> ConfigFormat.GEYSER;
            default -> ConfigFormat.CONFIG_FORMAT_UNSPECIFIED;
        };
    }

    /**
     * Converts a string category name (e.g. "SERVER", "PROXY") to the proto enum.
     * Returns INSTANCE_CATEGORY_UNSPECIFIED for null, empty, or unknown values.
     */
    public static InstanceCategory parseCategory(String value) {
        if (value == null || value.isBlank()) return InstanceCategory.INSTANCE_CATEGORY_UNSPECIFIED;
        return switch (value.toUpperCase()) {
            case "SERVER" -> InstanceCategory.SERVER;
            case "PROXY" -> InstanceCategory.PROXY;
            default -> InstanceCategory.INSTANCE_CATEGORY_UNSPECIFIED;
        };
    }
}
