package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.patch;
import static io.javalin.apibuilder.ApiBuilder.path;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;

import java.io.IOException;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;

import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin endpoints that mutate {@code controller.yml} on disk and the controller's
 * live in-memory state. Used by the dashboard / daemon installers to register
 * themselves with the controller without forcing the operator to hand-edit YAML.
 * <p>
 * Both CORS and subnet-allow changes apply immediately via the in-memory
 * mutable lists ({@code CorsAllowList}, {@code AllowedSubnetsList}). The YAML
 * write keeps them durable across restarts via {@link ControllerYamlMutator}.
 */
public final class AdminConfigRoutes {

    private static final Logger logger = LoggerFactory.getLogger(AdminConfigRoutes.class);

    private final PrexorController controller;

    public AdminConfigRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        path("/api/v1/admin", () -> {
            patch("/cors/origins", this::patchCorsOrigins);
            patch("/network/allowed-subnets", this::patchAllowedSubnets);
        });
    }

    private void patchCorsOrigins(Context ctx) {
        // Use ADMIN-only USERS_CREATE as a proxy for "real admin" — there is no
        // dedicated CONFIG_WRITE permission today and adding one would be a separate
        // PR. Worth tightening once a config-mutation permission exists.
        JwtAuthMiddleware.requirePermission(ctx, Permission.USERS_CREATE);

        CorsPatchRequest req = ctx.bodyAsClass(CorsPatchRequest.class);
        if (req == null
                || req.action() == null
                || req.origin() == null
                || req.origin().isBlank()) {
            ctx.status(400);
            ctx.json(errorResponse("VALIDATION_ERROR", "action and origin are required", 400));
            return;
        }
        String action = req.action().toLowerCase();
        if (!action.equals("add") && !action.equals("remove")) {
            ctx.status(400);
            ctx.json(errorResponse("VALIDATION_ERROR", "action must be 'add' or 'remove'", 400));
            return;
        }
        String origin = req.origin().trim();
        if (!origin.startsWith("http://") && !origin.startsWith("https://")) {
            ctx.status(400);
            ctx.json(errorResponse("VALIDATION_ERROR", "origin must start with http:// or https://", 400));
            return;
        }

        try {
            boolean changed =
                    ControllerYamlMutator.upsertList("http.cors.allowedOrigins", origin, action.equals("add"));
            if (changed) {
                if (action.equals("add")) {
                    controller.corsAllowList().add(origin);
                } else {
                    controller.corsAllowList().remove(origin);
                }
                logger.info("CORS allow-list {}: {} (by user {})", action, origin, ctx.attribute("username"));
            }
            ctx.status(200);
            ctx.json(Map.of(
                    "ok",
                    true,
                    "changed",
                    changed,
                    "restartRequired",
                    false,
                    "allowedOrigins",
                    controller.corsAllowList().snapshot()));
        } catch (IOException e) {
            logger.error("Failed to patch CORS origins: {}", e.getMessage(), e);
            ctx.status(500);
            ctx.json(errorResponse("INTERNAL_ERROR", "Failed to update controller.yml: " + e.getMessage(), 500));
        }
    }

    private void patchAllowedSubnets(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.USERS_CREATE);

        SubnetPatchRequest req = ctx.bodyAsClass(SubnetPatchRequest.class);
        if (req == null
                || req.action() == null
                || req.cidr() == null
                || req.cidr().isBlank()) {
            ctx.status(400);
            ctx.json(errorResponse("VALIDATION_ERROR", "action and cidr are required", 400));
            return;
        }
        String action = req.action().toLowerCase();
        if (!action.equals("add") && !action.equals("remove")) {
            ctx.status(400);
            ctx.json(errorResponse("VALIDATION_ERROR", "action must be 'add' or 'remove'", 400));
            return;
        }
        String cidr = req.cidr().trim();
        // Reject malformed CIDRs up-front so the YAML never gets a bad entry that
        // the live list would silently drop on next load.
        try {
            me.prexorjustin.prexorcloud.controller.rest.middleware.CidrRange.parse(cidr);
        } catch (IllegalArgumentException e) {
            ctx.status(400);
            ctx.json(errorResponse("VALIDATION_ERROR", "invalid CIDR: " + e.getMessage(), 400));
            return;
        }

        try {
            boolean changed = ControllerYamlMutator.upsertList("network.allowedSubnets", cidr, action.equals("add"));
            if (changed) {
                if (action.equals("add")) {
                    controller.allowedSubnetsList().add(cidr);
                } else {
                    controller.allowedSubnetsList().remove(cidr);
                }
                logger.info("Allowed subnets {}: {} (by user {})", action, cidr, ctx.attribute("username"));
            }
            ctx.status(200);
            ctx.json(Map.of(
                    "ok",
                    true,
                    "changed",
                    changed,
                    "restartRequired",
                    false,
                    "allowedSubnets",
                    controller.allowedSubnetsList().snapshot(),
                    "wideOpen",
                    controller.allowedSubnetsList().isWideOpen()));
        } catch (IOException e) {
            logger.error("Failed to patch allowed subnets: {}", e.getMessage(), e);
            ctx.status(500);
            ctx.json(errorResponse("INTERNAL_ERROR", "Failed to update controller.yml: " + e.getMessage(), 500));
        }
    }

    public record CorsPatchRequest(String action, String origin) {}

    public record SubnetPatchRequest(String action, String cidr) {}
}
