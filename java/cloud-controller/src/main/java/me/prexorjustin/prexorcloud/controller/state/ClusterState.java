package me.prexorjustin.prexorcloud.controller.state;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import me.prexorjustin.prexorcloud.api.event.events.GroupAggregatesUpdatedEvent;
import me.prexorjustin.prexorcloud.api.event.events.InstanceMetricsUpdatedEvent;
import me.prexorjustin.prexorcloud.api.event.events.InstanceStateChangedEvent;
import me.prexorjustin.prexorcloud.api.event.events.NodeCacheStatusUpdatedEvent;
import me.prexorjustin.prexorcloud.api.event.events.NodeConnectedEvent;
import me.prexorjustin.prexorcloud.api.event.events.NodeDisconnectedEvent;
import me.prexorjustin.prexorcloud.api.event.events.NodeStatusUpdatedEvent;
import me.prexorjustin.prexorcloud.api.event.events.PlayerConnectedEvent;
import me.prexorjustin.prexorcloud.api.event.events.PlayerDisconnectedEvent;
import me.prexorjustin.prexorcloud.api.event.events.PlayerTransferEvent;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe in-memory cluster state. Publishes events on state changes.
 * Rebuilt from scratch on controller restart.
 */
public final class ClusterState {

    private static final Logger logger = LoggerFactory.getLogger(ClusterState.class);

    private final NodeRegistry nodeRegistry = new NodeRegistry();
    private final InstanceRegistry instanceRegistry = new InstanceRegistry();
    private final PlayerSessionRegistry playerSessionRegistry = new PlayerSessionRegistry();
    private final WorkloadIdentityRegistry workloadIdentityRegistry;
    private final EventBus eventBus;
    // Durable plugin-token store (Mongo), nullable in dev/tests. Node/instance/player runtime state
    // is NOT projected to a shared store: the single-writer leader owns every daemon stream and
    // rebuilds that state in-memory from daemon re-announce, so only the tokens need durability
    // (the 401 fix — a cold leader reads a still-valid token back rather than rejecting the plugin).
    private final WorkloadTokenStore tokenStore;
    /** Last published (runningInstances, totalPlayers) per group, for emit-on-change deduplication. */
    private final ConcurrentHashMap<String, long[]> lastGroupAggregates = new ConcurrentHashMap<>();

    public ClusterState(EventBus eventBus, WorkloadTokenStore tokenStore) {
        this.eventBus = eventBus;
        this.tokenStore = tokenStore;
        this.workloadIdentityRegistry =
                tokenStore == null ? new WorkloadIdentityRegistry() : new WorkloadIdentityRegistry(tokenStore);
    }

    public ClusterState(EventBus eventBus) {
        this(eventBus, null);
    }

    /** Warm the in-memory plugin-token cache from durable storage on leadership takeover. */
    public void hydrate(WorkloadTokenStore store) {
        workloadIdentityRegistry.hydrate(store.loadAllTokens());
    }

    /**
     * Wire the post-takeover grace signal (typically {@code ConvergenceGate.isObserving()}) so a freshly
     * elected leader accepts a still-durable plugin token whose instance the daemon has not yet re-reported,
     * instead of 401'ing it for the duration of the convergence window. See {@code WorkloadIdentityRegistry}.
     */
    public void setPostTakeoverGraceSupplier(java.util.function.BooleanSupplier supplier) {
        workloadIdentityRegistry.setPostTakeoverGraceSupplier(supplier);
    }

    // --- Nodes ---

    public void addNode(
            String nodeId,
            String address,
            long totalMemoryMb,
            Map<String, String> labels,
            Instant connectedSince,
            NodeHostInfo hostInfo) {
        var node = nodeRegistry.add(nodeId, address, totalMemoryMb, labels, connectedSince, hostInfo);
        logger.debug("Node added: {} (address={}, labels={})", nodeId, address, labels);
        eventBus.publish(new NodeConnectedEvent(nodeId, nodeId, connectedSince));
    }

    public void removeNode(String nodeId, String reason) {
        nodeRegistry.remove(nodeId);
        logger.debug("Node removed: {} ({})", nodeId, reason);
        eventBus.publish(new NodeDisconnectedEvent(nodeId, reason, Instant.now()));
    }

