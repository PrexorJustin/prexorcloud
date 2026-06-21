package me.prexorjustin.prexorcloud.controller.rest.middleware;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.prexorjustin.prexorcloud.controller.config.RateLimitingConfig;

import org.junit.jupiter.api.Test;

class RateLimitMiddlewareTest {

    @Test
    void inMemoryCounterEnforcesPerKeyLimitWithinWindow() {
        var middleware = new RateLimitMiddleware(new RateLimitingConfig(100, 300));
        long now = 100L;
        // limit = 1: the first request passes, the second within the window is limited.
        assertFalse(middleware.isLimitedForTesting("ip", "127.0.0.1", 1, now));
        assertTrue(middleware.isLimitedForTesting("ip", "127.0.0.1", 1, now));
    }

    @Test
    void countersAreKeyScoped() {
        var middleware = new RateLimitMiddleware(new RateLimitingConfig(100, 300));
        long now = 100L;
        assertFalse(middleware.isLimitedForTesting("ip", "10.0.0.1", 1, now));
        // A distinct key has its own window and is unaffected by the first.
        assertFalse(middleware.isLimitedForTesting("ip", "10.0.0.2", 1, now));
        assertTrue(middleware.isLimitedForTesting("ip", "10.0.0.1", 1, now));
    }
}
