package me.prexorjustin.prexorcloud.controller.observability;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import me.prexorjustin.prexorcloud.controller.observability.ControllerLogBuffer.LogFilter;
import me.prexorjustin.prexorcloud.controller.observability.ControllerLogBuffer.LogRecord;
import me.prexorjustin.prexorcloud.controller.observability.ControllerLogBuffer.Subscription;

/**
 * Per-node ring buffer of recent daemon log records mirrored from each
 * connected daemon over the existing daemon→controller gRPC stream. Backs
 * `prexorctl logs daemon <node-id>` and the dashboard daemon-log view.
 *
 * <p>
 * Each node id gets its own bounded {@link ControllerLogBuffer} so that one
 * chatty daemon cannot evict log records from another node's history. Buffers
 * are created lazily on first record and survive node disconnects so an
 * operator can still page through the last window after a daemon falls off
 * the cluster (the buffer is cleared by {@link #remove(String)} when the node
 * is permanently removed).
 * </p>
 */
public final class DaemonLogStore {

    private final int perNodeCapacity;
    private final ConcurrentHashMap<String, ControllerLogBuffer> buffers = new ConcurrentHashMap<>();

    public DaemonLogStore() {
        this(2000);
    }

    public DaemonLogStore(int perNodeCapacity) {
        if (perNodeCapacity <= 0) throw new IllegalArgumentException("perNodeCapacity must be positive");
        this.perNodeCapacity = perNodeCapacity;
    }

    public LogRecord append(
            String nodeId,
            long timestampMs,
            String level,
            String logger,
            String thread,
            String message,
            String throwable,
            Map<String, String> mdc) {
        return bufferFor(nodeId).append(timestampMs, level, logger, thread, message, throwable, mdc);
    }

    public List<LogRecord> recent(String nodeId, LogFilter filter, int limit) {
        ControllerLogBuffer buffer = buffers.get(nodeId);
        if (buffer == null) return List.of();
        return buffer.recent(filter, limit);
    }

    public Subscription subscribe(String nodeId, Consumer<LogRecord> listener) {
        return bufferFor(nodeId).subscribe(listener);
    }

    public int size(String nodeId) {
        ControllerLogBuffer buffer = buffers.get(nodeId);
        return buffer == null ? 0 : buffer.size();
    }

    public int capacity() {
        return perNodeCapacity;
    }

    public Set<String> nodes() {
        return Set.copyOf(buffers.keySet());
    }

    /** Drops the buffer for {@code nodeId}. Safe to call when no buffer exists. */
    public void remove(String nodeId) {
        buffers.remove(nodeId);
    }

    private ControllerLogBuffer bufferFor(String nodeId) {
        return buffers.computeIfAbsent(nodeId, id -> new ControllerLogBuffer(perNodeCapacity));
    }
}
