package me.prexorjustin.prexorcloud.controller.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("InMemoryLoginAttemptStore")
class InMemoryLoginAttemptStoreTest {

    @Test
    @DisplayName("recordFailure increments and returns running count")
    void recordsCount() {
        var store = new InMemoryLoginAttemptStore();
        assertEquals(1, store.recordFailure("u", Duration.ofMinutes(15)));
        assertEquals(2, store.recordFailure("u", Duration.ofMinutes(15)));
        assertEquals(3, store.recordFailure("u", Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("recordFailure resets when window has elapsed")
    void resetsAfterWindow() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-05-02T10:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        var store = new InMemoryLoginAttemptStore(clock);
        assertEquals(1, store.recordFailure("u", Duration.ofMinutes(15)));
        now.set(now.get().plus(Duration.ofMinutes(20)));
        assertEquals(1, store.recordFailure("u", Duration.ofMinutes(15)), "counter should reset after window");
    }

    @Test
    @DisplayName("lockedUntil expires after deadline")
    void lockExpires() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-05-02T10:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        var store = new InMemoryLoginAttemptStore(clock);
        Instant until = now.get().plus(Duration.ofMinutes(15));
        store.lockUntil("u", until);

        assertTrue(store.lockedUntil("u").isPresent());
        now.set(until.plusSeconds(1));
        assertTrue(store.lockedUntil("u").isEmpty());
    }

    @Test
    @DisplayName("clear removes counter and lock")
    void clearRemovesAll() {
        var store = new InMemoryLoginAttemptStore();
        store.recordFailure("u", Duration.ofMinutes(15));
        store.lockUntil("u", Instant.now().plus(Duration.ofMinutes(15)));
        store.clear("u");
        assertTrue(store.lockedUntil("u").isEmpty());
        // Subsequent failure starts fresh
        assertEquals(1, store.recordFailure("u", Duration.ofMinutes(15)));
    }
}
