package me.prexorjustin.prexorcloud.common.concurrent;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Exponential backoff with jitter for transient-failure retries.
 *
 * <p>
 * Use for outbound HTTP, gRPC, or any I/O where the failure mode might be
 * "try again in a moment". Do NOT use to mask programming errors — the
 * {@code retryOn} predicate should specifically match transient conditions
 * (timeouts, 5xx, connection-reset).
 * </p>
 */
public final class Backoff {

    private Backoff() {}

    /**
     * Run {@code op} up to {@code policy.maxAttempts()} times, sleeping
     * exponentially between attempts. Throws the last exception if all
     * attempts fail.
     */
    public static <T> T withRetries(Callable<T> op, Policy policy) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            try {
                return op.call();
            } catch (Exception e) {
                last = e;
                if (attempt == policy.maxAttempts() || !policy.retryOn().test(e)) {
                    throw e;
                }
                sleep(policy.delayFor(attempt));
            }
        }
        throw last;
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while backing off", ie);
        }
    }

    /**
     * Backoff configuration. Use {@link #defaultPolicy()} for the typical
     * 3-attempt exponential-with-jitter; build a custom policy with the
     * static factories for unusual cases.
     */
    public record Policy(
            int maxAttempts, Duration baseDelay, Duration cap, double jitter, Predicate<Exception> retryOn) {

        public Policy {
            if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
            if (baseDelay.isNegative() || baseDelay.isZero()) {
                throw new IllegalArgumentException("baseDelay must be > 0");
            }
            if (cap.compareTo(baseDelay) < 0) {
                throw new IllegalArgumentException("cap must be >= baseDelay");
            }
            if (jitter < 0.0 || jitter > 1.0) {
                throw new IllegalArgumentException("jitter must be in [0, 1]");
            }
        }

        Duration delayFor(int attempt) {
            long capMs = cap.toMillis();
            long expMs = Math.min(capMs, baseDelay.toMillis() * (1L << Math.min(attempt - 1, 30)));
            double jitterFactor = 1.0 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * jitter;
            long withJitter = Math.max(0L, (long) (expMs * jitterFactor));
            return Duration.ofMillis(Math.min(withJitter, capMs));
        }

        public static Policy defaultPolicy() {
            return new Policy(3, Duration.ofMillis(250), Duration.ofSeconds(5), 0.25, e -> true);
        }

        public Policy withRetryOn(Predicate<Exception> predicate) {
            return new Policy(maxAttempts, baseDelay, cap, jitter, predicate);
        }
    }
}
