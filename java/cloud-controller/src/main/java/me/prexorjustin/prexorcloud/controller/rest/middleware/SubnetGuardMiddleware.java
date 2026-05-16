package me.prexorjustin.prexorcloud.controller.rest.middleware;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;

import me.prexorjustin.prexorcloud.controller.rest.RestServer;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST defense-in-depth: rejects requests whose source IP isn't in the
 * controller's {@link AllowedSubnetsList} with HTTP 403.
 * <p>
 * Bypassed for:
 * <ul>
 *   <li>The bootstrap endpoint ({@code /api/v1/bootstrap/exchange}) — the
 *       join token is the auth there, and the endpoint itself adds the source
 *       IP to the allow-list as a side effect of a successful exchange.</li>
 *   <li>Health/ready probes — must be reachable from load balancers / cloud
 *       monitoring that you may not want to enumerate in CIDR form.</li>
 *   <li>OPTIONS preflight — browser preflight requests carry no payload and
 *       are already filtered upstream by the CORS handler.</li>
 *   <li>Loopback — always allowed regardless of config; see
 *       {@link AllowedSubnetsList#allows(InetAddress)}.</li>
 * </ul>
 * <p>
 * Caveat: behind a reverse proxy / load balancer, {@link Context#ip()} returns
 * the proxy IP unless trust-forwarded-for is configured. In that case put the
 * proxy's IP in {@code allowedSubnets} — the CIDR guard isn't the right tool
 * for filtering original-client IPs in a proxied deployment.
 */
public final class SubnetGuardMiddleware implements Handler {

    private static final Logger logger = LoggerFactory.getLogger(SubnetGuardMiddleware.class);

    private static final Set<String> EXEMPT_PATHS = Set.of(
            "/api/v1/bootstrap/exchange",
            "/api/v1/system/health",
            "/api/v1/system/ready",
            "/health",
            "/ready",
            "/metrics");

    private final AllowedSubnetsList allowList;

    public SubnetGuardMiddleware(AllowedSubnetsList allowList) {
        this.allowList = allowList;
    }

    @Override
    public void handle(@NotNull Context ctx) {
        if ("OPTIONS".equalsIgnoreCase(ctx.method().name())) return;
        String path = ctx.path();
        if (EXEMPT_PATHS.contains(path)) return;

        InetAddress source;
        try {
            source = InetAddress.getByName(ctx.ip());
        } catch (UnknownHostException e) {
            // Should never happen — ctx.ip() is the resolved peer address.
            logger.warn("Failed to resolve source IP {}: {}", ctx.ip(), e.getMessage());
            ctx.status(403);
            ctx.json(RestServer.errorResponse("FORBIDDEN", "Source IP not resolvable", 403));
            ctx.skipRemainingHandlers();
            return;
        }
        if (!allowList.allows(source)) {
            logger.warn(
                    "Rejected {} {} from {} — not in allowedSubnets",
                    ctx.method().name(),
                    path,
                    ctx.ip());
            ctx.status(403);
            ctx.json(RestServer.errorResponse(
                    "FORBIDDEN", "Source IP " + ctx.ip() + " is not in the controller's allowedSubnets list", 403));
            ctx.skipRemainingHandlers();
        }
    }
}