    public void updateNodeStatus(
            String nodeId,
            double cpuUsage,
            long totalMemoryMb,
            long usedMemoryMb,
            long freeDiskMb,
            long totalDiskMb,
            int instanceCount,
            Set<Integer> usedPorts) {
        var updated = nodeRegistry.updateStatus(
                nodeId, cpuUsage, totalMemoryMb, usedMemoryMb, freeDiskMb, totalDiskMb, instanceCount, usedPorts);
        if (updated != null) {
            eventBus.publish(
                    new NodeStatusUpdatedEvent(nodeId, cpuUsage, usedMemoryMb, totalMemoryMb, updated.lastHeartbeat()));
        }
    }

    /**
     * Atomically reserve a port + memory for a placement (single-writer guard against
     * concurrent placements double-allocating a port — see {@link NodeRegistry#reservePlacement}).
     * Returns the claimed reservation (whose {@link NodeRegistry.Reservation#port()} the caller
     * must use), or empty if the node is gone or has no free port in range.
     */
    public Optional<NodeRegistry.Reservation> reservePlacement(
            String nodeId, long memoryMb, int portRangeStart, int portRangeEnd) {
        var reservation = nodeRegistry.reservePlacement(nodeId, memoryMb, portRangeStart, portRangeEnd);
        reservation.ifPresent(r -> {
            eventBus.publish(new NodeStatusUpdatedEvent(
                    nodeId,
                    r.node().cpuUsage(),
                    r.node().usedMemoryMb(),
                    r.node().totalMemoryMb(),
                    r.node().lastHeartbeat()));
        });
        return reservation;
    }

    /** Release a reservation (port + memory) made by {@link #reservePlacement} — placement rollback. */
    public void releasePlacement(String nodeId, long memoryMb, int port) {
        var updated = nodeRegistry.releasePlacement(nodeId, memoryMb, port);
        if (updated != null) {
            eventBus.publish(new NodeStatusUpdatedEvent(
                    nodeId,
                    updated.cpuUsage(),
                    updated.usedMemoryMb(),
                    updated.totalMemoryMb(),
                    updated.lastHeartbeat()));
        }
    }

    /**
     * Apply a daemon NodeStatus heartbeat as telemetry that cannot recycle a controller
     * reservation. Ports union with the existing set; memory is preserved while an
     * instance on the node is still pre-running (SCHEDULED/PREPARING/STARTING) and then
     * follows the daemon (self-healing). See {@link NodeRegistry#applyTelemetry}.
     */
    public void applyNodeTelemetry(
            String nodeId,
            double cpuUsage,
            long totalMemoryMb,
            long reportedUsedMemoryMb,
            long freeDiskMb,
            long totalDiskMb,
            int reportedInstanceCount,
            Set<Integer> reportedPorts) {
        var onNode = getInstancesByNode(nodeId);
        boolean hasPreRunning = onNode.stream()
                .anyMatch(i -> i.state() == InstanceState.SCHEDULED
                        || i.state() == InstanceState.PREPARING
                        || i.state() == InstanceState.STARTING);
        var updated = nodeRegistry.applyTelemetry(
                nodeId,
                cpuUsage,
                totalMemoryMb,
                reportedUsedMemoryMb,
                freeDiskMb,
                totalDiskMb,
                reportedInstanceCount,
                reportedPorts,
                onNode.size(),
                hasPreRunning);
        if (updated != null) {
            eventBus.publish(new NodeStatusUpdatedEvent(
                    nodeId,
                    updated.cpuUsage(),
                    updated.usedMemoryMb(),
                    updated.totalMemoryMb(),
                    updated.lastHeartbeat()));
        }
    }

    public void setNodeStatus(String nodeId, NodeState.NodeStatus status) {
        nodeRegistry.setStatus(nodeId, status);
    }

    public void recordHeartbeat(String nodeId) {
        nodeRegistry.recordHeartbeat(nodeId, Instant.now());
    }

    public Optional<NodeState> getNode(String nodeId) {
        return nodeRegistry.get(nodeId);
    }

    public Collection<NodeState> getAllNodes() {
        return nodeRegistry.getAll();
    }

    // --- Instances ---

    public void addInstance(InstanceInfo instance) {
        instanceRegistry.add(instance);
        logger.debug("Instance added: {} (group={})", instance.id(), instance.group());
        eventBus.publish(new InstanceStateChangedEvent(
                instance.id(),
                instance.group(),
                instance.nodeId(),
                toModuleState(InstanceState.INSTANCE_STATE_UNSPECIFIED),
                toModuleState(instance.state())));
        publishGroupAggregatesIfChanged(instance.group());
    }

