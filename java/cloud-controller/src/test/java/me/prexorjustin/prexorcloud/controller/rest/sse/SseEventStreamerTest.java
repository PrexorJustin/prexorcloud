package me.prexorjustin.prexorcloud.controller.rest.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.redis.RedisKeys;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.Limit;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

class SseEventStreamerTest {

    @Test
    void assignsSequencesAndKeepsBoundedReplayWindow() throws InterruptedException {
        var eventBus = new EventBus();
        try {
            var streamer = new SseEventStreamer(eventBus, new ObjectMapper(), new SseTicketManager(), 2);

            eventBus.publish(new TestCloudEvent("one"));
            eventBus.publish(new TestCloudEvent("two"));
            eventBus.publish(new TestCloudEvent("three"));

            awaitSequence(streamer, 3);

            var replay = streamer.replayEventsAfter(1);
            assertEquals(2, replay.size());
            assertEquals(2, replay.get(0).sequence());
            assertEquals(3, replay.get(1).sequence());
            assertEquals(2L, replay.get(0).envelope().get("sequence"));
            assertEquals(3L, replay.get(1).envelope().get("sequence"));
            assertEquals("TEST_EVENT", replay.get(0).envelope().get("type"));
            assertEquals("TEST_EVENT", replay.get(1).envelope().get("type"));
        } finally {
            eventBus.shutdown();
        }
    }

    @Test
    void reportsReplayGapWhenLastSeenIsOlderThanWindow() throws InterruptedException {
        var eventBus = new EventBus();
        try {
            var streamer = new SseEventStreamer(eventBus, new ObjectMapper(), new SseTicketManager(), 2);

            eventBus.publish(new TestCloudEvent("one"));
            eventBus.publish(new TestCloudEvent("two"));
            eventBus.publish(new TestCloudEvent("three"));

            awaitSequence(streamer, 3);

            assertTrue(streamer.replayGapAfter(0));
            assertFalse(streamer.replayGapAfter(1));
            assertFalse(streamer.replayGapAfter(2));
            assertEquals(2, streamer.earliestSequence());
            assertEquals(3, streamer.latestSequence());
        } finally {
            eventBus.shutdown();
        }
    }

    @Test
    void redisReplayStoreKeepsSequenceAndReplayAcrossStreamerInstances() throws InterruptedException {
        var storedReplay = new ArrayList<StreamMessage<String, String>>();
        var redis = redisReplayCommands(storedReplay, 2);
        var objectMapper = new ObjectMapper();
        var eventBus = new EventBus();
        try {
            var streamer = new SseEventStreamer(
                    eventBus,
                    objectMapper,
                    new SseTicketManager(),
                    new SseEventStreamer.RedisReplayStore(redis, objectMapper, 2));

            eventBus.publish(new TestCloudEvent("one"));
            awaitSequence(streamer, 1);
            awaitReplayContains(streamer, 1);
            eventBus.publish(new TestCloudEvent("two"));
            awaitSequence(streamer, 2);
            awaitReplayContains(streamer, 2);
            eventBus.publish(new TestCloudEvent("three"));

            awaitSequence(streamer, 3);
            awaitReplaySize(storedReplay, 2);
            awaitReplayContains(streamer, 3);
        } finally {
            eventBus.shutdown();
        }

        var restartedBus = new EventBus();
        try {
            var restarted = new SseEventStreamer(
                    restartedBus,
                    objectMapper,
                    new SseTicketManager(),
                    new SseEventStreamer.RedisReplayStore(redis, objectMapper, 2));

            assertEquals(3, restarted.latestSequence());
            assertEquals(2, restarted.earliestSequence());
            assertTrue(restarted.replayGapAfter(0));
            assertFalse(restarted.replayGapAfter(1));
            var replay = restarted.replayEventsAfter(1);
            assertEquals(2, replay.size());
            assertEquals(2, replay.get(0).sequence());
            assertEquals(3, replay.get(1).sequence());
        } finally {
            restartedBus.shutdown();
        }
    }

    @Test
    void rejectsInvalidReplayCapacity() {
        var eventBus = new EventBus();
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new SseEventStreamer(eventBus, new ObjectMapper(), new SseTicketManager(), 0));
        } finally {
            eventBus.shutdown();
        }
    }

    private static void awaitSequence(SseEventStreamer streamer, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (streamer.latestSequence() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, streamer.latestSequence());
    }

    private static void awaitReplaySize(List<?> replay, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (replay.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, replay.size());
    }

    private static void awaitReplayContains(SseEventStreamer streamer, long sequence) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (streamer.replayEventsAfter(0).stream().noneMatch(event -> event.sequence() == sequence)
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(streamer.replayEventsAfter(0).stream().anyMatch(event -> event.sequence() == sequence));
    }

    @SuppressWarnings("unchecked")
    private static RedisCommands<String, String> redisReplayCommands(
            List<StreamMessage<String, String>> replay, int replayCapacity) {
        AtomicLong sequence = new AtomicLong();
        AtomicLong streamId = new AtomicLong();
        return (RedisCommands<String, String>) Proxy.newProxyInstance(
                RedisCommands.class.getClassLoader(), new Class<?>[] {RedisCommands.class}, (ignored, method, args) -> {
                    return switch (method.getName()) {
                        case "incr" -> sequence.incrementAndGet();
                        case "get" -> RedisKeys.SSE_SEQUENCE.equals(args[0]) ? Long.toString(sequence.get()) : null;
                        case "xadd" -> {
                            @SuppressWarnings("unchecked")
                            Map<String, String> body = (Map<String, String>) args[2];
                            replay.add(new StreamMessage<>(
                                    (String) args[0], Long.toString(streamId.incrementAndGet()), Map.copyOf(body)));
                            while (replay.size() > replayCapacity) {
                                replay.removeFirst();
                            }
                            yield Long.toString(streamId.get());
                        }
                        case "xrange" -> {
                            long count = args.length >= 3 && args[2] instanceof Limit limit
                                    ? limit.getCount()
                                    : replay.size();
                            yield List.copyOf(replay.subList(0, Math.min(replay.size(), (int) count)));
                        }
                        case "xlen" -> (long) replay.size();
                        case "lrange" -> List.of();
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    private record TestCloudEvent(String message) implements CloudEvent {

        @Override
        public String type() {
            return "TEST_EVENT";
        }
    }
}
