package me.prexorjustin.prexorcloud.controller.module.health;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import me.prexorjustin.prexorcloud.api.module.platform.ModuleHealth;

/**
 * Holds the latest health poll result per platform module (northstar-plan Track C.3 — Health-Checks).
 *
 * <p>The bootstrap drives a periodic poller that calls {@code PlatformModuleManager.pollHealth()}
 * and feeds the result into {@link #record}. This monitor is the read surface for the REST endpoint
 * and the {@code prexorcloud.module.health} metric. The stored map mirrors exactly the set of
 * modules in the most recent poll — a module that drops out of {@code ACTIVE} (uninstalled, failed,
 * stopped) is no longer polled and thus disappears from here on the next pass, so stale health for
 * a gone module is never reported.
 */
public final class ModuleHealthMonitor {

    /** Latest health observation for one module. */
    public record Snapshot(String moduleId, ModuleHealth.Status status, String detail, Instant checkedAt) {}

    private final Clock clock;
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    public ModuleHealthMonitor() {
        this(Clock.systemUTC());
    }

    public ModuleHealthMonitor(Clock clock) {
        this.clock = clock;
    }

    /**
     * Replace the stored health with the results of a fresh poll. {@code polled} is the full set of
     * active modules from this pass; any module previously stored but absent here is dropped.
     */
    public void record(Map<String, ModuleHealth> polled) {
        Instant now = clock.instant();
        snapshots.keySet().retainAll(polled.keySet());
        polled.forEach((moduleId, health) ->
                snapshots.put(moduleId, new Snapshot(moduleId, health.status(), health.detail(), now)));
    }

    public Optional<Snapshot> snapshot(String moduleId) {
        return Optional.ofNullable(snapshots.get(moduleId));
    }

    public Map<String, Snapshot> snapshots() {
        return new LinkedHashMap<>(snapshots);
    }

    /** Count of modules currently reporting the given status — backs the per-status metric gauge. */
    public long countByStatus(ModuleHealth.Status status) {
        return snapshots.values().stream()
                .filter(snapshot -> snapshot.status() == status)
                .count();
    }
}
