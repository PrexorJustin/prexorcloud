package me.prexorjustin.prexorcloud.controller.health;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import me.prexorjustin.prexorcloud.controller.PrexorController;

public final class ControllerReadinessProbe {

    public record Snapshot(String status, Map<String, Boolean> checks) {

        public boolean ready() {
            return checks.values().stream().allMatch(Boolean::booleanValue);
        }

        public int httpStatus() {
            return ready() ? 200 : 503;
        }
    }

    private final BooleanSupplier mongoReady;
    private final BooleanSupplier redisReady;
    private final BooleanSupplier schedulerReady;
    private final BooleanSupplier platformModulesReady;

    public ControllerReadinessProbe(
            BooleanSupplier mongoReady,
            BooleanSupplier redisReady,
            BooleanSupplier schedulerReady,
            BooleanSupplier platformModulesReady) {
        this.mongoReady = mongoReady;
        this.redisReady = redisReady;
        this.schedulerReady = schedulerReady;
        this.platformModulesReady = platformModulesReady;
    }

    public static ControllerReadinessProbe from(
            PrexorController controller, BooleanSupplier mongoReady, BooleanSupplier redisReady) {
        return new ControllerReadinessProbe(
                mongoReady,
                redisReady,
                controller::hasScheduler,
                () -> controller.moduleRegistry() != null
                        && controller.moduleRegistry().platformManager() != null);
    }

    public Snapshot snapshot() {
        Map<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("mongo", mongoReady.getAsBoolean());
        checks.put("redis", redisReady.getAsBoolean());
        checks.put("scheduler", schedulerReady.getAsBoolean());
        checks.put("platformModules", platformModulesReady.getAsBoolean());
        boolean ready = checks.values().stream().allMatch(Boolean::booleanValue);
        return new Snapshot(ready ? "READY" : "NOT_READY", Map.copyOf(checks));
    }

    public Map<String, Object> healthBody() {
        Snapshot snapshot = snapshot();
        return Map.of("status", "UP", "readiness", Map.of("status", snapshot.status(), "checks", snapshot.checks()));
    }

    public Map<String, Object> readinessBody() {
        Snapshot snapshot = snapshot();
        return Map.of("status", snapshot.status(), "checks", snapshot.checks());
    }
}
