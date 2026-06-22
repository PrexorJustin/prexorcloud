package me.prexorjustin.prexorcloud.controller.config;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import me.prexorjustin.prexorcloud.common.config.YamlConfigLoader;

/**
 * Produces the cluster-shared subset of {@link ControllerConfig}. The "shared
 * subset" is defined in {@code docs/engineering/cluster-join-plan.md} (see the
 * Field classification table). Getting this wrong is the bug the cluster-join
 * plan exists to prevent — a missing cluster-shared field leaves the new node
 * without authoritative config; an over-included node-local field would stamp a
 * per-node bind address or admin password onto the cluster state.
 *
 * <p>This projection seeds the initial {@code cluster_config} version on Day-0
 * (the config seed in {@code ClusterControlService}); the Mongo cluster store then
 * serves it to joining controllers. The legacy
 * {@code GET /api/v1/admin/cluster/join-template} REST endpoint that previously
 * served it has been removed.
 */
public final class ClusterJoinTemplate {

    private ClusterJoinTemplate() {}

    /**
     * Render the cluster-shared projection of {@code config} as a YAML string.
     * {@code security.initialAdminPassword} is wiped to the empty string;
     * {@code http} is reduced to just its {@code cors} subsection; {@code network}
     * is reduced to just {@code allowedSubnets}. Node-local sections (uuid, http
     * bind, grpc bind, logging, dashboard) are omitted entirely.
     */
    public static String renderYaml(ControllerConfig config) throws IOException {
        return YamlConfigLoader.mapper().writeValueAsString(buildSharedMap(config));
    }

    /**
     * Visible for tests so we can assert the structure without parsing YAML back.
     */
    public static Map<String, Object> buildSharedMap(ControllerConfig config) {
        Map<String, Object> root = new LinkedHashMap<>();

        // Cluster identity travels with the template so the joining controller knows
        // which cluster it's bound to before it ever opens the Mongo URI.
        Map<String, Object> cluster = new LinkedHashMap<>();
        if (config.cluster().id() != null) cluster.put("id", config.cluster().id());
        if (!cluster.isEmpty()) root.put("cluster", cluster);

        root.put("runtime", config.runtime());

        // http: only cors (host/port are per-node bind decisions).
        Map<String, Object> http = new LinkedHashMap<>();
        http.put("cors", config.http().cors());
        root.put("http", http);

        // network: only allowedSubnets (cluster-wide policy).
        Map<String, Object> network = new LinkedHashMap<>();
        network.put("allowedSubnets", config.network().allowedSubnets());
        root.put("network", network);

        root.put("database", config.database());
        root.put("backup", config.backup());

        // security: clone with initialAdminPassword wiped. The new node already has
        // admin users in Mongo, so the bootstrap admin password is meaningless there.
        SecurityControllerConfig src = config.security();
        root.put(
                "security",
                new SecurityControllerConfig(
                        src.jwtSecret(),
                        src.jwtExpirationMinutes(),
                        "",
                        src.rateLimiting(),
                        src.jwtPreviousSecrets(),
                        src.lockout(),
                        src.passwordReset()));

        root.put("metrics", config.metrics());
        root.put("scheduler", config.scheduler());
        root.put("heartbeat", config.heartbeat());
        root.put("crashes", config.crashes());
        root.put("modules", config.modules());
        root.put("maintenance", config.maintenance());
        root.put("share", config.share());

        return root;
    }
}
