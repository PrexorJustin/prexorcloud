package me.prexorjustin.prexorcloud.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import me.prexorjustin.prexorcloud.common.config.YamlConfigLoader;
import me.prexorjustin.prexorcloud.common.logging.LoggingSetup;
import me.prexorjustin.prexorcloud.common.util.ClassWarmup;
import me.prexorjustin.prexorcloud.common.util.FilePermissions;
import me.prexorjustin.prexorcloud.common.util.VersionInfo;
import me.prexorjustin.prexorcloud.controller.auth.AuthManager;
import me.prexorjustin.prexorcloud.controller.auth.MongoRoleStore;
import me.prexorjustin.prexorcloud.controller.auth.MongoUserStore;
import me.prexorjustin.prexorcloud.controller.auth.Role;
import me.prexorjustin.prexorcloud.controller.auth.passwordreset.InMemoryPasswordResetTokenStore;
import me.prexorjustin.prexorcloud.controller.auth.passwordreset.LogMailer;
import me.prexorjustin.prexorcloud.controller.auth.passwordreset.Mailer;
import me.prexorjustin.prexorcloud.controller.auth.passwordreset.PasswordResetManager;
import me.prexorjustin.prexorcloud.controller.auth.passwordreset.PasswordResetTokenStore;
import me.prexorjustin.prexorcloud.controller.auth.passwordreset.RedisPasswordResetTokenStore;
import me.prexorjustin.prexorcloud.controller.auth.passwordreset.SmtpMailer;
import me.prexorjustin.prexorcloud.controller.catalog.CatalogConfigLoader;
import me.prexorjustin.prexorcloud.controller.catalog.MongoCatalogStore;
import me.prexorjustin.prexorcloud.controller.cluster.ClusterControlService;
import me.prexorjustin.prexorcloud.controller.config.ClusterConfig;
import me.prexorjustin.prexorcloud.controller.config.ControllerConfig;
import me.prexorjustin.prexorcloud.controller.console.ConsoleBuffer;
import me.prexorjustin.prexorcloud.controller.crash.CrashLoopDetector;
import me.prexorjustin.prexorcloud.controller.crash.CrashStore;
import me.prexorjustin.prexorcloud.controller.deployment.DeploymentReconciler;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.group.MongoGroupStore;
import me.prexorjustin.prexorcloud.controller.grpc.*;
import me.prexorjustin.prexorcloud.controller.grpc.MtlsEnforcementInterceptor;
import me.prexorjustin.prexorcloud.controller.health.ControllerReadinessProbe;
import me.prexorjustin.prexorcloud.controller.lifecycle.InstanceLifecycleManager;
import me.prexorjustin.prexorcloud.controller.lifecycle.NodeDrainManager;
import me.prexorjustin.prexorcloud.controller.metrics.MetricsCollector;
import me.prexorjustin.prexorcloud.controller.module.ModuleRegistry;
import me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleManager;
import me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleStorageManager;
import me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleStore;
import me.prexorjustin.prexorcloud.controller.network.MongoNetworkStore;
import me.prexorjustin.prexorcloud.controller.network.NetworkManager;
import me.prexorjustin.prexorcloud.controller.redis.RedisConnection;
import me.prexorjustin.prexorcloud.controller.redis.RedisEventBridge;
import me.prexorjustin.prexorcloud.controller.rest.RestServer;
import me.prexorjustin.prexorcloud.controller.runtime.InMemoryRuntimeServices;
import me.prexorjustin.prexorcloud.controller.runtime.RedisRuntimeServices;
import me.prexorjustin.prexorcloud.controller.runtime.RuntimeServices;
import me.prexorjustin.prexorcloud.controller.scheduler.InstancePlacementCoordinator;
import me.prexorjustin.prexorcloud.controller.scheduler.NodeMessageDispatcher;
import me.prexorjustin.prexorcloud.controller.scheduler.RedisStartRetryWakeupQueue;
import me.prexorjustin.prexorcloud.controller.scheduler.ScalingEvaluator;
import me.prexorjustin.prexorcloud.controller.scheduler.Scheduler;
import me.prexorjustin.prexorcloud.controller.scheduler.StartRetryWakeupQueue;
import me.prexorjustin.prexorcloud.controller.scheduler.WeightedNodeSelector;
import me.prexorjustin.prexorcloud.controller.scheduler.composition.ClusterStateBedrockRemoteResolver;
import me.prexorjustin.prexorcloud.controller.scheduler.composition.InstanceCompositionPlanner;
import me.prexorjustin.prexorcloud.controller.security.CertificateRotationTask;
import me.prexorjustin.prexorcloud.controller.security.TlsMaterialWatcher;
import me.prexorjustin.prexorcloud.controller.session.HeartbeatTracker;
import me.prexorjustin.prexorcloud.controller.session.NodeSessionManager;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.MongoStateStore;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.controller.state.WorkflowStateStore;
import me.prexorjustin.prexorcloud.controller.template.BaseTemplateGenerator;
import me.prexorjustin.prexorcloud.controller.template.TemplateManager;
import me.prexorjustin.prexorcloud.controller.template.TemplateMerger;
import me.prexorjustin.prexorcloud.security.ca.CertificateAuthority;
import me.prexorjustin.prexorcloud.security.jwt.JwtManager;
import me.prexorjustin.prexorcloud.security.tls.ReloadableServerSslContext;
import me.prexorjustin.prexorcloud.security.token.FileJoinTokenStore;
import me.prexorjustin.prexorcloud.security.token.JoinTokenStore;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for PrexorCloud Controller.
 * <p>
 * Owns the application lifecycle: config loading, subsystem initialization,
 * shutdown hook registration, and the main entry point. Routes and gRPC
 * services never see this class — they receive a fully-initialized
 * {@link PrexorController} (pure service registry).
 */
