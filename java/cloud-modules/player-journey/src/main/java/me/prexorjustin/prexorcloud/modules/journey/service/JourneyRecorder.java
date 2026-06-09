package me.prexorjustin.prexorcloud.modules.journey.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import me.prexorjustin.prexorcloud.api.domain.PlayerJourneyEntry;
import me.prexorjustin.prexorcloud.api.event.EventBus;
import me.prexorjustin.prexorcloud.api.event.EventSubscription;
import me.prexorjustin.prexorcloud.api.event.events.PlayerConnectedEvent;
import me.prexorjustin.prexorcloud.api.event.events.PlayerDisconnectedEvent;
import me.prexorjustin.prexorcloud.api.event.events.PlayerJourneyEvent;
import me.prexorjustin.prexorcloud.api.event.events.PlayerTransferEvent;
import me.prexorjustin.prexorcloud.modules.journey.data.JourneyRepository;

/**
 * Subscribes to the three {@code PLAYER_*} lifecycle events and persists one
 * normalised {@link PlayerJourneyEntry} per observation, then republishes the
 * entry as a {@link PlayerJourneyEvent} so SSE consumers see a unified stream.
 *
 * <p>Note: {@link me.prexorjustin.prexorcloud.api.event.events.InstanceCrashedEvent} does not produce per-player entries
 * here — the module would need {@code ClusterState.getAllPlayers()} (which it
 * doesn't have) to enumerate affected players. Crash-driven player movements
 * surface via the {@code PLAYER_DISCONNECTED} events the controller fires
 * when its player tracker reaps the affected players.
 */
public final class JourneyRecorder {

    private final EventBus events;
    private final JourneyRepository repository;
    private final Clock clock;
    private List<EventSubscription> subscriptions = List.of();

    public JourneyRecorder(EventBus events, JourneyRepository repository, Clock clock) {
        this.events = events;
        this.repository = repository;
        this.clock = clock;
    }

    public void start() {
        if (!subscriptions.isEmpty()) return;
        List<EventSubscription> subs = new ArrayList<>(3);
        subs.add(events.subscribe(PlayerConnectedEvent.class, this::onConnected));
        subs.add(events.subscribe(PlayerTransferEvent.class, this::onTransferred));
        subs.add(events.subscribe(PlayerDisconnectedEvent.class, this::onDisconnected));
        subscriptions = List.copyOf(subs);
    }

    public void stop() {
        for (EventSubscription sub : subscriptions) {
            sub.unsubscribe();
        }
        subscriptions = List.of();
    }

    private void onConnected(PlayerConnectedEvent event) {
        record(new PlayerJourneyEntry(
                event.uuid(),
                nullSafe(event.name()),
                event.type(),
                "",
                nullSafe(event.instanceId()),
                nullSafe(event.group()),
                clock.instant()));
    }

    private void onTransferred(PlayerTransferEvent event) {
        // group is left empty on transfers — the old controller-side service
        // looked it up from ClusterState; modules don't have that, and the
        // upstream PLAYER_CONNECTED/DISCONNECTED bracketing the transfer
        // already carries group, so consumers can stitch.
        record(new PlayerJourneyEntry(
                event.uuid(),
                nullSafe(event.name()),
                event.type(),
                nullSafe(event.fromInstanceId()),
                nullSafe(event.toInstanceId()),
                "",
                clock.instant()));
    }

    private void onDisconnected(PlayerDisconnectedEvent event) {
        record(new PlayerJourneyEntry(
                event.uuid(),
                nullSafe(event.name()),
                event.type(),
                nullSafe(event.instanceId()),
                "",
                nullSafe(event.group()),
                clock.instant()));
    }

    private void record(PlayerJourneyEntry entry) {
        repository.save(entry);
        events.publish(new PlayerJourneyEvent(entry));
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
