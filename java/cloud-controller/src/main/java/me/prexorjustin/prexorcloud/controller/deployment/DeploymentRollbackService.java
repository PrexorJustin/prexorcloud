package me.prexorjustin.prexorcloud.controller.deployment;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

import me.prexorjustin.prexorcloud.api.event.events.GroupUpdatedEvent;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.group.GroupStore;
import me.prexorjustin.prexorcloud.controller.group.MongoGroupStore;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a real deployment rollback: restore the group to the most recent COMPLETED deployment's config
 * snapshot, then roll the group's instances onto it as a fresh deployment (linked via {@code rollbackOf}).
 *
 * <p>Before this, both the manual rollback endpoint and the reconciler's auto-rollback only relabelled the
 * deployment record {@code ROLLED_BACK} while the bad config stayed live — so "auto-rollback on failure"
 * reverted nothing. This is the piece that makes it actually revert.
 */
public final class DeploymentRollbackService {

    private static final Logger logger = LoggerFactory.getLogger(DeploymentRollbackService.class);

    private final StateStore stateStore;
    private final GroupManager groupManager;
    private final GroupStore groupStore;
    private final ClusterState clusterState;
    private final EventBus eventBus;
    // Re-runs the rolling restart for the new rollback deployment. Injected so the service doesn't depend on
    // the Scheduler directly and so the re-deploy runs off the caller's thread (the reconciler's, on auto-rollback).
    private final Consumer<DeploymentRecord> redeploy;

    public DeploymentRollbackService(
            StateStore stateStore,
            GroupManager groupManager,
            GroupStore groupStore,
            ClusterState clusterState,
            EventBus eventBus,
            Consumer<DeploymentRecord> redeploy) {
        this.stateStore = stateStore;
        this.groupManager = groupManager;
        this.groupStore = groupStore;
        this.clusterState = clusterState;
        this.eventBus = eventBus;
        this.redeploy = redeploy;
    }

    /**
     * Roll {@code failed}'s group back to the most recent COMPLETED deployment that carries a config
     * snapshot. Returns {@code false} (changing nothing) when there is no prior good revision to restore —
     * the caller has already marked {@code failed} ROLLED_BACK, so the group stays on the current config
     * for manual intervention.
     */
    public boolean rollback(DeploymentRecord failed) {
        Optional<DeploymentRecord> target = stateStore.getDeployments(failed.groupName(), 50, 0).stream()
                .filter(d -> "COMPLETED".equals(d.state()))
                .filter(d -> d.revision() != failed.revision())
                .filter(d -> d.groupSnapshot() != null && !d.groupSnapshot().isBlank())
                .findFirst();
        if (target.isEmpty()) {
            logger.warn(
                    "Cannot roll back group {} (failed revision {}): no prior COMPLETED deployment with a config "
                            + "snapshot to restore",
                    failed.groupName(),
                    failed.revision());
            return false;
        }
        DeploymentRecord good = target.get();

        GroupConfig restored;
        try {
            restored = MongoGroupStore.fromJson(good.groupSnapshot());
        } catch (RuntimeException e) {
            logger.error(
                    "Rollback of group {} aborted: could not parse the revision {} config snapshot: {}",
                    failed.groupName(),
                    good.revision(),
                    e.getMessage());
            return false;
        }

        try {
            // Restore the group config the same way a normal update does (in-memory + Mongo + EventBus signal),
            // so caches/proxies refresh; the rolling restart below then converges instances onto it.
            groupManager.update(restored);
            groupStore.save(restored);
            eventBus.publish(new GroupUpdatedEvent(restored.name()));
        } catch (Exception e) {
            logger.error(
                    "Rollback of group {} aborted: could not restore the revision {} config: {}",
                    failed.groupName(),
                    good.revision(),
                    e.getMessage());
            return false;
        }

        var recent = stateStore.getDeployments(failed.groupName(), 1, 0);
        int nextRevision =
                recent.isEmpty() ? failed.revision() + 1 : recent.getFirst().revision() + 1;
        int runningCount = (int) clusterState.getInstancesByGroup(failed.groupName()).stream()
                .filter(i -> i.state() == InstanceState.RUNNING)
                .count();
        var rollbackDeployment = new DeploymentRecord(
                0,
                failed.groupName(),
                nextRevision,
                "rollback",
                good.strategy(),
                "IN_PROGRESS",
                good.templateSnapshot(),
                good.configSnapshot(),
                runningCount,
                0,
                Instant.now().toString(),
                null,
                failed.revision(),
                good.groupSnapshot());
        var saved = stateStore.createDeployment(rollbackDeployment);
        logger.info(
                "Rolling back group {}: restored the config of revision {} and dispatched rollback deployment r{} "
                        + "(rollbackOf={})",
                failed.groupName(),
                good.revision(),
                saved.revision(),
                failed.revision());
        redeploy.accept(saved);
        return true;
    }
}
