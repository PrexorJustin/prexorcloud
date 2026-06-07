package me.prexorjustin.prexorcloud.controller.grpc;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import me.prexorjustin.prexorcloud.api.event.events.NodeConnectedEvent;
import me.prexorjustin.prexorcloud.api.event.events.NodeDisconnectedEvent;
import me.prexorjustin.prexorcloud.common.util.InputValidator;
import me.prexorjustin.prexorcloud.controller.catalog.CatalogConfigLoader;
import me.prexorjustin.prexorcloud.controller.catalog.CatalogStore;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.module.platform.ModuleDistributor;
import me.prexorjustin.prexorcloud.controller.redis.RedisKeys;
import me.prexorjustin.prexorcloud.controller.scheduler.Scheduler;
import me.prexorjustin.prexorcloud.controller.session.HeartbeatTracker;
import me.prexorjustin.prexorcloud.controller.session.NodeSession;
import me.prexorjustin.prexorcloud.controller.session.NodeSessionManager;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.NodeHostInfo;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.protocol.ControllerMessage;
import me.prexorjustin.prexorcloud.protocol.Handshake;
import me.prexorjustin.prexorcloud.protocol.HandshakeAck;
import me.prexorjustin.prexorcloud.protocol.InstanceState;
import me.prexorjustin.prexorcloud.protocol.PreWarmCache;
import me.prexorjustin.prexorcloud.protocol.PreWarmEntry;
import me.prexorjustin.prexorcloud.protocol.RequestCacheStatus;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns daemon-stream open/close: handshake validation + node registration on
 * one end, session/ownership cleanup on the other. Extracted from
 * {@code DaemonServiceImpl}'s connect-stream handler.
 *
 * <p>The per-stream state (sessionId, nodeId, handshakeComplete) stays in the
 * caller's {@code StreamObserver}; {@link #handleHandshake} returns the new
 * identifiers as a result record so the caller can assign them.
 */
