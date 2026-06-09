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

    private static final Logger logger = LoggerFactory.getLogger(DeploymentReconciler.class);

    private final ClusterState clusterState;
    private final StateStore stateStore;
    private final EventBus eventBus;
    private final long evaluationIntervalSeconds;
    private final StopInstanceAction stopInstanceAction;
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
        int updated = Math.max(
                deployment.updatedInstances(), countUpdatedInstances(deployment.groupName(), deployment.revision()));
        if (updated > deployment.updatedInstances()) {
            stateStore.updateDeploymentProgress(deployment.id(), updated);
        }

        String haltedState = null;
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

            if (!waitForReplacement(deployment.groupName(), updated, deployment.revision(), rolloutConfig, stepGuard)) {
                return;
            }
            if (rolloutConfig.healthGateEnabled()
                    && !waitForHealthyUpdatedInstances(
                            deployment.groupName(), updated, deployment.revision(), rolloutConfig, stepGuard)) {
                haltedState = rolloutConfig.autoRollbackOnFailure() ? "ROLLED_BACK" : "FAILED";
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

    private boolean waitForReplacement(
            String groupName,
            int expectedUpdated,
            int revision,
            DeploymentRolloutConfig rolloutConfig,
            StepGuard stepGuard) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(evaluationIntervalSeconds * 2);
        while (System.nanoTime() < deadlineNanos) {
            if (!stepGuard.allow("wait for replacement in group " + groupName)) {
                return false;
            }
            if (countUpdatedInstances(groupName, revision) >= expectedUpdated) {
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
                "Rolling restart: no replacement reached revision {} for group {} within {}s — continuing anyway",
                revision,
                groupName,
                evaluationIntervalSeconds * 2);
        return true;
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
