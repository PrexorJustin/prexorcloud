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
    private final RedisRuntimeStore runtimeStore;
    /** Last published (runningInstances, totalPlayers) per group, for emit-on-change deduplication. */
    private final ConcurrentHashMap<String, long[]> lastGroupAggregates = new ConcurrentHashMap<>();

    public ClusterState(EventBus eventBus, RedisRuntimeStore runtimeStore) {
        this.eventBus = eventBus;
        this.runtimeStore = runtimeStore;
        this.workloadIdentityRegistry =
                runtimeStore == null ? new WorkloadIdentityRegistry() : new WorkloadIdentityRegistry(runtimeStore);
    }

    public ClusterState(EventBus eventBus) {
        this(eventBus, null);
    }

    /**
     * Hydrates in-memory registries from a Redis snapshot taken at startup. Called
     * once after construction when Redis is available.
     */
    public void hydrate(RedisRuntimeStore store) {
        var snapshot = store.loadAll();
        nodeRegistry.hydrate(snapshot.nodes());
        instanceRegistry.hydrate(snapshot.instances());
        playerSessionRegistry.hydrate(snapshot.players());
        workloadIdentityRegistry.hydrate(snapshot.pluginTokens());
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
        if (runtimeStore != null) runtimeStore.saveNode(nodeId, node);
        logger.debug("Node added: {} (address={}, labels={})", nodeId, address, labels);
        eventBus.publish(new NodeConnectedEvent(nodeId, nodeId, connectedSince));
    }

    public void removeNode(String nodeId, String reason) {
        nodeRegistry.remove(nodeId);
        if (runtimeStore != null) runtimeStore.removeNode(nodeId);
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
            if (runtimeStore != null) runtimeStore.saveNode(nodeId, updated);
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
            if (runtimeStore != null) runtimeStore.saveNode(nodeId, r.node());
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
            if (runtimeStore != null) runtimeStore.saveNode(nodeId, updated);
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
            if (runtimeStore != null) runtimeStore.saveNode(nodeId, updated);
            eventBus.publish(new NodeStatusUpdatedEvent(
                    nodeId,
                    updated.cpuUsage(),
                    updated.usedMemoryMb(),
                    updated.totalMemoryMb(),
                    updated.lastHeartbeat()));
        }
    }

    public void setNodeStatus(String nodeId, NodeState.NodeStatus status) {
        var updated = nodeRegistry.setStatus(nodeId, status);
        if (updated != null && runtimeStore != null) runtimeStore.saveNode(nodeId, updated);
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
        if (runtimeStore != null) runtimeStore.saveInstance(instance.id(), instance);
        logger.debug("Instance added: {} (group={})", instance.id(), instance.group());
        eventBus.publish(new InstanceStateChangedEvent(
                instance.id(),
                instance.group(),
                instance.nodeId(),
                toModuleState(InstanceState.INSTANCE_STATE_UNSPECIFIED),
                toModuleState(instance.state())));
        publishGroupAggregatesIfChanged(instance.group());
    }

    /** Per-instance count of consecutive reconciles in which the instance was absent from Redis. */
    private final ConcurrentHashMap<String, Integer> redisAbsenceStreak = new ConcurrentHashMap<>();

    /** Prune a local instance mirror only after it has been gone from Redis this many ticks. */
    private static final int PRUNE_AFTER_ABSENT_TICKS = 2;

    /**
     * Converge this controller's in-memory instance view toward the shared Redis projection,
     * which the node-owning controller keeps current via write-through. Fixes cross-controller
     * divergence: a daemon's instance-status updates only reach the node-owning controller's
     * {@link ClusterState}, so a peer that wins a group lease/leadership would otherwise be
     * blind to those instances (re-placing duplicates) or retain phantoms (inflating its scale
     * math). Three passes:
     *
     * <ul>
     *   <li><b>Add-if-missing</b> — learn instances present in Redis but unknown locally.
     *   <li><b>Converge state</b> — when a known instance's local state differs from Redis,
     *       apply the Redis state <em>through the transition validator</em>, so a stale read
     *       can never move a fresher local state backward (no owner-write-back clobber).
     *   <li><b>Prune</b> — drop local mirrors absent from the shared projection for
     *       {@link #PRUNE_AFTER_ABSENT_TICKS} consecutive ticks (the owner deletes the Redis
     *       key on {@code removeInstance}; the grace tick guards against a transient scan miss).
     * </ul>
     *
     * @return the number of previously-unknown instances learned this pass
     */
    public int reconcileInstancesFromRedis() {
        if (runtimeStore == null) return 0;
        int learned = 0;
        Map<String, InstanceInfo> redis = runtimeStore.loadInstances();

        for (var entry : redis.entrySet()) {
            redisAbsenceStreak.remove(entry.getKey());
            Optional<InstanceInfo> local = instanceRegistry.get(entry.getKey());
            if (local.isEmpty()) {
                instanceRegistry.add(entry.getValue());
                learned++;
            } else if (local.get().state() != entry.getValue().state()) {
                // Mirror the owner's authoritative state; the validator rejects a stale
                // backward transition, so this never overwrites a fresher local state.
                updateInstanceState(entry.getKey(), entry.getValue().state());
            }
        }

        List<String> toPrune = new java.util.ArrayList<>();
        for (InstanceInfo local : instanceRegistry.getAll()) {
            if (redis.containsKey(local.id())) continue;
            int streak = redisAbsenceStreak.merge(local.id(), 1, Integer::sum);
            if (streak >= PRUNE_AFTER_ABSENT_TICKS) toPrune.add(local.id());
        }
        for (String id : toPrune) {
            redisAbsenceStreak.remove(id);
            removeInstanceLocalMirror(id);
            logger.info("Pruned instance {} (gone from shared projection for {} ticks)", id, PRUNE_AFTER_ABSENT_TICKS);
        }

        if (learned > 0) {
            logger.debug("Reconciled {} previously-unknown instance(s) from Redis", learned);
        }
        return learned;
    }

    /**
     * On-demand adopt of a single peer-placed instance from the shared Redis projection.
     * Called by the node-owning controller's daemon status/console handlers when a daemon
     * reports for an instance this controller has not yet learned: a peer (the group-lease
     * holder) places the instance and writes it to Redis at {@code SCHEDULED} time, but
     * status flows only to the node-owner, which would otherwise drop it as "unknown
     * instance" until the next periodic {@link #reconcileInstancesFromRedis} tick — and that
     * tick rides the scheduler loop, which can stall. Adopting here breaks that deadlock
     * deterministically.
     *
     * <p>Guarded: only adopts when the Redis record exists <em>and</em> is assigned to
     * {@code reportingNodeId} — the daemon is authoritative for instances physically on its
     * own node, so a mismatch (or absence) is not adopted.
     *
     * @return {@code true} if the instance is now present locally (adopted or already known)
     */
    public boolean adoptInstanceFromRedis(String instanceId, String reportingNodeId) {
        if (instanceRegistry.get(instanceId).isPresent()) return true;
        if (runtimeStore == null) return false;
        InstanceInfo fromRedis = runtimeStore.loadInstance(instanceId).orElse(null);
        if (fromRedis == null || !reportingNodeId.equals(fromRedis.nodeId())) return false;
        instanceRegistry.add(fromRedis);
        logger.info(
                "Adopted peer-placed instance {} on node {} from Redis (state={})",
                instanceId,
                reportingNodeId,
                fromRedis.state());
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
        if (updated != null && runtimeStore != null) runtimeStore.saveInstance(instanceId, updated);
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
        if (updated != null && runtimeStore != null) runtimeStore.saveInstance(instanceId, updated);
    }

    /**
     * Drop a stale local instance mirror <em>without</em> touching the shared Redis
     * projection — used by {@link #reconcileInstancesFromRedis} pruning, where the instance
     * is already gone from Redis (the node-owner deleted it). Deliberately does not call
     * {@code runtimeStore.remove*}: re-deleting could clobber a Redis entry the owner has
     * since re-created in a narrow scan race. Cleans only in-memory mirrors (instance +
     * player sessions) and refreshes group aggregates.
     */
    private void removeInstanceLocalMirror(String instanceId) {
        Optional<InstanceInfo> removed = instanceRegistry.get(instanceId);
        String group = removed.map(InstanceInfo::group).orElse(null);
        playerSessionRegistry.removeByInstance(instanceId);
        instanceRegistry.remove(instanceId);
        removed.ifPresent(i -> releaseNodePort(i.nodeId(), i.port()));
        if (group != null) publishGroupAggregatesIfChanged(group);
    }

    /** Release a freed instance port from the owning node and persist the node delta. */
    private void releaseNodePort(String nodeId, int port) {
        var updated = nodeRegistry.releasePort(nodeId, port);
        if (updated != null && runtimeStore != null) runtimeStore.saveNode(nodeId, updated);
    }

    public void removeInstance(String instanceId) {
        Optional<InstanceInfo> removed = instanceRegistry.get(instanceId);
        String group = removed.map(InstanceInfo::group).orElse(null);
        if (runtimeStore != null) {
            playerSessionRegistry.getAll().forEach(player -> {
                if (instanceId.equals(player.instanceId()) || instanceId.equals(player.proxyInstanceId())) {
                    runtimeStore.removePlayer(player.uuid());
                }
            });
            workloadIdentityRegistry.pluginTokens().forEach((token, entry) -> {
                if (instanceId.equals(entry.instanceId())) runtimeStore.removePluginToken(token);
            });
        }
        playerSessionRegistry.removeByInstance(instanceId);
        instanceRegistry.remove(instanceId);
        removed.ifPresent(i -> releaseNodePort(i.nodeId(), i.port()));
        unregisterPluginToken(instanceId);
        if (runtimeStore != null) runtimeStore.removeInstance(instanceId);
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
        if (runtimeStore != null) {
            workloadIdentityRegistry
                    .getPluginToken(token)
                    .ifPresent(entry -> runtimeStore.savePluginToken(token, entry));
        }
        return token;
    }

    /**
     * Register an externally-supplied token (test fixtures). Production paths
     * should call {@link #issuePluginToken}.
     */
    public void registerPluginToken(String token, String instanceId) {
        workloadIdentityRegistry.registerPluginToken(token, instanceId);
        if (runtimeStore != null) {
            workloadIdentityRegistry
                    .getPluginToken(token)
                    .ifPresent(entry -> runtimeStore.savePluginToken(token, entry));
        }
    }

    /**
     * Import a token with an explicit issue timestamp for test fixtures and
     * legacy hydration paths.
     */
    public void importPluginToken(String token, String instanceId, Instant issuedAt) {
        workloadIdentityRegistry.importPluginToken(token, instanceId, issuedAt);
        if (runtimeStore != null) {
            workloadIdentityRegistry.getPluginToken(token).ifPresent(entry -> {
                if (Instant.now().isBefore(entry.expiresAt())) {
                    runtimeStore.savePluginToken(token, entry);
                } else {
                    runtimeStore.removePluginToken(token);
                }
            });
        }
    }

    public Optional<String> validatePluginToken(String token) {
        Optional<String> validated = workloadIdentityRegistry.validatePluginToken(token, this::getInstance);
        if (validated.isEmpty()
                && runtimeStore != null
                && workloadIdentityRegistry.getPluginToken(token).isEmpty()) {
            if (hydratePluginTokenFromRedis(token)) {
                return workloadIdentityRegistry.validatePluginToken(token, this::getInstance);
            }
            runtimeStore.removePluginToken(token);
        }
        return validated;
    }

    public Optional<String> validatePluginToken(String token, long sequence) {
        Optional<String> validated = workloadIdentityRegistry.validatePluginToken(token, sequence, this::getInstance);
        if (validated.isEmpty()
                && runtimeStore != null
                && workloadIdentityRegistry.getPluginToken(token).isEmpty()) {
            if (hydratePluginTokenFromRedis(token)) {
                return workloadIdentityRegistry.validatePluginToken(token, sequence, this::getInstance);
            }
            runtimeStore.removePluginToken(token);
        }
        return validated;
    }

    /**
     * Hydrate a plugin token from the shared Redis projection into the in-process registry.
     * A token is issued on whichever controller placed the instance, but the plugin connects
     * to the controller that owns its node's daemon stream — which may be a different
     * controller. Without this read-through, that controller's in-process map misses the token
     * and the old code would reject the call AND delete the still-valid token from Redis.
     *
     * @return true if a (non-expired) token was found in Redis and adopted locally
     */
    private boolean hydratePluginTokenFromRedis(String token) {
        if (runtimeStore == null) return false;
        var entry = runtimeStore.loadPluginToken(token).orElse(null);
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
            if (runtimeStore != null) runtimeStore.removePluginToken(currentToken);
            return Optional.empty();
        }
        if (runtimeStore != null) {
            runtimeStore.removePluginToken(currentToken);
            runtimeStore.savePluginToken(result.get().token(), result.get().entry());
        }
        return Optional.of(result.get().token());
    }

    public Optional<String> refreshPluginToken(String currentToken, long sequence) {
        var result = workloadIdentityRegistry.refreshPluginToken(currentToken, sequence, this::getInstance);
        if (result.isEmpty()) {
            if (runtimeStore != null) runtimeStore.removePluginToken(currentToken);
            return Optional.empty();
        }
        if (runtimeStore != null) {
            runtimeStore.removePluginToken(currentToken);
            runtimeStore.savePluginToken(result.get().token(), result.get().entry());
        }
        return Optional.of(result.get().token());
    }

    public void revokePluginToken(String token) {
        workloadIdentityRegistry.revokeToken(token);
        if (runtimeStore != null) runtimeStore.removePluginToken(token);
    }

    public boolean revokePluginTokenId(String tokenId) {
        boolean revoked = false;
        for (var entry : workloadIdentityRegistry.pluginTokens().entrySet()) {
            if (entry.getValue().tokenId().equals(tokenId)) {
                revoked = workloadIdentityRegistry.revokeTokenId(tokenId);
                if (revoked && runtimeStore != null) {
                    runtimeStore.removePluginToken(entry.getKey());
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
        if (runtimeStore != null) {
            workloadIdentityRegistry.pluginTokens().forEach((token, entry) -> {
                if (instanceId.equals(entry.instanceId())) runtimeStore.removePluginToken(token);
            });
        }
        workloadIdentityRegistry.unregisterPluginTokens(instanceId);
    }

    // --- Remote event application (multi-controller HA) ---
    // These update in-memory state only — no Redis write (source controller already
    // wrote), no EventBus publish (prevents infinite loops).

    public void applyRemoteNodeConnected(String nodeId, String sessionId, Instant timestamp) {
        nodeRegistry.add(nodeId, "", 0, Map.of(), timestamp, null);
    }

    public void applyRemoteNodeDisconnected(String nodeId) {
        nodeRegistry.remove(nodeId);
    }

    public void applyRemoteNodeStatusUpdated(String nodeId, double cpuUsage, long usedMemoryMb, long totalMemoryMb) {
        nodeRegistry
                .get(nodeId)
                .ifPresent(existing -> nodeRegistry.updateStatus(
                        nodeId,
                        cpuUsage,
                        totalMemoryMb,
                        usedMemoryMb,
                        existing.freeDiskMb(),
                        existing.totalDiskMb(),
                        existing.instanceCount(),
                        existing.usedPorts()));
    }

    public void applyRemoteInstanceStateChanged(
            String instanceId, me.prexorjustin.prexorcloud.api.domain.InstanceState newState) {
        instanceRegistry.updateState(instanceId, fromModuleState(newState));
    }

    public void applyRemotePlayerConnected(UUID uuid, String name, String instanceId, String group) {
        playerSessionRegistry.addReportedByBackend(uuid, name, instanceId, group);
    }

    public void applyRemotePlayerDisconnected(UUID uuid) {
        playerSessionRegistry.remove(uuid);
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
        if (runtimeStore != null) runtimeStore.savePlayer(uuid, updated.player());
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
        if (runtimeStore != null) runtimeStore.savePlayer(uuid, updated.player());
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
            if (runtimeStore != null) runtimeStore.removePlayer(uuid);
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
        logger.warn("Rejected invalid instance transition for {}: {} -> {}", instanceId, existing.state(), nextState);
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

    /** Convert module-api InstanceState to protobuf InstanceState. */
    public static InstanceState fromModuleState(me.prexorjustin.prexorcloud.api.domain.InstanceState moduleState) {
        return switch (moduleState) {
            case SCHEDULED -> InstanceState.SCHEDULED;
            case PREPARING -> InstanceState.PREPARING;
            case STARTING -> InstanceState.STARTING;
            case RUNNING -> InstanceState.RUNNING;
            case STOPPING -> InstanceState.STOPPING;
            case STOPPED -> InstanceState.STOPPED;
            case CRASHED -> InstanceState.CRASHED;
            case DRAINING -> InstanceState.DRAINING;
        };
    }
}
