package me.prexorjustin.prexorcloud.controller.grpc;

import me.prexorjustin.prexorcloud.api.event.events.InstanceCrashedEvent;
import me.prexorjustin.prexorcloud.common.util.InputValidator;
import me.prexorjustin.prexorcloud.controller.crash.CrashClassifier;
import me.prexorjustin.prexorcloud.controller.crash.CrashLoopDetector;
import me.prexorjustin.prexorcloud.controller.crash.CrashRecord;
import me.prexorjustin.prexorcloud.controller.crash.CrashStore;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
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
    private final StateStore stateStore;

    DaemonCrashEventReceiver(
            ClusterState clusterState,
            CrashStore crashStore,
            CrashLoopDetector crashLoopDetector,
            EventBus eventBus,
            StateStore stateStore) {
        this.clusterState = clusterState;
        this.crashStore = crashStore;
        this.crashLoopDetector = crashLoopDetector;
        this.eventBus = eventBus;
        this.stateStore = stateStore;
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

        CrashRecord record = crashStore.add(
                report.getInstanceId(),
                report.getGroup(),
                nodeId,
                report.getExitCode(),
                classification,
                report.getLogTailList(),
                report.getUptimeMs());
        // The in-memory ring buffer is only a hot cache; the REST/dashboard Crashes view reads from
        // the StateStore. Without this the crash is logged + detected but never shows up in
        // /api/v1/crashes (and is lost on restart / invisible to other HA controllers).
        try {
            stateStore.saveCrash(record);
        } catch (RuntimeException e) {
            logger.warn("Failed to persist crash {} to state store: {}", record.id(), e.getMessage());
        }
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
