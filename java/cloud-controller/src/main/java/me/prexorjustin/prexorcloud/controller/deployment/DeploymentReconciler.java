package me.prexorjustin.prexorcloud.controller.deployment;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reconciles deployment rollouts independently from the main scheduler loop.
 */
public final class DeploymentReconciler {

    @FunctionalInterface
    public interface StopInstanceAction {
        boolean stop(String instanceId, boolean force);
    }

    @FunctionalInterface
    public interface StepGuard {
        boolean allow(String action);
    }

    /** Executes a real rollback of a failed deployment (restore previous config + re-deploy). */
    @FunctionalInterface
    public interface RollbackAction {
        void rollback(DeploymentRecord failed);
    }

    private static final Logger logger = LoggerFactory.getLogger(DeploymentReconciler.class);

    private final ClusterState clusterState;
    private final StateStore stateStore;
    private final EventBus eventBus;
    private final long evaluationIntervalSeconds;
    private final StopInstanceAction stopInstanceAction;
    // Triggered when auto-rollback fires. No-op by default so tests and the pre-wiring window are safe;
    // bootstrap injects the DeploymentRollbackService.
    private volatile RollbackAction rollbackAction = failed -> {};
    // Tracer for the deployment.reconcile span (Track D.2); no-op default until bootstrap injects.
    private io.opentelemetry.api.trace.Tracer tracer =
            io.opentelemetry.api.OpenTelemetry.noop().getTracer("prexorcloud-controller");

    public DeploymentReconciler(
            ClusterState clusterState,
            StateStore stateStore,
            EventBus eventBus,
            long evaluationIntervalSeconds,
            StopInstanceAction stopInstanceAction) {
        this.clusterState = clusterState;
        this.stateStore = stateStore;
        this.eventBus = eventBus;
        this.evaluationIntervalSeconds = evaluationIntervalSeconds;
        this.stopInstanceAction = stopInstanceAction;
    }

    /**
     * Execute a rolling restart for all RUNNING instances of a group. Stops each instance
     * sequentially, lets placement replace it, and persists deployment progress.
     */
    /** Swap in the real OpenTelemetry tracer (Track D.2). Null restores the no-op default. */
    public void setTracer(io.opentelemetry.api.trace.Tracer tracer) {
        this.tracer = tracer != null
                ? tracer
                : io.opentelemetry.api.OpenTelemetry.noop().getTracer("prexorcloud-controller");
    }

    /** Inject the rollback executor (bootstrap). Null leaves the no-op default. */
    public void setRollbackAction(RollbackAction rollbackAction) {
        if (rollbackAction != null) {
            this.rollbackAction = rollbackAction;
        }
    }

    public void rollingRestart(DeploymentRecord deployment) {
        rollingRestart(deployment, action -> true);
    }

    public void rollingRestart(DeploymentRecord deployment, StepGuard stepGuard) {
        me.prexorjustin.prexorcloud.controller.observability.telemetry.Spans.run(
                tracer, "deployment.reconcile", () -> doRollingRestart(deployment, stepGuard));
    }

    private void doRollingRestart(DeploymentRecord deployment, StepGuard stepGuard) {
        int total = deployment.totalInstances() > 0
                ? deployment.totalInstances()
                : clusterState.getInstancesByGroup(deployment.groupName()).size();
        var rolloutConfig = DeploymentRolloutConfig.fromConfigSnapshot(deployment.configSnapshot(), total);
        // A rollback deployment must not itself auto-roll-back: it restores known-good config, so if even
        // that fails we halt as FAILED for manual intervention rather than loop rollback -> rollback.
        boolean canAutoRollback = rolloutConfig.autoRollbackOnFailure() && !"rollback".equals(deployment.trigger());
        int updated = Math.max(
                deployment.updatedInstances(), countUpdatedInstances(deployment.groupName(), deployment.revision()));
        if (updated > deployment.updatedInstances()) {
            stateStore.updateDeploymentProgress(deployment.id(), updated);
        }

        String haltedState = null;
        boolean triggerRollback = false;
        while (updated < total) {
            var current = stateStore.getDeployment(deployment.groupName(), deployment.revision());
            if (current.isEmpty()) {
                return;
            }

            String currentState = current.get().state();
            if ("PAUSED".equals(currentState) || "ROLLED_BACK".equals(currentState)) {
                haltedState = currentState;
                break;
            }

            int waveSize = rolloutConfig.nextWaveSize(updated, total);
            if (waveSize <= 0) {
                break;
            }
            int waveTarget = Math.min(total, updated + waveSize);
            if (!stepGuard.allow("rolling restart for group " + deployment.groupName())) {
                return;
            }

            while (updated < waveTarget) {
                var nextInstance = selectNextOutdatedInstance(deployment.groupName(), deployment.revision());
                if (nextInstance.isEmpty()) {
                    updated = Math.max(updated, countUpdatedInstances(deployment.groupName(), deployment.revision()));
                    break;
                }

                if (!stopInstanceAction.stop(nextInstance.get().id(), false)) {
                    logger.warn(
                            "Rolling restart for group {} cannot stop {} right now; leaving deployment in progress",
                            deployment.groupName(),
                            nextInstance.get().id());
                    return;
                }

                updated++;
                stateStore.updateDeploymentProgress(deployment.id(), updated);
            }

            ReplacementOutcome replacement = waitForReplacement(
                    deployment.groupName(), updated, deployment.revision(), rolloutConfig, stepGuard);
            if (replacement == ReplacementOutcome.RETRY) {
                return;
            }
            if (replacement == ReplacementOutcome.FAILED) {
                // The wave's replacement crashed or was never scheduled (e.g. no capacity). Halt rather than
                // stop more instances into an outage — the old "continue anyway" was the footgun.
                haltedState = canAutoRollback ? "ROLLED_BACK" : "FAILED";
                triggerRollback = canAutoRollback;
                break;
            }
            if (rolloutConfig.healthGateEnabled()
                    && !waitForHealthyUpdatedInstances(
                            deployment.groupName(), updated, deployment.revision(), rolloutConfig, stepGuard)) {
                haltedState = canAutoRollback ? "ROLLED_BACK" : "FAILED";
                triggerRollback = canAutoRollback;
                break;
            }
        }

        String finalState = haltedState != null ? haltedState : "COMPLETED";
        stateStore.updateDeploymentState(deployment.id(), finalState);
        logger.info(
                "Rolling restart for group {} {} ({}/{} instances restarted)",
                deployment.groupName(),
                finalState.toLowerCase(),
                updated,
                total);
        if (triggerRollback) {
            rollbackAction.rollback(deployment);
        }
    }

