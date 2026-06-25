package me.prexorjustin.prexorcloud.daemon.process;

import static me.prexorjustin.prexorcloud.daemon.process.prep.PreparationOps.isRetryablePreparationFailure;
import static me.prexorjustin.prexorcloud.daemon.process.prep.PreparationOps.stageFailure;
import static me.prexorjustin.prexorcloud.daemon.process.prep.PreparationOps.validateSafeName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.api.module.platform.ExitInfo;
import me.prexorjustin.prexorcloud.api.module.platform.InstanceHandle;
import me.prexorjustin.prexorcloud.api.module.platform.InstanceSpec;
import me.prexorjustin.prexorcloud.daemon.config.InstancesConfig;
import me.prexorjustin.prexorcloud.daemon.grpc.DaemonGrpcClient;
import me.prexorjustin.prexorcloud.daemon.module.DaemonModuleHost;
import me.prexorjustin.prexorcloud.daemon.process.prep.ArtifactProvisioner;
import me.prexorjustin.prexorcloud.daemon.process.prep.PreparationOperation;
import me.prexorjustin.prexorcloud.daemon.process.prep.PreparationOps;
import me.prexorjustin.prexorcloud.daemon.process.prep.ResolvedExtensionSpec;
import me.prexorjustin.prexorcloud.daemon.process.prep.ResolvedStartSpec;
import me.prexorjustin.prexorcloud.daemon.process.prep.StartPreparationException;
import me.prexorjustin.prexorcloud.daemon.process.prep.TemplatePreparation;
import me.prexorjustin.prexorcloud.daemon.process.prep.WorkspaceManager;
import me.prexorjustin.prexorcloud.daemon.process.prep.WorkspaceManager.PreparationWorkspace;
import me.prexorjustin.prexorcloud.daemon.template.ArtifactCache;
import me.prexorjustin.prexorcloud.daemon.template.JarCache;
import me.prexorjustin.prexorcloud.daemon.template.PaperBootstrapCache;
import me.prexorjustin.prexorcloud.daemon.template.ServerConfigPatcher;
import me.prexorjustin.prexorcloud.daemon.template.TemplateCache;
import me.prexorjustin.prexorcloud.protocol.CompositionPlan;
import me.prexorjustin.prexorcloud.protocol.ConfigPatch;
import me.prexorjustin.prexorcloud.protocol.RunningInstance;
import me.prexorjustin.prexorcloud.protocol.RuntimeArtifact;
import me.prexorjustin.prexorcloud.protocol.RuntimeIsolation;
import me.prexorjustin.prexorcloud.protocol.StartFailureDisposition;
import me.prexorjustin.prexorcloud.protocol.StartInstance;
import me.prexorjustin.prexorcloud.protocol.StartPreparationStage;
import me.prexorjustin.prexorcloud.protocol.TemplateRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages all server processes on this daemon node.
 */
public final class ProcessManager {

    private static final Logger logger = LoggerFactory.getLogger(ProcessManager.class);
    private static final long STOPPED_CLEANUP_DELAY_SECONDS = 5;
    private static final long CRASHED_CLEANUP_DELAY_SECONDS = 30;
    private static final int PREPARATION_RETRY_ATTEMPTS = 3;
    private static final long PREPARATION_RETRY_DELAY_MILLIS = 250;
    private static final int TRANSIENT_PREPARATION_RETRY_DELAY_SECONDS = 3;
    private static final int TRANSIENT_PROCESS_START_RETRY_DELAY_SECONDS = 5;

