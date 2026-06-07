package me.prexorjustin.prexorcloud.modules.journey.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import me.prexorjustin.prexorcloud.api.domain.PlayerJourneyEntry;
import me.prexorjustin.prexorcloud.api.event.CloudEvent;
import me.prexorjustin.prexorcloud.api.event.CustomCloudEvent;
import me.prexorjustin.prexorcloud.api.event.EventBus;
import me.prexorjustin.prexorcloud.api.event.EventHandler;
import me.prexorjustin.prexorcloud.api.event.EventSubscription;
import me.prexorjustin.prexorcloud.api.event.EventSubscriptionBuilder;
import me.prexorjustin.prexorcloud.api.event.events.InstanceCrashedEvent;
import me.prexorjustin.prexorcloud.api.event.events.PlayerConnectedEvent;
import me.prexorjustin.prexorcloud.api.event.events.PlayerDisconnectedEvent;
import me.prexorjustin.prexorcloud.api.event.events.PlayerJourneyEvent;
import me.prexorjustin.prexorcloud.api.event.events.PlayerTransferEvent;
import me.prexorjustin.prexorcloud.modules.journey.data.JourneyRepository;

import org.junit.jupiter.api.Test;

class JourneyRecorderTest {

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void persistsConnectTransferDisconnectAsRawEntries() {
        TestBus bus = new TestBus();
        JourneyRepository repository = mock(JourneyRepository.class);
        var recorder = new JourneyRecorder(bus, repository, fixedClock());

        recorder.start();
        bus.publish(new PlayerConnectedEvent(PLAYER, "Steve", "lobby-1", "lobby"));
        bus.publish(new PlayerTransferEvent(PLAYER, "Steve", "lobby-1", "survival-3"));
        bus.publish(new PlayerDisconnectedEvent(PLAYER, "Steve", "survival-3", "survival"));

        verify(repository, times(3)).save(any(PlayerJourneyEntry.class));

        var republished = bus.collected().stream()
                .filter(PlayerJourneyEvent.class::isInstance)
                .map(e -> ((PlayerJourneyEvent) e).entry().eventType())
                .toList();
        assertTrue(republished.contains("PLAYER_CONNECTED"), "got: " + republished);
        assertTrue(republished.contains("PLAYER_TRANSFER"), "got: " + republished);
        assertTrue(republished.contains("PLAYER_DISCONNECTED"), "got: " + republished);
    }

    @Test
    void crashEventDoesNotProduceJourneyEntries() {
        // Layer 5 dropped the controller-side cluster fallback that turned a single
        // INSTANCE_CRASHED into per-player crash entries. Verify the module no
        // longer reacts to that event — the gap is documented.
        TestBus bus = new TestBus();
        JourneyRepository repository = mock(JourneyRepository.class);
        var recorder = new JourneyRecorder(bus, repository, fixedClock());

        recorder.start();
        bus.publish(new InstanceCrashedEvent("survival-3", "survival", "node-1", 137, "OOM", List.of(), 1000L));

        verify(repository, never()).save(any(PlayerJourneyEntry.class));
    }

    @Test
    void stopUnsubscribesAllHandlers() {
        TestBus bus = new TestBus();
        JourneyRepository repository = mock(JourneyRepository.class);
        var recorder = new JourneyRecorder(bus, repository, fixedClock());
        recorder.start();
        recorder.stop();

        bus.publish(new PlayerConnectedEvent(PLAYER, "Steve", "lobby-1", "lobby"));

        verify(repository, never()).save(any(PlayerJourneyEntry.class));
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-05-09T12:00:00Z"), ZoneOffset.UTC);
    }

    /**
     * Minimal in-memory EventBus implementation. Only the methods JourneyRecorder
     * actually uses are functional — the rest throw to flush out any drift.
     */
    private static final class TestBus implements EventBus {

        private final java.util.Map<Class<? extends CloudEvent>, List<EventHandler<?>>> handlers =
                new java.util.HashMap<>();
        private final List<CloudEvent> collected = new ArrayList<>();

        @Override
        public <T extends CloudEvent> EventSubscriptionBuilder<T> on(Class<T> eventType) {
            throw new UnsupportedOperationException("on() not used by recorder");
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public <T extends CloudEvent> EventSubscription subscribe(Class<T> eventType, EventHandler<T> handler) {
            handlers.computeIfAbsent(eventType, k -> new ArrayList<>()).add(handler);
            return () -> handlers.getOrDefault(eventType, List.of()).remove(handler);
        }

        @Override
        public EventSubscription subscribeByType(String type, EventHandler<CustomCloudEvent> handler) {
            throw new UnsupportedOperationException("subscribeByType() not used by recorder");
        }

        @Override
        public EventSubscription subscribeAll(EventHandler<CloudEvent> handler) {
            throw new UnsupportedOperationException("subscribeAll() not used by recorder");
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void publish(CloudEvent event) {
            collected.add(event);
            List<EventHandler<?>> typed = handlers.getOrDefault(event.getClass(), List.of());
            for (EventHandler<?> handler : List.copyOf(typed)) {
                ((EventHandler) handler).handle(event);
            }
        }

        List<CloudEvent> collected() {
            return collected;
        }
    }
}