public final class PrexorCloudBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(PrexorCloudBootstrap.class);

    private static final Path CONFIG_PATH = Path.of("config", "controller.yml");
    private static final String DEFAULT_CONFIG = "defaults/controller.yml";

    private static final Path SECURITY_DIR = Path.of("config", "security");
    private static final Path CA_KEYSTORE = SECURITY_DIR.resolve("ca.p12");
    private static final Path CA_PEM = SECURITY_DIR.resolve("ca.pem");
    private static final Path CA_PASSWORD_FILE = SECURITY_DIR.resolve(".ca-password");
    private static final Path SERVER_KEYSTORE = SECURITY_DIR.resolve("server.p12");
    private static final Path JOIN_TOKENS_FILE = SECURITY_DIR.resolve("join-tokens.json");
    private static final Path FORWARDING_SECRET_FILE = SECURITY_DIR.resolve("forwarding.secret");

    /**
     * Operator-installed wire token. Present means "this controller has not yet
     * joined the cluster — run the join flow on next boot." Deleted by the
     * bootstrap after the join completes; a half-failed join leaves it in place
     * so the operator can retry by restarting.
     */
    private static final Path PENDING_JOIN_TOKEN_FILE = SECURITY_DIR.resolve("pending-join-token");
    // Written by a graceful `cluster leave`; fences this controller from auto-rejoining (and
    // forming a rogue same-groupId group) on a systemd/Docker restart. Cleared on a real re-join.
    public static final Path LEFT_MARKER_FILE = SECURITY_DIR.resolve(".cluster-left");

    private static final Path CLUSTER_MATERIALS_DIR = SECURITY_DIR.resolve("cluster");

    private static final Path TEMPLATE_FILES_DIR = Path.of("templates");
    private static final Path GROUPS_DIR = Path.of("groups");

    // Mutable: initSecurity() may regenerate and persist the JWT secret.
    // Written only during single-threaded startup; volatile is not needed and
    // would mislead readers into thinking compound read-modify-write is safe.
    private ControllerConfig config;

    // Lifecycle fields held for the shutdown hook.
    private MongoClient mongoClient;
    private RuntimeServices runtime;
    private RedisEventBridge eventBridge;
    private MongoDatabase mongoDatabase;
    private GrpcServer grpcServer;
    private RestServer restServer;
    private me.prexorjustin.prexorcloud.modules.runtime.ModuleRouteRegistry moduleRouteRegistry;
    private ScheduledExecutorService heartbeatScheduler;
    private ScheduledExecutorService auditRotationExecutor;
    private ScheduledExecutorService platformModuleReconcileExecutor;
    private ScheduledExecutorService moduleHealthExecutor;
    private ScheduledExecutorService pendingRequestScheduler;
    private me.prexorjustin.prexorcloud.controller.grpc.PendingRequestRegistry pendingRequests;
    private InstanceLifecycleManager lifecycleManager;
    private NodeDrainManager drainManager;
    private ShutdownManager shutdownManager;
    private ClusterControlService clusterControlService;
    private me.prexorjustin.prexorcloud.controller.cluster.reload.ClusterConfigReloadCoordinator clusterConfigReload;
    private me.prexorjustin.prexorcloud.controller.module.resource.ModuleResourceTracker moduleResourceTracker;
    private me.prexorjustin.prexorcloud.controller.module.resource.ModuleQuotaEnforcer moduleQuotaEnforcer;
    private me.prexorjustin.prexorcloud.controller.module.health.ModuleHealthMonitor moduleHealthMonitor;
    private me.prexorjustin.prexorcloud.controller.observability.telemetry.Telemetry telemetry;
    private final me.prexorjustin.prexorcloud.controller.observability.telemetry.MongoCommandTracer mongoCommandTracer =
            new me.prexorjustin.prexorcloud.controller.observability.telemetry.MongoCommandTracer();

    public PrexorCloudBootstrap(ControllerConfig config) {
        this.config = config;
    }

    public PrexorController start() throws Exception {
        VersionInfo version = VersionInfo.get();
        logger.info("PrexorCloud Controller v{} (Java {})", version.version(), version.javaVersion());

        var store = initStorage();
        clusterControlService = new ClusterControlService(config, config.uuid());
        startClusterControlPlane();
        config = clusterControlService.effectiveConfig();
        logger.info("Cluster control plane online (cluster.id={})", clusterControlService.clusterId());
        if (clusterControlService.unsafeResetDetected()) {
            // Record the catastrophic single-survivor reset as an audit event (the runbook
            // promises this marker). The audit log lives in Mongo and survives the Raft wipe.
            store.audit(
                    "system",
                    "cluster.recovery.unsafe-reset",
                    "cluster",
                    clusterControlService.clusterId(),
                    "Single-survivor reset: Raft state was re-formed as a single-member cluster from a wiped"
                            + " dataDir under a retained cluster.id. Cluster CA, seed secret, and config history"
                            + " were regenerated; join tokens must be re-issued.",
                    null);
        }
        // Distributed tracing (Track D). Built here — after the effective cluster config resolves —
        // so the Redis client can be instrumented at connection time. No-op unless telemetry.enabled.
        telemetry = me.prexorjustin.prexorcloud.controller.observability.telemetry.Telemetry.create(config.telemetry());
        runtime = initRuntimeServices();
        var core = initCore(runtime.runtimeStore(), store);
        clusterControlService.attachEventBus(core.eventBus());
        var security = initSecurity();
        var auth = initAuth(security, store, runtime);
        var templates = initTemplates(core, store);
        var crash = initCrashDetection(core);
        var network = initNetworks(templates);
        var modules = initModuleManagers(templates);
        var obs = initObservability(core, templates, crash, modules);

        var controller = new PrexorController(config, core, security, auth, templates, crash, network, modules, obs);
        controller.setClusterControlPlane(clusterControlService.controlPlane());
        controller.setTelemetry(telemetry);
        if (controller.authManager() != null) {
            controller.authManager().setTracer(telemetry.tracer());
        }
        clusterControlService.attachTracer(telemetry.tracer());
        if (telemetry.isEnabled()) {
            mongoCommandTracer.attachTracer(telemetry.tracer());
        }
        var pasteClient = new me.prexorjustin.prexorcloud.controller.share.PasteClient(config.share());
        controller.setShareService(new me.prexorjustin.prexorcloud.controller.share.ShareService(
                config.share(),
                pasteClient,
                java.time.Clock.systemUTC(),
                controller.stateStore(),
                controller.metricsCollector()));
        initPasswordReset(controller, runtime);
        bootPlatformModules(controller, modules);

        if (controller.metricsCollector() != null && runtime instanceof RedisRuntimeServices redisRuntime) {
            redisRuntime.attachMetricsCollector(controller.metricsCollector());
        }

        wireRedisEventBridge(core, templates, network);
        this.pendingRequestScheduler = java.util.concurrent.Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "prexor-pending-request-timeouts");
            t.setDaemon(true);
            return t;
        });
        this.pendingRequests =
                new me.prexorjustin.prexorcloud.controller.grpc.PendingRequestRegistry(pendingRequestScheduler);
        var scheduler = initScheduler(controller, modules);
        controller.setScheduler(scheduler);
        initLifecycle(controller);
        var healingLeaseManager = runtime.newLeaseManager(config.scheduler().evaluationIntervalSeconds() * 2);
        lifecycleManager.attachHealingWorkflow(controller.workflowStateStore(), scheduler, healingLeaseManager);
        initGrpc(controller, security.caPassword());
        initRestServer(controller);
        initClusterConfigReload(controller);
        reconcileDurableWorkflows(controller);

        registerShutdownHooks(controller, modules);

        logger.info(
                "Controller ready - REST :{} | gRPC :{} | {} templates | {} groups",
                config.http().port(),
                config.grpc().port(),
                controller.templateManager().getAll().size(),
                controller.groupManager().getAll().size());

        return controller;
    }

    public void close() {
        if (shutdownManager != null) {
            shutdownManager.shutdown();
        }
    }

    private static final ObjectMapper REDIS_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Pick the cluster control-plane entry branch:
     *
     * <ul>
     *   <li>{@code pending-join-token} on disk → run {@code startInJoinMode} (Day-N).
     *       On success delete the token. On failure leave it in place so a
     *       restart retries automatically.</li>
     *   <li>{@code config/security/cluster/} already populated → restart with the
     *       persisted TLS material (Day-0 founder coming back, or post-join restart).</li>
     *   <li>Otherwise → Day-0 fresh bootstrap. The cluster control service mints
     *       its own CA + self leaf cert and persists them locally.</li>
     * </ul>
     *
     * <p>After a successful join the cluster id from the leader gets mirrored into
     * {@code controller.yml} via {@link #persistClusterIdentity} so subsequent
     * restarts hit the "yaml mismatch refusal" guard if the operator points the
     * controller at the wrong Raft state dir.
     */
    private void startClusterControlPlane() throws Exception {
        Files.createDirectories(SECURITY_DIR);
        FilePermissions.setOwnerOnly(SECURITY_DIR);
        var materials = new me.prexorjustin.prexorcloud.controller.cluster.LocalClusterMaterials(CLUSTER_MATERIALS_DIR);

        if (Files.isRegularFile(PENDING_JOIN_TOKEN_FILE)) {
            // The operator is (re)joining this controller to a cluster. Clear any stale
            // "gracefully left" fence so the join proceeds.
            Files.deleteIfExists(LEFT_MARKER_FILE);
            String token = Files.readString(PENDING_JOIN_TOKEN_FILE).trim();
            if (token.isEmpty()) {
                throw new IllegalStateException(PENDING_JOIN_TOKEN_FILE + " exists but is empty — "
                        + "delete it to run Day-0 bootstrap, or repopulate it with a wire token.");
            }
            String restAddr = config.http().host() + ":" + config.http().port();
            String grpcAddr = config.grpc().host() + ":" + config.grpc().port();
            String raftAddr = config.raft().host() + ":" + config.raft().port();
            var identity = new me.prexorjustin.prexorcloud.controller.cluster.ClusterControlService.JoinIdentity(
                    raftAddr, restAddr, grpcAddr);
            logger.info(
                    "Found pending join token at {} — joining cluster as {} (raft={}, rest={}, grpc={})",
                    PENDING_JOIN_TOKEN_FILE,
                    config.uuid(),
                    raftAddr,
                    restAddr,
                    grpcAddr);
            clusterControlService.startInJoinMode(token, materials, identity);
            // Persist the new cluster id mirror so the next boot's "yaml vs raft" guard fires.
            String joinedClusterId = clusterControlService.clusterId();
            if (joinedClusterId != null) {
                persistClusterIdentity(joinedClusterId);
            }
            Files.delete(PENDING_JOIN_TOKEN_FILE);
            logger.info("Cluster join complete — deleted {}", PENDING_JOIN_TOKEN_FILE);
            return;
        }

        // Fence against the leave-orphan split-brain: a controller that gracefully left the
        // cluster must NOT auto-rejoin on a systemd/Docker restart. Its persisted Raft state is
        // stale, and re-forming a group (same fixed groupId + clusterId) makes it a rogue peer
        // that corrupts the live cluster's Raft config. Refuse to start until an operator either
        // stages a join token (handled above — re-join) or explicitly re-bootstraps.
        if (Files.isRegularFile(LEFT_MARKER_FILE)) {
            String marker = Files.readString(LEFT_MARKER_FILE).trim();
            throw new IllegalStateException("This controller gracefully left its cluster (" + LEFT_MARKER_FILE
                    + ": " + marker + ") and will not rejoin automatically — auto-restarting it would resurrect"
                    + " stale Raft state and corrupt the live cluster. To rejoin, stage a join token at "
                    + PENDING_JOIN_TOKEN_FILE + ". To re-bootstrap a brand-new cluster on this host, delete "
                    + LEFT_MARKER_FILE + " and the " + CLUSTER_MATERIALS_DIR + " directory.");
        }

        clusterControlService.start(materials);
    }

    private RuntimeServices initRuntimeServices() {
        if (config.redis() == null) {
            // Production-profile installs are rejected by ConfigValidator before reaching here,
            // so this branch only fires in development. Make the divergence loud — silent
            // in-process fallbacks are exactly the kind of dev/prod skew that ships bugs.
            logger.warn("Running without Redis — coordination features (distributed rate-limit, SSE tickets,"
                    + " scheduler locks, event bridge) use in-process fallbacks. This is fine for"
                    + " single-controller development; set 'redis.uri' in controller.yml before"
                    + " running multiple controllers or load-testing against production behaviour.");
            return new InMemoryRuntimeServices();
        }
        // Instrument Redis only when telemetry is on; Lettuce is built without the adapter otherwise
        // and behaves exactly as before (Track D.1).
        io.lettuce.core.tracing.Tracing redisTracing = telemetry != null && telemetry.isEnabled()
                ? new me.prexorjustin.prexorcloud.controller.observability.telemetry.RedisTracing(telemetry.tracer())
                : null;
        return new RedisRuntimeServices(
                new RedisConnection(config.redis().uri(), redisTracing), REDIS_MAPPER, config.uuid());
    }

    private PrexorController.CoreServices initCore(
            me.prexorjustin.prexorcloud.controller.state.RedisRuntimeStore runtimeStore, StateStore stateStore) {
        var eventBus = new EventBus();
        var clusterState = new ClusterState(eventBus, runtimeStore);
        if (runtimeStore != null) clusterState.hydrate(runtimeStore);
        var workflowStateStore = new WorkflowStateStore(stateStore);
        var sessionManager = new NodeSessionManager();
        var consoleBuffer = new ConsoleBuffer(runtime.consoleFloodWindow());
        var logBuffer = new me.prexorjustin.prexorcloud.controller.observability.ControllerLogBuffer();
        me.prexorjustin.prexorcloud.controller.observability.RingBufferLogAppender.attachToRoot(logBuffer);
        return new PrexorController.CoreServices(
                eventBus, clusterState, workflowStateStore, sessionManager, consoleBuffer, logBuffer);
    }

    /**
     * SANs for the gRPC server certificate: always localhost/127.0.0.1, plus any operator-configured
     * {@code grpc.subjectAltNames}, plus this host's non-loopback addresses (best-effort). Remote
     * daemons and proxies verify the controller's cert against the address they dial, so that address
     * must appear here. In Docker only the container bridge IP is visible, so operators must set
     * {@code grpc.subjectAltNames} to the host's reachable address(es).
     */
    private List<String> serverCertSans() {
        var sans = new java.util.LinkedHashSet<String>();
        sans.add("localhost");
        sans.add("127.0.0.1");
        sans.addAll(config.grpc().subjectAltNames());
        try {
            var ifaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                var ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                var addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    var a = addrs.nextElement();
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isMulticastAddress()) continue;
                    sans.add(a.getHostAddress());
                }
            }
        } catch (java.net.SocketException e) {
            logger.warn("Could not enumerate interfaces for gRPC server cert SANs: {}", e.getMessage());
        }
        return List.copyOf(sans);
    }

    private PrexorController.SecurityServices initSecurity() throws Exception {
        Files.createDirectories(SECURITY_DIR);
        FilePermissions.setOwnerOnly(SECURITY_DIR);

        char[] caPassword;
        if (Files.exists(CA_PASSWORD_FILE)) {
            caPassword = Files.readString(CA_PASSWORD_FILE).trim().toCharArray();
        } else {
            caPassword = generateRandomPassword();
            Files.writeString(CA_PASSWORD_FILE, new String(caPassword));
            FilePermissions.setOwnerReadWrite(CA_PASSWORD_FILE);
        }

        var ca = CertificateAuthority.loadOrCreate(CA_KEYSTORE, caPassword, "PrexorCloud CA", 3650);
        ca.exportCaPem(CA_PEM);

        if (!Files.exists(SERVER_KEYSTORE)) {
            List<String> sans = serverCertSans();
            var serverCert = ca.issueServerCertificate("prexorcloud-controller", sans, 825);
            serverCert.savePkcs12(SERVER_KEYSTORE, caPassword);
        }

        String jwtSecret = config.security().jwtSecret();
        if (jwtSecret == null || jwtSecret.isBlank()) {
            jwtSecret = JwtManager.generateSecret();
            logger.info("No JWT secret configured — generated and persisting a new one");
            var updatedSecurity = new me.prexorjustin.prexorcloud.controller.config.SecurityControllerConfig(
                    jwtSecret,
                    config.security().jwtExpirationMinutes(),
                    config.security().initialAdminPassword(),
                    config.security().rateLimiting(),
                    config.security().jwtPreviousSecrets(),
                    config.security().lockout());
            config = new ControllerConfig(
                    config.uuid(),
                    config.http(),
                    config.grpc(),
                    config.network(),
                    config.database(),
                    config.logging(),
                    config.scheduler(),
                    config.heartbeat(),
                    config.runtime(),
                    updatedSecurity,
                    config.crashes(),
                    config.metrics(),
                    config.modules(),
                    config.maintenance(),
                    config.dashboard(),
                    config.backup(),
                    config.share(),
                    config.networks(),
                    config.events(),
                    config.redis(),
                    config.cluster(),
                    config.raft());
            YamlConfigLoader.mapper().writeValue(CONFIG_PATH.toFile(), config);
        }
        var jwtManager = new JwtManager(jwtSecret, config.security().jwtExpirationMinutes());
        for (String previous : config.security().jwtPreviousSecrets()) {
            if (previous == null || previous.isBlank()) continue;
            try {
                jwtManager.addPreviousKey(previous);
            } catch (RuntimeException ex) {
                logger.warn("ignoring invalid security.jwtPreviousSecrets entry: {}", ex.getMessage());
            }
        }
        if (jwtManager.acceptableKeyCount() > 1) {
            logger.info(
                    "JWT manager configured with {} acceptable keys (rotation overlap window)",
                    jwtManager.acceptableKeyCount());
        }

        JoinTokenStore joinTokenStore = new FileJoinTokenStore(JOIN_TOKENS_FILE);

        if (!Files.exists(FORWARDING_SECRET_FILE)) {
            String secret = java.util.UUID.randomUUID().toString().replace("-", "");
            Files.writeString(FORWARDING_SECRET_FILE, secret);
            logger.info("Generated cluster forwarding secret");
        }

        return new PrexorController.SecurityServices(ca, jwtManager, joinTokenStore, caPassword);
    }

    private StateStore initStorage() {
        // Register the OTel command listener at client-build time (Track D.1). It stays inert
        // until attachTracer() runs after the telemetry SDK is up — see start().
        // Explicit majority read/write concern is the control-plane default: the single-writer
        // leadership lease and durable lifecycle records must be majority-acked so they survive a
        // Mongo primary failover. The lease ops layer linearizable read on top per-operation
        // (see MongoLeaderElector). A connection-string concern still overrides this if set.
        var mongoSettings = com.mongodb.MongoClientSettings.builder()
                .applyConnectionString(
                        new com.mongodb.ConnectionString(config.database().uri()))
                .addCommandListener(mongoCommandTracer)
                .readConcern(com.mongodb.ReadConcern.MAJORITY)
                .writeConcern(com.mongodb.WriteConcern.MAJORITY)
                .build();
        mongoClient = MongoClients.create(mongoSettings);
        assertReplicaSetMode(mongoClient);
        mongoDatabase = mongoClient.getDatabase(config.database().database());
        var stateStore = new MongoStateStore(mongoClient, mongoDatabase);
        stateStore.initialize();
        migrateClusterIdentityFromMongoIfNeeded(stateStore);
        return stateStore;
    }

    /**
     * Boot-time guard: the single-writer control plane requires MongoDB in <em>replica-set
     * mode</em>. Change streams (the reactive reconcile), {@code majority}/{@code linearizable}
     * read/write concern, and the fenced leadership lease all depend on it — and transactions in
     * {@link MongoStateStore#runInTransaction} already do. The check is on <em>mode</em>, not member
     * count: a single-member replica set passes (the recommended minimum; grow to 3 members for
     * Mongo HA with zero code change). A bare standalone fails fast with a clear remedy rather than
     * silently degrading (e.g. change streams would simply never fire).
     */
    private void assertReplicaSetMode(MongoClient client) {
        org.bson.Document hello;
        try {
            hello = client.getDatabase("admin").runCommand(new org.bson.Document("hello", 1));
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Could not query MongoDB topology via 'hello' at "
                            + config.database().uri() + " — is MongoDB reachable? (" + e.getMessage() + ")",
                    e);
        }
        // A replica-set member reports its set name; a standalone (and a mongos router) does not.
        String setName = hello.getString("setName");
        if (setName == null || setName.isBlank()) {
            throw new IllegalStateException("MongoDB must run in replica-set mode — connected to a standalone at "
                    + config.database().uri()
                    + ". The control plane needs change streams, majority/linearizable reads, and the"
                    + " leadership lease, none of which work on a standalone. A single-member replica set"
                    + " is sufficient: start mongod with `--replSet rs0` then run"
                    + " rs.initiate() once (same box, same data). See docs/engineering/decisions.md"
                    + " (single-writer control plane ADR).");
        }
        logger.info("MongoDB replica-set mode confirmed (set: {})", setName);
    }

    /**
     * One-shot v1.0 → v1.1 migration of cluster identity from Mongo into the Raft
     * state machine. Runs <em>before</em> {@link ClusterControlService} starts so
     * that the Raft reconcile picks up the migrated id from {@code controller.yml}.
     *
     * <p>Three cases:
     * <ul>
     *   <li><b>Mongo absent</b> — no legacy state. Skip. Day-0 first-boot or a
     *       fresh v1.1 install. {@code ClusterControlService} will mint a fresh
     *       id or honour an operator-supplied one.</li>
     *   <li><b>Mongo present, yaml absent or matching</b> — pre-seed
     *       {@code controller.yml} with the legacy id, drop {@code cluster_meta}.
     *       {@code ClusterControlService} will reuse the id when stamping fresh
     *       Raft state.</li>
     *   <li><b>Mongo present, yaml present, mismatch</b> — refuse to boot. Same
     *       guard as the v1 plan: the operator has pointed at the wrong Mongo.</li>
     * </ul>
     *
     * <p>Idempotent on retry: a crash after {@link #persistClusterIdentity} but
     * before {@link StateStore#dropClusterMeta} leaves Mongo+yaml in a matching
     * state; the next boot's mismatch check passes and the drop runs cleanly.
     */
    private void migrateClusterIdentityFromMongoIfNeeded(StateStore stateStore) {
        String mongoId = stateStore.getClusterId().orElse(null);
        if (mongoId == null) {
            return;
        }
        String yamlId = config.cluster() == null ? null : config.cluster().id();
        if (yamlId != null && !yamlId.equals(mongoId)) {
            throw new IllegalStateException("Configured cluster.id=" + yamlId
                    + " but legacy Mongo cluster_meta has cluster.id=" + mongoId
                    + ". Either point database.uri at the correct Mongo, or remove cluster.id from"
                    + " controller.yml to adopt this Mongo's existing id.");
        }
        if (yamlId == null) {
            persistClusterIdentity(mongoId);
            logger.info("v1.0 → v1.1 migration: adopted cluster.id={} from legacy Mongo cluster_meta", mongoId);
        }
        stateStore.dropClusterMeta();
        logger.info("v1.0 → v1.1 migration: dropped legacy cluster_meta collection — Raft is now the source of truth");
    }

    private void persistClusterIdentity(String clusterId) {
        ClusterConfig prior = config.cluster();
        ClusterConfig updated = new ClusterConfig(clusterId, prior.joinedFrom(), prior.joinedAt());
        config = new ControllerConfig(
                config.uuid(),
                config.http(),
                config.grpc(),
                config.network(),
                config.database(),
                config.logging(),
                config.scheduler(),
                config.heartbeat(),
                config.runtime(),
                config.security(),
                config.crashes(),
                config.metrics(),
                config.modules(),
                config.maintenance(),
                config.dashboard(),
                config.backup(),
                config.share(),
                config.networks(),
                config.events(),
                config.redis(),
                updated,
                config.raft());
        try {
            YamlConfigLoader.mapper().writeValue(CONFIG_PATH.toFile(), config);
        } catch (java.io.IOException e) {
            // Don't fail the boot — the cluster id is the source of truth in Mongo. The next
            // boot will hit the "yaml absent + mongo present" branch and adopt it again.
            logger.warn("Could not persist cluster.id={} to controller.yml: {}", clusterId, e.getMessage());
        }
    }

    private PrexorController.AuthServices initAuth(
            PrexorController.SecurityServices security, StateStore stateStore, RuntimeServices runtime)
            throws Exception {
        var roleStore = new MongoRoleStore(mongoDatabase);
        roleStore.ensureDefaults();
        Role.initialize(roleStore);

        var userStore = new MongoUserStore(mongoDatabase);
        var authManager = new AuthManager(
                userStore,
                security.jwtManager(),
                runtime.loginAttemptStore(),
                this.config.security().lockout());
        authManager.ensureAdminUser(this.config.security().initialAdminPassword());

        return new PrexorController.AuthServices(stateStore, authManager, roleStore, runtime.jwtRevocationStore());
    }

    /**
     * Wire the password-reset manager when {@code security.passwordReset.enabled=true}.
     * Skipped silently otherwise so installs that have not opted in see no extra
     * moving parts. Mailer falls back to {@link LogMailer} until SMTP host is
     * configured; the token store is Redis-backed when coordination is on.
     */
    private void initPasswordReset(PrexorController controller, RuntimeServices runtime) {
        var prConfig = controller.config().security().passwordReset();
        if (!prConfig.enabled()) {
            logger.info("Password reset disabled — security.passwordReset.enabled=false");
            return;
        }
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        PasswordResetTokenStore tokenStore;
        if (runtime.coordinationEnabled()) {
            tokenStore = new RedisPasswordResetTokenStore(runtime.redisCommands(), json);
        } else {
            logger.warn("Password-reset tokens are stored in-memory because the coordination store is disabled — a "
                    + "reset link will only work on the controller that issued it. Configure Redis/Valkey for HA.");
            tokenStore = new InMemoryPasswordResetTokenStore();
        }

        Mailer mailer;
        var smtp = prConfig.smtp();
        if (smtp.enabled()) {
            String from = smtp.from().isBlank() ? ("no-reply@" + smtp.host()) : smtp.from();
            mailer = new SmtpMailer(new SmtpMailer.Config(
                    smtp.host(),
                    smtp.port(),
                    smtp.startTls(),
                    smtp.implicitTls(),
                    smtp.username().isBlank() ? null : smtp.username(),
                    smtp.password().isBlank() ? null : smtp.password(),
                    from,
                    smtp.connectTimeoutMs(),
                    smtp.readTimeoutMs()));
            logger.info(
                    "Password-reset mailer: SMTP {}:{} (startTls={}, implicitTls={}, auth={})",
                    smtp.host(),
                    smtp.port(),
                    smtp.startTls(),
                    smtp.implicitTls(),
                    !smtp.username().isBlank());
        } else {
            mailer = new LogMailer();
            logger.warn("Password-reset mailer: LogMailer (SMTP host not configured — reset links will be logged "
                    + "instead of emailed; not suitable for production)");
        }

        String resetBase = prConfig.resetUrlBase();
        var manager = new PasswordResetManager(
                controller.authManager().userStore(),
                tokenStore,
                mailer,
                java.time.Duration.ofMinutes(prConfig.tokenTtlMinutes()),
                resetBase == null ? "" : resetBase);
        controller.setPasswordResetManager(manager);
        logger.info(
                "Password reset enabled (token TTL = {} minutes, reset URL base = '{}')",
                prConfig.tokenTtlMinutes(),
                resetBase == null ? "" : resetBase);
    }

    private PrexorController.TemplateServices initTemplates(PrexorController.CoreServices core, StateStore stateStore)
            throws Exception {
        var templateManager = new TemplateManager(TEMPLATE_FILES_DIR, stateStore, core.eventBus());
        templateManager.loadAll();

        var catalogStore = new MongoCatalogStore(mongoDatabase);
        if (catalogStore.getAll().isEmpty()) {
            var yamlCatalog = new CatalogConfigLoader(Path.of("config"));
            for (var entry : yamlCatalog.getAll()) {
                catalogStore.addEntry(
                        entry.platform(),
                        entry.category(),
                        entry.configFormat(),
                        entry.version(),
                        entry.downloadUrl(),
                        entry.sha256());
            }
        }

        var baseTemplateGenerator = new BaseTemplateGenerator(templateManager);
        baseTemplateGenerator.ensureBaseTemplates();
        for (var entry : catalogStore.getAll()) {
            baseTemplateGenerator.ensurePlatformTemplate(entry.platform(), entry.category(), entry.configFormat());
        }

        var templateMerger = new TemplateMerger(templateManager, stateStore);

        var groupStore = new MongoGroupStore(mongoDatabase);
        var groupManager = new GroupManager(templateManager);
        for (var group : groupStore.loadAll()) {
            try {
                groupManager.create(group);
            } catch (Exception e) {
                logger.warn("Failed to load group '{}': {}", group.name(), e.getMessage());
            }
        }

        return new PrexorController.TemplateServices(
                templateManager, templateMerger, groupStore, groupManager, catalogStore, baseTemplateGenerator);
    }

    private PrexorController.NetworkServices initNetworks(PrexorController.TemplateServices templates) {
        var networkStore = new MongoNetworkStore(mongoDatabase);
        var networkManager = new NetworkManager(templates.groupManager(), templates.catalogStore());
        var existing = networkStore.loadAll();
        for (var network : existing) {
            try {
                networkManager.create(network);
            } catch (Exception e) {
                logger.warn("Failed to load network '{}': {}", network.name(), e.getMessage());
            }
        }
        // Seed networks declared in controller.yaml that are not yet persisted.
        // Operators can subsequently mutate them via REST; the YAML field is a
        // first-install convenience, not a source of truth.
        var seenNames = new java.util.HashSet<String>();
        for (var network : existing) seenNames.add(network.name());
        for (var seed : config.networks()) {
            if (seed == null || seenNames.contains(seed.name())) continue;
            try {
                networkManager.create(seed);
                networkStore.save(seed);
                seenNames.add(seed.name());
                logger.info("Seeded network '{}' from controller.yaml", seed.name());
            } catch (Exception e) {
                logger.warn("Failed to seed network '{}' from controller.yaml: {}", seed.name(), e.getMessage());
            }
        }
        return new PrexorController.NetworkServices(networkStore, networkManager);
    }

    /**
     * Wire the Redis pub/sub-backed cross-controller event bridge.
     *
     * <p>No-op when {@code runtime.coordinationEnabled()} is false (single-node
     * dev with no Redis). When active, the bridge fans the local
     * {@code core.eventBus()} out to every other controller in the cluster
     * so dashboard SSE consumers see events from any node uniformly.
     */
    private void wireRedisEventBridge(
            PrexorController.CoreServices core,
            PrexorController.TemplateServices templates,
            PrexorController.NetworkServices network) {
        if (!runtime.coordinationEnabled()) {
            return;
        }
        templates.groupManager().setGroupStore(templates.groupStore());
        network.networkManager().setNetworkStore(network.networkStore());
        var publishPubSub = runtime.openPubSubConnection();
        var subscribePubSub = runtime.openPubSubConnection();
        eventBridge = new RedisEventBridge(
                config.uuid(),
                core.eventBus(),
                core.clusterState(),
                core.sessionManager(),
                templates.groupManager(),
                publishPubSub,
                subscribePubSub,
                REDIS_MAPPER);
        eventBridge.register();
        eventBridge.subscribe();
    }

    /**
     * Register every shutdown action with the {@link ShutdownManager} and
     * install the JVM shutdown hook that drives it. The order of
     * registrations matters — {@code ShutdownManager} drains in
     * registration order, so the table below reads top-to-bottom as
     * "what gets quiesced first when SIGTERM lands".
     */
    private void registerShutdownHooks(PrexorController controller, ModuleRegistry modules) {
        shutdownManager = new ShutdownManager();
        shutdownManager.register("telemetry", () -> {
            if (telemetry != null) telemetry.close();
        });
        shutdownManager.register("module quota enforcer", () -> {
            if (moduleQuotaEnforcer != null) moduleQuotaEnforcer.close();
        });
        shutdownManager.register("module resource tracker", () -> {
            if (moduleResourceTracker != null) moduleResourceTracker.close();
        });
        shutdownManager.register("platform modules", modules.platformManager()::close);
        shutdownManager.register("cluster_config reload", () -> {
            if (clusterConfigReload != null) clusterConfigReload.close();
        });
        shutdownManager.register("event bus", controller.eventBus()::shutdown);
        shutdownManager.register("REST server", () -> {
            if (restServer != null) restServer.stop();
        });
        shutdownManager.register("scheduler", controller.scheduler()::stop);
        shutdownManager.register("heartbeat", () -> ShutdownManager.awaitExecutor(heartbeatScheduler, "heartbeat"));
        shutdownManager.register(
                "audit rotation", () -> ShutdownManager.awaitExecutor(auditRotationExecutor, "audit-rotation"));
        shutdownManager.register(
                "platform module reconcile",
                () -> ShutdownManager.awaitExecutor(platformModuleReconcileExecutor, "platform-module-reconcile"));
        shutdownManager.register(
                "module health monitor",
                () -> ShutdownManager.awaitExecutor(moduleHealthExecutor, "module-health-monitor"));
        shutdownManager.register("pending-request scheduler", () -> {
            if (pendingRequestScheduler != null) {
                ShutdownManager.awaitExecutor(pendingRequestScheduler, "pending-request-timeouts");
            }
        });
        shutdownManager.register("lifecycle manager", () -> {
            if (lifecycleManager != null) lifecycleManager.stop();
        });
        shutdownManager.register("gRPC server", () -> {
            if (grpcServer != null) grpcServer.stop();
        });
        shutdownManager.register("cluster control plane", () -> {
            if (clusterControlService != null) {
                try {
                    clusterControlService.close();
                } catch (java.io.IOException e) {
                    logger.warn("error closing cluster control plane: {}", e.getMessage());
                }
            }
        });
        shutdownManager.register("state store", controller.stateStore()::close);
        shutdownManager.register("mongo", () -> {
            if (mongoClient != null) mongoClient.close();
        });
        shutdownManager.register("runtime services", () -> {
            if (runtime != null) runtime.close();
        });
        shutdownManager.register("controller", controller::shutdown);

        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            logger.info("Shutting down...");
                            close();
                        },
                        "shutdown-hook"));
    }

    private PrexorController.CrashServices initCrashDetection(PrexorController.CoreServices core) {
        var crashStore = new CrashStore(config.crashes().ringBufferSize());
        var crashLoopDetector = new CrashLoopDetector(
                config.crashes().crashLoopThreshold(), config.crashes().crashLoopWindowSeconds(), core.eventBus());
        return new PrexorController.CrashServices(crashStore, crashLoopDetector);
    }

    private ModuleRegistry initModuleManagers(PrexorController.TemplateServices templates) throws Exception {
        var modulesDataDir = Path.of(config.modules().dataDirectory());
        Files.createDirectories(modulesDataDir);
        var moduleObjectMapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        var frontendManager = new me.prexorjustin.prexorcloud.controller.module.ModuleFrontendManager(
                moduleObjectMapper, modulesDataDir);

        var platformStore =
                new PlatformModuleStore(Path.of(config.modules().directory()).resolve(".platform-store"));
        var platformStorageMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        var platformStorageManager =
                new PlatformModuleStorageManager(mongoDatabase, mongoClient, runtime, platformStorageMapper);
        var platformLeaseManager = runtime.newLeaseManager(config.scheduler().evaluationIntervalSeconds() * 2);
        var signatureVerifier = buildSignatureVerifier(config);
        moduleRouteRegistry = new me.prexorjustin.prexorcloud.modules.runtime.ModuleRouteRegistry();
        var platformManager = new PlatformModuleManager(
                platformStore,
                new me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleRuntimeFactory
                        .JarRuntimeFactory(),
                platformStorageManager,
                platformLeaseManager,
                signatureVerifier,
                moduleRouteRegistry);
        platformManager.setClassLoaderTracker(
                new me.prexorjustin.prexorcloud.controller.module.platform.ModuleClassLoaderTracker());
        // loadStoredModules() is intentionally deferred until after the controller has
        // registered its built-in capability handles (e.g. prexor.player.journey), so
        // modules that require those handles activate on first load instead of waiting
        // for the next reconcile tick.
        return new ModuleRegistry(frontendManager, platformManager);
    }

    private void bootPlatformModules(PrexorController controller, ModuleRegistry modules) {
        wireProductionModuleContext(controller, modules.platformManager());
        wireModuleQuotaEnforcer(controller);
        wireCapabilityEventPublishing(controller, modules.platformManager());
        registerBuiltinCapabilities(controller, modules.platformManager());
        modules.platformManager().loadStoredModules();
        refreshPlatformFrontends(modules.frontendManager(), modules.platformManager());
        startPlatformModuleReconciler(modules.frontendManager(), modules.platformManager());
        startModuleHealthMonitor(controller, modules.platformManager());
    }

    /**
     * Register the controller-provided built-in capability handles. Runs after
     * {@link #wireCapabilityEventPublishing} so the registrations surface as
     * {@code CapabilityRegisteredEvent}s for SSE consumers, and before
     * {@link me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleManager#loadStoredModules}
     * so modules requiring these capabilities resolve on first load.
     *
     * <p>Each {@code if} guards against a service that wasn't wired in the
     * current bootstrap profile (e.g. embedded tests that skip the daemon
     * gateway). Skipping is silent — modules that {@code require} an unbound
     * capability stay parked until the provider shows up.
     */
    private void registerBuiltinCapabilities(PrexorController controller, PlatformModuleManager platformManager) {
        var registry = platformManager.capabilityRegistry();
        if (controller.instanceFileTreeService() != null && controller.instanceFileContentService() != null) {
            registry.registerBuiltinHandle(
                    me.prexorjustin.prexorcloud.api.module.capability.InstanceFileAccess.CAPABILITY_ID,
                    "1.0.0",
                    new me.prexorjustin.prexorcloud.controller.module.capability.ControllerInstanceFileAccess(
                            controller.instanceFileTreeService(), controller.instanceFileContentService()));
        }
    }

    /**
     * Bridges {@link me.prexorjustin.prexorcloud.modules.runtime.CapabilityRegistry} mutations
     * to {@link me.prexorjustin.prexorcloud.api.event.events.CapabilityRegisteredEvent}
     * / {@link me.prexorjustin.prexorcloud.api.event.events.CapabilityUnregisteredEvent}
     * / {@link me.prexorjustin.prexorcloud.api.event.events.CapabilityProviderChangedEvent}.
     * Wired before {@link #registerBuiltinCapabilities} so the controller's own
     * built-in handles also surface as REGISTERED events for late-connecting
     * SSE consumers (the dashboard's {@code useCapability}).
     */
    private void wireCapabilityEventPublishing(PrexorController controller, PlatformModuleManager platformManager) {
        var bus = controller.eventBus();
        platformManager
                .capabilityRegistry()
                .setListener(new me.prexorjustin.prexorcloud.modules.runtime.CapabilityRegistry.Listener() {
                    @Override
                    public void onCapabilityRegistered(String capabilityId, String version, String moduleId) {
                        bus.publish(new me.prexorjustin.prexorcloud.api.event.events.CapabilityRegisteredEvent(
                                capabilityId, version, moduleId));
                    }

                    @Override
                    public void onCapabilityUnregistered(String capabilityId, String moduleId) {
                        bus.publish(new me.prexorjustin.prexorcloud.api.event.events.CapabilityUnregisteredEvent(
                                capabilityId, moduleId));
                    }

                    @Override
                    public void onCapabilityProviderChanged(
                            String capabilityId, String moduleId, String fromVersion, String toVersion) {
                        bus.publish(new me.prexorjustin.prexorcloud.api.event.events.CapabilityProviderChangedEvent(
                                capabilityId, moduleId, fromVersion, toVersion));
                    }
                });
    }

    /**
     * Plug in the {@link me.prexorjustin.prexorcloud.controller.module.platform.ControllerModuleContext}
     * factory so modules see the live event bus and a real task scheduler in
     * their lifecycle hooks. Must run before {@link PlatformModuleManager#loadStoredModules}.
     */
    private void wireProductionModuleContext(PrexorController controller, PlatformModuleManager platformManager) {
        // Each module gets its OWN named scheduler via the resource tracker (C.2 stage 1),
        // so its controller-side work is attributable: threads carry the module id and the
        // tracker samples per-module CPU / allocation. The reconcile supplier lets the
        // sampler shut down schedulers for modules that have been uninstalled.
        moduleResourceTracker = new me.prexorjustin.prexorcloud.controller.module.resource.ModuleResourceTracker(
                () -> platformManager.listModules().stream()
                        .map(PlatformModuleManager.ManagedPlatformModule::moduleId)
                        .collect(java.util.stream.Collectors.toSet()),
                10_000L);
        moduleResourceTracker.start();
        controller.setModuleResourceTracker(moduleResourceTracker);
        platformManager.setContextFactory((manifest, jarPath, previousVersion, capabilities, storage) ->
                new me.prexorjustin.prexorcloud.controller.module.platform.ControllerModuleContext(
                        manifest,
                        jarPath,
                        previousVersion,
                        capabilities,
                        storage,
                        controller.eventBus(),
                        moduleResourceTracker.schedulerFor(manifest.id())));
    }

    /**
     * Stand up the soft-quota enforcer (Track C.2 stage 2) on top of the stage-1 tracker. Runs
     * after {@link #wireProductionModuleContext} (so {@code moduleResourceTracker} exists) and once
     * the controller — hence its {@code MetricsCollector} — is built. A no-op unless at least one
     * module declares an enforceable quota under {@code modules.quotas.<id>}; the enforcer's own
     * {@code start()} is idempotent and bails when nothing is configured.
     */
    private void wireModuleQuotaEnforcer(PrexorController controller) {
        if (moduleResourceTracker == null) {
            return;
        }
        var metrics = controller.metricsCollector();
        me.prexorjustin.prexorcloud.controller.module.resource.ModuleQuotaEnforcer.BreachSink sink = metrics != null
                ? (moduleId, resource) -> metrics.recordModuleQuotaExceeded(moduleId, resource.tag())
                : (moduleId, resource) -> {};
        moduleQuotaEnforcer = new me.prexorjustin.prexorcloud.controller.module.resource.ModuleQuotaEnforcer(
                moduleResourceTracker, config.modules().quotas(), sink);
        moduleQuotaEnforcer.start();
        controller.setModuleQuotaEnforcer(moduleQuotaEnforcer);
    }

    private PrexorController.ObservabilityServices initObservability(
            PrexorController.CoreServices core,
            PrexorController.TemplateServices templates,
            PrexorController.CrashServices crash,
            ModuleRegistry modules) {
        MetricsCollector metricsCollector = null;
        if (config.metrics().enabled()) {
            metricsCollector = new MetricsCollector(
                    core.clusterState(),
                    templates.groupManager(),
                    crash.crashStore(),
                    core.workflowStateStore(),
                    modules.platformManager());
        }

        return new PrexorController.ObservabilityServices(metricsCollector);
    }

    private void initLifecycle(PrexorController controller) {
        lifecycleManager = new InstanceLifecycleManager(
                controller.clusterState(),
                controller.eventBus(),
                controller.groupManager(),
                controller.consoleBuffer(),
                controller.stateStore());
    }

    private void initGrpc(PrexorController controller, char[] caPassword) throws Exception {
        long heartbeatMs = config.heartbeat().intervalMs();
        var heartbeatTracker = new HeartbeatTracker(
                controller.sessionManager(),
                controller.clusterState(),
                controller.eventBus(),
                config.heartbeat().missedThreshold());

        var reloadableSslContext = ReloadableServerSslContext.build(
                SERVER_KEYSTORE, caPassword, CA_PEM, runtime.nodeCertRevocationStore());
        var daemonDeps = new DaemonServiceImpl.Deps(
                controller.sessionManager(),
                controller.clusterState(),
                heartbeatTracker,
                controller.eventBus(),
                controller.crashStore(),
                controller.crashLoopDetector(),
                controller.templateManager(),
                controller.templateMerger(),
                controller.stateStore(),
                controller.consoleBuffer(),
                controller.groupManager(),
                controller.catalogStore(),
                runtime.coordinationEnabled() ? runtime.redisCommands() : null,
                config.uuid(),
                pendingRequests,
                eventBridge);
        var daemonService = new DaemonServiceImpl(
                daemonDeps,
                heartbeatMs,
                config.heartbeat().missedThreshold(),
                config.http().port());
        daemonService.attachScheduler(controller.scheduler());
        daemonService.attachDaemonLogStore(controller.daemonLogStore());
        if (controller.metricsCollector() != null) {
            daemonService.attachMetricsCollector(controller.metricsCollector());
            controller.metricsCollector().registerDaemonSessionMetrics(controller.sessionManager());
        }

        // Layer 7: wire daemon-host platform-module distribution and the controller→daemon
        // event bridge. Distributor fans out install/upgrade/uninstall to connected daemons
        // and re-syncs each daemon on handshake; forwarder bridges controller-bus events
        // to daemons that explicitly subscribed.
        var platformManager = controller.moduleRegistry().platformManager();
        var moduleDistributor = new me.prexorjustin.prexorcloud.controller.module.platform.ModuleDistributor(
                platformManager.platformStore(), controller.sessionManager());
        platformManager.setDistributorHook(moduleDistributor);
        daemonService.attachModuleDistributor(moduleDistributor);
        var daemonEventForwarder = new DaemonEventForwarder(controller.eventBus(), controller.sessionManager());
        daemonService.attachDaemonEventForwarder(daemonEventForwarder);
        // Auto-register callback: when a daemon redeems its join token, add the
        // daemon's source IP as /32 to the controller's allowed-subnets list and
        // persist to controller.yml. This is what lets the operator tighten the
        // default 0.0.0.0/0 to per-daemon CIDRs without having to manage them
        // by hand. Non-fatal if the YAML write fails.
        java.util.function.Consumer<String> subnetAutoReg = cidr -> {
            try {
                if (controller.allowedSubnetsList().add(cidr)) {
                    me.prexorjustin.prexorcloud.controller.rest.route.ControllerYamlMutator.upsertList(
                            "network.allowedSubnets", cidr, true);
                }
            } catch (Exception e) {
                logger.warn("Failed to persist auto-registered subnet {}: {}", cidr, e.getMessage());
            }
        };
        var bootstrapService = new BootstrapServiceImpl(
                controller.joinTokenStore(),
                controller.ca(),
                CA_PEM,
                365,
                controller.stateStore(),
                controller.jwtManager(),
                subnetAutoReg);
        var adminService = new AdminServiceImpl(controller.joinTokenStore());
        var clusterMembershipService = new me.prexorjustin.prexorcloud.controller.grpc.ClusterMembershipServiceImpl(
                clusterControlService.controlPlane(), java.time.Clock.systemUTC(), controller.stateStore());

        var mtlsInterceptor = new MtlsEnforcementInterceptor(runtime.nodeCertRevocationStore());
        var subnetGuard =
                new me.prexorjustin.prexorcloud.controller.grpc.SubnetGuardInterceptor(controller.allowedSubnetsList());

        grpcServer = new GrpcServer(
                config.grpc().host(),
                config.grpc().port(),
                reloadableSslContext.sslContext(),
                daemonService,
                bootstrapService,
                adminService,
                clusterMembershipService,
                mtlsInterceptor,
                subnetGuard);
        grpcServer.start();

        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatScheduler.scheduleAtFixedRate(
                heartbeatTracker::pingAll, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);

        var tlsWatcher = new TlsMaterialWatcher(reloadableSslContext, SERVER_KEYSTORE, caPassword, CA_PEM);
        heartbeatScheduler.scheduleAtFixedRate(tlsWatcher, 30, 30, TimeUnit.SECONDS);

        var certRotation = new CertificateRotationTask(
                controller.ca(), SERVER_KEYSTORE, caPassword, 30, 825, serverCertSans(), tlsWatcher);
        heartbeatScheduler.scheduleAtFixedRate(certRotation, 1, 24 * 60 * 60, TimeUnit.SECONDS);

        var timeseriesSampler = new me.prexorjustin.prexorcloud.controller.state.MetricsTimeseriesSampler(
                controller.clusterState(), controller.metricsTimeseries());
        long sampleIntervalMs = config.metrics().collectionIntervalSeconds() * 1000L;
        heartbeatScheduler.scheduleAtFixedRate(
                timeseriesSampler, sampleIntervalMs, sampleIntervalMs, TimeUnit.MILLISECONDS);
    }

    private Scheduler initScheduler(PrexorController controller, ModuleRegistry modules) {
        var scheduler = buildScheduler(controller, modules);
        // Cluster-singleton gate for the deployment reconciliation loop. Without
        // this, every controller iterated IN_PROGRESS deployments on every tick
        // and raced on Mongo.
        scheduler.setClusterLeaseManager(new me.prexorjustin.prexorcloud.controller.cluster.ClusterLeaseManager(
                clusterControlService.controlPlane(), config.uuid()));
        scheduler.start();

        int retentionDays = config.scheduler().auditRetentionDays();
        auditRotationExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "audit-rotation");
            t.setDaemon(true);
            return t;
        });
        // Cluster-singleton: only one controller in the cluster runs the prune per tick.
        // Pre-Phase-8 every controller ran it in parallel, duplicating the work and
        // racing on Mongo. The lease is named so future operators can inspect it via
        // /api/v1/cluster/leases.
        var auditPrunerLease = new me.prexorjustin.prexorcloud.controller.cluster.ClusterLeaseManager(
                clusterControlService.controlPlane(), config.uuid());
        java.time.Duration auditPrunerTtl = java.time.Duration.ofHours(1);
        auditRotationExecutor.scheduleAtFixedRate(
                () -> auditPrunerLease.runUnderLease(
                        "audit-pruner",
                        auditPrunerTtl,
                        () -> controller.stateStore().pruneAuditLog(retentionDays)),
                1,
                24,
                TimeUnit.HOURS);

        var drainLeaseManager = runtime.newLeaseManager(config.scheduler().evaluationIntervalSeconds() * 2);
        drainManager = new NodeDrainManager(
                controller.clusterState(),
                controller.workflowStateStore(),
                scheduler,
                controller.sessionManager(),
                controller.eventBus(),
                controller.groupManager(),
                drainLeaseManager);

        return scheduler;
    }

    private void reconcileDurableWorkflows(PrexorController controller) {
        if (lifecycleManager != null) {
            lifecycleManager.reconcilePersistedHealingActions();
        }
        if (controller.hasScheduler()) {
            controller.scheduler().reconcilePersistedStartRetries();
            controller.scheduler().reconcilePersistedDeployments();
        }
        if (drainManager != null) {
            drainManager.reconcilePersistedDrains();
        }
    }

    @SuppressWarnings("HttpUrlsUsage") // internal cluster URL injected into daemon env, not an external endpoint
    private Scheduler buildScheduler(PrexorController controller, ModuleRegistry modules) {
        String controllerHttpUrl = "http://"
                + ("0.0.0.0".equals(config.http().host())
                        ? "localhost"
                        : config.http().host()) + ":"
                + config.http().port();
        var nodeSelector = new WeightedNodeSelector();
        var scalingEvaluator = new ScalingEvaluator(
                controller.clusterState(), config.scheduler().scalingCooldownSeconds(), runtime);
        var leaseManager = runtime.newLeaseManager(config.scheduler().evaluationIntervalSeconds() * 2);
        var redisSync = runtime.coordinationEnabled() ? runtime.redisCommands() : null;
        var nodeMessageDispatcher = new NodeMessageDispatcher(controller.sessionManager(), eventBridge, redisSync);
        if (controller.metricsCollector() != null) {
            nodeMessageDispatcher.attachMetricsCollector(controller.metricsCollector());
        }
        controller.setInstanceFileTreeService(
                new me.prexorjustin.prexorcloud.controller.diagnostics.InstanceFileTreeService(
                        nodeMessageDispatcher, pendingRequests, config.uuid()));
        controller.setInstanceFileContentService(
                new me.prexorjustin.prexorcloud.controller.diagnostics.InstanceFileContentService(
                        nodeMessageDispatcher, pendingRequests, config.uuid()));

        // Cross-controller HA: when a daemon reply lands on a controller other than the
        // originating one, RedisEventBridge re-publishes it on CHANNEL_REPLY. Wire the
        // local handler to dispatch the DaemonMessage variants into our pending registry.
        if (eventBridge != null) {
            eventBridge.onRemoteReply(daemonMessage -> {
                switch (daemonMessage.getPayloadCase()) {
                    case INSTANCE_FILE_TREE ->
                        pendingRequests.complete(
                                daemonMessage.getInstanceFileTree().getRequestId(),
                                daemonMessage.getInstanceFileTree());
                    case INSTANCE_FILE_CONTENT ->
                        pendingRequests.complete(
                                daemonMessage.getInstanceFileContent().getRequestId(),
                                daemonMessage.getInstanceFileContent());
                    default -> {
                        // No-op: other payloads do not currently use the cross-controller reply channel.
                    }
                }
            });
        }
        StartRetryWakeupQueue startRetryWakeupQueue = redisSync != null
                ? new RedisStartRetryWakeupQueue(
                        redisSync, config.uuid(), config.scheduler().evaluationIntervalSeconds())
                : null;
        var compositionPlanner = new InstanceCompositionPlanner(
                controller.templateManager(),
                controller.catalogStore(),
                modules.platformManager(),
                controller.metricsCollector(),
                new ClusterStateBedrockRemoteResolver(controller.clusterState()));
        var placementCoordinator = new InstancePlacementCoordinator(
                controller.clusterState(),
                nodeSelector,
                scalingEvaluator,
                controller.stateStore(),
                compositionPlanner,
                nodeMessageDispatcher,
                controllerHttpUrl);
        var deploymentReconciler = new DeploymentReconciler(
                controller.clusterState(),
                controller.stateStore(),
                controller.eventBus(),
                config.scheduler().evaluationIntervalSeconds(),
                (instanceId, force) -> controller.scheduler().stopInstance(instanceId, force));
        Scheduler scheduler = new Scheduler(
                controller.groupManager(),
                controller.clusterState(),
                scalingEvaluator,
                controller.crashLoopDetector(),
                controller.stateStore(),
                controller.workflowStateStore(),
                placementCoordinator,
                deploymentReconciler,
                config.scheduler().evaluationIntervalSeconds(),
                () -> controller.config().maintenance().enabled(),
                leaseManager,
                startRetryWakeupQueue,
                nodeMessageDispatcher,
                controller.eventChoreographer(),
                controller.metricsCollector());
        if (controller.telemetry() != null) {
            var tracer = controller.telemetry().tracer();
            scheduler.setTracer(tracer);
            placementCoordinator.setTracer(tracer);
            deploymentReconciler.setTracer(tracer);
        }
        return scheduler;
    }

    private void initRestServer(PrexorController controller) throws Exception {
        var backupCatalog = new me.prexorjustin.prexorcloud.controller.recovery.BackupCatalog(
                Path.of(controller.config().backup().directory()));
        var backupServices = new me.prexorjustin.prexorcloud.controller.recovery.BackupServices(
                mongoDatabase, Path.of(""), backupCatalog);
        restServer = new RestServer(
                controller, runtime, createReadinessProbe(controller), backupServices, moduleRouteRegistry);
        restServer.start();
    }

    /**
     * Phase-7 (Track A.5): wire the cluster_config live-reload fan-out. Each
     * subscriber holds a live, mutable subsystem and re-reads its slice of the
     * folded config when a config version is committed through Raft. Runs after
     * {@link #initRestServer} so the rate-limit middleware instance exists.
     */
    private void initClusterConfigReload(PrexorController controller) {
        clusterConfigReload = new me.prexorjustin.prexorcloud.controller.cluster.reload.ClusterConfigReloadCoordinator(
                        clusterControlService.controlPlane()::effectiveConfig, controller.eventBus())
                .register(new me.prexorjustin.prexorcloud.controller.cluster.reload.CorsAllowListReloader(
                        controller.corsAllowList()))
                .register(new me.prexorjustin.prexorcloud.controller.cluster.reload.RateLimitReloader(
                        restServer.rateLimitMiddleware()))
                .register(new me.prexorjustin.prexorcloud.controller.cluster.reload.JwtSecretReloader(
                        controller.jwtManager(), controller.config().security().jwtSecret()));
        clusterConfigReload.start();
    }

    private ControllerReadinessProbe createReadinessProbe(PrexorController controller) {
        return ControllerReadinessProbe.from(controller, this::isMongoReady, this::isRedisReady);
    }

    private boolean isMongoReady() {
        if (mongoDatabase == null) {
            return false;
        }
        try {
            mongoDatabase.runCommand(new Document("ping", 1));
            return true;
        } catch (Exception _) {
            return false;
        }
    }

    private boolean isRedisReady() {
        if (!runtime.coordinationEnabled()) {
            return false;
        }
        try {
            return "PONG".equalsIgnoreCase(runtime.redisCommands().ping());
        } catch (Exception _) {
            return false;
        }
    }

    private void refreshPlatformFrontends(
            me.prexorjustin.prexorcloud.controller.module.ModuleFrontendManager frontendManager,
            PlatformModuleManager platformManager) {
        var managedModuleIds = platformManager.listModules().stream()
                .map(PlatformModuleManager.ManagedPlatformModule::moduleId)
                .collect(java.util.stream.Collectors.toSet());
        for (var frontend : frontendManager.allFrontends()) {
            if (!managedModuleIds.contains(frontend.moduleName())) {
                frontendManager.removeFrontend(frontend.moduleName());
            }
        }
        for (var managed : platformManager.listModules()) {
            if (managed.manifest().frontend() == null) {
                frontendManager.removeFrontend(managed.moduleId());
                continue;
            }
            if (!frontendManager.extractFrontend(managed.moduleId(), managed.jarPath())) {
                logger.warn(
                        "Platform module '{}' declares a frontend but no extractable frontend bundle was found",
                        managed.moduleId());
            }
        }
    }

    private void startPlatformModuleReconciler(
            me.prexorjustin.prexorcloud.controller.module.ModuleFrontendManager frontendManager,
            PlatformModuleManager platformManager) {
        platformModuleReconcileExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "platform-module-reconcile");
            t.setDaemon(true);
            return t;
        });
        long intervalSeconds = Math.max(1L, config.scheduler().evaluationIntervalSeconds());
        platformModuleReconcileExecutor.scheduleAtFixedRate(
                () -> {
                    try {
                        if (platformManager.reconcileStoredModules()) {
                            refreshPlatformFrontends(frontendManager, platformManager);
                        }
                    } catch (Exception e) {
                        logger.warn("Platform module reconciliation failed: {}", e.getMessage());
                    }
                },
                1,
                intervalSeconds,
                TimeUnit.SECONDS);
    }

    /**
     * Stand up the per-module health poller (Track C.3 — Health-Checks) on its own executor, kept
     * separate from the reconciler so a slow {@code healthCheck()} can't delay reconciliation. Each
     * tick polls every active module and folds the result into the {@link ModuleHealthMonitor} that
     * backs the REST endpoint and the {@code prexorcloud.module.health} metric.
     */
    private void startModuleHealthMonitor(PrexorController controller, PlatformModuleManager platformManager) {
        moduleHealthMonitor = new me.prexorjustin.prexorcloud.controller.module.health.ModuleHealthMonitor();
        controller.setModuleHealthMonitor(moduleHealthMonitor);
        if (controller.metricsCollector() != null) {
            controller.metricsCollector().registerModuleHealthMetrics(moduleHealthMonitor);
        }
        moduleHealthExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "module-health-monitor");
            t.setDaemon(true);
            return t;
        });
        long intervalSeconds = Math.max(5L, config.scheduler().evaluationIntervalSeconds());
        moduleHealthExecutor.scheduleAtFixedRate(
                () -> {
                    try {
                        moduleHealthMonitor.record(platformManager.pollHealth());
                    } catch (Exception e) {
                        logger.warn("Module health poll failed: {}", e.getMessage());
                    }
                },
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS);
    }

    private static char[] generateRandomPassword() {
        SecureRandom random = new SecureRandom();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        char[] password = new char[32];
        for (int i = 0; i < password.length; i++) {
            password[i] = chars.charAt(random.nextInt(chars.length()));
        }
        return password;
    }

    private static me.prexorjustin.prexorcloud.security.signing.PlatformModuleSignatureVerifier buildSignatureVerifier(
            ControllerConfig config) {
        var signing = config.modules().signing();
        boolean required = signing.requiredOrDefault(config.runtime().production());
        String trustRoot = signing.trustRoot();

        // Development escape-hatch: when required=true is set in dev profile (e.g. an
        // operator copy-pasted a production controller.yml into a local lab),
        // allowUnsignedDevelopment relaxes back to NOOP with a loud warning so the
        // developer isn't stuck unable to install unsigned local builds.
        if (required && !config.runtime().production() && signing.allowUnsignedDevelopment()) {
            logger.warn("modules.signing.required=true but runtime.profile=development and "
                    + "modules.signing.allowUnsignedDevelopment=true — accepting unsigned "
                    + "platform modules. Flip allowUnsignedDevelopment=false to enforce "
                    + "signature verification locally.");
            return me.prexorjustin.prexorcloud.security.signing.PlatformModuleSignatureVerifier.NOOP;
        }

        if (!required) {
            if (config.runtime().production()) {
                logger.warn("modules.signing.required=false in production profile — packages will install without "
                        + "signature verification. This is unsafe and intended only for incident recovery.");
            }
            return me.prexorjustin.prexorcloud.security.signing.PlatformModuleSignatureVerifier.NOOP;
        }

        if (trustRoot == null || trustRoot.isBlank()) {
            logger.error(
                    "modules.signing.required=true but modules.signing.trustRoot is empty — installs will fail-closed");
            return me.prexorjustin.prexorcloud.security.signing.PlatformModuleSignatureVerifier.failClosed();
        }

        return switch (signing.mode()) {
            case COSIGN_BUNDLE -> {
                var verifier = me.prexorjustin.prexorcloud.security.signing.PlatformModuleSignatureVerifier
                        .CosignBundleVerifier.fromPemBundle(Path.of(trustRoot));
                var rekor = signing.rekor();
                if (rekor != null
                        && rekor.policy()
                                != me.prexorjustin.prexorcloud.controller.config.ModuleSigningConfig.RekorConfig.Policy
                                        .DISABLED) {
                    var rekorKeys = me.prexorjustin.prexorcloud.security.signing.PlatformModuleSignatureVerifier
                            .CosignBundleVerifier.loadRekorPublicKeys(Path.of(rekor.publicKey()));
                    var policy = me.prexorjustin.prexorcloud.security.signing.PlatformModuleSignatureVerifier
                            .CosignBundleVerifier.RekorPolicy.valueOf(
                            rekor.policy().name());
                    logger.info(
                            "modules.signing.rekor.policy={} (loaded {} rekor public keys)", policy, rekorKeys.size());
                    yield verifier.withRekor(policy, rekorKeys);
                }
                yield verifier;
            }
            case KEYED ->
                me.prexorjustin.prexorcloud.security.signing.PlatformModuleSignatureVerifier.TrustRootVerifier
                        .fromPemBundle(Path.of(trustRoot));
        };
    }

    /**
     * Register the gRPC {@code pick_first} load balancer + DNS name-resolver providers. They ship in
     * grpc-core via {@code META-INF/services}, but the shaded fat jar drops grpc-core's service files
     * during the shadow merge, and a server JVM discovers providers via {@link java.util.ServiceLoader}
     * only — so without this the cluster-join client channel fails with "Could not find policy
     * 'pick_first'". Same workaround as the daemon ({@code PrexorDaemon.registerGrpcProviders}).
     * Idempotent; harmless if the service files are ever fixed.
     */
    private static void registerGrpcProviders() {
        try {
            io.grpc.LoadBalancerRegistry.getDefaultRegistry()
                    .register(new io.grpc.internal.PickFirstLoadBalancerProvider());
            io.grpc.NameResolverRegistry.getDefaultRegistry().register(new io.grpc.internal.DnsNameResolverProvider());
        } catch (RuntimeException | LinkageError e) {
            logger.warn("Could not pre-register gRPC providers: {}", e.toString());
        }
    }

    public static void main(String[] args) {
        // Ensure all child threads (including gRPC Netty event loops) inherit the
        // application classloader so logback classes are visible from shaded Netty
        // threads.
        Thread.currentThread().setContextClassLoader(PrexorCloudBootstrap.class.getClassLoader());
        registerGrpcProviders();

        try {
            ControllerConfig config = YamlConfigLoader.load(CONFIG_PATH, ControllerConfig.class, DEFAULT_CONFIG);
            me.prexorjustin.prexorcloud.controller.config.ConfigValidator.validate(config);
            LoggingSetup.configure(config.logging(), "controller");
            ClassWarmup.loadErrorPathClasses();

            var bootstrap = new PrexorCloudBootstrap(config);
            PrexorController controller = bootstrap.start();
            controller.awaitShutdown();
        } catch (Exception e) {
            logger.error("Failed to start PrexorCloud Controller: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
