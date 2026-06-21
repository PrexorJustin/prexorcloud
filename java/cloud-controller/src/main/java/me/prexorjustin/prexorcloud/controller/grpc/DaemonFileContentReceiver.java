package me.prexorjustin.prexorcloud.controller.grpc;

import me.prexorjustin.prexorcloud.protocol.InstanceFileContent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes daemon-replied {@link InstanceFileContent} messages back to their pending
 * controller-side futures. Sibling to {@link DaemonFileTreeReceiver}. Under the
 * single-writer control plane the leader owns every daemon stream and originates
 * every request, so a reply always completes a local pending future.
 */
final class DaemonFileContentReceiver {

    private static final Logger logger = LoggerFactory.getLogger(DaemonFileContentReceiver.class);

    private final PendingRequestRegistry registry;

    DaemonFileContentReceiver(PendingRequestRegistry registry) {
        this.registry = registry;
    }

    void handleInstanceFileContent(String nodeId, InstanceFileContent reply) {
        if (reply.getRequestId().isEmpty()) {
            logger.warn("Dropping InstanceFileContent from node {} with empty request_id", nodeId);
            return;
        }
        registry.complete(reply.getRequestId(), reply);
    }
}
