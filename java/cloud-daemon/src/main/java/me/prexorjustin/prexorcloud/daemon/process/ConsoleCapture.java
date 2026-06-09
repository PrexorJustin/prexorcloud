package me.prexorjustin.prexorcloud.daemon.process;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe ring buffer for capturing console output lines. Streams each line
 * to a callback for real-time forwarding.
 */
public final class ConsoleCapture {

    private static final Logger logger = LoggerFactory.getLogger(ConsoleCapture.class);
    private static final int MAX_LINE_LENGTH = 8192;
    private static final long DROP_LOG_INTERVAL_MS = 5000;

    private final Deque<String> buffer;
    private final int capacity;
    private final BiConsumer<String, String> lineCallback; // (instanceId, line)
    private final String instanceId;
    private final ConsoleRateLimiter rateLimiter;
    private long lastDropLogMs;

    public ConsoleCapture(String instanceId, int capacity, BiConsumer<String, String> lineCallback) {
        this(instanceId, capacity, 0, lineCallback);
    }

    public ConsoleCapture(
            String instanceId, int capacity, int maxLinesPerSecond, BiConsumer<String, String> lineCallback) {
        this.instanceId = instanceId;
        this.capacity = capacity;
        this.lineCallback = lineCallback;
        this.buffer = new ArrayDeque<>(capacity);
        this.rateLimiter = maxLinesPerSecond > 0 ? new ConsoleRateLimiter(maxLinesPerSecond) : null;
    }

    /**
     * Add a line to the ring buffer and notify the callback.
     */
    public synchronized void addLine(String line) {
        if (line.length() > MAX_LINE_LENGTH) {
            line = line.substring(0, MAX_LINE_LENGTH) + "... (truncated)";
        }

        if (buffer.size() >= capacity) {
            buffer.pollFirst();
        }
        buffer.addLast(line);

        if (lineCallback != null && (rateLimiter == null || rateLimiter.tryAcquire())) {
            lineCallback.accept(instanceId, line);
        }

        if (rateLimiter != null) {
            long now = System.currentTimeMillis();
            if (now - lastDropLogMs > DROP_LOG_INTERVAL_MS) {
                long dropped = rateLimiter.getAndResetDroppedCount();
                if (dropped > 0) {
                    logger.info("Console rate limiter for {}: dropped {} lines in last interval", instanceId, dropped);
                }
                lastDropLogMs = now;
            }
        }
    }

    /**
     * Get a snapshot of the current buffer contents.
     */
    public synchronized List<String> getLines() {
        return List.copyOf(buffer);
    }

    /**
     * Get the last N lines.
     */
    public synchronized List<String> getTail(int n) {
        List<String> all = List.copyOf(buffer);
        if (all.size() <= n) return all;
        return all.subList(all.size() - n, all.size());
    }
}
