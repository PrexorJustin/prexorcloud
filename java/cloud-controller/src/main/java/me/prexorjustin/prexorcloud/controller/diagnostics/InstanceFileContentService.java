package me.prexorjustin.prexorcloud.controller.diagnostics;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import me.prexorjustin.prexorcloud.controller.grpc.CorrelationId;
import me.prexorjustin.prexorcloud.controller.grpc.PendingRequestRegistry;
import me.prexorjustin.prexorcloud.controller.scheduler.NodeMessageDispatcher;
import me.prexorjustin.prexorcloud.protocol.ControllerMessage;
import me.prexorjustin.prexorcloud.protocol.InstanceFileContent;
import me.prexorjustin.prexorcloud.protocol.ReadInstanceFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Requests a bounded read of a single file under an instance working directory
 * from the owning daemon. Mirrors {@link InstanceFileTreeService}: blocking
 * with a 20 s ceiling, never throws — unreachable / timeout / daemon-reported
 * errors surface as {@link InstanceFileContentResult#unavailable(String)}.
 */
public final class InstanceFileContentService {

    private static final Logger logger = LoggerFactory.getLogger(InstanceFileContentService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** Default cap on the body returned to the controller — kept modest because pste bodies are bounded too. */
    public static final int DEFAULT_MAX_BYTES = 64 * 1024;

    private final NodeMessageDispatcher dispatcher;
    private final PendingRequestRegistry registry;
    private final String controllerId;

    public InstanceFileContentService(NodeMessageDispatcher dispatcher, PendingRequestRegistry registry) {
        this(dispatcher, registry, "");
    }

    public InstanceFileContentService(
            NodeMessageDispatcher dispatcher, PendingRequestRegistry registry, String controllerId) {
        this.dispatcher = dispatcher;
        this.registry = registry;
        this.controllerId = controllerId == null ? "" : controllerId;
    }

    public InstanceFileContentResult read(
            String nodeId, String group, String instanceId, String relPath, int maxBytes, boolean tail) {
        if (nodeId == null || nodeId.isBlank()) return InstanceFileContentResult.unavailable("NODE_UNKNOWN");
        if (relPath == null || relPath.isBlank()) return InstanceFileContentResult.unavailable("INVALID_REQUEST");

        String requestId = CorrelationId.mint(controllerId);
        CompletableFuture<InstanceFileContent> future = registry.register(requestId, nodeId, TIMEOUT);
        var message = ControllerMessage.newBuilder()
                .setReadInstanceFile(ReadInstanceFile.newBuilder()
                        .setRequestId(requestId)
                        .setGroup(group == null ? "" : group)
                        .setInstanceId(instanceId == null ? "" : instanceId)
                        .setPath(relPath)
                        .setMaxBytes(maxBytes > 0 ? maxBytes : DEFAULT_MAX_BYTES)
                        .setTail(tail))
                .build();
        boolean delivered = dispatcher.dispatch(nodeId, message);
        if (!delivered) {
            registry.failAll(
                    e -> requestId.equals(e.requestId()),
                    new IllegalStateException("dispatch failed for node " + nodeId));
            return InstanceFileContentResult.unavailable("DAEMON_UNREACHABLE");
        }
        try {
            InstanceFileContent reply = future.get(TIMEOUT.toMillis() + 1_000, TimeUnit.MILLISECONDS);
            if (!reply.getError().isEmpty()) {
                return InstanceFileContentResult.unavailable(reply.getError());
            }
            String body = reply.getContent().toString(StandardCharsets.UTF_8);
            return new InstanceFileContentResult(body, reply.getTotalSizeBytes(), reply.getTruncated(), "");
        } catch (TimeoutException timeout) {
            logger.warn("Instance file read timed out for {}/{}:{}", nodeId, instanceId, relPath);
            return InstanceFileContentResult.unavailable("TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return InstanceFileContentResult.unavailable("INTERRUPTED");
        } catch (Exception e) {
            logger.warn("Instance file read failed for {}/{}:{}: {}", nodeId, instanceId, relPath, e.getMessage());
            return InstanceFileContentResult.unavailable("ERROR");
        }
    }
}
