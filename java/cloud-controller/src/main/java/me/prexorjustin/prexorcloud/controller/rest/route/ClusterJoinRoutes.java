package me.prexorjustin.prexorcloud.controller.rest.route;

import static io.javalin.apibuilder.ApiBuilder.get;
import static me.prexorjustin.prexorcloud.controller.rest.RestServer.errorResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Permission;
import me.prexorjustin.prexorcloud.controller.config.ClusterJoinTemplate;
import me.prexorjustin.prexorcloud.controller.config.ControllerConfig;
import me.prexorjustin.prexorcloud.controller.rest.RestServer;
import me.prexorjustin.prexorcloud.controller.rest.middleware.JwtAuthMiddleware;

import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Endpoints that let a new controller bootstrap itself into an existing HA cluster
 * by fetching the cluster-shared subset of {@code controller.yml} (plus the module
 * signing trust root PEM bytes) from a running controller. See
 * {@code docs/engineering/cluster-join-plan.md}.
 *
 * <p>Gated behind {@link Permission#CLUSTER_JOIN}, which is deliberately withheld
 * from the built-in {@code ADMIN} role: issuing a template returns the cluster's
 * JWT secret, Mongo URI, Redis URI, and SMTP credentials. Operators must create a
 * custom role bundling this permission to expose the endpoint to anyone.
 */
public final class ClusterJoinRoutes {

    private static final Logger logger = LoggerFactory.getLogger(ClusterJoinRoutes.class);

    private final PrexorController controller;

    public ClusterJoinRoutes(PrexorController controller) {
        this.controller = controller;
    }

    public void register() {
        get("/api/v1/admin/cluster/join-template", this::getJoinTemplate);
    }

    private void getJoinTemplate(Context ctx) {
        JwtAuthMiddleware.requirePermission(ctx, Permission.CLUSTER_JOIN);

        ControllerConfig config = controller.config();
        String clusterId = controller.stateStore().getClusterId().orElse(null);
        if (clusterId == null) {
            // Bootstrap is supposed to stamp cluster_meta before the REST layer comes
            // up; if we're here something has reset that collection mid-run.
            ctx.status(503);
            ctx.json(errorResponse(
                    "CLUSTER_NOT_STAMPED",
                    "Mongo cluster_meta is not stamped; restart this controller to stamp it.",
                    503));
            return;
        }

        String controllerYaml;
        try {
            controllerYaml = ClusterJoinTemplate.renderYaml(config);
        } catch (IOException e) {
            logger.error("Failed to render cluster-shared YAML projection: {}", e.getMessage(), e);
            ctx.status(500);
            ctx.json(errorResponse("INTERNAL_ERROR", "Failed to render join template", 500));
            return;
        }

        String trustRootB64;
        try {
            trustRootB64 = readTrustRootIfPresent(config);
        } catch (IOException e) {
            logger.error("Failed to read module signing trust root: {}", e.getMessage(), e);
            ctx.status(500);
            ctx.json(errorResponse(
                    "TRUST_ROOT_UNREADABLE",
                    "Cluster signing trust root configured but unreadable: " + e.getMessage(),
                    500));
            return;
        }

        String issuedBy = ctx.attribute("username");
        Map<String, Object> issuer = new LinkedHashMap<>();
        issuer.put("username", issuedBy);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clusterId", clusterId);
        body.put("controllerYaml", controllerYaml);
        body.put("trustRootPem", trustRootB64);
        body.put("issuedAt", Instant.now().toString());
        body.put("issuedBy", issuer);

        ctx.status(200);
        ctx.json(body);

        RestServer.audit(
                ctx,
                controller.stateStore(),
                "cluster.join_template.issued",
                "cluster",
                clusterId,
                Map.of("clusterId", clusterId, "trustRootEmbedded", trustRootB64 != null));
        logger.info("Issued cluster join template (clusterId={}, requester={} @ {})", clusterId, issuedBy, ctx.ip());
    }

    /**
     * Returns the module signing trust root PEM bytes, base64-encoded, or {@code null}
     * when no trust root is configured. We don't fail the endpoint when the trust root
     * is absent — a development-profile cluster may legitimately have signing disabled.
     */
    private static String readTrustRootIfPresent(ControllerConfig config) throws IOException {
        String trustRoot = config.modules() == null
                ? null
                : (config.modules().signing() == null
                        ? null
                        : config.modules().signing().trustRoot());
        if (trustRoot == null || trustRoot.isBlank()) {
            return null;
        }
        Path path = Path.of(trustRoot);
        if (!Files.exists(path)) {
            throw new IOException("trust root configured at " + trustRoot + " but the file is missing");
        }
        return Base64.getEncoder().encodeToString(Files.readAllBytes(path));
    }
}