    /**
     * Atomically place a freshly-scheduled instance only while its group is still below
     * {@code maxInstances} non-terminal instances. Returns {@code false} when a concurrent placement
     * already filled the group — the caller must release any node reservation it took.
     *
     * <p>This is the single serialization point that keeps the two independent placement paths —
     * crash-heal replacement ({@code Scheduler.scheduleReplacement}, on the healing worker) and the
     * min-instance / scale-up reconcile ({@code Scheduler.evaluateGroup}, on the scheduler thread) —
     * from both passing their own {@code active < max} check and over-provisioning the group. The
     * per-group lease that used to serialize them is gone (leadership is the only fence now), so the
     * count check and the insert must be one critical section. The count excludes the instance's own
     * id (re-placing a crashed id under the same name is not a net add) and terminal records.
     */
    public synchronized boolean addInstanceWithinCap(InstanceInfo instance, int maxInstances) {
        long active = instanceRegistry.getByGroup(instance.group()).stream()
                .filter(i -> !i.id().equals(instance.id()))
                .filter(ClusterState::countsAsRunning)
                .count();
        if (active >= maxInstances) {
            return false;
        }
        addInstance(instance);
        return true;
    }

    public void updateInstanceState(String instanceId, InstanceState newState) {
        InstanceInfo existing = instanceRegistry.get(instanceId).orElse(null);
        if (!canApplyInstanceTransition(instanceId, existing, newState)) return;
        InstanceInfo updated = instanceRegistry.updateState(instanceId, newState);

        if (existing != null && updated != null && existing.state() != newState) {
            eventBus.publish(new InstanceStateChangedEvent(
                    instanceId,
                    existing.group(),
                    existing.nodeId(),
                    toModuleState(existing.state()),
                    toModuleState(newState)));
            publishGroupAggregatesIfChanged(existing.group());
        }
    }

    /**
     * Renewable readiness assertion from a live in-instance workload (server plugin / proxy).
     *
     * <p>Unlike the original one-shot {@code /ready}, the workload re-asserts readiness on every
     * heartbeat. That makes run-state self-healing: a lost one-shot readiness POST (a network blip
     * during boot) or a cold-leader rebuild after a failover no longer pins a live instance at
     * {@code STARTING} — the next heartbeat re-derives {@code RUNNING} from the authoritative source
     * (the workload itself) instead of the controller guessing. This is the renewable counterpart to
     * the daemon-reconnect adopt heuristic in {@code DaemonConnectionLifecycle}: existence/placement
     * is the daemon's authority, readiness is the workload's.
     *
     * <p>Promotes only from the pre-ready states ({@code SCHEDULED}/{@code PREPARING}/{@code STARTING}).
     * It is deliberately a no-op for every other state:
     * <ul>
     *   <li>{@code RUNNING} — already ready (the overwhelmingly common repeat case);</li>
     *   <li>{@code DRAINING}/{@code STOPPING} — a deliberate shutdown the operator or scheduler
     *       initiated. A repeating readiness ping must never un-drain a server, and the transition
     *       validator <em>does</em> permit {@code DRAINING -> RUNNING} (for legitimate un-drains), so
     *       routing this through {@link #updateInstanceState} unconditionally would be a footgun;</li>
     *   <li>{@code STOPPED}/{@code CRASHED} — terminal. Resurrecting a falsely-terminal record stays
     *       the daemon-reconnect adopt path's job: it holds the node/port needed to rebuild it.</li>
     * </ul>
     *
     * <p>When no record exists yet (e.g. a cold leader before the daemon handshake re-adopts the
     * instance) this is a no-op — there is nothing to promote and readiness alone cannot rebuild a
     * record (it carries no placement). The adopt path recreates the record; this keeps it RUNNING.
     */
    public void renewInstanceReadiness(String instanceId) {
        InstanceInfo existing = instanceRegistry.get(instanceId).orElse(null);
        if (existing == null) return;
        switch (existing.state()) {
            case SCHEDULED, PREPARING, STARTING -> updateInstanceState(instanceId, InstanceState.RUNNING);
            default -> {
                // RUNNING: already ready. DRAINING/STOPPING/STOPPED/CRASHED: respect the intent.
            }
        }
    }

