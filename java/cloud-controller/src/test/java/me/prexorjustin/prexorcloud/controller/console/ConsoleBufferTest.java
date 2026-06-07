package me.prexorjustin.prexorcloud.controller.console;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

class ConsoleBufferTest {

    @Test
    void truncatesLongLinesBeforeBuffering() {
        var buffer = new ConsoleBuffer(10, 32, 10, 1000);

        var result = buffer.append("lobby-1", "x".repeat(80), 100);

        assertEquals(1, result.acceptedLines().size());
        assertEquals(32, result.acceptedLines().getFirst().length());
        assertTrue(result.acceptedLines().getFirst().endsWith(" ... [truncated]"));
        assertEquals(result.acceptedLines(), buffer.getLines("lobby-1"));
    }

    @Test
    void rateLimitsWithinWindowAndEmitsSummaryOnNextWindow() {
        var buffer = new ConsoleBuffer(10, 128, 2, 1000);

        assertEquals(
                "line-1",
                buffer.append("lobby-1", "line-1", 100).acceptedLines().getFirst());
        assertEquals(
                "line-2",
                buffer.append("lobby-1", "line-2", 200).acceptedLines().getFirst());
        var dropped = buffer.append("lobby-1", "line-3", 300);
        assertTrue(dropped.acceptedLines().isEmpty());
        assertTrue(dropped.rateLimitedOrTruncated());
        assertEquals(1, dropped.suppressedCount());

        var resumed = buffer.append("lobby-1", "line-4", 1200);

        assertEquals(2, resumed.acceptedLines().size());
        assertEquals(
                "[prexorcloud] suppressed 1 console line due to rate limiting",
                resumed.acceptedLines().getFirst());
        assertEquals("line-4", resumed.acceptedLines().get(1));
        assertEquals(
                "[prexorcloud] suppressed 1 console line due to rate limiting",
                buffer.getLines("lobby-1").get(2));
    }

    @Test
    void evictClearsBufferedLinesAndRateWindow() {
        var buffer = new ConsoleBuffer(10, 128, 1, 1000);

        buffer.append("lobby-1", "line-1", 100);
        assertTrue(buffer.append("lobby-1", "line-2", 200).acceptedLines().isEmpty());
        buffer.evict("lobby-1");

        assertTrue(buffer.getLines("lobby-1").isEmpty());
        assertEquals(
                "line-3",
                buffer.append("lobby-1", "line-3", 300).acceptedLines().getFirst());
    }

    @Test
    void redisFloodWindowSuppressesAcrossControllerBuffers() {
        var redisStore = redisBackedFloodStore();
        var firstController = new ConsoleBuffer(10, 128, 2, 1000, redisStore);
        var secondController = new ConsoleBuffer(10, 128, 2, 1000, redisStore);

        assertEquals(
                "line-1",
                firstController.append("lobby-1", "line-1", 100).acceptedLines().getFirst());
        assertEquals(
                "line-2",
                secondController
                        .append("lobby-1", "line-2", 200)
                        .acceptedLines()
                        .getFirst());

        var dropped = firstController.append("lobby-1", "line-3", 300);
        assertTrue(dropped.acceptedLines().isEmpty());
        assertEquals(1, dropped.suppressedCount());

        var resumed = secondController.append("lobby-1", "line-4", 1200);

        assertEquals(2, resumed.acceptedLines().size());
        assertEquals(
                "[prexorcloud] suppressed 1 console line due to rate limiting",
                resumed.acceptedLines().getFirst());
        assertEquals("line-4", resumed.acceptedLines().get(1));
    }

    @SuppressWarnings("unchecked")
    private static RedisConsoleFloodWindowStore redisBackedFloodStore() {
        Map<String, Window> windows = new HashMap<>();
        var commands = (RedisCommands<String, String>) Proxy.newProxyInstance(
                RedisCommands.class.getClassLoader(),
                new Class<?>[] {RedisCommands.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "eval" -> {
                        String[] keys = (String[]) args[2];
                        String[] values = (String[]) args[3];
                        yield record(windows, keys[0], values);
                    }
                    case "del" -> {
                        long removed = 0;
                        for (String key : redisKeys(args[0])) {
                            removed += windows.remove(key) == null ? 0 : 1;
                        }
                        yield removed;
                    }
                    default -> defaultValue(method.getReturnType());
                });
        return new RedisConsoleFloodWindowStore(commands);
    }

    private static List<Long> record(Map<String, Window> windows, String key, String[] values) {
        long now = Long.parseLong(values[0]);
        int maxLines = Integer.parseInt(values[1]);
        long windowMs = Long.parseLong(values[2]);
        Window window = windows.computeIfAbsent(key, ignored -> new Window(now));
        long previousSuppressed = 0;
        if (now - window.startedAtMs >= windowMs) {
            previousSuppressed = window.suppressed;
            window.startedAtMs = now;
            window.emitted = 0;
            window.suppressed = 0;
        }
        if (window.emitted >= maxLines) {
            window.suppressed++;
            return List.of(0L, previousSuppressed, (long) window.suppressed);
        }
        window.emitted++;
        return List.of(1L, previousSuppressed, 0L);
    }

    private static String[] redisKeys(Object arg) {
        return arg instanceof String[] keys ? keys : new String[] {(String) arg};
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType == long.class) return 0L;
        if (returnType == int.class) return 0;
        return null;
    }

    private static final class Window {
        private long startedAtMs;
        private int emitted;
        private int suppressed;

        private Window(long startedAtMs) {
            this.startedAtMs = startedAtMs;
        }
    }
}
