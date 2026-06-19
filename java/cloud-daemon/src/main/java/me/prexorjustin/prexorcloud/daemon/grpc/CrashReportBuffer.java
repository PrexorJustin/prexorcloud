package me.prexorjustin.prexorcloud.daemon.grpc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import me.prexorjustin.prexorcloud.protocol.CrashReport;

/**
 * Bounded FIFO buffer for crash reports that could not be delivered (the daemon was disconnected or
 * mid leader-redirect, Phase 3). The daemon replays the buffer at-least-once on reconnect so a crash
 * during a failover window is still recorded — preserving the #12 guarantee (crashes persisted +
 * queryable), which is exactly when a crash is most likely and most wanted.
 *
 * <p>When full, the oldest report is dropped: crash reports are infrequent, so a generous cap rarely
 * sheds, but an unbounded buffer could grow without limit if the controller never returns. Replay is
 * at-least-once (a re-sent crash may duplicate a record the controller already stored) — far better
 * than losing one. Thread-safe: {@code add} runs on crash-detection threads, {@code drainAll} on the
 * reconnect/handshake thread.
 */
final class CrashReportBuffer {

    private final int maxSize;
    private final ArrayDeque<CrashReport> queue = new ArrayDeque<>();
    private long dropped = 0;

    CrashReportBuffer(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
    }

    synchronized void add(CrashReport report) {
        if (report == null) {
            return;
        }
        while (queue.size() >= maxSize) {
            queue.pollFirst();
            dropped++;
        }
        queue.addLast(report);
    }

    /** Remove and return all buffered reports in FIFO order (empties the buffer). */
    synchronized List<CrashReport> drainAll() {
        if (queue.isEmpty()) {
            return List.of();
        }
        var out = new ArrayList<>(queue);
        queue.clear();
        return out;
    }

    synchronized int size() {
        return queue.size();
    }

    /** Total reports dropped because the buffer was full (observability). */
    synchronized long droppedCount() {
        return dropped;
    }
}