    public void updateInstanceStatus(String instanceId, InstanceState state, int playerCount, long uptimeMs) {
        InstanceInfo existing = instanceRegistry.get(instanceId).orElse(null);
        if (!canApplyInstanceTransition(instanceId, existing, state)) return;
        InstanceInfo updated = instanceRegistry.updateStatus(instanceId, state, playerCount, uptimeMs);

        if (existing != null && updated != null && existing.state() != state) {
            eventBus.publish(new InstanceStateChangedEvent(
                    instanceId,
                    existing.group(),
                    existing.nodeId(),
                    toModuleState(existing.state()),
                    toModuleState(state)));
        }
        // playerCount may have shifted even when state didn't — re-evaluate aggregates.
        if (existing != null) publishGroupAggregatesIfChanged(existing.group());
    }

    /** Release a freed instance port from the owning node. */
    private void releaseNodePort(String nodeId, int port) {
        nodeRegistry.releasePort(nodeId, port);
    }

    public void removeInstance(String instanceId) {
        Optional<InstanceInfo> removed = instanceRegistry.get(instanceId);
        String group = removed.map(InstanceInfo::group).orElse(null);
        playerSessionRegistry.removeByInstance(instanceId);
        instanceRegistry.remove(instanceId);
        removed.ifPresent(i -> releaseNodePort(i.nodeId(), i.port()));
        unregisterPluginToken(instanceId);
        if (group != null) publishGroupAggregatesIfChanged(group);
    }

    /**
     * Recomputes the (runningInstances, totalPlayers) tuple for {@code group} and
     * fires {@link GroupAggregatesUpdatedEvent} only if it changed since the last
     * emit. Lets dashboards patch cached aggregates without re-fetching.
     */
    private void publishGroupAggregatesIfChanged(String group) {
        if (group == null || group.isEmpty()) return;
        var instances = instanceRegistry.getByGroup(group);
        int running =
                (int) instances.stream().filter(ClusterState::countsAsRunning).count();
        int totalPlayers =
                instances.stream().mapToInt(InstanceInfo::playerCount).sum();
        long packed = ((long) running << 32) | (totalPlayers & 0xFFFF_FFFFL);
        long[] previous = lastGroupAggregates.get(group);
        if (previous != null && previous[0] == packed) return;
        // Skip the first-ever emit for a group we've never seen running — only
        // publish when transitioning away from the previously-observed value.
        if (previous == null && running == 0) return;
        if (running == 0) {
            lastGroupAggregates.remove(group);
        } else {
            lastGroupAggregates.put(group, new long[] {packed});
        }
        eventBus.publish(new GroupAggregatesUpdatedEvent(group, running, totalPlayers));
    }

    /** Issue a new short-lived plugin token for the instance and persist it. */
    public String issuePluginToken(String instanceId) {
        String token = workloadIdentityRegistry.issuePluginToken(instanceId);
        if (tokenStore != null) {
            workloadIdentityRegistry.getPluginToken(token).ifPresent(entry -> tokenStore.saveToken(token, entry));
        }
        return token;
    }

    /**
     * Register an externally-supplied token (test fixtures). Production paths
     * should call {@link #issuePluginToken}.
     */
    public void registerPluginToken(String token, String instanceId) {
        workloadIdentityRegistry.registerPluginToken(token, instanceId);
        if (tokenStore != null) {
            workloadIdentityRegistry.getPluginToken(token).ifPresent(entry -> tokenStore.saveToken(token, entry));
        }
    }

    /**
     * Import a token with an explicit issue timestamp for test fixtures and
     * legacy hydration paths.
     */
    public void importPluginToken(String token, String instanceId, Instant issuedAt) {
        workloadIdentityRegistry.importPluginToken(token, instanceId, issuedAt);
        if (tokenStore != null) {
            workloadIdentityRegistry.getPluginToken(token).ifPresent(entry -> {
                if (Instant.now().isBefore(entry.expiresAt())) {
                    tokenStore.saveToken(token, entry);
                } else {
                    tokenStore.removeToken(token);
                }
            });
        }
    }

    public Optional<String> validatePluginToken(String token) {
        Optional<String> validated = workloadIdentityRegistry.validatePluginToken(token, this::getInstance);
        if (validated.isEmpty()
                && tokenStore != null
                && workloadIdentityRegistry.getPluginToken(token).isEmpty()) {
            if (hydratePluginTokenFromStore(token)) {
                return workloadIdentityRegistry.validatePluginToken(token, this::getInstance);
            }
            tokenStore.removeToken(token);
        }
        return validated;
    }

