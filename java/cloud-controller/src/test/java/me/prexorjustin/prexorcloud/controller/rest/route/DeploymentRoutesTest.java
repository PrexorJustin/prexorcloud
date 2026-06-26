package me.prexorjustin.prexorcloud.controller.rest.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import me.prexorjustin.prexorcloud.controller.deployment.DeploymentRecord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DeploymentRoutes")
class DeploymentRoutesTest {

    @Test
    @DisplayName("uses default deployment page size when parameters are absent")
    void usesDefaultPagination() {
        DeploymentRoutes.DeploymentPageRequest request = DeploymentRoutes.resolveDeploymentPageRequest(null, null, 100);

        assertEquals(1, request.page());
        assertEquals(50, request.pageSize());
        assertEquals(0, request.offset());
        assertEquals(50, request.limit());
    }

    @Test
    @DisplayName("derives deployment offset from page and pageSize")
    void usesExplicitPagination() {
        DeploymentRoutes.DeploymentPageRequest request = DeploymentRoutes.resolveDeploymentPageRequest(3, 20, 100);

        assertEquals(3, request.page());
        assertEquals(20, request.pageSize());
        assertEquals(40, request.offset());
        assertEquals(20, request.limit());
    }

    @Test
    @DisplayName("resolves rollout trigger options with canary percent")
    void resolvesTriggerOptionsWithCanaryPercent() {
        var options = DeploymentRoutes.resolveTriggerOptions(
                Map.of(
                        "strategy",
                        "ROLLING",
                        "batchSize",
                        3,
                        "canaryPercent",
                        25,
                        "healthGateEnabled",
                        true,
                        "autoRollbackOnFailure",
                        true,
                        "promotionTimeoutSeconds",
                        45,
                        "minHealthySeconds",
                        30),
                "ROLLING",
                8);

        assertEquals("ROLLING", options.strategy());
        assertEquals(3, options.batchSize());
        assertEquals(2, options.canaryInstances());
        assertEquals(25, options.canaryPercent());
        assertEquals(true, options.healthGateEnabled());
        assertEquals(true, options.autoRollbackOnFailure());
        assertEquals(45L, options.promotionTimeoutSeconds());
        assertEquals(30L, options.minHealthySeconds());
    }

    @Test
    @DisplayName("rejects ambiguous canary request options")
    void rejectsAmbiguousCanaryRequestOptions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DeploymentRoutes.resolveTriggerOptions(
                        Map.of("canaryInstances", 1, "canaryPercent", 25), "ROLLING", 6));
    }

    @Test
    @DisplayName("CANARY strategy fills safe defaults")
    void canaryStrategyAppliesSafeDefaults() {
        var options = DeploymentRoutes.resolveTriggerOptions(Map.of("strategy", "CANARY"), "ROLLING", 6);
        assertEquals("CANARY", options.strategy());
        assertEquals(1, options.canaryInstances());
        assertEquals(true, options.healthGateEnabled());
        assertEquals(true, options.autoRollbackOnFailure());
        assertEquals(30L, options.minHealthySeconds());
        assertEquals(18.0, options.minHealthyTps().doubleValue());
    }

    @Test
    @DisplayName("explicit options override CANARY defaults")
    void canaryStrategyRespectsExplicitOverrides() {
        var options = DeploymentRoutes.resolveTriggerOptions(
                Map.of("strategy", "CANARY", "autoRollbackOnFailure", false, "minHealthyTps", 15.0), "ROLLING", 6);
        assertEquals(false, options.autoRollbackOnFailure());
        assertEquals(15.0, options.minHealthyTps().doubleValue());
        assertEquals(true, options.healthGateEnabled());
    }

    @Test
    @DisplayName("builds config snapshot with rollout settings")
    void buildsConfigSnapshotWithRolloutSettings() {
        String snapshot = DeploymentRoutes.buildConfigSnapshot(
                "lobby", "ROLLING", 2, null, 25, true, true, 45L, 30L, 18.0, 120L, 8);

        assertTrue(snapshot.contains("\"group\":\"lobby\""));
        assertTrue(snapshot.contains("\"strategy\":\"ROLLING\""));
        assertTrue(snapshot.contains("\"batchSize\":2"));
        assertTrue(snapshot.contains("\"canaryInstances\":2"));
        assertTrue(snapshot.contains("\"canaryPercent\":25"));
        assertTrue(snapshot.contains("\"healthGateEnabled\":true"));
        assertTrue(snapshot.contains("\"autoRollbackOnFailure\":true"));
        assertTrue(snapshot.contains("\"promotionTimeoutSeconds\":45"));
        assertTrue(snapshot.contains("\"minHealthySeconds\":30"));
        assertTrue(snapshot.contains("\"minHealthyTps\":18.0"));
        assertTrue(snapshot.contains("\"replacementTimeoutSeconds\":120"));
    }

    @Test
    @DisplayName("maps deployment record to operator-friendly rollout json")
    void mapsDeploymentRecordToOperatorFriendlyRolloutJson() {
        var deployment = new DeploymentRecord(
                7,
                "lobby",
                3,
                "manual",
                "ROLLING",
                "IN_PROGRESS",
                "{}",
                DeploymentRoutes.buildConfigSnapshot(
                        "lobby", "ROLLING", 2, null, 25, true, true, 45L, 30L, 18.0, 120L, 8),
                8,
                2,
                "2026-04-25T12:00:00Z",
                null,
                null);

        var json = DeploymentRoutes.deploymentToJson(deployment);

        assertEquals("lobby", json.get("groupName"));
        assertEquals(2, json.get("updatedInstances"));
        var rollout = assertInstanceOf(Map.class, json.get("rollout"));
        assertEquals(2, rollout.get("batchSize"));
        assertEquals(2, rollout.get("canaryInstances"));
        assertEquals(true, rollout.get("healthGateEnabled"));
        assertEquals(true, rollout.get("autoRollbackOnFailure"));
        assertEquals(45L, rollout.get("promotionTimeoutSeconds"));
        assertEquals(30L, rollout.get("minHealthySeconds"));
    }
}
