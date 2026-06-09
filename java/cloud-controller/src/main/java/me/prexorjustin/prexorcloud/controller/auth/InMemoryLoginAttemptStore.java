package me.prexorjustin.prexorcloud.controller.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemoryLoginAttemptStore implements LoginAttemptStore {

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Long> locks = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryLoginAttemptStore() {
        this(Clock.systemUTC());
    }

    public InMemoryLoginAttemptStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public int recordFailure(String username, Duration window) {
        long now = clock.millis();
        long windowMs = Math.max(1L, window.toMillis());
        Counter c = counters.compute(username, (k, existing) -> {
            if (existing == null || (now - existing.windowStart.get()) > windowMs) {
                return new Counter(now);
            }
            existing.count.incrementAndGet();
            return existing;
        });
        return c.count.get();
    }

    @Override
    public void clear(String username) {
        counters.remove(username);
        locks.remove(username);
    }

    @Override
    public void lockUntil(String username, Instant until) {
        locks.put(username, until.toEpochMilli());
    }

    @Override
    public Optional<Instant> lockedUntil(String username) {
        Long until = locks.get(username);
        if (until == null) return Optional.empty();
        if (clock.millis() >= until) {
            locks.remove(username, until);
            return Optional.empty();
        }
        return Optional.of(Instant.ofEpochMilli(until));
    }

    private static final class Counter {

        final AtomicLong windowStart;
        final AtomicInteger count;

        Counter(long now) {
            this.windowStart = new AtomicLong(now);
            this.count = new AtomicInteger(1);
        }
    }
}
