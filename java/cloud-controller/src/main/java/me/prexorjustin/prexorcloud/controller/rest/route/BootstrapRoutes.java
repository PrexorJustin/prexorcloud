package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.path;
import static io.javalin.apibuilder.ApiBuilder.post;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;

import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.grpc.BootstrapServiceImpl;

import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST surface for daemon join-token bootstrap, mirroring the gRPC
 * {@link BootstrapServiceImpl#exchangeJoinToken exchangeJoinToken} call.
 * <p>
 * Exists so the Go CLI can drive bootstrap from a wizard without taking on a
 * gRPC client dependency. The daemon's existing gRPC path is unchanged; the CLI
 * just races ahead, drops the cert files into the daemon's config dir, and the
 * daemon notices it's already bootstrapped on first start.
 */
public final class BootstrapRoutes {

    private static final Logger logger = LoggerFactory.getLogger(BootstrapRoutes.class);
    private static final Path CA_PEM = Path.of("config", "security", "ca.pem");
    private static final int NODE_CERT_VALIDITY_DAYS = 365;

    private final BootstrapServiceImpl service;

    public BootstrapRoutes(PrexorController controller) {
        // Auto-register the daemon's source IP as /32 in the controller's
        // allowed-subnets list after a successful exchange, so subsequent REST +
        // gRPC traffic from that IP passes the subnet guard. Mirrors the
        // PrexorCloudBootstrap-side wiring for the gRPC bootstrap path.
        java.util.function.Consumer<String> subnetAutoReg = cidr -> {
            try {
                if (controller.allowedSubnetsList().add(cidr)) {
                    ControllerYamlMutator.upsertList("network.allowedSubnets", cidr, true);
                }
            } catch (Exception e) {
                logger.warn("Failed to persist auto-registered subnet {}: {}", cidr, e.getMessage());
            }
        };
        this.service = new BootstrapServiceImpl(
                controller.joinTokenStore(),
                controller.ca(),
                CA_PEM,
                NODE_CERT_VALIDITY_DAYS,
                controller.stateStore(),
                controller.jwtManager(),
                subnetAutoReg);
    }

    public void register() {
        path("/api/v1/bootstrap", () -> post("/exchange", this::exchange));
    }

    private void exchange(Context ctx) {
        ExchangeRequest req = ctx.bodyAsClass(ExchangeRequest.class);
        if (req == null || req.joinToken() == null || req.joinToken().isBlank()) {
            ctx.status(400);
            ctx.json(errorResponse("VALIDATION_ERROR", "joinToken is required", 400));
            return;
        }
        if (req.nodeId() == null || req.nodeId().isBlank()) {
            ctx.status(400);
            ctx.json(errorResponse("VALIDATION_ERROR", "nodeId is required", 400));
            return;
        }
        try {
            // Resolve source IP for the auto-register side effect. Loopback /
            // unresolvable peer addresses → null (no registration needed).
            String sourceCidr = null;
            try {
                var src = java.net.InetAddress.getByName(ctx.ip());
                if (!src.isLoopbackAddress()) {
                    sourceCidr = src.getHostAddress() + (src instanceof java.net.Inet6Address ? "/128" : "/32");
                }
            } catch (java.net.UnknownHostException ignored) {
                // leave sourceCidr null
            }
            var result = service.exchange(req.joinToken(), req.nodeId(), sourceCidr);
            if (result == null) {
                ctx.status(401);
                ctx.json(errorResponse("UNAUTHORIZED", "Invalid or expired join token", 401));
                return;
            }
            ctx.status(200);
            ctx.json(Map.of(
                    "pkcs12Base64", Base64.getEncoder().encodeToString(result.pkcs12()),
                    "pkcs12Password", result.pkcs12Password(),
                    "caCertificatePem", new String(result.caPem()),
                    "cliToken", result.cliToken()));
        } catch (Exception e) {
            logger.error("Bootstrap exchange failed for node {}: {}", req.nodeId(), e.getMessage(), e);
            ctx.status(500);
            ctx.json(errorResponse("INTERNAL_ERROR", "Bootstrap failed", 500));
        }
    }

    public record ExchangeRequest(String joinToken, String nodeId) {}
}