final class DaemonConnectionLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(DaemonConnectionLifecycle.class);

    private static final Set<String> SENSITIVE_KEYS =
            Set.of("password", "secret", "token", "key", "credential", "authorization", "api_key", "apikey");

    private final NodeSessionManager sessionManager;
    private final HeartbeatTracker heartbeatTracker;
    private final EventBus eventBus;
    private final ClusterState clusterState;
    private final StateStore stateStore;
    private final GroupManager groupManager;
    private final CatalogStore catalogStore;
    private final RedisCommands<String, String> redisCommands; // nullable
    private final String controllerId; // nullable
    private final Supplier<Scheduler> scheduler;
    private final Supplier<ModuleDistributor> moduleDistributor;
    private final Supplier<DaemonEventForwarder> daemonEventForwarder;
    private final PendingRequestRegistry pendingRequests;
    private final long heartbeatIntervalMs;
    private final int heartbeatMissedThreshold;
    private final int controllerApiPort;

    DaemonConnectionLifecycle(
            NodeSessionManager sessionManager,
            HeartbeatTracker heartbeatTracker,
            EventBus eventBus,
            ClusterState clusterState,
            StateStore stateStore,
            GroupManager groupManager,
            CatalogStore catalogStore,
            RedisCommands<String, String> redisCommands,
            String controllerId,
            Supplier<Scheduler> scheduler,
            Supplier<ModuleDistributor> moduleDistributor,
            Supplier<DaemonEventForwarder> daemonEventForwarder,
            PendingRequestRegistry pendingRequests,
            long heartbeatIntervalMs,
            int heartbeatMissedThreshold,
            int controllerApiPort) {
        this.sessionManager = sessionManager;
        this.heartbeatTracker = heartbeatTracker;
        this.eventBus = eventBus;
        this.clusterState = clusterState;
        this.stateStore = stateStore;
        this.groupManager = groupManager;
        this.catalogStore = catalogStore;
        this.redisCommands = redisCommands;
        this.controllerId = controllerId;
        this.scheduler = scheduler;
        this.moduleDistributor = moduleDistributor;
        this.daemonEventForwarder = daemonEventForwarder;
        this.pendingRequests = pendingRequests;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.heartbeatMissedThreshold = heartbeatMissedThreshold;
        this.controllerApiPort = controllerApiPort;
    }

    /**
     * Validates and applies a handshake. Returns the assigned
     * {@code (sessionId, nodeId)} on success, or {@link Optional#empty()} if
     * the handshake was rejected — in which case {@code response.onError} has
     * already been called.
     *
     * @param alreadyComplete whether the caller has already accepted a
     *                        handshake on this stream (replays are rejected)
     */
    Optional<HandshakeResult> handleHandshake(
            Handshake handshake, StreamObserver<ControllerMessage> response, boolean alreadyComplete) {
        if (alreadyComplete) {
            response.onError(Status.FAILED_PRECONDITION
                    .withDescription("Handshake already completed for this stream")
                    .asRuntimeException());
            return Optional.empty();
        }
        String candidateNodeId = handshake.getNodeId();
        if (!InputValidator.isSafeName(candidateNodeId)) {
            logger.warn("Rejected handshake with invalid nodeId: {}", candidateNodeId);
            response.onError(Status.INVALID_ARGUMENT
                    .withDescription(
                            "Invalid nodeId: must be 1-64 alphanumeric characters with dots/hyphens/underscores")
                    .asRuntimeException());
            return Optional.empty();
        }
        if (handshake.getVersion().isBlank()) {
            response.onError(Status.INVALID_ARGUMENT
                    .withDescription("Handshake version is required")
                    .asRuntimeException());
            return Optional.empty();
        }
        if (handshake.getProtocolVersion() < 1) {
            response.onError(Status.FAILED_PRECONDITION
                    .withDescription("Unsupported daemon protocol version: " + handshake.getProtocolVersion())
                    .asRuntimeException());
            return Optional.empty();
        }
        try {
            InputValidator.requireMaxLength(handshake.getVersion(), 64, "version");
            InputValidator.requireMaxLength(handshake.getAdvertiseAddress(), 255, "advertiseAddress");
            for (var entry : handshake.getLabelsMap().entrySet()) {
                InputValidator.requireMaxLength(entry.getKey(), 64, "labelKey");
                InputValidator.requireMaxLength(entry.getValue(), 256, "labelValue");
            }
        } catch (IllegalArgumentException e) {
            response.onError(
                    Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            return Optional.empty();
        }

        String nodeId = candidateNodeId;
        String sessionId = UUID.randomUUID().toString();

        String address = handshake.getAdvertiseAddress();
        if (address.isBlank()) {
            address = DaemonServiceImpl.extractPeerAddress();
        }

        var labels = Map.copyOf(handshake.getLabelsMap());
        var session = new NodeSession(sessionId, nodeId, response, Instant.now());
        var replacedSession = sessionManager.register(session).orElse(null);
        if (replacedSession != null && !replacedSession.sessionId().equals(sessionId)) {
            heartbeatTracker.removeSession(replacedSession.sessionId());
            logger.info(
                    "Replacing stale session {} with {} for node {}", replacedSession.sessionId(), sessionId, nodeId);
        }

        if (redisCommands != null && controllerId != null) {
            try {
                persistNodeOwnership(nodeId);
            } catch (Exception e) {
                logger.warn("Failed to register node ownership in Redis for {}: {}", nodeId, e.getMessage());
            }
        }

        var hostInfo = NodeHostInfo.UNKNOWN;
        if (handshake.hasHostInfo()) {
            var hi = handshake.getHostInfo();
            hostInfo = new NodeHostInfo(
                    hi.getOsName(),
                    hi.getOsVersion(),
                    hi.getArch(),
                    hi.getCpuModel(),
                    hi.getCpuPhysicalCores(),
                    hi.getCpuLogicalCores(),
                    hi.getCpuMaxFreqHz(),
                    hi.getJavaVersion(),
                    hi.getJavaVendor(),
                    hi.getJavaRuntime(),
                    hi.getJavaGc());
        }

        clusterState.addNode(nodeId, address, handshake.getTotalMemoryMb(), labels, Instant.now(), hostInfo);
        stateStore.registerNode(nodeId);

        int daemonProtocolVersion = handshake.getProtocolVersion();
        logger.debug("Node {} protocol_version={}", nodeId, daemonProtocolVersion);

        logger.info(
                "Node {} connected (session={}, address={}, version={}, cpus={}, memory={}MB, os={} {}, cpu={}, labels={}, running={})",
                nodeId,
                sessionId,
                address,
                handshake.getVersion(),
                handshake.getAvailableCpus(),
                handshake.getTotalMemoryMb(),
                hostInfo.osName(),
                hostInfo.osVersion(),
                hostInfo.cpuModel(),
                redactSensitiveKeys(labels),
                handshake.getRunningInstancesCount());

        reconcileInstances(nodeId, handshake);
        Scheduler sched = scheduler.get();
        if (sched != null) {
            sched.reconcileRecoverableStartsForNode(nodeId);
        }

        eventBus.publish(new NodeConnectedEvent(nodeId, sessionId, Instant.now()));

        var ack = ControllerMessage.newBuilder()
                .setHandshakeAck(HandshakeAck.newBuilder()
                        .setSessionId(sessionId)
                        .setHeartbeatIntervalMs(heartbeatIntervalMs)
                        .setControllerApiPort(controllerApiPort)
                        .setProtocolVersion(1)
                        .setProtocolCompatible(daemonProtocolVersion >= 1))
                .build();
        response.onNext(ack);

        sendPreWarmCache(response);

        response.onNext(ControllerMessage.newBuilder()
                .setRequestCacheStatus(RequestCacheStatus.newBuilder())
                .build());

        var distributor = moduleDistributor.get();
        if (distributor != null) {
            try {
                distributor.syncDaemon(nodeId);
            } catch (Exception e) {
                logger.warn("Failed to sync platform modules to node {}: {}", nodeId, e.getMessage());
            }
        }

        return Optional.of(new HandshakeResult(sessionId, nodeId));
    }

    /**
     * Tears down a stream's session + node ownership. Safe to call with
     * {@code null} ids — early-disconnect before handshake leaves both null.
     */
    void cleanup(String sessionId, String nodeId, String reason) {
        boolean activeSessionRemoved = false;
        if (sessionId != null) {
            activeSessionRemoved = sessionManager.invalidate(sessionId);
            heartbeatTracker.removeSession(sessionId);
        }
        if (nodeId != null) {
            DaemonEventForwarder forwarder = daemonEventForwarder.get();
            if (forwarder != null) {
                forwarder.onDisconnect(nodeId);
            }
            if (pendingRequests != null) {
                pendingRequests.failAll(
                        entry -> nodeId.equals(entry.scope()),
                        new IllegalStateException("node " + nodeId + " disconnected: " + reason));
            }
        }
        if (nodeId != null && activeSessionRemoved) {
            if (redisCommands != null) {
                try {
                    redisCommands.del(RedisKeys.nodeOwner(nodeId));
                } catch (Exception e) {
                    logger.warn("Failed to remove node ownership in Redis for {}: {}", nodeId, e.getMessage());
                }
            }
            clusterState.removeNode(nodeId, reason);
            stateStore.updateNodeLastSeen(nodeId);
            eventBus.publish(new NodeDisconnectedEvent(nodeId, reason, Instant.now()));
        }
    }

    /**
     * Refreshes the Redis TTL on this node's ownership hint. Called on each
     * pong to keep the routing record alive while the daemon is online.
     */
    void refreshNodeOwnershipTtl(String nodeId) {
        if (redisCommands == null || controllerId == null || nodeId == null) {
            return;
        }
        try {
            redisCommands.expire(RedisKeys.nodeOwner(nodeId), nodeOwnerTtl().getSeconds());
        } catch (Exception e) {
            logger.warn("Failed to refresh node ownership TTL in Redis for {}: {}", nodeId, e.getMessage());
        }
    }

    private void reconcileInstances(String reconnectedNodeId, Handshake handshake) {
        var daemonInstanceIds = new HashSet<String>();
        for (var running : handshake.getRunningInstancesList()) {
            if (!InputValidator.isSafeName(running.getInstanceId())) {
                logger.warn("reconcileInstances: skipping invalid instanceId from node {}", reconnectedNodeId);
                continue;
            }
            if (!verifyNodeOwnership(running.getInstanceId(), reconnectedNodeId)) continue;
            daemonInstanceIds.add(running.getInstanceId());
            clusterState.updateInstanceStatus(running.getInstanceId(), running.getState(), 0, 0);
        }

        var controllerInstances = clusterState.getInstancesByNode(reconnectedNodeId);
        for (var instance : controllerInstances) {
            if (instance.state() == InstanceState.STOPPED || instance.state() == InstanceState.CRASHED) {
                continue;
            }
            if (!daemonInstanceIds.contains(instance.id())) {
                logger.warn(
                        "Instance {} was on node {} but not reported after reconnect -- marking as CRASHED",
                        instance.id(),
                        reconnectedNodeId);
                clusterState.updateInstanceState(instance.id(), InstanceState.CRASHED);
            }
        }
    }

    private boolean verifyNodeOwnership(String instanceId, String nodeId) {
        var instance = clusterState.getInstance(instanceId);
        if (instance.isEmpty()) {
            logger.warn("reconcileInstances: unknown instance {}", instanceId);
            return false;
        }
        if (!instance.get().nodeId().equals(nodeId)) {
            logger.warn(
                    "reconcileInstances: instance {} belongs to node {}, not {}",
                    instanceId,
                    instance.get().nodeId(),
                    nodeId);
            return false;
        }
        return true;
    }

    private void sendPreWarmCache(StreamObserver<ControllerMessage> response) {
        try {
            var seen = new HashSet<String>();
            var entries = new ArrayList<PreWarmEntry>();

            for (GroupConfig group : groupManager.getAll()) {
                GroupConfig resolved = groupManager.resolveGroup(group.name());
                String platform = resolved.platform();
                String version = resolved.platformVersion();
                if (platform.isBlank() || version.isBlank()) continue;

                String key = platform + "/" + version;
                if (!seen.add(key)) continue;

                var catalogEntries = catalogStore.getByPlatform(platform);
                var match = catalogEntries.stream()
                        .filter(e -> e.version().equals(version))
                        .findFirst();
                if (match.isEmpty()) {
                    match = catalogEntries.stream()
                            .filter(CatalogConfigLoader.CatalogEntry::recommended)
                            .findFirst();
                }
                if (match.isEmpty()) continue;

                var catalogEntry = match.get();
                entries.add(PreWarmEntry.newBuilder()
                        .setPlatform(platform)
                        .setPlatformVersion(catalogEntry.version())
                        .setConfigFormat(DaemonServiceImpl.parseConfigFormat(catalogEntry.configFormat()))
                        .setJarFile(resolved.jarFile())
                        .setDownloadUrl(catalogEntry.downloadUrl())
                        .setSha256(catalogEntry.sha256())
                        .build());
            }

            if (!entries.isEmpty()) {
                response.onNext(ControllerMessage.newBuilder()
                        .setPreWarmCache(PreWarmCache.newBuilder().addAllEntries(entries))
                        .build());
                logger.debug("Sent pre-warm hints for {} platform/version combinations", entries.size());
            }
        } catch (IOException e) {
            logger.warn("Failed to build pre-warm cache hints: {}", e.getMessage());
        }
    }

    private void persistNodeOwnership(String nodeId) {
        if (redisCommands == null || controllerId == null || nodeId == null) {
            return;
        }
        redisCommands.setex(RedisKeys.nodeOwner(nodeId), nodeOwnerTtl().getSeconds(), controllerId);
    }

    private Duration nodeOwnerTtl() {
        return RedisKeys.nodeOwnerTtl(heartbeatIntervalMs, heartbeatMissedThreshold);
    }

    private static Map<String, String> redactSensitiveKeys(Map<String, String> map) {
        var result = new LinkedHashMap<String, String>();
        for (var entry : map.entrySet()) {
            String lowerKey = entry.getKey().toLowerCase();
            boolean sensitive = SENSITIVE_KEYS.stream().anyMatch(lowerKey::contains);
            result.put(entry.getKey(), sensitive ? "***REDACTED***" : entry.getValue());
        }
        return result;
    }

    record HandshakeResult(String sessionId, String nodeId) {}
}
