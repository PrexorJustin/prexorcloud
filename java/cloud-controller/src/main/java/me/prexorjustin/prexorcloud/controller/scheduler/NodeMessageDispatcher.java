package me.prexorjustin.prexorcloud.controller.scheduler;

import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.cluster.Leadership;
import me.prexorjustin.prexorcloud.controller.metrics.MetricsCollector;
import me.prexorjustin.prexorcloud.controller.session.NodeSession;
import me.prexorjustin.prexorcloud.controller.session.NodeSessionManager;
import me.prexorjustin.prexorcloud.protocol.ControllerMessage;

/**
 * Dispatches controller messages to a node's local daemon session.
 *
 * <p>Single-writer: the leader owns every daemon stream (the daemon handshake redirects
 * followers to the leader), so the target session is always local on the leader. There is
 * no cross-controller relay — a dispatch with no local session simply fails.
 */
public final class NodeMessageDispatcher {

    private final NodeSessionManager sessionManager;
    private volatile MetricsCollector metricsCollector;
    // Single-writer fencing: every outbound command is stamped with the leader's epoch so the daemon
    // can reject a deposed leader's stale commands. Defaults to always-leader (epoch 1) so
    // single-controller installs + tests are unaffected.
    private volatile Leadership leadership = Leadership.alwaysLeader();

    public NodeMessageDispatcher(NodeSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public void attachMetricsCollector(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    /** Inject single-writer leadership (bootstrap) so outbound commands carry the leader's epoch. */
    public void setLeadership(Leadership leadership) {
        this.leadership = leadership;
    }

    public boolean dispatch(String nodeId, ControllerMessage message) {
        // Stamp the issuing leader's fencing epoch so the daemon can reject a deposed leader's stale
        // commands. Only overwrite an unset epoch — a caller that pre-stamps keeps theirs.
        if (message.getEpoch() == 0L) {
            message = message.toBuilder().setEpoch(leadership.currentEpoch()).build();
        }
        Optional<NodeSession> session = sessionManager.getByNodeId(nodeId);
        boolean delivered = session.isPresent();
        if (delivered) {
            session.get().send(message);
        }
        MetricsCollector mc = metricsCollector;
        if (mc != null) {
            mc.recordDaemonOutbound(message.getPayloadCase().name(), delivered);
        }
        return delivered;
    }
}
