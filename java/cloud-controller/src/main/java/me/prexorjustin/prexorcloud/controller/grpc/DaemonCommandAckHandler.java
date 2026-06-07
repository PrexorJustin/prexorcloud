package me.prexorjustin.prexorcloud.controller.grpc;

import java.util.function.Supplier;

import me.prexorjustin.prexorcloud.common.util.InputValidator;
import me.prexorjustin.prexorcloud.controller.scheduler.Scheduler;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.protocol.InstanceState;
import me.prexorjustin.prexorcloud.protocol.ShutdownNodeAck;
import me.prexorjustin.prexorcloud.protocol.StartFailureDisposition;
import me.prexorjustin.prexorcloud.protocol.StartInstanceAck;
import me.prexorjustin.prexorcloud.protocol.StopInstanceAck;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Daemon-to-controller ack handlers: StartInstance, StopInstance, ShutdownNode.
 * Extracted from {@code DaemonServiceImpl}'s connect-stream handler.
 *
 * <p>The {@link Scheduler} is provided as a {@link Supplier} because it's
 * attached after construction; reading it lazily preserves the original
 * volatile-field-snapshot semantics.
 */
final class DaemonCommandAckHandler {

    private static final Logger logger = LoggerFactory.getLogger(DaemonCommandAckHandler.class);

    private final ClusterState clusterState;
    private final Supplier<Scheduler> scheduler;

    DaemonCommandAckHandler(ClusterState clusterState, Supplier<Scheduler> scheduler) {
        this.clusterState = clusterState;
        this.scheduler = scheduler;
    }

    void handleStartInstanceAck(String nodeId, StartInstanceAck ack) {
        try {
            InputValidator.requireSafeName(ack.getInstanceId(), "instanceId");
            InputValidator.requireMaxLength(ack.getErrorMessage(), 512, "errorMessage");
            InputValidator.requireMaxLength(ack.getPlanHash(), 128, "planHash");
            InputValidator.requireMaxLength(ack.getErrorCode(), 128, "errorCode");
            InputValidator.requireNonNegative(ack.getRetryAfterSeconds(), "retryAfterSeconds");
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid start ack from node {}: {}", nodeId, e.getMessage());
            return;
        }
        if (!verifyNodeOwnership(ack.getInstanceId(), nodeId, "handleStartInstanceAck")) return;

        Scheduler sched = scheduler.get();
        if (ack.getAccepted()) {
            if (sched != null) {
                sched.clearStartRetryBudget(ack.getInstanceId());
            }
            logger.debug(
                    "Node {} accepted StartInstance for {} (stage={}, planHash={})",
                    nodeId,
                    ack.getInstanceId(),
                    ack.getStage(),
                    ack.getPlanHash());
            return;
        }

        if (isDuplicateStartAck(ack)) {
            if (sched != null) {
                sched.clearStartRetryBudget(ack.getInstanceId());
            }
            logger.info(
                    "Node {} reported duplicate StartInstance for {} at stage {} [{}]; treating as idempotent replay",
                    nodeId,
                    ack.getInstanceId(),
                    ack.getStage(),
                    ack.getErrorCode());
            return;
        }

        StartFailureDisposition disposition =
                ack.getFailureDisposition() == StartFailureDisposition.START_FAILURE_DISPOSITION_UNSPECIFIED
                        ? StartFailureDisposition.PERMANENT
                        : ack.getFailureDisposition();
        if (disposition == StartFailureDisposition.TRANSIENT
                && sched != null
                && sched.retryStart(
                        ack.getInstanceId(),
                        Math.max(1, ack.getRetryAfterSeconds()),
                        ack.getErrorCode().isBlank() ? ack.getStage().name() : ack.getErrorCode())) {
            clusterState.updateInstanceState(ack.getInstanceId(), InstanceState.SCHEDULED);
            logger.warn(
                    "Node {} transiently rejected StartInstance for {} at stage {} [{}]; retrying in {}s (planHash={}): {}",
                    nodeId,
                    ack.getInstanceId(),
                    ack.getStage(),
                    ack.getErrorCode(),
                    Math.max(1, ack.getRetryAfterSeconds()),
                    ack.getPlanHash(),
                    ack.getErrorMessage());
        } else {
            if (sched != null) {
                sched.clearStartRetryBudget(ack.getInstanceId());
            }
            clusterState.updateInstanceState(ack.getInstanceId(), InstanceState.CRASHED);
            logger.warn(
                    "Node {} rejected StartInstance for {} at stage {} [{}] disposition={} planHash={}: {}",
                    nodeId,
                    ack.getInstanceId(),
                    ack.getStage(),
                    ack.getErrorCode(),
                    disposition,
                    ack.getPlanHash(),
                    ack.getErrorMessage());
        }
    }

    void handleStopInstanceAck(String nodeId, StopInstanceAck ack) {
        try {
            InputValidator.requireSafeName(ack.getInstanceId(), "instanceId");
            InputValidator.requireMaxLength(ack.getErrorMessage(), 512, "errorMessage");
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid stop ack from node {}: {}", nodeId, e.getMessage());
            return;
        }
        if (!verifyNodeOwnership(ack.getInstanceId(), nodeId, "handleStopInstanceAck")) return;
        if (ack.getAccepted()) {
            logger.debug("Node {} accepted StopInstance for {}", nodeId, ack.getInstanceId());
        } else {
            logger.warn("Node {} rejected StopInstance for {}: {}", nodeId, ack.getInstanceId(), ack.getErrorMessage());
        }
    }

    void handleShutdownNodeAck(String nodeId, ShutdownNodeAck ack) {
        try {
            InputValidator.requireNonNegative(ack.getRunningInstances(), "runningInstances");
            InputValidator.requireNonNegative(ack.getEstimatedDrainSeconds(), "estimatedDrainSeconds");
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid shutdown ack from node {}: {}", nodeId, e.getMessage());
            return;
        }
        logger.debug(
                "Node {} acknowledged shutdown: {} instances running, estimated drain: {}s",
                nodeId,
                ack.getRunningInstances(),
                ack.getEstimatedDrainSeconds());
    }

    private boolean verifyNodeOwnership(String instanceId, String nodeId, String handlerName) {
        var instance = clusterState.getInstance(instanceId);
        if (instance.isEmpty()) {
            logger.warn("{}: unknown instance {}", handlerName, instanceId);
            return false;
        }
        if (!instance.get().nodeId().equals(nodeId)) {
            logger.warn(
                    "{}: instance {} belongs to node {}, not {}",
                    handlerName,
                    instanceId,
                    instance.get().nodeId(),
                    nodeId);
            return false;
        }
        return true;
    }

    private static boolean isDuplicateStartAck(StartInstanceAck ack) {
        return "INSTANCE_ALREADY_STARTING".equals(ack.getErrorCode())
                || "INSTANCE_ALREADY_RUNNING".equals(ack.getErrorCode());
    }
}
