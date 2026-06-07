package me.prexorjustin.prexorcloud.controller.observability;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Bounded ring buffer of recent controller log records. Backs the operator
 * `/api/v1/system/logs` REST + SSE surface used by `prexorctl logs controller`.
 *
 * <p>
 * Records are pushed by {@link RingBufferLogAppender}. New records fan out
 * synchronously to registered listeners (SSE clients) and are stored for late
 * REST/SSE-replay readers. Listener delivery is best-effort: a listener that
 * throws is removed.
 * </p>
 */
public final class ControllerLogBuffer {

    /**
     * Snapshot of a single log record. Field set is intentionally narrow: enough
     * to render in `prexorctl logs` and the operator dashboard, no PII beyond
     * what already lives in the message itself.
     */
    public record LogRecord(
            long sequence,
            long timestampMs,
            String level,
            String logger,
            String thread,
            String message,
            String throwable,
            Map<String, String> mdc) {}

    private final int capacity;
    private final Deque<LogRecord> records;
    private final List<Consumer<LogRecord>> listeners = new CopyOnWriteArrayList<>();
    private long nextSequence = 1L;

    public ControllerLogBuffer() {
        this(2000);
    }

    public ControllerLogBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.records = new ArrayDeque<>(capacity);
    }

    public synchronized LogRecord append(
            long timestampMs,
            String level,
            String logger,
            String thread,
            String message,
            String throwable,
            Map<String, String> mdc) {
        LogRecord record = new LogRecord(
                nextSequence++, timestampMs, level, logger, thread, message, throwable, mdc == null ? Map.of() : mdc);
        if (records.size() >= capacity) {
            records.pollFirst();
        }
        records.addLast(record);
        for (Consumer<LogRecord> listener : listeners) {
            try {
                listener.accept(record);
            } catch (Exception _) {
                // Drop the failing listener. We never want appender hot path to throw.
                listeners.remove(listener);
            }
        }
        return record;
    }

    /**
     * Returns up to {@code limit} most-recent records that match the filter,
     * oldest first.
     */
    public synchronized List<LogRecord> recent(LogFilter filter, int limit) {
        if (limit <= 0) return List.of();
        List<LogRecord> matched = new ArrayList<>(Math.min(limit, records.size()));
        // Iterate newest → oldest, collect up to limit, then reverse.
        var it = records.descendingIterator();
        while (it.hasNext() && matched.size() < limit) {
            LogRecord record = it.next();
            if (filter == null || filter.matches(record)) {
                matched.add(record);
            }
        }
        java.util.Collections.reverse(matched);
        return matched;
    }

    public Subscription subscribe(Consumer<LogRecord> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public synchronized int size() {
        return records.size();
    }

    public int capacity() {
        return capacity;
    }

    /**
     * Filter applied at read time. {@code minLevel} compares ordinals against
     * Logback levels (TRACE=0, DEBUG=10, INFO=20, WARN=30, ERROR=40); a record
     * matches when its level is at least the configured minimum and, when
     * {@code loggerPrefix} is non-empty, its logger name starts with the prefix.
     */
    public record LogFilter(int minLevelInt, String loggerPrefix) {

        public LogFilter {
            if (loggerPrefix == null) loggerPrefix = "";
        }

        public static LogFilter accept() {
            return new LogFilter(0, "");
        }

        public static LogFilter atLeast(String level, String loggerPrefix) {
            return new LogFilter(levelInt(level), loggerPrefix == null ? "" : loggerPrefix);
        }

        public boolean matches(LogRecord record) {
            if (levelInt(record.level()) < minLevelInt) return false;
            if (!loggerPrefix.isEmpty()
                    && (record.logger() == null || !record.logger().startsWith(loggerPrefix))) {
                return false;
            }
            return true;
        }

        public static int levelInt(String level) {
            if (level == null) return 0;
            return switch (level.toUpperCase(java.util.Locale.ROOT)) {
                case "TRACE" -> 0;
                case "DEBUG" -> 10;
                case "INFO" -> 20;
                case "WARN" -> 30;
                case "ERROR" -> 40;
                default -> 0;
            };
        }
    }

    /** Returned from {@link #subscribe} so callers can unregister cleanly. */
    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
