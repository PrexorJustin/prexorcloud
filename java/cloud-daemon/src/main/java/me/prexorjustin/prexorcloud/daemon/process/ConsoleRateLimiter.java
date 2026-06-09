package me.prexorjustin.prexorcloud.daemon.process;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free token bucket rate limiter for console output lines.
 */
public class ConsoleRateLimiter {

    private final int maxPerSecond;
    private final AtomicLong tokens;
    private final AtomicLong lastRefillNanos;
    private final AtomicLong droppedCount = new AtomicLong();

    public ConsoleRateLimiter(int maxPerSecond) {
        this.maxPerSecond = maxPerSecond;
        this.tokens = new AtomicLong(maxPerSecond);
        this.lastRefillNanos = new AtomicLong(System.nanoTime());
    }

    public boolean tryAcquire() {
        refill();
        while (true) {
            long current = tokens.get();
            if (current <= 0) {
                droppedCount.incrementAndGet();
                return false;
            }
            if (tokens.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long last = lastRefillNanos.get();
        long elapsedNanos = now - last;
        if (elapsedNanos < 1_000_000) return; // less than 1ms, skip refill

        long tokensToAdd = (elapsedNanos * maxPerSecond) / 1_000_000_000L;
        if (tokensToAdd <= 0) return;

        if (lastRefillNanos.compareAndSet(last, now)) {
            long current = tokens.get();
            long newTokens = Math.min(current + tokensToAdd, maxPerSecond);
            tokens.set(newTokens);
        }
    }

    public long getAndResetDroppedCount() {
        return droppedCount.getAndSet(0);
    }
}
