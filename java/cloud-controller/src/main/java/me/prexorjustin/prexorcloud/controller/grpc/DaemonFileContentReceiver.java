package me.prexorjustin.prexorcloud.controller.grpc;

import me.prexorjustin.prexorcloud.controller.redis.RedisEventBridge;
import me.prexorjustin.prexorcloud.protocol.DaemonMessage;
import me.prexorjustin.prexorcloud.protocol.InstanceFileContent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes daemon-replied {@link InstanceFileContent} messages back to their
 * pending controller-side futures. Sibling to {@link DaemonFileTreeReceiver};
 * applies the same originator-aware forwarding for multi-controller HA.
 */
final class DaemonFileContentReceiver {

    private static final Logger logger = LoggerFactory.getLogger(DaemonFileContentReceiver.class);

    private final PendingRequestRegistry registry;
    private final String controllerId;
    private final RedisEventBridge eventBridge;

    DaemonFileContentReceiver(PendingRequestRegistry registry) {
        this(registry, "", null);
    }

    DaemonFileContentReceiver(PendingRequestRegistry registry, String controllerId, RedisEventBridge eventBridge) {
        this.registry = registry;
        this.controllerId = controllerId == null ? "" : controllerId;
        this.eventBridge = eventBridge;
    }

    void handleInstanceFileContent(String nodeId, InstanceFileContent reply) {
        if (reply.getRequestId().isEmpty()) {
            logger.warn("Dropping InstanceFileContent from node {} with empty request_id", nodeId);
            return;
        }
        String originator = CorrelationId.originator(reply.getRequestId());
        if (!originator.isEmpty() && !originator.equals(controllerId)) {
            if (eventBridge != null) {
                logger.debug(
                        "Forwarding InstanceFileContent reply {} -> controller {}", reply.getRequestId(), originator);
                eventBridge.routeReply(
                        originator,
                        DaemonMessage.newBuilder()
                                .setInstanceFileContent(reply)
                                .build()
                                .toByteArray());
            } else {
                logger.warn(
                        "InstanceFileContent {} originated by {} but we are {} and no event bridge is wired",
                        reply.getRequestId(),
                        originator,
                        controllerId);
            }
            return;
        }
        registry.complete(reply.getRequestId(), reply);
    }
}
