package me.prexorjustin.prexorcloud.controller.grpc;

import me.prexorjustin.prexorcloud.api.event.events.InstanceCrashedEvent;
import me.prexorjustin.prexorcloud.common.util.InputValidator;
import me.prexorjustin.prexorcloud.controller.crash.CrashClassifier;
import me.prexorjustin.prexorcloud.controller.crash.CrashLoopDetector;
import me.prexorjustin.prexorcloud.controller.crash.CrashStore;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.protocol.CrashReport;
import me.prexorjustin.prexorcloud.protocol.ErrorReport;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Daemon-reported failure handlers: CrashReport (instance died) and
 * ErrorReport (daemon-side operational issue). Extracted from
 * {@code DaemonServiceImpl}'s connect-stream handler.
 */
final class DaemonCrashEventReceiver {

    private static final Logger logger = LoggerFactory.getLogger(DaemonCrashEventReceiver.class);

    private final ClusterState clusterState;
    private final CrashStore crashStore;
    private final CrashLoopDetector crashLoopDetector;
    private final EventBus eventBus;

    DaemonCrashEventReceiver(
            ClusterState clusterState, CrashStore crashStore, CrashLoopDetector crashLoopDetector, EventBus eventBus) {
        this.clusterState = clusterState;
        this.crashStore = crashStore;
        this.crashLoopDetector = crashLoopDetector;
        this.eventBus = eventBus;
    }

    void handleCrashReport(String nodeId, CrashReport report) {
        try {
            InputValidator.requireSafeName(report.getInstanceId(), "instanceId");
            InputValidator.requireSafeName(report.getGroup(), "group");
            InputValidator.requireNonNegativeLong(report.getUptimeMs(), "uptimeMs");
            if (report.getLogTailCount() > 500) {
                throw new IllegalArgumentException("logTail exceeds 500 entries: " + report.getLogTailCount());
            }
            for (String line : report.getLogTailList()) {
                InputValidator.requireMaxLength(line, 8192, "logTailLine");
            }
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid handleCrashReport from node {}: {}", nodeId, e.getMessage());
            return;
        }
        if (!verifyNodeOwnership(report.getInstanceId(), nodeId, "handleCrashReport")) return;

        String classification = CrashClassifier.classify(report.getExitCode(), report.getLogTailList());
        clusterState.updateInstanceState(report.getInstanceId(), InstanceState.CRASHED);

        crashStore.add(
                report.getInstanceId(),
                report.getGroup(),
                nodeId,
                report.getExitCode(),
                classification,
                report.getLogTailList(),
                report.getUptimeMs());
        crashLoopDetector.recordCrash(report.getGroup());

        eventBus.publish(new InstanceCrashedEvent(
                report.getInstanceId(),
                report.getGroup(),
                nodeId,
                report.getExitCode(),
                classification,
                report.getLogTailList(),
                report.getUptimeMs()));

        logger.warn(
                "Instance {} crashed on node {} (exit={}, classification={})",
                report.getInstanceId(),
                nodeId,
                report.getExitCode(),
                classification);
    }

    void handleErrorReport(String nodeId, ErrorReport err) {
        try {
            InputValidator.requireMaxLength(err.getErrorCode(), 64, "errorCode");
            InputValidator.requireMaxLength(err.getErrorMessage(), 1024, "errorMessage");
            InputValidator.requireMaxLength(err.getContext(), 256, "errorContext");
            InputValidator.requireNonNegative(err.getRetryAfterSeconds(), "retryAfterSeconds");
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid error report from node {}: {}", nodeId, e.getMessage());
            return;
        }
        logger.warn(
                "Error report from node {}: [{}] {} (context: {}, retry_after: {}s)",
                nodeId,
                err.getErrorCode(),
                err.getErrorMessage(),
                err.getContext(),
                err.getRetryAfterSeconds());
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
}