    public Optional<String> validatePluginToken(String token, long sequence) {
        Optional<String> validated = workloadIdentityRegistry.validatePluginToken(token, sequence, this::getInstance);
        if (validated.isEmpty()
                && tokenStore != null
                && workloadIdentityRegistry.getPluginToken(token).isEmpty()) {
            if (hydratePluginTokenFromStore(token)) {
                return workloadIdentityRegistry.validatePluginToken(token, sequence, this::getInstance);
            }
            tokenStore.removeToken(token);
        }
        return validated;
    }

    /**
     * Hydrate a plugin token from the durable {@link WorkloadTokenStore} into the in-process registry.
     * The 401 fix: a token is durable in Mongo, but a cold leader's in-memory cache misses it after a
     * leadership change. Rather than reject the call (and delete the still-valid token), read it back.
     *
     * @return true if a (non-expired) token was found in the store and adopted locally
     */
    private boolean hydratePluginTokenFromStore(String token) {
        if (tokenStore == null) return false;
        var entry = tokenStore.loadToken(token).orElse(null);
        if (entry == null) return false;
        workloadIdentityRegistry.adopt(token, entry);
        return workloadIdentityRegistry.getPluginToken(token).isPresent();
    }

    /**
     * Exchange an existing plugin token for a fresh one. The old token is
     * invalidated atomically. Returns the new bearer string or empty if the
     * caller's token was invalid/expired/revoked.
     */
    public Optional<String> refreshPluginToken(String currentToken) {
        var result = workloadIdentityRegistry.refreshPluginToken(currentToken, this::getInstance);
        if (result.isEmpty()) {
            if (tokenStore != null) tokenStore.removeToken(currentToken);
            return Optional.empty();
        }
        if (tokenStore != null) {
            tokenStore.removeToken(currentToken);
            tokenStore.saveToken(result.get().token(), result.get().entry());
        }
        return Optional.of(result.get().token());
    }

    public Optional<String> refreshPluginToken(String currentToken, long sequence) {
        var result = workloadIdentityRegistry.refreshPluginToken(currentToken, sequence, this::getInstance);
        if (result.isEmpty()) {
            if (tokenStore != null) tokenStore.removeToken(currentToken);
            return Optional.empty();
        }
        if (tokenStore != null) {
            tokenStore.removeToken(currentToken);
            tokenStore.saveToken(result.get().token(), result.get().entry());
        }
        return Optional.of(result.get().token());
    }

    public void revokePluginToken(String token) {
        workloadIdentityRegistry.revokeToken(token);
        if (tokenStore != null) tokenStore.removeToken(token);
    }

    public boolean revokePluginTokenId(String tokenId) {
        boolean revoked = false;
        for (var entry : workloadIdentityRegistry.pluginTokens().entrySet()) {
            if (entry.getValue().tokenId().equals(tokenId)) {
                revoked = workloadIdentityRegistry.revokeTokenId(tokenId);
                if (revoked && tokenStore != null) {
                    tokenStore.removeToken(entry.getKey());
                }
                break;
            }
        }
        return revoked;
    }

    public int revokePluginTokensForInstance(String instanceId) {
        var tokens = workloadIdentityRegistry.pluginTokens().entrySet().stream()
                .filter(entry -> entry.getValue().instanceId().equals(instanceId))
                .map(Map.Entry::getKey)
                .toList();
        tokens.forEach(this::revokePluginToken);
        return tokens.size();
    }

    public void unregisterPluginToken(String instanceId) {
        if (tokenStore != null) {
            workloadIdentityRegistry.pluginTokens().forEach((token, entry) -> {
                if (instanceId.equals(entry.instanceId())) tokenStore.removeToken(token);
            });
        }
        workloadIdentityRegistry.unregisterPluginTokens(instanceId);
    }

    public Optional<InstanceInfo> getInstance(String instanceId) {
        return instanceRegistry.get(instanceId);
    }

    public Collection<InstanceInfo> getAllInstances() {
        return instanceRegistry.getAll();
    }

    public List<InstanceInfo> getInstancesByGroup(String group) {
        return instanceRegistry.getByGroup(group);
    }

