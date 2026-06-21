package me.prexorjustin.prexorcloud.controller.scheduler;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates whether a group needs to scale up or down. Uses per-group
 * thresholds and timeouts from GroupConfig.
 */
public final class ScalingEvaluator {

    private static final Logger logger = LoggerFactory.getLogger(ScalingEvaluator.class);

    private final ClusterState clusterState;
    private final long defaultCooldownSeconds;
    private final Map<String, Instant> lastScaleAction = new ConcurrentHashMap<>();

    public ScalingEvaluator(ClusterState clusterState, long defaultCooldownSeconds) {
        this.clusterState = clusterState;
        this.defaultCooldownSeconds = defaultCooldownSeconds;
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

        // Dynamic scaling: if all running instances are above threshold capacity
        List<InstanceInfo> running = instances.stream()
                .filter(i -> i.state() == InstanceState.RUNNING)
                .toList();

        if (running.isEmpty()) return 0;

        double threshold = group.scaleUpThreshold();
        boolean allAboveThreshold = running.stream().allMatch(i -> {
            double ratio = (double) i.playerCount() / group.maxPlayers();
            return ratio >= threshold;
        });

        if (allAboveThreshold) {
            logger.debug(
                    "Group {} all instances above {}% capacity, scaling up", group.name(), (int) (threshold * 100));
            return 1;
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
        // Leader-memory cooldown: only the leader runs the scheduler, so the local map is
        // authoritative. A leadership change resets the window — at worst a group scales one
        // step early on the new leader, which the next evaluation pass corrects.
        lastScaleAction.put(group, Instant.now());
    }

    public void recordScaleAction(String group) {
        recordScaleAction(group, defaultCooldownSeconds);
    }

    private boolean isOnCooldown(String group, long cooldownSeconds) {
        Instant last = lastScaleAction.get(group);
        if (last == null) return false;
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
