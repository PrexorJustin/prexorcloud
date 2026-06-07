package me.prexorjustin.prexorcloud.daemon.grpc;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import me.prexorjustin.prexorcloud.daemon.config.ReconnectConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The internal {@link java.util.concurrent.ScheduledExecutorService} drives the
 * reconnect attempt itself, but {@code currentDelay} advances synchronously
 * inside {@code scheduleReconnect()} before the task is queued. The tests
 * exercise that synchronous progression without waiting for the scheduler.
 * Initial delays are large enough that no scheduled task could fire during
 * test execution.
 */
@DisplayName("ReconnectManager")
class ReconnectManagerTest {

    private ReconnectManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) manager.stop();
    }

    @Test
    @DisplayName("scheduleReconnect() advances currentDelay by the configured multiplier per attempt")
    void scheduleAdvancesBackoff() throws Exception {
        ReconnectConfig config = new ReconnectConfig(60_000, 600_000, 2.0);
        manager = new ReconnectManager(null, config);

        manager.scheduleReconnect();
        assertEquals(120_000L, currentDelay(manager));

        simulateTaskFired(manager);
        manager.scheduleReconnect();
        assertEquals(240_000L, currentDelay(manager));
    }

    @Test
    @DisplayName("currentDelay caps at maxDelayMs")
    void backoffCapsAtMax() throws Exception {
        ReconnectConfig config = new ReconnectConfig(60_000, 100_000, 2.0);
        manager = new ReconnectManager(null, config);

        manager.scheduleReconnect();
        assertEquals(100_000L, currentDelay(manager), "first advance hits the ceiling");

        simulateTaskFired(manager);
        manager.scheduleReconnect();
        assertEquals(100_000L, currentDelay(manager), "subsequent advances stay at the ceiling");
    }

    @Test
    @DisplayName("onConnected() resets currentDelay to initialDelayMs")
    void onConnectedResetsBackoff() throws Exception {
        ReconnectConfig config = new ReconnectConfig(60_000, 600_000, 2.0);
        manager = new ReconnectManager(null, config);

        manager.scheduleReconnect();
        simulateTaskFired(manager);
        manager.scheduleReconnect();
        assertNotEquals(60_000L, currentDelay(manager));

        manager.onConnected();
        assertEquals(60_000L, currentDelay(manager));
    }

    @Test
    @DisplayName("scheduleReconnect() is a no-op after stop()")
    void scheduleAfterStopIsNoOp() throws Exception {
        ReconnectConfig config = new ReconnectConfig(60_000, 600_000, 2.0);
        manager = new ReconnectManager(null, config);

        manager.stop();
        long before = currentDelay(manager);
        manager.scheduleReconnect();
        assertEquals(before, currentDelay(manager));
    }

    @Test
    @DisplayName("scheduleReconnect() is idempotent while a task is already pending")
    void scheduleReconnectIsIdempotentWhilePending() throws Exception {
        ReconnectConfig config = new ReconnectConfig(60_000, 600_000, 2.0);
        manager = new ReconnectManager(null, config);

        manager.scheduleReconnect();
        long afterFirst = currentDelay(manager);

        // Duplicate notifications (e.g. onError + onCompleted) must not stack tasks
        // or advance backoff a second time before the pending attempt runs.
        manager.scheduleReconnect();
        manager.scheduleReconnect();
        assertEquals(afterFirst, currentDelay(manager));
    }

    private static long currentDelay(ReconnectManager manager) throws Exception {
        Field field = ReconnectManager.class.getDeclaredField("currentDelay");
        field.setAccessible(true);
        return field.getLong(manager);
    }

    private static void simulateTaskFired(ReconnectManager manager) throws Exception {
        Field field = ReconnectManager.class.getDeclaredField("reconnectScheduled");
        field.setAccessible(true);
        ((AtomicBoolean) field.get(manager)).set(false);
    }
}
