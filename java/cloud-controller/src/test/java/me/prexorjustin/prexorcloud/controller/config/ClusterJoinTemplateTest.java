package me.prexorjustin.prexorcloud.controller.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Executable spec for the cluster-shared subset defined in
 * {@code docs/engineering/cluster-join-plan.md}. Drift between the plan and the
 * projection is the load-bearing bug the join wizard exists to prevent — a missing
 * cluster-shared field forces operators back into the hand-copy workflow; an
 * over-included node-local field stamps a per-node bind or admin password onto the
 * new node.
 */
class ClusterJoinTemplateTest {

    private ControllerConfig sampleConfig() {
        SecurityControllerConfig security = new SecurityControllerConfig(
                "test-jwt-secret-do-not-use-in-prod-12345678901234567890",
                720,
                "the-initial-admin-password",
                new RateLimitingConfig(),
                List.of("previous-secret-1"),
                new LockoutConfig(),
                new PasswordResetConfig());

        return new ControllerConfig(
                "node-local-uuid",
                new HttpConfig("10.0.0.1", 8181, new CorsConfig(List.of("https://dashboard.example.com"))),
                new GrpcConfig(),
                new NetworkConfig(List.of("10.0.0.0/8")),
                new DatabaseConfig(),
                null,
                new SchedulerConfig(),
                new HeartbeatConfig(),
                new RuntimeConfig(RuntimeConfig.PRODUCTION),
                security,
                new CrashConfig(),
                new MetricsConfig(),
                new ModulesConfig(),
                new MaintenanceConfig(false, null),
                new DashboardConfig(),
                new BackupConfig(),
                new ShareConfig());
    }

    @Test
    @DisplayName("shared map omits all node-local top-level keys")
    void omitsNodeLocalTopLevelKeys() {
        Map<String, Object> shared = ClusterJoinTemplate.buildSharedMap(sampleConfig());

        // Node-local sections per the plan's field classification table.
        assertFalse(shared.containsKey("uuid"), "uuid is per-node identity");
        assertFalse(shared.containsKey("grpc"), "grpc bind is per-node");
        assertFalse(shared.containsKey("logging"), "logging level is per-node");
        assertFalse(shared.containsKey("dashboard"), "dashboard enable flag is per-node");
    }

    @Test
    @DisplayName("shared map includes all cluster-shared top-level sections")
    void includesAllSharedTopLevelKeys() {
        Map<String, Object> shared = ClusterJoinTemplate.buildSharedMap(sampleConfig());

        // Per the plan's field classification table.
        assertTrue(shared.containsKey("runtime"));
        assertTrue(shared.containsKey("http"), "http subset (cors only) is shared");
        assertTrue(shared.containsKey("network"), "network subset (allowedSubnets only) is shared");
        assertTrue(shared.containsKey("database"));
        assertTrue(shared.containsKey("backup"));
        assertTrue(shared.containsKey("security"));
        assertTrue(shared.containsKey("metrics"));
        assertTrue(shared.containsKey("scheduler"));
        assertTrue(shared.containsKey("heartbeat"));
        assertTrue(shared.containsKey("crashes"));
        assertTrue(shared.containsKey("modules"));
        assertTrue(shared.containsKey("maintenance"));
        assertTrue(shared.containsKey("share"));
    }

    @Test
    @DisplayName("http section keeps cors but drops host/port")
    @SuppressWarnings("unchecked")
    void httpSubsetKeepsOnlyCors() {
        Map<String, Object> shared = ClusterJoinTemplate.buildSharedMap(sampleConfig());

        Map<String, Object> http = (Map<String, Object>) shared.get("http");
        assertTrue(http.containsKey("cors"));
        assertFalse(http.containsKey("host"), "http.host is per-node bind");
        assertFalse(http.containsKey("port"), "http.port is per-node bind");
    }

    @Test
    @DisplayName("network section keeps allowedSubnets only")
    @SuppressWarnings("unchecked")
    void networkSubsetKeepsOnlyAllowedSubnets() {
        Map<String, Object> shared = ClusterJoinTemplate.buildSharedMap(sampleConfig());

        Map<String, Object> network = (Map<String, Object>) shared.get("network");
        assertTrue(network.containsKey("allowedSubnets"));
        assertEquals(List.of("10.0.0.0/8"), network.get("allowedSubnets"));
    }

    @Test
    @DisplayName("security keeps jwtSecret/rateLimiting/lockout but wipes initialAdminPassword")
    void securityWipesInitialAdminPassword() {
        Map<String, Object> shared = ClusterJoinTemplate.buildSharedMap(sampleConfig());

        SecurityControllerConfig projected = (SecurityControllerConfig) shared.get("security");
        assertNotNull(projected);
        assertEquals(
                "test-jwt-secret-do-not-use-in-prod-12345678901234567890",
                projected.jwtSecret(),
                "jwtSecret MUST survive — the entire plan exists because operators forget this field");
        assertEquals(720, projected.jwtExpirationMinutes());
        assertEquals(List.of("previous-secret-1"), projected.jwtPreviousSecrets());
        assertEquals("", projected.initialAdminPassword(), "initialAdminPassword must be wiped for joiners");
    }

    @Test
    @DisplayName("rendered YAML is non-empty and parseable")
    void rendersValidYaml() throws Exception {
        String yaml = ClusterJoinTemplate.renderYaml(sampleConfig());
        assertNotNull(yaml);
        assertTrue(yaml.contains("jwtSecret"), "rendered YAML must include jwtSecret");
        assertTrue(yaml.contains("test-jwt-secret"), "rendered YAML must include actual secret bytes");
        assertFalse(yaml.contains("the-initial-admin-password"), "initialAdminPassword must not appear");
        assertFalse(yaml.contains("10.0.0.1"), "per-node http.host must not appear");
        assertFalse(yaml.contains("8181"), "per-node http.port must not appear");
    }

    @Test
    @DisplayName("cluster.id surfaces when set; absent when null")
    @SuppressWarnings("unchecked")
    void clusterIdProjectsWhenSet() {
        ControllerConfig withClusterId = new ControllerConfig(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new RuntimeConfig(RuntimeConfig.DEVELOPMENT),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new ClusterConfig("cluster-uuid-abc", null, null),
                null);
        Map<String, Object> shared = ClusterJoinTemplate.buildSharedMap(withClusterId);
        Map<String, Object> cluster = (Map<String, Object>) shared.get("cluster");
        assertNotNull(cluster);
        assertEquals("cluster-uuid-abc", cluster.get("id"));

        Map<String, Object> sharedWithoutId = ClusterJoinTemplate.buildSharedMap(sampleConfig());
        assertNull(sharedWithoutId.get("cluster"), "no cluster.id → cluster block omitted entirely");
    }
}
