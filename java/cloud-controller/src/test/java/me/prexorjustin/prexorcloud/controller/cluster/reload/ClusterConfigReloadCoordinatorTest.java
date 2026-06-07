package me.prexorjustin.prexorcloud.controller.cluster.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import me.prexorjustin.prexorcloud.api.event.events.ClusterConfigChangedEvent;
import me.prexorjustin.prexorcloud.controller.event.EventBus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ClusterConfigReloadCoordinator")
class ClusterConfigReloadCoordinatorTest {

    /** Poll until the condition holds or the deadline passes. EventBus dispatches on virtual threads. */
    private static boolean awaitTrue(BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    @Test
    @DisplayName("primes subscribers synchronously at start() with the current config")
    void primesAtStartup() {
        var bus = new EventBus();
        var current = new AtomicReference<Map<String, Object>>(Map.of("k", "v0"));
        var seen = new AtomicReference<Map<String, Object>>();

        var coordinator = new ClusterConfigReloadCoordinator(current::get, bus).register(seen::set);
        coordinator.start();

        assertEquals(Map.of("k", "v0"), seen.get());
        coordinator.close();
    }

    @Test
    @DisplayName("re-dispatches the folded config when a ClusterConfigChangedEvent is published")
    void reloadsOnEvent() {
        var bus = new EventBus();
        var current = new AtomicReference<Map<String, Object>>(Map.of("k", "v0"));
        var seen = new AtomicReference<Map<String, Object>>();
        var calls = new AtomicInteger();

        var coordinator = new ClusterConfigReloadCoordinator(current::get, bus).register(cfg -> {
            seen.set(cfg);
            calls.incrementAndGet();
        });
        coordinator.start(); // priming call #1

        current.set(Map.of("k", "v1"));
        bus.publish(new ClusterConfigChangedEvent(2, 1, "op", ClusterConfigChangedEvent.ACTION_PATCH));

        assertEquals(true, awaitTrue(() -> calls.get() >= 2, 2000));
        assertEquals(Map.of("k", "v1"), seen.get());
        coordinator.close();
    }

    @Test
    @DisplayName("isolates a throwing subscriber so the others still reload")
    void isolatesFailingSubscriber() {
        var bus = new EventBus();
        var current = new AtomicReference<Map<String, Object>>(Map.of("k", "v0"));
        var healthyCalls = new AtomicInteger();

        var coordinator = new ClusterConfigReloadCoordinator(current::get, bus)
                .register(cfg -> {
                    throw new IllegalStateException("boom");
                })
                .register(cfg -> healthyCalls.incrementAndGet());
        coordinator.start(); // healthy subscriber primed despite the failing one

        assertEquals(1, healthyCalls.get());

        bus.publish(new ClusterConfigChangedEvent(2, 1, "op", ClusterConfigChangedEvent.ACTION_PATCH));
        assertEquals(true, awaitTrue(() -> healthyCalls.get() >= 2, 2000));
        coordinator.close();
    }

    @Test
    @DisplayName("close() unsubscribes so later events are ignored")
    void closeUnsubscribes() {
        var bus = new EventBus();
        var current = new AtomicReference<Map<String, Object>>(Map.of("k", "v0"));
        var calls = new AtomicInteger();

        var coordinator =
                new ClusterConfigReloadCoordinator(current::get, bus).register(cfg -> calls.incrementAndGet());
        coordinator.start();
        assertEquals(1, calls.get());
        coordinator.close();

        bus.publish(new ClusterConfigChangedEvent(2, 1, "op", ClusterConfigChangedEvent.ACTION_PATCH));
        // Give any (incorrectly still-registered) async handler a chance to run, then assert it didn't.
        awaitTrue(() -> calls.get() > 1, 300);
        assertEquals(1, calls.get());
    }
}
