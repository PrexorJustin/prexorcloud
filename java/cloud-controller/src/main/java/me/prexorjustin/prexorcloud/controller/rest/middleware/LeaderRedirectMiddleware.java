package me.prexorjustin.prexorcloud.controller.rest.middleware;

import java.util.Set;
import java.util.function.Supplier;

import me.prexorjustin.prexorcloud.controller.cluster.Leadership;
import me.prexorjustin.prexorcloud.controller.rest.RestServer;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-writer routing: only the leader serves the control-plane API. A non-leader
 * controller redirects every API request to the leader with HTTP 307 (which clients
 * replay preserving method + body) — the HTTP analog of the daemon handshake redirect
 * ({@code DaemonConnectionLifecycle.applyLeadership}).
 *
 * <p>This makes "all client traffic reaches the leader" an <em>enforced</em> invariant
 * rather than something every client must get right, and is the prerequisite that lets
 * the cross-controller relay be deleted: once nothing lands on a follower, there is
 * nothing to relay and follower caches never need to be kept warm.
 *
 * <p>Exemptions: OPTIONS preflight (already short-circuited by the CORS handler) and
 * the infra probes that must answer per-instance (health / ready / metrics). When there
 * is no leader (the ~lease-TTL failover gap) a follower returns 503 {@code NO_LEADER}
 * with {@code Retry-After} so clients retry rather than act on a follower.
 *
 * <p>Defaults to always-leader (never redirects) so single-controller installs and tests
 * are unaffected; bootstrap injects the real elector + leader-address resolver.
 */
public final class LeaderRedirectMiddleware implements Handler {

    private static final Logger logger = LoggerFactory.getLogger(LeaderRedirectMiddleware.class);

    // Infra endpoints that must answer on the instance the probe/scrape hit, not the leader.
    private static final Set<String> EXEMPT_PATHS =
            Set.of("/api/v1/system/health", "/api/v1/system/ready", "/health", "/ready", "/metrics");

    private final Leadership leadership;
    private final Supplier<String> leaderRestAddressResolver;

    public LeaderRedirectMiddleware(Leadership leadership, Supplier<String> leaderRestAddressResolver) {
        this.leadership = leadership;
        this.leaderRestAddressResolver = leaderRestAddressResolver;
    }

    @Override
    public void handle(@NotNull Context ctx) {
        if (leadership.isLeader()) {
            return;
        }
        if ("OPTIONS".equalsIgnoreCase(ctx.method().name())) {
            return;
        }
        if (EXEMPT_PATHS.contains(ctx.path())) {
            return;
        }

        String leaderAddr = leaderRestAddressResolver.get();
        if (leaderAddr == null || leaderAddr.isBlank()) {
            // Failover gap: no known leader. Fail cleanly + retryably rather than serve from a follower.
            ctx.status(503);
            ctx.header("Retry-After", "2");
            ctx.json(
                    RestServer.errorResponse("NO_LEADER", "No controller is currently the leader; retry shortly", 503));
            ctx.skipRemainingHandlers();
            return;
        }

        String target = buildLeaderUrl(ctx, leaderAddr);
        ctx.status(307);
        ctx.header("Location", target);
        ctx.skipRemainingHandlers();
        logger.debug("Redirecting {} {} to leader at {}", ctx.method().name(), ctx.path(), leaderAddr);
    }

    /** Rebuild the request URL against the leader's {@code host:port}, preserving scheme/path/query. */
    private static String buildLeaderUrl(Context ctx, String leaderHostPort) {
        String base = ctx.scheme() + "://" + leaderHostPort + ctx.path();
        String query = ctx.queryString();
        return (query == null || query.isBlank()) ? base : base + "?" + query;
    }
}