    public List<InstanceInfo> getInstancesByNode(String nodeId) {
        return instanceRegistry.getByNode(nodeId);
    }

    /**
     * Whether an instance counts toward a group's user-facing "running" aggregate. A
     * terminal record (STOPPED/CRASHED) lingers in state until the cleanup delay reaps it;
     * counting it would inflate {@code runningInstances} (e.g. a node-disconnect-CRASHED
     * instance showing up as still running).
     */
    private static boolean countsAsRunning(InstanceInfo instance) {
        return instance.state() != InstanceState.STOPPED && instance.state() != InstanceState.CRASHED;
    }

    /** Count of a group's instances that are not in a terminal state (STOPPED/CRASHED). */
    public int runningInstanceCount(String group) {
        return (int) instanceRegistry.getByGroup(group).stream()
                .filter(ClusterState::countsAsRunning)
                .count();
    }

    // --- Players ---

    /**
     * Register a player from a backend server report. Preserves the existing
     * proxyInstanceId if the player was already registered by the proxy.
     */
    public void addPlayer(UUID uuid, String name, String instanceId, String group) {
        var updated = playerSessionRegistry.addReportedByBackend(uuid, name, instanceId, group);
        if (updated.created()) {
            eventBus.publish(new PlayerConnectedEvent(uuid, name, instanceId, group));
        } else {
            publishTransferIfMoved(updated, name);
        }
    }

    /**
     * Register a player from the proxy with explicit proxy instance tracking.
     */
    public void addPlayer(UUID uuid, String name, String instanceId, String group, String proxyInstanceId) {
        addPlayer(uuid, name, instanceId, group, proxyInstanceId, null);
    }

    /**
     * Register a player from the proxy with explicit proxy instance tracking and an authoritative
     * edition (blank ⇒ derived from the UUID). The Geyser sidecar passes {@code bedrock} so its
     * sessions aren't mis-detected as Java.
     */
    public void addPlayer(
            UUID uuid, String name, String instanceId, String group, String proxyInstanceId, String edition) {
        var updated = playerSessionRegistry.addReportedByProxy(uuid, name, instanceId, group, proxyInstanceId, edition);
        if (updated.created()) {
            eventBus.publish(new PlayerConnectedEvent(uuid, name, instanceId, group));
        } else {
            publishTransferIfMoved(updated, name);
        }
    }

    private void publishTransferIfMoved(PlayerSessionRegistry.PlayerMutationResult result, String name) {
        var previous = result.previous();
        if (previous == null) return;
        String oldInstance = previous.instanceId();
        String newInstance = result.player().instanceId();
        if (oldInstance == null || newInstance == null || oldInstance.equals(newInstance)) return;
        eventBus.publish(new PlayerTransferEvent(result.player().uuid(), name, oldInstance, newInstance));
    }

    public void removePlayer(UUID uuid) {
        var player = playerSessionRegistry.remove(uuid);
        if (player != null) {
            eventBus.publish(new PlayerDisconnectedEvent(uuid, player.name(), player.instanceId(), player.group()));
        }
    }

    public Optional<PlayerInfo> getPlayer(UUID uuid) {
        return playerSessionRegistry.get(uuid);
    }

    public Collection<PlayerInfo> getAllPlayers() {
        return playerSessionRegistry.getAll();
    }

    // --- Instance Metrics ---

    public void updateInstanceMetrics(InstanceMetrics metrics) {
        instanceRegistry.updateMetrics(metrics);
        var instance = instanceRegistry.get(metrics.instanceId()).orElse(null);
        if (instance != null) {
            updateInstanceStatus(metrics.instanceId(), instance.state(), metrics.playerCount(), metrics.uptimeMs());
            List<InstanceMetricsUpdatedEvent.WorldSnapshot> worlds = metrics.worlds() == null
                    ? List.of()
                    : metrics.worlds().stream()
                            .map(w -> new InstanceMetricsUpdatedEvent.WorldSnapshot(
                                    w.name(), w.environment(), w.entityCount(), w.chunkCount(), w.playerCount()))
                            .toList();
            eventBus.publish(new InstanceMetricsUpdatedEvent(
                    metrics.instanceId(),
                    instance.group(),
                    metrics.tps1m(),
                    metrics.tps5m(),
                    metrics.tps15m(),
                    metrics.msptAvg(),
                    metrics.heapUsedMb(),
                    metrics.heapMaxMb(),
                    metrics.gcCollections(),
                    metrics.gcTimeMs(),
                    metrics.threadCount(),
                    metrics.playerCount(),
                    metrics.maxPlayers(),
                    metrics.worldCount(),
                    metrics.totalEntities(),
                    metrics.totalChunks(),
                    worlds,
                    metrics.serverVersion(),
                    metrics.pluginCount()));
        }
    }

