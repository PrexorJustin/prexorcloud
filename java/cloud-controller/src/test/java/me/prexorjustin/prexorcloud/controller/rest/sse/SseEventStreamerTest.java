package me.prexorjustin.prexorcloud.controller.rest.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;
import me.prexorjustin.prexorcloud.controller.event.EventBus;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    private record TestCloudEvent(String message) implements CloudEvent {

        @Override
        public String type() {
            return "TEST_EVENT";
        }
    }
}
