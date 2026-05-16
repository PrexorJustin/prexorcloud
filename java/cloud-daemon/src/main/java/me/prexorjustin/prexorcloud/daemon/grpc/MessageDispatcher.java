package me.prexorjustin.prexorcloud.daemon.grpc;

import java.time.Instant;

import me.prexorjustin.prexorcloud.daemon.event.DaemonEventBus;
import me.prexorjustin.prexorcloud.daemon.module.DaemonModuleManager;
import me.prexorjustin.prexorcloud.daemon.process.InstanceFileReader;
import me.prexorjustin.prexorcloud.daemon.process.InstanceFileTreeWalker;
import me.prexorjustin.prexorcloud.daemon.process.ProcessManager;
import me.prexorjustin.prexorcloud.daemon.template.ArtifactCache;
import me.prexorjustin.prexorcloud.daemon.template.JarCache;
import me.prexorjustin.prexorcloud.daemon.template.PaperBootstrapCache;
import me.prexorjustin.prexorcloud.daemon.template.TemplateCache;
import me.prexorjustin.prexorcloud.protocol.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes incoming ControllerMessage variants to handler methods.
 */
public final class MessageDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(MessageDispatcher.class);

    private DaemonGrpcClient client;
    private ProcessManager processManager;
    private TemplateCache templateCache;
    private JarCache jarCache;
    private ArtifactCache artifactCache;
    private PaperBootstrapCache paperBootstrapCache;
    private DaemonModuleManager daemonModuleManager;
    private DaemonEventBus daemonEventBus;

    public void setClient(DaemonGrpcClient client) {
        this.client = client;
    }

    public void setProcessManager(ProcessManager processManager) {
        this.processManager = processManager;
    }

    public void setTemplateCache(TemplateCache templateCache) {
        this.templateCache = templateCache;
    }

    public void setCaches(JarCache jarCache, ArtifactCache artifactCache, PaperBootstrapCache paperBootstrapCache) {
        this.jarCache = jarCache;
        this.artifactCache = artifactCache;
        this.paperBootstrapCache = paperBootstrapCache;
    }

    public void setDaemonModuleManager(DaemonModuleManager daemonModuleManager) {
        this.daemonModuleManager = daemonModuleManager;
    }

    public void setDaemonEventBus(DaemonEventBus daemonEventBus) {
        this.daemonEventBus = daemonEventBus;
    }

    private Runnable shutdownCallback;

    public void setShutdownCallback(Runnable shutdownCallback) {
        this.shutdownCallback = shutdownCallback;
    }

    public void dispatch(ControllerMessage message) {
        switch (message.getPayloadCase()) {
            case HANDSHAKE_ACK -> handleHandshakeAck(message.getHandshakeAck());
            case PING -> handlePing(message.getPing());
            case START_INSTANCE -> handleStartInstance(message.getStartInstance());
            case STOP_INSTANCE -> handleStopInstance(message.getStopInstance());
            case SEND_COMMAND -> handleSendCommand(message.getSendCommand());
            case TEMPLATE_DATA -> handleTemplateData(message.getTemplateData());
            case TEMPLATE_UP_TO_DATE -> handleTemplateUpToDate(message.getTemplateUpToDate());
            case SHUTDOWN_NODE -> handleShutdownNode(message.getShutdownNode());
            case PRE_WARM_CACHE -> handlePreWarmCache(message.getPreWarmCache());
            case REQUEST_CACHE_STATUS -> handleRequestCacheStatus();
            case ERROR_REPORT -> {
                var err = message.getErrorReport();
                logger.warn(
                        "Error from controller: [{}] {} (context: {})",
                        err.getErrorCode(),
                        err.getErrorMessage(),
                        err.getContext());
            }
            case MODULE_INSTALL -> handleModuleInstall(message.getModuleInstall());
            case MODULE_UNINSTALL -> handleModuleUninstall(message.getModuleUninstall());
            case MODULE_EVENT -> handleModuleEvent(message.getModuleEvent());
            case WALK_INSTANCE_FILES -> handleWalkInstanceFiles(message.getWalkInstanceFiles());
            case READ_INSTANCE_FILE -> handleReadInstanceFile(message.getReadInstanceFile());
            default -> logger.warn("Unknown message type from controller: {}", message.getPayloadCase());
        }
    }

    private void handleWalkInstanceFiles(WalkInstanceFiles request) {
        if (processManager == null) {
            logger.warn("ProcessManager not initialized, cannot walk instance {}", request.getInstanceId());
            sendInstanceFileTree(InstanceFileTree.newBuilder()
                    .setRequestId(request.getRequestId())
                    .setError("DAEMON_NOT_READY")
                    .build());
            return;
        }
        Thread.startVirtualThread(() -> {
            InstanceFileTree reply = new InstanceFileTreeWalker(processManager.instancesDir()).walk(request);
            sendInstanceFileTree(reply);
        });
    }

    private void sendInstanceFileTree(InstanceFileTree reply) {
        if (client != null) {
            client.sendMessage(
                    DaemonMessage.newBuilder().setInstanceFileTree(reply).build());
        }
    }

    private void handleReadInstanceFile(ReadInstanceFile request) {
        if (processManager == null) {
            logger.warn(
                    "ProcessManager not initialized, cannot read instance file {}/{}",
                    request.getInstanceId(),
                    request.getPath());
            sendInstanceFileContent(InstanceFileContent.newBuilder()
                    .setRequestId(request.getRequestId())
                    .setError("DAEMON_NOT_READY")
                    .build());
            return;
        }
        Thread.startVirtualThread(() -> {
            InstanceFileContent reply = new InstanceFileReader(processManager.instancesDir()).read(request);
            sendInstanceFileContent(reply);
        });
    }

    private void sendInstanceFileContent(InstanceFileContent reply) {
        if (client != null) {
            client.sendMessage(
                    DaemonMessage.newBuilder().setInstanceFileContent(reply).build());
        }
    }

    private void handleHandshakeAck(HandshakeAck ack) {
        logger.info(
                "Handshake acknowledged. Session: {}, heartbeat interval: {}ms",
                ack.getSessionId(),
                ack.getHeartbeatIntervalMs());
        if (client != null) {
            if (ack.getControllerApiPort() > 0) {
                client.setControllerApiPort(ack.getControllerApiPort());
            }
            client.onHandshakeAckReceived();
        }
    }

    private void handlePing(Ping ping) {
        if (client != null) {
            client.sendPong(ping.getSequence());
        }
    }

    private void handleStartInstance(StartInstance start) {
        if (processManager == null) {
            logger.error("ProcessManager not initialized, cannot start instance {}", start.getInstanceId());
            sendStartAck(
                    start.getInstanceId(),
                    false,
                    "ProcessManager not initialized",
                    start.hasCompositionPlan() ? start.getCompositionPlan().getPlanHash() : "",
                    StartPreparationStage.VALIDATION,
                    "PROCESS_MANAGER_NOT_INITIALIZED",
                    StartFailureDisposition.PERMANENT,
                    0);
            return;
        }
        logger.info(
                "Starting instance: {} (group={}, port={}, memory={}MB)",
                start.getInstanceId(),
                start.getGroup(),
                start.getPort(),
                start.getMemoryMb());
        processManager.startInstance(
                start,
                result -> sendStartAck(
                        start.getInstanceId(),
                        result.accepted(),
                        result.errorMessage(),
                        result.planHash(),
                        result.stage(),
                        result.errorCode(),
                        result.failureDisposition(),
                        result.retryAfterSeconds()));
    }

    private void handleStopInstance(StopInstance stop) {
        if (processManager == null) {
            logger.error("ProcessManager not initialized, cannot stop instance {}", stop.getInstanceId());
            sendStopAck(stop.getInstanceId(), false, "ProcessManager not initialized");
            return;
        }
        logger.info("Stopping instance: {} (force={})", stop.getInstanceId(), stop.getForce());
        // Why: stopInstance may wait on the host process to drain (esp. force=false on Paper).
        // Off-load so the gRPC transport thread is free to dispatch the next message.
        Thread.startVirtualThread(() -> {
            try {
                processManager.stopInstance(stop.getInstanceId(), stop.getForce());
                sendStopAck(stop.getInstanceId(), true, "");
            } catch (Exception e) {
                logger.warn("stopInstance failed for {}: {}", stop.getInstanceId(), e.getMessage());
                sendStopAck(stop.getInstanceId(), false, e.getMessage() == null ? "stop failed" : e.getMessage());
            }
        });
    }

    private void sendStartAck(
            String instanceId,
            boolean accepted,
            String errorMessage,
            String planHash,
            StartPreparationStage stage,
            String errorCode,
            StartFailureDisposition failureDisposition,
            int retryAfterSeconds) {
        if (client != null) {
            client.sendMessage(DaemonMessage.newBuilder()
                    .setStartInstanceAck(StartInstanceAck.newBuilder()
                            .setInstanceId(instanceId)
                            .setAccepted(accepted)
                            .setErrorMessage(errorMessage)
                            .setPlanHash(planHash == null ? "" : planHash)
                            .setStage(stage == null ? StartPreparationStage.START_PREPARATION_STAGE_UNSPECIFIED : stage)
                            .setErrorCode(errorCode == null ? "" : errorCode)
                            .setFailureDisposition(
                                    failureDisposition == null
                                            ? StartFailureDisposition.START_FAILURE_DISPOSITION_UNSPECIFIED
                                            : failureDisposition)
                            .setRetryAfterSeconds(Math.max(0, retryAfterSeconds)))
                    .build());
        }
    }

    private void sendStopAck(String instanceId, boolean accepted, String errorMessage) {
        if (client != null) {
            client.sendMessage(DaemonMessage.newBuilder()
                    .setStopInstanceAck(StopInstanceAck.newBuilder()
                            .setInstanceId(instanceId)
                            .setAccepted(accepted)
                            .setErrorMessage(errorMessage))
                    .build());
        }
    }

    private void handleSendCommand(SendCommand cmd) {
        if (processManager == null) {
            logger.error("ProcessManager not initialized, cannot send command to {}", cmd.getInstanceId());
            return;
        }
        logger.debug("Sending command to {}: {}", cmd.getInstanceId(), cmd.getCommand());
        processManager.sendCommand(cmd.getInstanceId(), cmd.getCommand());
    }

    private void handleTemplateData(TemplateData data) {
        if (templateCache == null) {
            logger.warn("TemplateCache not initialized, discarding template data for {}", data.getTemplateName());
            return;
        }
        logger.debug(
                "Received template data: {} (hash={}, size={})",
                data.getTemplateName(),
                data.getHash(),
                data.getTarGz().size());
        // Why: onTemplateData writes the tarball to disk; for large templates this would
        // otherwise block the gRPC transport thread.
        Thread.startVirtualThread(() -> templateCache.onTemplateData(
                data.getTemplateName(), data.getHash(), data.getTarGz().toByteArray()));
    }

    private void handleTemplateUpToDate(TemplateUpToDate upToDate) {
        if (templateCache != null) {
            templateCache.onTemplateUpToDate(upToDate.getTemplateName());
        }
        logger.debug("Template up to date: {}", upToDate.getTemplateName());
    }

    private void handleShutdownNode(ShutdownNode shutdown) {
        logger.info("Shutdown requested by controller: {}", shutdown.getReason());
        if (shutdownCallback != null) {
            shutdownCallback.run();
        } else {
            logger.warn("No shutdown callback registered, exiting directly");
            System.exit(0);
        }
    }

    private void handlePreWarmCache(PreWarmCache preWarm) {
        if (jarCache == null || artifactCache == null || paperBootstrapCache == null) {
            logger.warn("Caches not initialized, ignoring pre-warm request");
            return;
        }

        logger.debug("Pre-warming caches for {} platform entries", preWarm.getEntriesCount());
        for (PreWarmEntry entry : preWarm.getEntriesList()) {
            Thread.startVirtualThread(() -> {
                try {
                    // Pre-download the server JAR
                    if (!entry.getPlatform().isBlank()
                            && !entry.getPlatformVersion().isBlank()
                            && !entry.getDownloadUrl().isBlank()
                            && !entry.getJarFile().isBlank()) {
                        var cachedJar = jarCache.resolve(
                                entry.getPlatform(),
                                entry.getPlatformVersion(),
                                entry.getJarFile(),
                                entry.getDownloadUrl(),
                                entry.getSha256().isBlank() ? null : entry.getSha256());

                        // Pre-warm bootstrap cache (generates patched JARs, libraries, CDS archive)
                        String configFormat = entry.getConfigFormat().name().toLowerCase();
                        if (paperBootstrapCache.supports(configFormat)) {
                            paperBootstrapCache.ensureWarmed(configFormat, entry.getPlatformVersion(), cachedJar);
                        }
                    }
                    logger.debug("Pre-warm complete for {}/{}", entry.getPlatform(), entry.getPlatformVersion());
                } catch (Exception e) {
                    logger.warn(
                            "Pre-warm failed for {}/{}: {}",
                            entry.getPlatform(),
                            entry.getPlatformVersion(),
                            e.getMessage());
                }
            });
        }
    }

    private void handleRequestCacheStatus() {
        Thread.startVirtualThread(() -> {
            var builder = CacheStatus.newBuilder();
            long total = 0;

            if (templateCache != null) {
                for (var e : templateCache.listEntries()) {
                    builder.addTemplates(TemplateCacheEntry.newBuilder()
                            .setName(e.name())
                            .setHash(e.hash())
                            .setSizeBytes(e.sizeBytes())
                            .setLastUsedMs(parseEpochMs(e.lastUsed())));
                    total += e.sizeBytes();
                }
            }

            if (jarCache != null) {
                for (var e : jarCache.listEntries()) {
                    builder.addJars(JarCacheEntry.newBuilder()
                            .setPlatform(e.platform())
                            .setVersion(e.version())
                            .setJarFile(e.jarFile())
                            .setSizeBytes(e.sizeBytes())
                            .setSha256(e.sha256())
                            .setCachedAtMs(parseEpochMs(e.cachedAt())));
                    total += e.sizeBytes();
                }
            }

            if (paperBootstrapCache != null) {
                for (var e : paperBootstrapCache.listEntries()) {
                    builder.addBootstraps(BootstrapCacheEntry.newBuilder()
                            .setConfigFormat(toConfigFormat(e.configFormat()))
                            .setVersion(e.version())
                            .setHasCds(e.hasCds())
                            .setSizeBytes(e.sizeBytes()));
                    total += e.sizeBytes();
                }
            }

            builder.setTotalSizeBytes(total);
            logger.debug(
                    "Reporting cache status: {} templates, {} jars, {} bootstraps, {} bytes total",
                    builder.getTemplatesCount(),
                    builder.getJarsCount(),
                    builder.getBootstrapsCount(),
                    total);

            if (client != null) {
                client.sendMessage(
                        DaemonMessage.newBuilder().setCacheStatus(builder).build());
            }
        });
    }

    private void handleModuleInstall(ModuleInstall install) {
        if (daemonModuleManager == null) {
            logger.warn("DaemonModuleManager not initialized, dropping ModuleInstall for {}", install.getModuleId());
            return;
        }
        logger.debug(
                "Received ModuleInstall: {} v{} (sha256={}, size={}B, upgrade={})",
                install.getModuleId(),
                install.getVersion(),
                install.getSha256(),
                install.getJarBytes().size(),
                install.getIsUpgrade());
        // Why: install writes the jar to disk, signature-verifies, opens a classloader, and runs
        // the module's onLoad/onStart hooks — all blocking. Off-load so the gRPC transport thread
        // can dispatch the next message and the module reports its state back asynchronously.
        Thread.startVirtualThread(() -> daemonModuleManager.install(install));
    }

    private void handleModuleUninstall(ModuleUninstall uninstall) {
        if (daemonModuleManager == null) {
            logger.warn(
                    "DaemonModuleManager not initialized, dropping ModuleUninstall for {}", uninstall.getModuleId());
            return;
        }
        logger.debug("Received ModuleUninstall: {}", uninstall.getModuleId());
        // Why: uninstall runs onStop/onUnload hooks; user-supplied module code, can block.
        Thread.startVirtualThread(() -> daemonModuleManager.uninstall(uninstall.getModuleId()));
    }

    private void handleModuleEvent(ModuleEvent event) {
        if (daemonEventBus == null) {
            // No subscribers — drop silently. Common during startup before the bus is wired.
            return;
        }
        daemonEventBus.publishFromController(
                event.getEventType(), event.getPayloadJson().toByteArray());
    }

    /**
     * Parses an ISO-8601 timestamp string to epoch milliseconds.
     */
    private static long parseEpochMs(String isoTimestamp) {
        if (isoTimestamp == null || isoTimestamp.isBlank()) return 0;
        try {
            return Instant.parse(isoTimestamp).toEpochMilli();
        } catch (Exception _) {
            return 0;
        }
    }

    /**
     * Converts a lowercase config format string to the protobuf
     * {@link ConfigFormat} enum.
     */
    private static ConfigFormat toConfigFormat(String format) {
        if (format == null) return ConfigFormat.CONFIG_FORMAT_UNSPECIFIED;
        return switch (format.toLowerCase()) {
            case "paper" -> ConfigFormat.PAPER;
            case "spigot" -> ConfigFormat.SPIGOT;
            case "velocity" -> ConfigFormat.VELOCITY;
            case "bungeecord" -> ConfigFormat.BUNGEECORD;
            default -> ConfigFormat.CONFIG_FORMAT_UNSPECIFIED;
        };
    }
}
