package me.prexorjustin.prexorcloud.controller.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The group cap is the single invariant that keeps the two independent placement paths — crash-heal
 * replacement and the min-instance reconcile — from over-provisioning a group once the per-group
 * lease is gone. {@link ClusterState#addInstanceWithinCap} is that atomic check-and-add.
 */
@DisplayName("ClusterState placement cap")
class ClusterStatePlacementCapTest {

    private static InstanceInfo scheduled(String id, String group, int port) {
        return new InstanceInfo(id, group, "node-1", InstanceState.SCHEDULED, port, 0, 0, Instant.now());
    }

    private static InstanceInfo warmScheduled(String id, String group, int port) {
        return scheduled(id, group, port).withWarm(true);
    }

    @Test
    @DisplayName("admits up to maxInstances then rejects")
    void capRejectsBeyondMax() {
        var state = new ClusterState(new EventBus());
        assertTrue(state.addInstanceWithinCap(scheduled("lobby-1", "lobby", 30000), 1));
        assertFalse(state.addInstanceWithinCap(scheduled("lobby-2", "lobby", 30001), 1));
        assertEquals(1, state.getInstancesByGroup("lobby").size());
    }

    @Test
    @DisplayName("terminal (CRASHED/STOPPED) records do not consume a slot")
    void terminalInstancesDoNotConsumeCap() {
        var state = new ClusterState(new EventBus());
        state.addInstance(scheduled("lobby-1", "lobby", 30000).withState(InstanceState.CRASHED));
        // the crashed lobby-1 is terminal — placing a fresh instance within cap 1 must still be admitted
        assertTrue(state.addInstanceWithinCap(scheduled("lobby-2", "lobby", 30001), 1));
    }

    @Test
    @DisplayName("re-placing the same non-terminal id is admitted (own id excluded from the count)")
    void reAddingSameIdIsAdmitted() {
        var state = new ClusterState(new EventBus());
        assertTrue(state.addInstanceWithinCap(scheduled("lobby-1", "lobby", 30000), 1));
        assertTrue(state.addInstanceWithinCap(scheduled("lobby-1", "lobby", 30000), 1));
        assertEquals(1, state.getInstancesByGroup("lobby").size());
    }

    @Test
    @DisplayName("concurrent placements for a max=1 group yield exactly one instance")
    void concurrentPlacementsRespectCap() throws InterruptedException {
        var state = new ClusterState(new EventBus());
        int threads = 16;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var wins = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    if (state.addInstanceWithinCap(scheduled("lobby-" + n, "lobby", 30000 + n), 1)) {
                        wins.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        assertEquals(1, wins.get(), "exactly one concurrent placement should win the cap=1 slot");
        assertEquals(1, state.getInstancesByGroup("lobby").size());
    }

    @Test
    @DisplayName("warm instances are capacity above the serving cap, not within it")
    void warmInstancesDoNotConsumeServingCap() {
        var state = new ClusterState(new EventBus());
        // Serving cap (maxServing=1) is filled.
        assertTrue(state.addInstanceWithinCap(scheduled("lobby-1", "lobby", 30000), 1, 2));
        // Serving is full, yet a warm placement is still admitted — it lives above the serving cap.
        // (This is the exact case that used to wedge the warm pool: at maxInstances serving it could
        // never refill because warm counted toward the serving cap.)
        assertTrue(state.addInstanceWithinCap(warmScheduled("lobby-w1", "lobby", 30001), 1, 2));
        // A second serving placement is still rejected by the serving cap.
        assertFalse(state.addInstanceWithinCap(scheduled("lobby-2", "lobby", 30002), 1, 2));
        assertEquals(2, state.getInstancesByGroup("lobby").size());
    }

    @Test
    @DisplayName("warm pool is bounded by warmPoolSize independently of serving")
    void warmPoolBoundedByWarmPoolSize() {
        var state = new ClusterState(new EventBus());
        assertTrue(state.addInstanceWithinCap(warmScheduled("lobby-w1", "lobby", 30000), 3, 2));
        assertTrue(state.addInstanceWithinCap(warmScheduled("lobby-w2", "lobby", 30001), 3, 2));
        // Third warm exceeds warmPoolSize=2 even though serving is empty.
        assertFalse(state.addInstanceWithinCap(warmScheduled("lobby-w3", "lobby", 30002), 3, 2));
        assertEquals(2, state.getInstancesByGroup("lobby").size());
    }

    @Test
    @DisplayName("serving and warm pools cap independently — total ceiling is maxServing + warmPoolSize")
    void servingAndWarmCappedIndependently() {
        var state = new ClusterState(new EventBus());
        // maxServing=2, warmPoolSize=1 -> up to 3 total (2 serving + 1 warm).
        assertTrue(state.addInstanceWithinCap(scheduled("s1", "lobby", 30000), 2, 1));
        assertTrue(state.addInstanceWithinCap(scheduled("s2", "lobby", 30001), 2, 1));
        assertFalse(state.addInstanceWithinCap(scheduled("s3", "lobby", 30002), 2, 1)); // serving full
        assertTrue(state.addInstanceWithinCap(warmScheduled("w1", "lobby", 30003), 2, 1));
        assertFalse(state.addInstanceWithinCap(warmScheduled("w2", "lobby", 30004), 2, 1)); // warm full
        assertEquals(3, state.getInstancesByGroup("lobby").size());
    }
}