    public Optional<InstanceMetrics> getInstanceMetrics(String instanceId) {
        return instanceRegistry.getMetrics(instanceId);
    }

    // --- Proxy Metrics ---

    public void updateProxyMetrics(ProxyMetrics metrics) {
        instanceRegistry.updateProxyMetrics(metrics);
        // Proxy metrics also carry the instance's live uptime + connected players; without this
        // the proxy InstanceInfo never gets them (the server metrics path does the same), so the
        // instance list shows a proxy frozen at 0 players / 0s uptime.
        var instance = instanceRegistry.get(metrics.instanceId()).orElse(null);
        if (instance != null) {
            updateInstanceStatus(
                    metrics.instanceId(), instance.state(), metrics.totalNetworkPlayers(), metrics.proxyUptimeMs());
        }
    }

    public Optional<ProxyMetrics> getProxyMetrics(String instanceId) {
        return instanceRegistry.getProxyMetrics(instanceId);
    }

    // --- Node Cache Status ---

    public void updateCacheStatus(String nodeId, NodeCacheStatus status) {
        nodeRegistry.updateCacheStatus(nodeId, status);
        eventBus.publish(new NodeCacheStatusUpdatedEvent(nodeId, status.totalSizeBytes(), Instant.now()));
    }

    public Optional<NodeCacheStatus> getCacheStatus(String nodeId) {
        return nodeRegistry.getCacheStatus(nodeId);
    }

    // --- Counts ---

    public int nodeCount() {
        return nodeRegistry.count();
    }

    public int instanceCount() {
        return instanceRegistry.count();
    }

    public int playerCount() {
        return playerSessionRegistry.count();
    }

    // --- Decomposed state surfaces ---

    public NodeRegistry nodeRegistry() {
        return nodeRegistry;
    }

    public InstanceRegistry instanceRegistry() {
        return instanceRegistry;
    }

    public PlayerSessionRegistry playerSessionRegistry() {
        return playerSessionRegistry;
    }

    public WorkloadIdentityRegistry workloadIdentityRegistry() {
        return workloadIdentityRegistry;
    }

    private boolean canApplyInstanceTransition(String instanceId, InstanceInfo existing, InstanceState nextState) {
        if (existing == null || existing.state() == nextState) {
            return true;
        }
        if (InstanceTransitionValidator.isValid(existing.state(), nextState)) {
            return true;
        }
        // DEBUG, not WARN: the common case is a benign idempotency guard — a daemon re-announcing a
        // RUNNING instance as STARTING after a reconnect/adoption. The transition is harmlessly
        // rejected (we keep the existing state); it does not indicate a fault.
        logger.debug("Rejected invalid instance transition for {}: {} -> {}", instanceId, existing.state(), nextState);
        return false;
    }

    /** Convert protobuf InstanceState to module-api InstanceState for events. */
    public static me.prexorjustin.prexorcloud.api.domain.InstanceState toModuleState(InstanceState protoState) {
        return switch (protoState) {
            case SCHEDULED -> me.prexorjustin.prexorcloud.api.domain.InstanceState.SCHEDULED;
            case PREPARING -> me.prexorjustin.prexorcloud.api.domain.InstanceState.PREPARING;
            case STARTING -> me.prexorjustin.prexorcloud.api.domain.InstanceState.STARTING;
            case RUNNING -> me.prexorjustin.prexorcloud.api.domain.InstanceState.RUNNING;
            case STOPPING -> me.prexorjustin.prexorcloud.api.domain.InstanceState.STOPPING;
            case STOPPED -> me.prexorjustin.prexorcloud.api.domain.InstanceState.STOPPED;
            case CRASHED -> me.prexorjustin.prexorcloud.api.domain.InstanceState.CRASHED;
            case DRAINING -> me.prexorjustin.prexorcloud.api.domain.InstanceState.DRAINING;
            default -> me.prexorjustin.prexorcloud.api.domain.InstanceState.SCHEDULED;
        };
    }
}
