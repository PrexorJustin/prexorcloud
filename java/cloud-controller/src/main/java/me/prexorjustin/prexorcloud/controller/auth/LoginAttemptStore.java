package me.prexorjustin.prexorcloud.controller.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Tracks failed-login counters and active lockouts per username.
 *
 * <p>Two implementations: a Redis-backed store for production (survives
 * controller restart and is shared across controllers) and an in-memory store
 * for development/tests. Both are exposed through {@code RuntimeServices} so
 * callers see a single non-null handle regardless of profile.
 */
public interface LoginAttemptStore {

    /**
     * Increments the failure counter for {@code username} and returns the new
     * count. The counter resets after {@code window} of inactivity.
     */
    int recordFailure(String username, Duration window);

    /** Clear the failure counter and any active lock for {@code username}. */
    void clear(String username);

    /** Set an active lock until the given instant. Replaces any existing lock. */
    void lockUntil(String username, Instant until);

    /**
     * @return the lock-until instant if {@code username} is currently locked,
     *         otherwise empty
     */
    Optional<Instant> lockedUntil(String username);
}
