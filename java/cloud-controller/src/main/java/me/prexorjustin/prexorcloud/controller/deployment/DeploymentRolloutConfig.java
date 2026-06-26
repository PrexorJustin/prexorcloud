package me.prexorjustin.prexorcloud.controller.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parsed rollout settings stored inside a deployment config snapshot.
 */
public record DeploymentRolloutConfig(
        int batchSize,
        int canaryInstances,
        boolean healthGateEnabled,
        boolean autoRollbackOnFailure,
        long promotionTimeoutSeconds,
        long minHealthySeconds,
        // Max seconds to wait for a stopped instance's replacement to be (re)scheduled before treating the
        // wave as failed. 0 → fall back to the reconciler's interval-derived default. Seeded from the
        // group's startupTimeoutSeconds so a rollout that can't place a replacement (e.g. no capacity) halts
        // instead of stopping more instances into an outage.
        long replacementTimeoutSeconds,
        // Min acceptable 1-minute TPS for an updated instance once it has stabilised (past minHealthySeconds).
        // 0 → no TPS gate. An updated server that reports TPS below this is a regression and fails the health
        // gate (→ rollback when auto-rollback is on). 0-TPS reports (no data / not a game server) are ignored.
        double minHealthyTps) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public DeploymentRolloutConfig {
        batchSize = Math.max(1, batchSize);
        canaryInstances = Math.max(0, canaryInstances);
        promotionTimeoutSeconds = Math.max(0L, promotionTimeoutSeconds);
        minHealthySeconds = Math.max(0L, minHealthySeconds);
        replacementTimeoutSeconds = Math.max(0L, replacementTimeoutSeconds);
        minHealthyTps = Math.max(0.0, minHealthyTps);
    }

    public static DeploymentRolloutConfig defaults() {
        return new DeploymentRolloutConfig(1, 0, false, false, 0, 0, 0, 0.0);
    }

    public static DeploymentRolloutConfig fromConfigSnapshot(String configSnapshot, int totalInstances) {
        if (configSnapshot == null || configSnapshot.isBlank()) {
            return defaults();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(configSnapshot);
            int batchSize = positiveInt(root.get("batchSize"), 1);
            int canaryInstances = positiveInt(root.get("canaryInstances"), 0);
            if (canaryInstances == 0 && root.has("canaryPercent")) {
                canaryInstances =
                        resolveCanaryInstances(totalInstances, null, positiveInt(root.get("canaryPercent"), 0));
            }
            boolean healthGateEnabled = booleanValue(root.get("healthGateEnabled"), false);
            boolean autoRollbackOnFailure = booleanValue(root.get("autoRollbackOnFailure"), false);
            long promotionTimeoutSeconds = positiveLong(root.get("promotionTimeoutSeconds"), 0L);
            long minHealthySeconds = positiveLong(root.get("minHealthySeconds"), 0L);
            long replacementTimeoutSeconds = positiveLong(root.get("replacementTimeoutSeconds"), 0L);
            double minHealthyTps = positiveDouble(root.get("minHealthyTps"), 0.0);
            return new DeploymentRolloutConfig(
                    batchSize,
                    canaryInstances,
                    healthGateEnabled,
                    autoRollbackOnFailure,
                    promotionTimeoutSeconds,
                    minHealthySeconds,
                    replacementTimeoutSeconds,
                    minHealthyTps);
        } catch (Exception _) {
            return defaults();
        }
    }

    public int nextWaveSize(int updatedInstances, int totalInstances) {
        int remaining = Math.max(0, totalInstances - updatedInstances);
        if (remaining == 0) {
            return 0;
        }
        if (updatedInstances == 0 && canaryInstances > 0) {
            return Math.min(remaining, canaryInstances);
        }
        return Math.min(remaining, batchSize);
    }

    public static int resolveCanaryInstances(int totalInstances, Integer canaryInstances, Integer canaryPercent) {
        if (canaryInstances != null) {
            return Math.max(0, Math.min(totalInstances, canaryInstances));
        }
        if (canaryPercent == null || canaryPercent <= 0 || totalInstances <= 0) {
            return 0;
        }
        int resolved = (int) Math.ceil(totalInstances * (canaryPercent / 100.0d));
        return Math.max(1, Math.min(totalInstances, resolved));
    }

    private static int positiveInt(JsonNode node, int defaultValue) {
        if (node == null || !node.canConvertToInt()) {
            return defaultValue;
        }
        return Math.max(0, node.asInt(defaultValue));
    }

    private static long positiveLong(JsonNode node, long defaultValue) {
        if (node == null || !node.canConvertToLong()) {
            return defaultValue;
        }
        return Math.max(0L, node.asLong(defaultValue));
    }

    private static double positiveDouble(JsonNode node, double defaultValue) {
        if (node == null || !node.isNumber()) {
            return defaultValue;
        }
        return Math.max(0.0, node.asDouble(defaultValue));
    }

    private static boolean booleanValue(JsonNode node, boolean defaultValue) {
        return node == null ? defaultValue : node.asBoolean(defaultValue);
    }
}
