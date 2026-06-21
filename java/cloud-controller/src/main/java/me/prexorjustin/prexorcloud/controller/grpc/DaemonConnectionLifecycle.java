package me.prexorjustin.prexorcloud.controller.grpc;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
import me.prexorjustin.prexorcloud.controller.cluster.Leadership;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.module.platform.ModuleDistributor;
import me.prexorjustin.prexorcloud.controller.scheduler.Scheduler;
import me.prexorjustin.prexorcloud.controller.session.HeartbeatTracker;
import me.prexorjustin.prexorcloud.controller.session.NodeSession;
import me.prexorjustin.prexorcloud.controller.session.NodeSessionManager;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
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
    private final Supplier<Scheduler> scheduler;
    private final Supplier<ModuleDistributor> moduleDistributor;
    private final Supplier<DaemonEventForwarder> daemonEventForwarder;
    private final PendingRequestRegistry pendingRequests;
    private final long heartbeatIntervalMs;
    private final int controllerApiPort;

    // Single-writer redirect (Phase 3): on handshake the leader stamps its fencing epoch on the ack;
    // a follower instead returns the leader's gRPC address so the daemon can redirect to it. Default
    // always-leader + empty resolver so single-controller installs and tests behave unchanged (no
    // redirect, epoch 1); bootstrap injects the real elector + a leader-address resolver.
    private volatile Leadership leadership = Leadership.alwaysLeader();
    private volatile Supplier<String> leaderGrpcAddressResolver = () -> "";
    // The cluster's live controller gRPC addresses, advertised on every HandshakeAck so the daemon's
    // seed list self-heals as membership changes. Default empty so single-controller installs and tests
    // behave unchanged; bootstrap injects the real member enumeration.
    private volatile Supplier<List<String>> controllerAddrsResolver = List::of;

    DaemonConnectionLifecycle(
            NodeSessionManager sessionManager,
            HeartbeatTracker heartbeatTracker,
            EventBus eventBus,
            ClusterState clusterState,
            StateStore stateStore,
            GroupManager groupManager,
            CatalogStore catalogStore,
            Supplier<Scheduler> scheduler,
            Supplier<ModuleDistributor> moduleDistributor,
            Supplier<DaemonEventForwarder> daemonEventForwarder,
            PendingRequestRegistry pendingRequests,
            long heartbeatIntervalMs,
            int controllerApiPort) {
        this.sessionManager = sessionManager;
        this.heartbeatTracker = heartbeatTracker;
        this.eventBus = eventBus;
        this.clusterState = clusterState;
        this.stateStore = stateStore;
        this.groupManager = groupManager;
        this.catalogStore = catalogStore;
        this.scheduler = scheduler;
        this.moduleDistributor = moduleDistributor;
        this.daemonEventForwarder = daemonEventForwarder;
        this.pendingRequests = pendingRequests;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.controllerApiPort = controllerApiPort;
    }

    /** Inject single-writer leadership (bootstrap, after the elector is up). Default = always-leader. */
    void setLeadership(Leadership leadership) {
        if (leadership != null) {
            this.leadership = leadership;
        }
    }

    /**
     * Inject the resolver that returns the current leader's gRPC address (or empty when this
     * controller is the leader / the leader is unknown). Used to redirect a daemon that handshakes
     * onto a follower.
     */
    void setLeaderGrpcAddressResolver(Supplier<String> resolver) {
        if (resolver != null) {
            this.leaderGrpcAddressResolver = resolver;
        }
    }

    /**
     * Inject the resolver that enumerates the cluster's live controller gRPC addresses, advertised to
     * daemons so their seed list self-heals as controllers are added / replaced.
     */
    void setControllerAddrsResolver(Supplier<List<String>> resolver) {
        if (resolver != null) {
            this.controllerAddrsResolver = resolver;
        }
    }

    /**
     * Stamp the leadership fields onto a {@link HandshakeAck} (Phase 3 redirect/fencing). The leader
     * sets its fencing {@code epoch} so the daemon floors on it; a follower instead sets
     * {@code leader_grpc_addr} so the daemon redirects. An empty address leaves the daemon on this
     * controller (no known leader yet — the daemon retries / rotates its seed list until one resolves).
     * Package-private + static so the decision is unit-tested without the full handshake harness.
     */
    static void applyLeadership(HandshakeAck.Builder ack, Leadership leadership, Supplier<String> leaderAddrResolver) {
        if (leadership.isLeader()) {
            ack.setEpoch(leadership.currentEpoch());
            return;
        }
        String leaderAddr = leaderAddrResolver.get();
        if (leaderAddr != null && !leaderAddr.isBlank()) {
            ack.setLeaderGrpcAddr(leaderAddr);
        }
    }

    /**
     * Stamp the cluster's live controller gRPC addresses onto a {@link HandshakeAck} so the daemon's
     * seed list self-heals. Applied for leader and follower alike. Package-private + static so it's
     * unit-tested without the full handshake harness.
     */
    static void applyControllerAddrs(HandshakeAck.Builder ack, Supplier<List<String>> resolver) {
        List<String> addrs = resolver.get();
        if (addrs == null) {
            return;
        }
        for (String addr : addrs) {
            if (addr != null && !addr.isBlank()) {
                ack.addControllerGrpcAddrs(addr);
            }
        }
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

        // Instance reconciliation is the LEADER's job only. A follower has no authority and an
        // unhydrated instance view; if it reconciled it would force-stop a running instance it simply
        // doesn't know about (and it's about to redirect the daemon to the leader anyway). Gating here
        // is what keeps a leadership change transparent to players.
        if (leadership.isLeader()) {
            reconcileInstances(nodeId, handshake);
            Scheduler sched = scheduler.get();
            if (sched != null) {
                sched.reconcileRecoverableStartsForNode(nodeId);
            }
        }

        eventBus.publish(new NodeConnectedEvent(nodeId, sessionId, Instant.now()));

        var ackBuilder = HandshakeAck.newBuilder()
                .setSessionId(sessionId)
                .setHeartbeatIntervalMs(heartbeatIntervalMs)
                .setControllerApiPort(controllerApiPort)
                .setProtocolVersion(1)
                .setProtocolCompatible(daemonProtocolVersion >= 1);
        applyLeadership(ackBuilder, leadership, leaderGrpcAddressResolver);
        // Advertise the cluster's live controllers regardless of leadership — the leader is every
        // healthy daemon's steady-state target, so it must send the list too (applyLeadership returns
        // early for the leader, hence a separate stamping step here).
        applyControllerAddrs(ackBuilder, controllerAddrsResolver);
        var ack = ControllerMessage.newBuilder().setHandshakeAck(ackBuilder).build();
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
            clusterState.removeNode(nodeId, reason);
            stateStore.updateNodeLastSeen(nodeId);
            eventBus.publish(new NodeDisconnectedEvent(nodeId, reason, Instant.now()));
        }
    }

    /** What to do with an instance a daemon reports running, given our current knowledge of it. */
    enum ReportedInstanceAction {
        /** Unknown to us but its group exists — adopt the live server (never kill a wanted instance). */
        ADOPT,
        /** Unknown to us and its group is gone — a genuine orphan; reap it. */
        REAP,
        /** Known, but our record places it on a different node — leave it (don't double-own). */
        SKIP_WRONG_NODE,
        /** Known and on this node — refresh its status from the daemon report. */
        UPDATE
    }

    /**
     * Pure adopt-vs-reap policy for a daemon-reported running instance. The daemon is the ground truth
     * that the instance is <em>running</em>; the only question is whether it is <em>wanted</em>. A
     * running instance whose group still exists is adopted, never killed — only an instance whose group
     * is gone is a genuine orphan. If our record exists but has gone terminal (CRASHED/STOPPED) while
     * the daemon still runs it, we re-adopt (resurrect) the daemon's ground truth rather than leave a
     * live server stuck "dead" in our view. Package-private + static so the policy is unit-tested
     * without the full handshake harness (mirrors {@link #applyLeadership}).
     */
    static ReportedInstanceAction decideReportedInstance(
            Optional<InstanceInfo> known, String reconnectedNodeId, boolean groupExists) {
        if (known.isEmpty()) {
            return groupExists ? ReportedInstanceAction.ADOPT : ReportedInstanceAction.REAP;
        }
        if (!known.get().nodeId().equals(reconnectedNodeId)) {
            return ReportedInstanceAction.SKIP_WRONG_NODE;
        }
        if (isTerminal(known.get().state())) {
            // Our record went terminal (e.g. a transient disconnect marked it CRASHED) but the daemon
            // is actually running it. Re-adopt its ground truth — a plain status update would be
            // rejected by the terminal-state transition guard, leaving the live server stuck "dead".
            return groupExists ? ReportedInstanceAction.ADOPT : ReportedInstanceAction.REAP;
        }
        return ReportedInstanceAction.UPDATE;
    }

    private static boolean isTerminal(InstanceState state) {
        return state == InstanceState.STOPPED || state == InstanceState.CRASHED;
    }

    private void reconcileInstances(String reconnectedNodeId, Handshake handshake) {
        var daemonInstanceIds = new HashSet<String>();
        for (var running : handshake.getRunningInstancesList()) {
            String instanceId = running.getInstanceId();
            if (!InputValidator.isSafeName(instanceId)) {
                logger.warn("reconcileInstances: skipping invalid instanceId from node {}", reconnectedNodeId);
                continue;
            }
            var known = clusterState.getInstance(instanceId);
            switch (decideReportedInstance(known, reconnectedNodeId, groupManager.exists(running.getGroup()))) {
                case ADOPT -> {
                    // The daemon is authoritatively running this instance and its group still exists,
                    // but we have no record of it (e.g. our state was lost across a leadership change /
                    // Redis gap). Adopt the live server instead of killing it — keeping the leader
                    // change transparent to players. Desired-state enforcement (excess scale-down) is
                    // the convergence-gated scheduler's job, not a reflexive handshake kill.
                    logger.info(
                            "reconcileInstances: adopting running instance {} (group={}) reported by node {}",
                            instanceId,
                            running.getGroup(),
                            reconnectedNodeId);
                    clusterState.addInstance(new InstanceInfo(
                            instanceId,
                            running.getGroup(),
                            reconnectedNodeId,
                            // The daemon never reports RUNNING — it only tracks SCHEDULED/PREPARING/STARTING;
                            // readiness is the in-server plugin's one-shot POST /api/plugin/ready to the
                            // controller. So adopting the daemon's reported state would pin an already-serving
                            // instance at STARTING forever (that one-shot /ready already fired, won't repeat),
                            // and the scheduler would keep re-dispatching it. Re-derive RUNNING: we only adopt
                            // an instance the daemon reports alive whose record we lost — it's a running server.
                            InstanceState.RUNNING,
                            running.getPort(),
                            0,
                            0,
                            Instant.now()));
                    daemonInstanceIds.add(instanceId);
                }
                case REAP -> {
                    // Genuine orphan: no group wants this instance (its group was deleted). Reap it so
                    // it stops holding its port and resources. Reaches here only on the leader.
                    logger.warn(
                            "reconcileInstances: node {} reports instance {} for unknown group {} -- stopping orphan",
                            reconnectedNodeId,
                            instanceId,
                            running.getGroup());
                    scheduler.get().stopOrphanInstanceOnNode(reconnectedNodeId, instanceId);
                }
                case SKIP_WRONG_NODE ->
                    logger.warn(
                            "reconcileInstances: instance {} belongs to node {}, not {}",
                            instanceId,
                            known.get().nodeId(),
                            reconnectedNodeId);
                case UPDATE -> {
                    daemonInstanceIds.add(instanceId);
                    clusterState.updateInstanceStatus(instanceId, running.getState(), 0, 0);
                }
            }
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
