package me.prexorjustin.prexorcloud.controller.session;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicLong;

import me.prexorjustin.prexorcloud.api.event.events.NodeHeartbeatResumedEvent;
import me.prexorjustin.prexorcloud.api.event.events.NodeHeartbeatStaleEvent;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.NodeState;
import me.prexorjustin.prexorcloud.protocol.ControllerMessage;
import me.prexorjustin.prexorcloud.protocol.Ping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks ping/pong heartbeats per session. Detects unreachable nodes when pongs
 * are missed.
 *
 * <p>
 * Uses Structured Concurrency (JEP 505) to send pings to all nodes in parallel,
 * so a slow/blocking send to one node doesn't delay others.
 * </p>
 */
public final class HeartbeatTracker {

    private static final Logger logger = LoggerFactory.getLogger(HeartbeatTracker.class);

    private final NodeSessionManager sessionManager;
    private final ClusterState clusterState;
    private final EventBus eventBus;
    private final int missedThreshold;
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private final Map<String, Integer> missedPongs = new ConcurrentHashMap<>();
    /** Sessions currently in the stale window (between STALE and RESUMED). */
    private final Set<String> staleSessions = ConcurrentHashMap.newKeySet();

    public HeartbeatTracker(
            NodeSessionManager sessionManager, ClusterState clusterState, EventBus eventBus, int missedThreshold) {
        this.sessionManager = sessionManager;
        this.clusterState = clusterState;
        this.eventBus = eventBus;
        this.missedThreshold = missedThreshold;
    }

    /**
     * Send ping to all connected daemons in parallel. Increment missed count.
     */
    public void pingAll() {
        long seq = sequenceCounter.incrementAndGet();
        var ping = ControllerMessage.newBuilder()
                .setPing(Ping.newBuilder().setSequence(seq))
                .build();

        var sessions = sessionManager.allSessions();
        if (sessions.isEmpty()) return;

        try (var scope = StructuredTaskScope.open()) {
            for (var session : sessions) {
                scope.fork(() -> {
                    pingSession(session, ping);
                    return null;
                });
            }
            scope.join();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private void pingSession(NodeSession session, ControllerMessage ping) {
        try {
            session.send(ping);
        } catch (Exception e) {
            logger.warn("Failed to ping node {}: {}", session.nodeId(), e.getMessage());
        }
        int missed = missedPongs.merge(session.sessionId(), 1, Integer::sum);
        if (missed >= missedThreshold) {
            markStale(session.sessionId(), session.nodeId(), missed);
        }
    }

    private void markStale(String sessionId, String nodeId, int missed) {
        if (!staleSessions.add(sessionId)) return;
        logger.warn("Node {} missed {} heartbeats, marking UNREACHABLE", nodeId, missed);
        clusterState.setNodeStatus(nodeId, NodeState.NodeStatus.UNREACHABLE);
        Instant lastHeartbeat =
                clusterState.getNode(nodeId).map(NodeState::lastHeartbeat).orElse(null);
        eventBus.publish(new NodeHeartbeatStaleEvent(nodeId, missed, lastHeartbeat));
    }

    /**
     * Record a pong from a session, resetting its missed count.
     */
    public void recordPong(String sessionId) {
        missedPongs.put(sessionId, 0);
        if (staleSessions.remove(sessionId)) {
            sessionManager.getBySessionId(sessionId).ifPresent(session -> {
                String nodeId = session.nodeId();
                logger.info("Node {} heartbeat resumed", nodeId);
                clusterState
                        .getNode(nodeId)
                        .filter(node -> node.status() == NodeState.NodeStatus.UNREACHABLE)
                        .ifPresent(node -> clusterState.setNodeStatus(nodeId, NodeState.NodeStatus.ONLINE));
                Instant lastHeartbeat = clusterState
                        .getNode(nodeId)
                        .map(NodeState::lastHeartbeat)
                        .orElse(null);
                eventBus.publish(new NodeHeartbeatResumedEvent(nodeId, lastHeartbeat));
            });
        }
    }

    /**
     * Remove tracking for a session that disconnected.
     */
    public void removeSession(String sessionId) {
        missedPongs.remove(sessionId);
        staleSessions.remove(sessionId);
    }
}
