package me.prexorjustin.prexorcloud.controller.grpc;

import me.prexorjustin.prexorcloud.controller.redis.RedisEventBridge;
import me.prexorjustin.prexorcloud.protocol.DaemonMessage;
import me.prexorjustin.prexorcloud.protocol.InstanceFileTree;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes daemon-replied {@link InstanceFileTree} messages back to their
 * pending controller-side futures. Lives on the inbound bidi stream and is
 * invoked from {@link DaemonServiceImpl#connect}.
 *
 * <p>
 * Multi-controller HA: the originator id is embedded in {@code request_id}
 * via {@link CorrelationId#mint(String)}. When the originator differs from
 * the local controller, the reply is republished on the Redis
 * {@code CHANNEL_REPLY} so the originating controller can complete its
 * own pending future.
 * </p>
 */
final class DaemonFileTreeReceiver {

    private static final Logger logger = LoggerFactory.getLogger(DaemonFileTreeReceiver.class);

    private final PendingRequestRegistry registry;
    private final String controllerId;
    private final RedisEventBridge eventBridge;

    DaemonFileTreeReceiver(PendingRequestRegistry registry) {
        this(registry, "", null);
    }

    DaemonFileTreeReceiver(PendingRequestRegistry registry, String controllerId, RedisEventBridge eventBridge) {
        this.registry = registry;
        this.controllerId = controllerId == null ? "" : controllerId;
        this.eventBridge = eventBridge;
    }

    void handleInstanceFileTree(String nodeId, InstanceFileTree reply) {
        if (reply.getRequestId().isEmpty()) {
            logger.warn("Dropping InstanceFileTree from node {} with empty request_id", nodeId);
            return;
        }
        String originator = CorrelationId.originator(reply.getRequestId());
        if (!originator.isEmpty() && !originator.equals(controllerId)) {
            if (eventBridge != null) {
                logger.debug("Forwarding InstanceFileTree reply {} -> controller {}", reply.getRequestId(), originator);
                eventBridge.routeReply(
                        originator,
                        DaemonMessage.newBuilder()
                                .setInstanceFileTree(reply)
                                .build()
                                .toByteArray());
            } else {
                logger.warn(
                        "InstanceFileTree {} originated by {} but we are {} and no event bridge is wired",
                        reply.getRequestId(),
                        originator,
                        controllerId);
            }
            return;
        }
        registry.complete(reply.getRequestId(), reply);
    }
}
