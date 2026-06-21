package me.prexorjustin.prexorcloud.controller.grpc;

import me.prexorjustin.prexorcloud.protocol.InstanceFileTree;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes daemon-replied {@link InstanceFileTree} messages back to their pending
 * controller-side futures. Lives on the inbound bidi stream and is invoked from
 * {@link DaemonServiceImpl#connect}.
 *
 * <p>Under the single-writer control plane the leader owns every daemon stream and
 * originates every request, so a reply always completes a local pending future — no
 * cross-controller relay.
 */
final class DaemonFileTreeReceiver {

    private static final Logger logger = LoggerFactory.getLogger(DaemonFileTreeReceiver.class);

    private final PendingRequestRegistry registry;

    DaemonFileTreeReceiver(PendingRequestRegistry registry) {
        this.registry = registry;
    }

    void handleInstanceFileTree(String nodeId, InstanceFileTree reply) {
        if (reply.getRequestId().isEmpty()) {
            logger.warn("Dropping InstanceFileTree from node {} with empty request_id", nodeId);
            return;
        }
        registry.complete(reply.getRequestId(), reply);
    }
}
