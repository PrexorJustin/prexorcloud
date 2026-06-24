package me.prexorjustin.prexorcloud.controller.scheduler;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.controller.state.ScaleActionStore;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates whether a group needs to scale up or down. Uses per-group
 * thresholds and timeouts from GroupConfig.
 */
public final class ScalingEvaluator {

    private static final Logger logger = LoggerFactory.getLogger(ScalingEvaluator.class);

    // TPS at/below which a running game server is treated as tick-starved (overloaded) and warrants
    // extra capacity regardless of player load. A constant for now; becomes a per-group ScalingPolicy
    // signal threshold once the v2 GroupSpec.scaling.signals are wired into the engine.
    private static final double TPS_DEGRADED_FLOOR = 18.0;

    private final ClusterState clusterState;
    private final long defaultCooldownSeconds;
    private final ScaleActionStore scaleActionStore;
    // In-memory fast path for the current leader; seeded lazily from the store (incl. a negative
    // EPOCH entry) so steady-state cooldown checks never hit Mongo.
    private final Map<String, Instant> lastScaleAction = new ConcurrentHashMap<>();

    public ScalingEvaluator(ClusterState clusterState, long defaultCooldownSeconds, ScaleActionStore scaleActionStore) {
        this.clusterState = clusterState;
        this.defaultCooldownSeconds = defaultCooldownSeconds;
        this.scaleActionStore = scaleActionStore;
    }

    /**
     * Check if a group needs more instances. Returns the number of instances to add
     * (0 if none needed).
     */
    public int evaluateScaleUp(GroupConfig group) {
        // Manual groups: never evaluate
        if ("MANUAL".equals(group.scalingMode())) return 0;

        List<InstanceInfo> instances = getActiveInstances(group.name());
        int currentCount = instances.size();

        // Always maintain minimum
        if (currentCount < group.minInstances()) {
            int needed = group.minInstances() - currentCount;
            logger.debug(
                    "Group {} below minimum: {} < {}, need {} more",
                    group.name(),
                    currentCount,
                    group.minInstances(),
                    needed);
            return needed;
        }

        // Static groups: maintain exactly minInstances, never scale beyond
        if ("STATIC".equals(group.scalingMode())) return 0;

        // Don't scale above max
        if (currentCount >= group.maxInstances()) return 0;

        // Check per-group cooldown
        long cooldown = group.scaleCooldownSeconds() > 0 ? group.scaleCooldownSeconds() : defaultCooldownSeconds;
        if (isOnCooldown(group.name(), cooldown)) return 0;

        // Dynamic scaling on AGGREGATE player load across the running fleet (Group/Template v2,
        // Phase 1). The old rule scaled only when *every* running instance was saturated -- a single
        // quiet instance suppressed scale-up while the rest overflowed -- and always added exactly one.
        // Now we trigger on mean utilisation and scale by N toward the target in one step.
        List<InstanceInfo> running = instances.stream()
                .filter(i -> i.state() == InstanceState.RUNNING)
                .toList();

        if (running.isEmpty()) return 0;

        double target = group.scaleUpThreshold();
        int maxPlayers = group.maxPlayers();
        int runningCount = running.size();
        long totalPlayers = running.stream().mapToLong(InstanceInfo::playerCount).sum();

        // Mean per-instance load across the running fleet = totalPlayers / (runningCount * maxPlayers).
        double utilization = (double) totalPlayers / ((long) runningCount * maxPlayers);
        boolean loadHigh = utilization >= target;

        // Tick health: a server starved below the TPS floor is overloaded even at moderate player
        // load -- add capacity so the proxy can route new players to a healthy instance. Only count
        // instances that actually report TPS (> 0); 0 means "no data yet / not a game server".
        boolean tpsDegraded = running.stream()
                .map(InstanceInfo::tps1m)
                .anyMatch(tps -> tps > 0.0 && tps < TPS_DEGRADED_FLOOR);

        if (!loadHigh && !tpsDegraded) return 0;

        int additional;
        if (loadHigh) {
            // Scale by N: add enough instances so the aggregate falls back to/under the target,
            // instead of a flat +1. desiredRunning is the count at which mean load sits at the target.
            int desiredRunning = (int) Math.ceil(totalPlayers / (target * maxPlayers));
            additional = Math.max(1, desiredRunning - runningCount);
        } else {
            // TPS relief only: one instance to shed load off the tick-starved server(s).
            additional = 1;
        }

        // Never exceed the ceiling, counting in-flight (active, not-yet-running) starts.
        int headroom = group.maxInstances() - currentCount;
        int toAdd = Math.min(additional, headroom);

        if (toAdd > 0) {
            logger.debug(
                    "Group {} scaling up by {} (load {}% vs target {}%, loadHigh={}, tpsDegraded={})",
                    group.name(),
                    toAdd,
                    (int) (utilization * 100),
                    (int) (target * 100),
                    loadHigh,
                    tpsDegraded);
            return toAdd;
        }

        return 0;
    }

    /**
     * Returns instance ID to scale down, or null if no scale-down is appropriate.
     */
    public String evaluateScaleDown(GroupConfig group) {
        // Static and Manual groups: never scale down
        if (!"DYNAMIC".equals(group.scalingMode())) return null;

        List<InstanceInfo> instances = getActiveInstances(group.name());
        int currentCount = instances.size();

        if (currentCount <= group.minInstances()) return null;

        long cooldown = group.scaleCooldownSeconds() > 0 ? group.scaleCooldownSeconds() : defaultCooldownSeconds;
        if (isOnCooldown(group.name(), cooldown)) return null;

        long emptyTimeout = group.scaleDownAfterSeconds();

        // Find a running instance with 0 players that's been empty long enough
        return instances.stream()
                .filter(i -> i.state() == InstanceState.RUNNING)
                .filter(i -> i.playerCount() == 0)
                .filter(i -> {
                    long uptimeSeconds = i.uptimeMs() / 1000;
                    return uptimeSeconds > emptyTimeout;
                })
                .map(InstanceInfo::id)
                .findFirst()
                .orElse(null);
    }

    public void recordScaleAction(String group, long cooldownSeconds) {
        // Only the leader runs the scheduler, so there is a single writer. The in-memory map is the
        // fast path; the store makes the cooldown survive a failover so a new leader does not scale a
        // group one step early.
        Instant now = Instant.now();
        lastScaleAction.put(group, now);
        scaleActionStore.recordScaleAction(group, now);
    }

    public void recordScaleAction(String group) {
        recordScaleAction(group, defaultCooldownSeconds);
    }

    private boolean isOnCooldown(String group, long cooldownSeconds) {
        Instant last = lastScaleAction.get(group);
        if (last == null) {
            // First check on this leader (e.g. after a failover): consult the store once and cache the
            // result -- including "none" as EPOCH -- so subsequent ticks stay on the in-memory path.
            last = scaleActionStore.getLastScaleAction(group).orElse(Instant.EPOCH);
            lastScaleAction.put(group, last);
        }
        return Instant.now().isBefore(last.plusSeconds(cooldownSeconds));
    }

    private List<InstanceInfo> getActiveInstances(String group) {
        return clusterState.getInstancesByGroup(group).stream()
                .filter(i -> i.state() != InstanceState.STOPPED
                        && i.state() != InstanceState.CRASHED
                        && i.state() != InstanceState.DRAINING)
                .toList();
    }
}
