package me.prexorjustin.prexorcloud.controller.console;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-instance console line ring buffer for the controller. Buffers recent
 * lines so new SSE clients receive history on connect.
 */
public final class ConsoleBuffer {

    private static final int DEFAULT_CAPACITY = 500;
    private static final int DEFAULT_MAX_LINE_CHARS = 4096;
    private static final int DEFAULT_MAX_LINES_PER_WINDOW = 200;
    private static final long DEFAULT_WINDOW_MS = 1000L;
    private static final String TRUNCATED_SUFFIX = " ... [truncated]";

    private final int capacity;
    private final int maxLineChars;
    private final int maxLinesPerWindow;
    private final long windowMs;
    private final Map<String, Deque<String>> buffers = new ConcurrentHashMap<>();
    private final FloodWindowStore floodWindowStore;

    public ConsoleBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public ConsoleBuffer(int capacity) {
        this(capacity, DEFAULT_MAX_LINE_CHARS, DEFAULT_MAX_LINES_PER_WINDOW, DEFAULT_WINDOW_MS);
    }

    public ConsoleBuffer(FloodWindowStore floodWindowStore) {
        this(
                DEFAULT_CAPACITY,
                DEFAULT_MAX_LINE_CHARS,
                DEFAULT_MAX_LINES_PER_WINDOW,
                DEFAULT_WINDOW_MS,
                floodWindowStore);
    }

    ConsoleBuffer(int capacity, int maxLineChars, int maxLinesPerWindow, long windowMs) {
        this(capacity, maxLineChars, maxLinesPerWindow, windowMs, new InMemoryFloodWindowStore());
    }

    ConsoleBuffer(
            int capacity, int maxLineChars, int maxLinesPerWindow, long windowMs, FloodWindowStore floodWindowStore) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (maxLineChars < TRUNCATED_SUFFIX.length() + 1) {
            throw new IllegalArgumentException("maxLineChars must leave room for truncation suffix");
        }
        if (maxLinesPerWindow <= 0) {
            throw new IllegalArgumentException("maxLinesPerWindow must be positive");
        }
        if (windowMs <= 0) {
            throw new IllegalArgumentException("windowMs must be positive");
        }
        this.capacity = capacity;
        this.maxLineChars = maxLineChars;
        this.maxLinesPerWindow = maxLinesPerWindow;
        this.windowMs = windowMs;
        this.floodWindowStore = floodWindowStore == null ? new InMemoryFloodWindowStore() : floodWindowStore;
    }

    public synchronized AppendResult append(String instanceId, String line) {
        return append(instanceId, line, System.currentTimeMillis());
    }

    public synchronized AppendResult append(String instanceId, String line, long timestampMs) {
        long effectiveTimestamp = timestampMs > 0 ? timestampMs : System.currentTimeMillis();
        FloodDecision decision =
                floodWindowStore.recordLine(instanceId, effectiveTimestamp, maxLinesPerWindow, windowMs);
        List<String> acceptedLines = new ArrayList<>(2);
        if (!decision.accepted()) {
            return new AppendResult(List.of(), true, decision.suppressedCount());
        }
        if (decision.previousSuppressedCount() > 0) {
            acceptedLines.add(suppressionSummary(decision.previousSuppressedCount()));
        }

        String sanitized = truncate(line == null ? "" : line);
        acceptedLines.add(sanitized);
        for (String acceptedLine : acceptedLines) {
            appendAccepted(instanceId, acceptedLine);
        }
        return new AppendResult(List.copyOf(acceptedLines), sanitized.length() < (line == null ? 0 : line.length()), 0);
    }

    private void appendAccepted(String instanceId, String line) {
        Deque<String> deque = buffers.computeIfAbsent(instanceId, k -> new ArrayDeque<>(capacity));
        if (deque.size() >= capacity) {
            deque.pollFirst();
        }
        deque.addLast(line);
    }

    public synchronized List<String> getLines(String instanceId) {
        Deque<String> deque = buffers.get(instanceId);
        if (deque == null) return List.of();
        return List.copyOf(deque);
    }

    public synchronized void evict(String instanceId) {
        buffers.remove(instanceId);
        floodWindowStore.clear(instanceId);
    }

    private String truncate(String line) {
        if (line.length() <= maxLineChars) return line;
        return line.substring(0, maxLineChars - TRUNCATED_SUFFIX.length()) + TRUNCATED_SUFFIX;
    }

    private static String suppressionSummary(int suppressed) {
        return "[prexorcloud] suppressed " + suppressed + " console line" + (suppressed == 1 ? "" : "s")
                + " due to rate limiting";
    }

    public record AppendResult(List<String> acceptedLines, boolean rateLimitedOrTruncated, int suppressedCount) {}

    public interface FloodWindowStore {
        FloodDecision recordLine(String instanceId, long timestampMs, int maxLinesPerWindow, long windowMs);

        void clear(String instanceId);
    }

    public static FloodWindowStore newInMemoryFloodWindowStore() {
        return new InMemoryFloodWindowStore();
    }

    public record FloodDecision(boolean accepted, int previousSuppressedCount, int suppressedCount) {}

    private static final class InMemoryFloodWindowStore implements FloodWindowStore {
        private final Map<String, RateWindow> rateWindows = new ConcurrentHashMap<>();

        @Override
        public FloodDecision recordLine(String instanceId, long timestampMs, int maxLinesPerWindow, long windowMs) {
            RateWindow window = rateWindows.computeIfAbsent(instanceId, _ -> new RateWindow(timestampMs));
            int previousSuppressed = 0;
            if (timestampMs - window.startedAtMs >= windowMs) {
                previousSuppressed = window.suppressed;
                window.reset(timestampMs);
            }
            if (window.emitted >= maxLinesPerWindow) {
                window.suppressed++;
                return new FloodDecision(false, previousSuppressed, window.suppressed);
            }
            window.emitted++;
            return new FloodDecision(true, previousSuppressed, 0);
        }

        @Override
        public void clear(String instanceId) {
            rateWindows.remove(instanceId);
        }

        private static final class RateWindow {
            private long startedAtMs;
            private int emitted;
            private int suppressed;

            private RateWindow(long startedAtMs) {
                this.startedAtMs = startedAtMs;
            }

            private void reset(long startedAtMs) {
                this.startedAtMs = startedAtMs;
                this.emitted = 0;
                this.suppressed = 0;
            }
        }
    }
}