    private final Path instancesDir;
    private final InstancesConfig config;
    private final DaemonGrpcClient grpcClient;
    private final JarCache jarCache;
    private final ArtifactCache artifactCache;
    private final PaperBootstrapCache paperBootstrapCache;
    private final ArtifactProvisioner artifacts;
    private final TemplatePreparation templates;
    private final WorkspaceManager workspace;
    private final String nodeId;
    private volatile DaemonModuleHost moduleHost;
    private final Map<String, ServerProcess> processes = new ConcurrentHashMap<>();
    private final Set<String> startingInstances = ConcurrentHashMap.newKeySet();
    private final Set<String> staticInstances = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "instance-cleanup");
        t.setDaemon(true);
        return t;
    });

    public ProcessManager(
            Path instancesDir,
            InstancesConfig config,
            DaemonGrpcClient grpcClient,
            TemplateCache templateCache,
            JarCache jarCache,
            ArtifactCache artifactCache,
            PaperBootstrapCache paperBootstrapCache,
            String nodeId) {
        this.instancesDir = instancesDir;
        this.config = config;
        this.grpcClient = grpcClient;
        this.jarCache = jarCache;
        this.artifactCache = artifactCache;
        this.paperBootstrapCache = paperBootstrapCache;
        this.artifacts = new ArtifactProvisioner(jarCache, artifactCache, paperBootstrapCache);
        this.templates = new TemplatePreparation(templateCache, grpcClient, nodeId);
        this.workspace = new WorkspaceManager(instancesDir);
        this.nodeId = nodeId;
    }

    /**
     * Wires the daemon-host module dispatcher so platform-module {@code onInstanceStarting}
     * / {@code onInstanceStarted} / {@code onInstanceStopping} / {@code onInstanceStopped}
     * hooks fire around each instance lifecycle transition. Optional — when unset, instance
     * lifecycle proceeds without module dispatch (used by tests and pre-Layer-7 daemons).
     */
    public void setDaemonModuleHost(DaemonModuleHost moduleHost) {
        this.moduleHost = moduleHost;
    }

    /** Root directory under which {@code <group>/<instanceId>} working directories live. */
    public Path instancesDir() {
        return instancesDir;
    }

    /**
     * Start a new server instance.
     */
    public void startInstance(StartInstance start, java.util.function.Consumer<StartResult> completion) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(completion, "completion");
        String instanceId = start.getInstanceId();
        if (!start.hasCompositionPlan()) {
            completion.accept(new StartResult(
                    false,
                    "MISSING_COMPOSITION_PLAN",
                    "StartInstance for " + instanceId + " is missing a composition plan",
                    "",
                    StartPreparationStage.VALIDATION,
                    StartFailureDisposition.PERMANENT,
                    0));
            return;
        }
        String planHash = start.getCompositionPlan().getPlanHash();

        if (!startingInstances.add(instanceId)) {
            logger.warn("Instance {} already starting, ignoring duplicate", instanceId);
            completion.accept(new StartResult(
                    false,
                    "INSTANCE_ALREADY_STARTING",
                    "Instance " + instanceId + " is already starting",
                    planHash,
                    StartPreparationStage.VALIDATION,
                    StartFailureDisposition.PERMANENT,
                    0));
            return;
        }
        if (processes.containsKey(instanceId)) {
            startingInstances.remove(instanceId);
            logger.warn("Instance {} already exists, ignoring start", instanceId);
            completion.accept(new StartResult(
                    false,
                    "INSTANCE_ALREADY_RUNNING",
                    "Instance " + instanceId + " already exists",
                    planHash,
                    StartPreparationStage.VALIDATION,
                    StartFailureDisposition.PERMANENT,
                    0));
            return;
        }

        Thread.startVirtualThread(() -> {
            try {
                completion.accept(doStartInstance(start));
            } catch (StartPreparationException e) {
                logger.warn("Failed to prepare instance {} at stage {}: {}", instanceId, e.stage(), e.getMessage());
                var disposition = startFailureDisposition(e);
                completion.accept(new StartResult(
                        false,
                        e.errorCode(),
                        e.getMessage(),
                        e.planHash(),
                        e.stage(),
                        disposition,
                        recommendedRetryDelaySeconds(e.stage(), disposition)));
            } catch (Exception e) {
                logger.error("Failed to start instance {}: {}", instanceId, e.getMessage(), e);
                var disposition = startFailureDisposition(e);
                completion.accept(new StartResult(
                        false,
                        "START_FAILED",
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                        planHash,
                        StartPreparationStage.PROCESS_START,
                        disposition,
                        recommendedRetryDelaySeconds(StartPreparationStage.PROCESS_START, disposition)));
            } finally {
                startingInstances.remove(instanceId);
            }
        });
    }

    private StartResult doStartInstance(StartInstance start) throws Exception {
        ResolvedStartSpec spec = applyModuleStartingHooks(resolveSpec(start));
        String instanceId = spec.instanceId();
        String group = spec.group();

        validateSafeName(group, "group");
        validateSafeName(instanceId, "instance ID");

        Path groupDir = instancesDir.resolve(group);
        Path instanceDir = groupDir.resolve(instanceId);
        if (!instanceDir
                .toAbsolutePath()
                .normalize()
                .startsWith(instancesDir.toAbsolutePath().normalize())) {
            throw stageFailure(
                    StartPreparationStage.VALIDATION,
                    "PATH_TRAVERSAL",
                    spec.planHash(),
                    "Path traversal detected: " + instanceDir);
        }
        Path workingDir = null;
        Path backupDir = null;
        boolean promoted = false;
        try {
            PreparationWorkspace prepared =
                    workspace.prepareWorkspace(spec.staticInstance(), group, instanceId, instanceDir);
            workingDir = prepared.workingDir();
            backupDir = prepared.backupDir();
            if (!workingDir
                    .toAbsolutePath()
                    .normalize()
                    .startsWith(instancesDir.toAbsolutePath().normalize())) {
                throw stageFailure(
                        StartPreparationStage.VALIDATION,
                        "PATH_TRAVERSAL",
                        spec.planHash(),
                        "Path traversal detected: " + workingDir);
            }
            try (var scope = StructuredTaskScope.open()) {
                Path prepDir = workingDir;
                var templateTask = scope.fork(() -> {
                    templates.applyTemplates(spec, prepDir);
                    return null;
                });
                var runtimeTask = scope.fork(() -> resolveRuntimeArtifact(spec, prepDir));
                var extensionTask = scope.fork(() -> resolveExtensionArtifacts(spec));
                var bootstrapTask = scope.fork(() -> {
                    preWarmBootstrap(spec);
                    return null;
                });

                try {
                    scope.join();
                    templateTask.get();
                    Path preparedRuntime = runtimeTask.get();
                    List<Path> cachedExtensions = extensionTask.get();
                    bootstrapTask.get();

                    templates.applyVariableSubstitution(spec, prepDir);
                    installPreparedRuntime(preparedRuntime, prepDir.resolve(spec.jarFile()));
                    applyBootstrapCache(spec, preparedRuntime, prepDir);
                    templates.patchConfigs(spec, prepDir);
                    installExtensionArtifacts(spec, cachedExtensions, prepDir);
                } catch (StructuredTaskScope.FailedException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof StartPreparationException startPreparationException) {
                        throw startPreparationException;
                    }
                    if (cause instanceof Exception exception) {
                        throw exception;
                    }
                    throw e;
                }
            }

            WorkspaceManager.replaceDirectory(workingDir, instanceDir);
            workingDir = instanceDir;
            promoted = true;

            int shutdownGrace =
                    spec.shutdownGraceSeconds() > 0 ? spec.shutdownGraceSeconds() : config.shutdownTimeoutSeconds();
            var serverProcess = new ServerProcess(
                    instanceId,
                    group,
                    workingDir,
                    config.logRingBufferLines(),
                    config.maxConsoleOutputLinesPerSecond(),
                    grpcClient,
                    spec.port(),
                    spec.memoryMb(),
                    spec.cpuReservation(),
                    spec.diskReservationMb(),
                    spec.jvmArgs(),
                    spec.env(),
                    spec.jarFile(),
                    spec.category(),
                    spec.pluginToken(),
                    nodeId,
                    shutdownGrace,
                    config.killTimeoutSeconds(),
                    (crashed) -> onProcessExited(instanceId, group, crashed));

            serverProcess.start();
            if (spec.staticInstance()) {
                staticInstances.add(instanceId);
            }
            processes.put(instanceId, serverProcess);
            dispatchInstanceStarted(serverProcess);
            if (backupDir != null) {
                workspace.cleanupPreparationDirectory(backupDir, group, instanceId + "_previous");
            }
            logger.info(
                    "Instance {} started (group={}, port={}, static={}, templates={}, extensions={}, planHash={})",
                    instanceId,
                    group,
                    spec.port(),
                    spec.staticInstance(),
                    spec.templates().size(),
                    spec.extensions().size(),
                    abbreviatePlanHash(spec.planHash()));
            return new StartResult(
                    true,
                    "",
                    "",
                    spec.planHash(),
                    StartPreparationStage.PROCESS_START,
                    StartFailureDisposition.START_FAILURE_DISPOSITION_UNSPECIFIED,
                    0);
        } catch (Exception e) {
            workspace.cleanupPreparationDirectory(promoted ? instanceDir : workingDir, group, instanceId);
            if (backupDir != null) {
                workspace.restoreBackupDirectory(backupDir, instanceDir, group, instanceId);
            }
            throw e;
        }
    }

    public record StartResult(
            boolean accepted,
            String errorCode,
            String errorMessage,
            String planHash,
            StartPreparationStage stage,
            StartFailureDisposition failureDisposition,
            int retryAfterSeconds) {}

    private ResolvedStartSpec resolveSpec(StartInstance start) {
        if (!start.hasCompositionPlan()) {
            throw new IllegalStateException(
                    "StartInstance for " + start.getInstanceId() + " is missing a composition plan");
        }
        CompositionPlan plan = start.getCompositionPlan();
        RuntimeArtifact runtime = plan.hasRuntime() ? plan.getRuntime() : RuntimeArtifact.getDefaultInstance();
        RuntimeIsolation isolation = plan.hasIsolation() ? plan.getIsolation() : start.getIsolation();
        String jarFile = runtime.getJarFile();
        String runtimeDownloadUrl = runtime.getDownloadUrl();
        String runtimeSha256 = runtime.getSha256();
        String platform = runtime.getPlatform();
        String platformVersion = runtime.getPlatformVersion();
        String category = instanceCategoryName(runtime.getCategory());
        String configFormat = configFormatName(runtime.getConfigFormat());
        List<TemplateRef> templates = List.copyOf(plan.getTemplatesList());
        List<ResolvedExtensionSpec> extensions = plan.getExtensionsList().stream()
                .map(extension -> new ResolvedExtensionSpec(
                        extension.getModuleId(),
                        extension.getExtensionId(),
                        extension.getVariantId(),
                        extension.getFileName(),
                        extension.getDownloadUrl(),
                        extension.getSha256(),
                        extension.getInstallPath()))
                .toList();
        List<ServerConfigPatcher.ConfigPatch> configPatches = plan.getConfigPatchesList().stream()
                .map(ProcessManager::toConfigPatch)
                .toList();
        String planHash = plan.getPlanHash();

        return new ResolvedStartSpec(
                start.getInstanceId(),
                start.getGroup(),
                start.getPort(),
                start.getMemoryMb(),
                clampCpuReservation(isolation.getCpuReservation()),
                Math.max(0, isolation.getDiskReservationMb()),
                List.copyOf(start.getJvmArgsList()),
                Map.copyOf(start.getEnvMap()),
                jarFile,
                start.getPluginToken(),
                start.getStaticInstance(),
                List.copyOf(start.getProtectedPathsList()),
                start.getShutdownGraceSeconds(),
                start.getMaxPlayers(),
                category,
                configFormat,
                platform,
                platformVersion,
                runtimeDownloadUrl,
                runtimeSha256,
                templates,
                extensions,
                configPatches,
                planHash,
                Map.copyOf(start.getResolvedVariablesMap()));
    }

    private static double clampCpuReservation(double cpuReservation) {
        return Math.max(0.0, Math.min(1.0, cpuReservation));
    }

    private Path resolveRuntimeArtifact(ResolvedStartSpec spec, Path instanceDir) throws StartPreparationException {
        return artifacts.resolveRuntimeArtifact(spec, instanceDir);
    }

    private List<Path> resolveExtensionArtifacts(ResolvedStartSpec spec) throws StartPreparationException {
        return artifacts.resolveExtensionArtifacts(spec);
    }

    private void preWarmBootstrap(ResolvedStartSpec spec) {
        artifacts.preWarmBootstrap(spec);
    }

    private void installPreparedRuntime(Path preparedRuntime, Path jarPath) throws StartPreparationException {
        artifacts.installPreparedRuntime(preparedRuntime, jarPath);
    }

    private void applyBootstrapCache(ResolvedStartSpec spec, Path preparedRuntime, Path instanceDir) {
        artifacts.applyBootstrapCache(spec, preparedRuntime, instanceDir);
    }

    private void installExtensionArtifacts(ResolvedStartSpec spec, List<Path> cachedExtensions, Path instanceDir)
            throws StartPreparationException {
        artifacts.installExtensionArtifacts(spec, cachedExtensions, instanceDir);
    }

    private static String abbreviatePlanHash(String planHash) {
        return planHash == null || planHash.length() < 8 ? planHash : planHash.substring(0, 8);
    }

    private static String instanceCategoryName(me.prexorjustin.prexorcloud.protocol.InstanceCategory category) {
        return switch (category) {
            case PROXY -> "PROXY";
            case SERVER, INSTANCE_CATEGORY_UNSPECIFIED, UNRECOGNIZED -> "SERVER";
        };
    }

    private static String configFormatName(me.prexorjustin.prexorcloud.protocol.ConfigFormat format) {
        return switch (format) {
            case PAPER -> "paper";
            case SPIGOT -> "spigot";
            case VELOCITY -> "velocity";
            case BUNGEECORD -> "bungeecord";
            case GEYSER -> "geyser";
            case CONFIG_FORMAT_UNSPECIFIED, UNRECOGNIZED -> "";
        };
    }

    private static ServerConfigPatcher.ConfigPatch toConfigPatch(ConfigPatch patch) {
        return new ServerConfigPatcher.ConfigPatch(patch.getFile(), patch.getKey(), patch.getValue());
    }

    // Thin delegators kept on ProcessManager so ProcessManagerTest can keep
    // calling ProcessManager.replaceDirectory / deleteDirectoryTree /
    // copyDirectoryTree / stageStaticWorkspace by name. The real
    // implementations live in WorkspaceManager; do not duplicate logic here.
    static void replaceDirectory(Path sourceDir, Path targetDir) throws IOException {
        WorkspaceManager.replaceDirectory(sourceDir, targetDir);
    }

    static boolean deleteDirectoryTree(Path dir) throws IOException {
        return WorkspaceManager.deleteDirectoryTree(dir);
    }

    static void stageStaticWorkspace(Path instanceDir, Path workingDir, Path backupDir) throws IOException {
        WorkspaceManager.stageStaticWorkspace(instanceDir, workingDir, backupDir);
    }

    static void copyDirectoryTree(Path sourceDir, Path targetDir) throws IOException {
        WorkspaceManager.copyDirectoryTree(sourceDir, targetDir);
    }

    // withPreparationRetries + isRetryablePreparationFailure both live in
    // PreparationOps now (this package). The static imports at the top
    // make the unqualified calls in this file resolve to those. The two
    // thin wrappers below are kept for the test surface — ProcessManagerTest
    // calls them as ProcessManager.x and we don't want to perturb that.
    static <T> T withPreparationRetries(String operation, PreparationOperation<T> action) throws Exception {
        return PreparationOps.withPreparationRetries(operation, action);
    }

    static boolean isRetryablePreparationFailure(Throwable failure) {
        return PreparationOps.isRetryablePreparationFailure(failure);
    }

    private static StartFailureDisposition startFailureDisposition(Throwable failure) {
        return isRetryablePreparationFailure(failure)
                ? StartFailureDisposition.TRANSIENT
                : StartFailureDisposition.PERMANENT;
    }

    private static int recommendedRetryDelaySeconds(StartPreparationStage stage, StartFailureDisposition disposition) {
        if (disposition != StartFailureDisposition.TRANSIENT) {
            return 0;
        }
        return switch (stage) {
            case PROCESS_START -> TRANSIENT_PROCESS_START_RETRY_DELAY_SECONDS;
            case VALIDATION, START_PREPARATION_STAGE_UNSPECIFIED -> 0;
            default -> TRANSIENT_PREPARATION_RETRY_DELAY_SECONDS;
        };
    }

    // linkOrCopy, downloadToFile, stageFailure — all live in PreparationOps
    // now. The static imports at the top of this file make the unqualified
    // call sites in this file resolve to the PreparationOps versions
    // directly; no thin wrappers needed because nothing outside this file
    // (including tests) references those names on ProcessManager.

    /**
     * Stop a running instance.
     */
    public void stopInstance(String instanceId, boolean force) {
        ServerProcess process = processes.get(instanceId);
        if (process == null) {
            logger.warn("Instance {} not found for stop", instanceId);
            return;
        }
        dispatchInstanceStopping(process);
        process.stop(force);
    }

    /**
     * Send a command to a running instance's stdin.
     */
    public void sendCommand(String instanceId, String command) {
        ServerProcess process = processes.get(instanceId);
        if (process == null) {
            logger.warn("Instance {} not found for command", instanceId);
            return;
        }
        process.sendCommand(command);
    }

    public Optional<ServerProcess> getProcess(String instanceId) {
        return Optional.ofNullable(processes.get(instanceId));
    }

    public Set<String> getInstanceIds() {
        return Collections.unmodifiableSet(processes.keySet());
    }

    public int instanceCount() {
        return (int) processes.values().stream().filter(ServerProcess::isAlive).count();
    }

    public List<Integer> usedPorts() {
        return processes.values().stream()
                .filter(ServerProcess::isAlive)
                .map(ServerProcess::port)
                .toList();
    }

    /**
     * Stop all running instances concurrently using {@link StructuredTaskScope}
     * (JEP 505).
     */
    public void stopAll() {
        logger.info("Stopping all {} instances", processes.size());
        try (var scope = StructuredTaskScope.open()) {
            for (var entry : processes.entrySet()) {
                scope.fork(() -> {
                    try {
                        entry.getValue().stop(false);
                    } catch (Exception e) {
                        logger.warn("Error stopping instance {}: {}", entry.getKey(), e.getMessage());
                    }
                    return null;
                });
            }
            scope.join();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while stopping instances");
        }
    }

    /**
     * Called by {@link ServerProcess} when the process exits. Removes from the
     * process map and schedules directory cleanup for dynamic instances.
     */
    public void onProcessExited(String instanceId, String group, boolean crashed) {
        ServerProcess exited = processes.remove(instanceId);
        dispatchInstanceStopped(instanceId, group, exited, crashed);

        if (staticInstances.contains(instanceId)) {
            logger.info("Static instance {} exited -- preserving directory", instanceId);
            return;
        }

        long delay = crashed ? CRASHED_CLEANUP_DELAY_SECONDS : STOPPED_CLEANUP_DELAY_SECONDS;
        logger.debug("Scheduling directory cleanup for {} in {}s", instanceId, delay);
        cleanupExecutor.schedule(() -> deleteInstanceDir(group, instanceId), delay, TimeUnit.SECONDS);
    }

    private void deleteInstanceDir(String group, String instanceId) {
        deleteInstanceDir(group, instanceId, 0);
    }

    private static final int MAX_DELETE_RETRIES = 3;
    private static final long DELETE_RETRY_DELAY_SECONDS = 10;

    private void deleteInstanceDir(String group, String instanceId, int attempt) {
        // A crashed/stopped instance's directory cleanup is delayed. If the same instance id was
        // rescheduled onto this node in the meantime (crash-heal reuses the id and its directory),
        // the directory is now the working dir of a LIVE process — deleting it would yank the cwd out
        // from under the running server (world saves land on a dead inode, file access breaks). Skip.
        if (processes.containsKey(instanceId)) {
            logger.info("Skipping stale cleanup of {}/{} -- instance is running again", group, instanceId);
            return;
        }

        Path instanceDir = instancesDir.resolve(group).resolve(instanceId);
        if (!Files.isDirectory(instanceDir)) return;

        boolean allDeleted;
        try {
            allDeleted = WorkspaceManager.deleteDirectoryTree(instanceDir);
        } catch (IOException e) {
            logger.warn("Failed to walk instance directory {}/{}: {}", group, instanceId, e.getMessage());
            return;
        }

        if (allDeleted) {
            logger.debug("Deleted instance directory: {}/{}", group, instanceId);
        } else if (attempt < MAX_DELETE_RETRIES) {
            logger.debug(
                    "Some files in {}/{} still locked, retrying in {}s (attempt {}/{})",
                    group,
                    instanceId,
                    DELETE_RETRY_DELAY_SECONDS,
                    attempt + 1,
                    MAX_DELETE_RETRIES);
            cleanupExecutor.schedule(
                    () -> deleteInstanceDir(group, instanceId, attempt + 1),
                    DELETE_RETRY_DELAY_SECONDS,
                    TimeUnit.SECONDS);
        } else {
            // Move to quarantine so it doesn't accumulate silently
            try {
                Path target = workspace.quarantineDirectory(instanceDir, group + "_" + instanceId);
                logger.warn("Moved undeletable {}/{} to quarantine: {}", group, instanceId, target);
            } catch (IOException moveErr) {
                logger.error(
                        "Could not delete or quarantine {}/{} after {} attempts: {}",
                        group,
                        instanceId,
                        MAX_DELETE_RETRIES,
                        moveErr.getMessage());
            }
        }
    }

    /**
     * Returns the list of currently alive instances for the handshake message.
     */
    public List<RunningInstance> getRunningInstances() {
        return processes.values().stream()
                .filter(ServerProcess::isAlive)
                .map(p -> RunningInstance.newBuilder()
                        .setInstanceId(p.instanceId())
                        .setGroup(p.group())
                        .setPort(p.port())
                        .setState(p.state())
                        .build())
                .toList();
    }

    public void removeInstance(String instanceId) {
        processes.remove(instanceId);
        staticInstances.remove(instanceId);
    }

    // === Daemon-host platform-module instance-lifecycle dispatch (Layer 7) ===

    /**
     * Build a mutable {@link InstanceSpec} from {@code spec}, fan out
     * {@code onInstanceStarting} to every active daemon module, and read mutations of
     * {@code jvmArgs} / {@code env} back into a replacement {@link ResolvedStartSpec}.
     * Returns {@code spec} unchanged when no module host is wired or no mutations were
     * applied. Module exceptions are swallowed by {@link DaemonModuleHost}.
     */
    private ResolvedStartSpec applyModuleStartingHooks(ResolvedStartSpec spec) {
        DaemonModuleHost host = moduleHost;
        if (host == null) {
            return spec;
        }
        InstanceSpec instanceSpec = new InstanceSpec(
                spec.instanceId(),
                spec.group(),
                spec.port(),
                spec.memoryMb(),
                spec.jvmArgs(),
                spec.env(),
                spec.platform(),
                spec.platformVersion(),
                spec.jarFile(),
                spec.planHash());
        host.dispatchInstanceStarting(instanceSpec);
        // Always re-bind to the post-mutation lists; copying is cheap and avoids surprising
        // aliasing if a module retained references to the mutable views.
        return new ResolvedStartSpec(
                spec.instanceId(),
                spec.group(),
                spec.port(),
                spec.memoryMb(),
                spec.cpuReservation(),
                spec.diskReservationMb(),
                List.copyOf(instanceSpec.jvmArgs()),
                Map.copyOf(instanceSpec.env()),
                spec.jarFile(),
                spec.pluginToken(),
                spec.staticInstance(),
                spec.protectedPaths(),
                spec.shutdownGraceSeconds(),
                spec.maxPlayers(),
                spec.category(),
                spec.configFormat(),
                spec.platform(),
                spec.platformVersion(),
                spec.runtimeDownloadUrl(),
                spec.runtimeSha256(),
                spec.templates(),
                spec.extensions(),
                spec.configPatches(),
                spec.planHash(),
                spec.resolvedVariables());
    }

    private void dispatchInstanceStarted(ServerProcess process) {
        DaemonModuleHost host = moduleHost;
        if (host == null) return;
        try {
            host.dispatchInstanceStarted(handleFor(process));
        } catch (Exception e) {
            logger.debug("dispatchInstanceStarted threw for {}: {}", process.instanceId(), e.getMessage());
        }
    }

    private void dispatchInstanceStopping(ServerProcess process) {
        DaemonModuleHost host = moduleHost;
        if (host == null) return;
        try {
            host.dispatchInstanceStopping(handleFor(process));
        } catch (Exception e) {
            logger.debug("dispatchInstanceStopping threw for {}: {}", process.instanceId(), e.getMessage());
        }
    }

    private void dispatchInstanceStopped(String instanceId, String group, ServerProcess exited, boolean crashed) {
        DaemonModuleHost host = moduleHost;
        if (host == null) return;
        InstanceHandle handle;
        long durationMs;
        if (exited != null) {
            handle = handleFor(exited);
            durationMs = exited.uptimeMs();
        } else {
            handle = new InstanceHandle(
                    instanceId, group == null ? "" : group, 0, -1L, java.time.Instant.EPOCH, "STOPPED");
            durationMs = 0;
        }
        ExitInfo exit = new ExitInfo(0, durationMs, crashed, null);
        try {
            host.dispatchInstanceStopped(handle, exit);
        } catch (Exception e) {
            logger.debug("dispatchInstanceStopped threw for {}: {}", instanceId, e.getMessage());
        }
    }

    private static InstanceHandle handleFor(ServerProcess process) {
        long startedAtMs = process.startedAtMs();
        return new InstanceHandle(
                process.instanceId(),
                process.group(),
                process.port(),
                process.pid(),
                startedAtMs == 0 ? java.time.Instant.EPOCH : java.time.Instant.ofEpochMilli(startedAtMs),
                process.state() == null ? "" : process.state().name());
    }
}