    private Optional<InstanceInfo> selectNextOutdatedInstance(String groupName, int revision) {
        return clusterState.getInstancesByGroup(groupName).stream()
                .filter(instance -> instance.state() == InstanceState.RUNNING)
                .filter(instance -> instance.deploymentRevision() < revision)
                .findFirst();
    }

    private int countUpdatedInstances(String groupName, int revision) {
        return (int) clusterState.getInstancesByGroup(groupName).stream()
                .filter(instance -> instance.deploymentRevision() >= revision)
                .filter(instance -> instance.state() != InstanceState.STOPPED)
                .filter(instance -> instance.state() != InstanceState.CRASHED)
                .count();
    }

    private int countHealthyUpdatedInstances(String groupName, int revision) {
        return (int) clusterState.getInstancesByGroup(groupName).stream()
                .filter(instance -> instance.deploymentRevision() >= revision)
                .filter(instance -> instance.state() == InstanceState.RUNNING)
                .count();
    }

    private int countStableHealthyUpdatedInstances(String groupName, int revision, long minHealthySeconds) {
        long minUptimeMs = TimeUnit.SECONDS.toMillis(Math.max(0L, minHealthySeconds));
        return (int) clusterState.getInstancesByGroup(groupName).stream()
                .filter(instance -> instance.deploymentRevision() >= revision)
                .filter(instance -> instance.state() == InstanceState.RUNNING)
                .filter(instance -> instance.uptimeMs() >= minUptimeMs)
                .count();
    }

    private int countFailedUpdatedInstances(String groupName, int revision) {
        return (int) clusterState.getInstancesByGroup(groupName).stream()
                .filter(instance -> instance.deploymentRevision() >= revision)
                .filter(instance -> instance.state() == InstanceState.CRASHED)
                .count();
    }

    /** Outcome of waiting for a wave's stopped instances to be replaced. */
    private enum ReplacementOutcome {
        /** Replacements reached the new revision — proceed to the health gate / next wave. */
        READY,
        /** Step guard denied or the thread was interrupted — abandon this pass, retry on the next tick. */
        RETRY,
        /** A replacement crashed, or none was scheduled within the timeout — halt the rollout. */
        FAILED
    }

    private ReplacementOutcome waitForReplacement(
            String groupName,
            int expectedUpdated,
            int revision,
            DeploymentRolloutConfig rolloutConfig,
            StepGuard stepGuard) {
        long timeoutSeconds = rolloutConfig.replacementTimeoutSeconds() > 0
                ? rolloutConfig.replacementTimeoutSeconds()
                : evaluationIntervalSeconds * 2;
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadlineNanos) {
            if (!stepGuard.allow("wait for replacement in group " + groupName)) {
                return ReplacementOutcome.RETRY;
            }
            // A replacement that crashed on boot is a definitive wave failure — catch it here even when the
            // health gate is off, so a crash-looping new revision never slips through to the next wave.
            if (countFailedUpdatedInstances(groupName, revision) > 0) {
                logger.warn(
                        "Rolling restart: a replacement for group {} revision {} crashed before coming up",
                        groupName,
                        revision);
                return ReplacementOutcome.FAILED;
            }
            if (countUpdatedInstances(groupName, revision) >= expectedUpdated) {
                return ReplacementOutcome.READY;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return ReplacementOutcome.RETRY;
            }
        }
        logger.warn(
                "Rolling restart: no replacement reached revision {} for group {} within {}s — halting the rollout",
                revision,
                groupName,
                timeoutSeconds);
        return ReplacementOutcome.FAILED;
    }

    private boolean waitForHealthyUpdatedInstances(
            String groupName,
            int expectedUpdated,
            int revision,
            DeploymentRolloutConfig rolloutConfig,
            StepGuard stepGuard) {
        long timeoutSeconds = rolloutConfig.promotionTimeoutSeconds() > 0
                ? rolloutConfig.promotionTimeoutSeconds()
                : evaluationIntervalSeconds * 2;
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadlineNanos) {
            if (!stepGuard.allow("wait for healthy rollout wave in group " + groupName)) {
                return false;
            }
            if (countFailedUpdatedInstances(groupName, revision) > 0) {
                logger.warn(
                        "Rolling restart: rollout wave for group {} revision {} observed crashed updated instances",
                        groupName,
                        revision);
                return false;
            }
            int healthyUpdated = rolloutConfig.minHealthySeconds() > 0
                    ? countStableHealthyUpdatedInstances(groupName, revision, rolloutConfig.minHealthySeconds())
                    : countHealthyUpdatedInstances(groupName, revision);
            if (healthyUpdated >= expectedUpdated) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        logger.warn(
                "Rolling restart: rollout wave for group {} revision {} failed health gate after {}s (minHealthySeconds={})",
                groupName,
                revision,
                timeoutSeconds,
                rolloutConfig.minHealthySeconds());
        return false;
    }
}
