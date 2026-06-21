package me.prexorjustin.prexorcloud.controller.rest.middleware;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import me.prexorjustin.prexorcloud.controller.config.RateLimitingConfig;
import me.prexorjustin.prexorcloud.controller.rest.RestServer;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HttpStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Sliding-window rate limiter per IP address and per authenticated user.
 *
 * <p>
 * A fixed-window counter held in leader memory that resets every 60 seconds.
 * Since all client traffic is served by the single leader, a per-leader bucket
 * is the cluster-wide limit — no shared store required.
 * </p>
 */
public final class RateLimitMiddleware implements Handler {

    private static final long WINDOW_MS = 60_000L;

    // Operator-tunable limits are volatile so the cluster_config live-reload can
    // swap them in atomically while requests are in flight (see reconfigure()).
    private volatile int perIpLimit;
    private volatile int perUserLimit;
    private final int loginLimit;

    private final Map<String, WindowCounter> ipCounters = new ConcurrentHashMap<>();
    private final Map<String, WindowCounter> userCounters = new ConcurrentHashMap<>();
    private final Map<String, WindowCounter> loginCounters = new ConcurrentHashMap<>();

    private volatile long lastCleanup = System.currentTimeMillis();

    public RateLimitMiddleware(RateLimitingConfig config) {
        this.perIpLimit = config.perIpPerMinute();
        this.perUserLimit = config.perUserPerMinute();
        this.loginLimit = 10; // Stricter limit for login attempts
    }

    /**
     * Apply new operator-tunable limits from a cluster_config reload. The active
     * sliding-window counters are intentionally left intact — only the thresholds
     * the next request is checked against change. Returns true if any threshold
     * actually changed.
     */
    public boolean reconfigure(RateLimitingConfig config) {
        boolean changed = perIpLimit != config.perIpPerMinute() || perUserLimit != config.perUserPerMinute();
        if (changed) {
            this.perIpLimit = config.perIpPerMinute();
            this.perUserLimit = config.perUserPerMinute();
        }
        return changed;
    }

    @Override
    public void handle(@NotNull Context ctx) {
        // Periodic cleanup of stale counters
        long now = System.currentTimeMillis();
        if (now - lastCleanup > WINDOW_MS * 2) {
            cleanup(now);
            lastCleanup = now;
        }

        String ip = ctx.ip();
        String path = ctx.path();

        // Stricter rate limit on login endpoint
        if (path.equals("/api/v1/auth/login") && ctx.method().name().equals("POST")) {
            if (isLimited("login", loginCounters, ip, loginLimit, now)) {
                reject(ctx);
                return;
            }
        }

        // Per-IP rate limit
        if (isLimited("ip", ipCounters, ip, perIpLimit, now)) {
            reject(ctx);
            return;
        }
    }

    /**
     * Returns a handler for per-user rate limiting. Must be registered AFTER
     * JwtAuthMiddleware so the "username" attribute is populated.
     */
    public Handler perUserHandler() {
        return ctx -> {
            String username = ctx.attribute("username");
            if (username != null) {
                long now = System.currentTimeMillis();
                if (isLimited("user", userCounters, username, perUserLimit, now)) {
                    reject(ctx);
                }
            }
        };
    }

    boolean isLimitedForTesting(String bucket, String key, int limit, long now) {
        return isLimited(bucket, ipCounters, key, limit, now);
    }

    private boolean isLimited(String bucket, Map<String, WindowCounter> counters, String key, int limit, long now) {
        WindowCounter counter = counters.computeIfAbsent(key, _ -> new WindowCounter());
        return counter.incrementAndCheck(limit, now);
    }

    private void reject(Context ctx) {
        ctx.status(HttpStatus.TOO_MANY_REQUESTS);
        ctx.header("Retry-After", "60");
        ctx.json(RestServer.errorResponse("RATE_LIMITED", "Too many requests — try again later", 429));
    }

    private void cleanup(long now) {
        cleanupMap(ipCounters, now);
        cleanupMap(userCounters, now);
        cleanupMap(loginCounters, now);
    }

    private void cleanupMap(Map<String, WindowCounter> map, long now) {
        map.entrySet().removeIf(e -> now - e.getValue().windowStart.get() > WINDOW_MS * 2);
    }

    /**
     * Simple fixed-window counter. Resets when the current window expires.
     */
    private static final class WindowCounter {

        final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
        final AtomicInteger count = new AtomicInteger(0);

        boolean incrementAndCheck(int limit, long now) {
            long start = windowStart.get();
            if (now - start > WINDOW_MS) {
                // Window expired — reset
                if (windowStart.compareAndSet(start, now)) {
                    count.set(1);
                }
                return false;
            }
            return count.incrementAndGet() > limit;
        }
    }
}
