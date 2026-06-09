package me.prexorjustin.prexorcloud.controller.diagnostics;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import me.prexorjustin.prexorcloud.controller.grpc.CorrelationId;
import me.prexorjustin.prexorcloud.controller.grpc.PendingRequestRegistry;
import me.prexorjustin.prexorcloud.controller.scheduler.NodeMessageDispatcher;
import me.prexorjustin.prexorcloud.protocol.ControllerMessage;
import me.prexorjustin.prexorcloud.protocol.InstanceFileTree;
import me.prexorjustin.prexorcloud.protocol.WalkInstanceFiles;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Requests a structure-only filetree of an instance working directory from the
 * owning daemon, blocking with a 20 s ceiling. Never throws — unreachable
 * daemons, timeouts, or daemon-reported errors all surface as an
 * {@link InstanceFileTreeResult} carrying an {@code error} tag, so the
 * diagnostics snapshot can embed an "unavailable" marker per-instance and still
 * succeed overall.
 *
 * <p>
 * Known limitation (fast-follow): in multi-controller HA the reply lands on
 * whichever controller owns the daemon session; a cross-controller request
 * returns {@code DAEMON_UNREACHABLE} via the dispatch path.
 * </p>
 */
public final class InstanceFileTreeService {

    private static final Logger logger = LoggerFactory.getLogger(InstanceFileTreeService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** Caps stamped on every {@link WalkInstanceFiles} the controller emits. */
    public static final int MAX_ENTRIES = 5000;

    public static final int MAX_DEPTH = 24;
    public static final int SUMMARIZE_THRESHOLD = 500;

    private final NodeMessageDispatcher dispatcher;
    private final PendingRequestRegistry registry;
    private final String controllerId;

    public InstanceFileTreeService(NodeMessageDispatcher dispatcher, PendingRequestRegistry registry) {
        this(dispatcher, registry, "");
    }

    public InstanceFileTreeService(
            NodeMessageDispatcher dispatcher, PendingRequestRegistry registry, String controllerId) {
        this.dispatcher = dispatcher;
        this.registry = registry;
        this.controllerId = controllerId == null ? "" : controllerId;
    }

    public InstanceFileTreeResult walkInstanceFiles(String nodeId, String group, String instanceId) {
        return walkInstanceFiles(nodeId, group, instanceId, MAX_ENTRIES, MAX_DEPTH);
    }

    /**
     * Variant with explicit caps. Why: the diagnostics collector walks every instance and
     * embeds the result in a single bundle — tighter caps there prevent a multi-MB snapshot
     * on large clusters. The dashboard file-browser keeps the default looser caps.
     */
    public InstanceFileTreeResult walkInstanceFiles(
            String nodeId, String group, String instanceId, int maxEntries, int maxDepth) {
        if (nodeId == null || nodeId.isBlank()) return InstanceFileTreeResult.unavailable("NODE_UNKNOWN");
        String requestId = CorrelationId.mint(controllerId);
        CompletableFuture<InstanceFileTree> future = registry.register(requestId, nodeId, TIMEOUT);
        var message = ControllerMessage.newBuilder()
                .setWalkInstanceFiles(WalkInstanceFiles.newBuilder()
                        .setRequestId(requestId)
                        .setGroup(group == null ? "" : group)
                        .setInstanceId(instanceId == null ? "" : instanceId)
                        .setMaxEntries(maxEntries)
                        .setMaxDepth(maxDepth)
                        .setSummarizeThreshold(SUMMARIZE_THRESHOLD))
                .build();
        boolean delivered = dispatcher.dispatch(nodeId, message);
        if (!delivered) {
            registry.failAll(
                    e -> requestId.equals(e.requestId()),
                    new IllegalStateException("dispatch failed for node " + nodeId));
            return InstanceFileTreeResult.unavailable("DAEMON_UNREACHABLE");
        }
        try {
            InstanceFileTree reply = future.get(TIMEOUT.toMillis() + 1_000, TimeUnit.MILLISECONDS);
            if (!reply.getError().isEmpty()) {
                return InstanceFileTreeResult.unavailable(reply.getError());
            }
            var entries = new ArrayList<InstanceFileTreeResult.Entry>(reply.getEntriesCount());
            for (var fe : reply.getEntriesList()) {
                entries.add(new InstanceFileTreeResult.Entry(
                        fe.getPath(),
                        fe.getSizeBytes(),
                        fe.getIsDir(),
                        fe.getModifiedAtMs(),
                        fe.getSummary(),
                        fe.getChildCount()));
            }
            return new InstanceFileTreeResult(entries, reply.getTruncated(), "");
        } catch (TimeoutException timeout) {
            logger.warn("Instance filetree timed out for {}/{}", nodeId, instanceId);
            return InstanceFileTreeResult.unavailable("TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return InstanceFileTreeResult.unavailable("INTERRUPTED");
        } catch (Exception e) {
            logger.warn("Instance filetree failed for {}/{}: {}", nodeId, instanceId, e.getMessage());
            return InstanceFileTreeResult.unavailable("ERROR");
        }
    }
}
