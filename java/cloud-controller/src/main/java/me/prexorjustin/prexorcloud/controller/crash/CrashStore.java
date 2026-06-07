package me.prexorjustin.prexorcloud.controller.crash;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * In-memory ring buffer of crash records.
 */
public final class CrashStore {

    private final Deque<CrashRecord> buffer;
    private final int capacity;

    public CrashStore(int capacity) {
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(capacity);
    }

    /**
     * Add a crash record. Evicts the oldest record if at capacity.
     *
     * @return the stored crash record (with generated ID)
     */
    public synchronized CrashRecord add(
            String instanceId,
            String group,
            String nodeId,
            int exitCode,
            String classification,
            List<String> logTail,
            long uptimeMs) {
        String id = "crash-" + UUID.randomUUID();
        var cause = CrashCauseExtractor.extract(logTail, classification, exitCode);
        var record = new CrashRecord(
                id,
                instanceId,
                group,
                nodeId,
                exitCode,
                classification,
                cause.summary(),
                cause.signature(),
                logTail,
                uptimeMs,
                Instant.now());

        if (buffer.size() >= capacity) {
            buffer.pollFirst();
        }
        buffer.addLast(record);
        return record;
    }

    public synchronized List<CrashRecord> getAll() {
        return List.copyOf(buffer);
    }

    public synchronized List<CrashRecord> getByGroup(String group) {
        return buffer.stream().filter(r -> r.group().equals(group)).toList();
    }

    public synchronized List<CrashRecord> getByNode(String nodeId) {
        return buffer.stream().filter(r -> r.nodeId().equals(nodeId)).toList();
    }

    public synchronized int size() {
        return buffer.size();
    }
}
