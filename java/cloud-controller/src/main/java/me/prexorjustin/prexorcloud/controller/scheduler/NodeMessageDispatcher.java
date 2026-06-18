package me.prexorjustin.prexorcloud.controller.scheduler;

import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.metrics.MetricsCollector;
import me.prexorjustin.prexorcloud.controller.redis.RedisEventBridge;
import me.prexorjustin.prexorcloud.controller.redis.RedisKeys;
import me.prexorjustin.prexorcloud.controller.session.NodeSession;
import me.prexorjustin.prexorcloud.controller.session.NodeSessionManager;
import me.prexorjustin.prexorcloud.protocol.ControllerMessage;

import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches controller messages to a node's local session or routes them to a remote controller.
 */
public final class NodeMessageDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(NodeMessageDispatcher.class);

    private final NodeSessionManager sessionManager;
    private final RedisEventBridge eventBridge;
    private final RedisCommands<String, String> redisCommands;
    private volatile MetricsCollector metricsCollector;

    public NodeMessageDispatcher(
            NodeSessionManager sessionManager,
            RedisEventBridge eventBridge,
            RedisCommands<String, String> redisCommands) {
        this.sessionManager = sessionManager;
        this.eventBridge = eventBridge;
        this.redisCommands = redisCommands;
    }

    public void attachMetricsCollector(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    /**
     * Whether this controller owns {@code nodeId}'s daemon gRPC stream. True on exactly one
     * controller per connected node (single-writer), so it is the authority for placing,
     * dispatching, and tracking instances on that node — see the placer==node-owner invariant.
     */
    public boolean ownsNode(String nodeId) {
        return sessionManager.getByNodeId(nodeId).isPresent();
    }

    public boolean dispatch(String nodeId, ControllerMessage message) {
        Optional<NodeSession> session = sessionManager.getByNodeId(nodeId);
        boolean delivered;
        if (session.isPresent()) {
            session.get().send(message);
            delivered = true;
        } else {
            delivered = routeRemote(nodeId, message);
        }
        MetricsCollector mc = metricsCollector;
        if (mc != null) {
            mc.recordDaemonOutbound(message.getPayloadCase().name(), delivered);
        }
        return delivered;
    }

    private boolean routeRemote(String nodeId, ControllerMessage message) {
        if (redisCommands == null || eventBridge == null) {
            return false;
        }
        try {
            String owner = redisCommands.get(RedisKeys.nodeOwner(nodeId));
            if (owner == null) {
                return false;
            }
            eventBridge.routeCommand(owner, nodeId, message.toByteArray());
            logger.debug("Routed command to remote controller {} for node {}", owner, nodeId);
            return true;
        } catch (Exception e) {
            logger.warn("Failed to route remote command to node {}: {}", nodeId, e.getMessage());
            return false;
        }
    }
}
