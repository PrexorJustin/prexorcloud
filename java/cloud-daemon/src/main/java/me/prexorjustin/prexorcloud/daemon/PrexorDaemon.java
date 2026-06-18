package me.prexorjustin.prexorcloud.daemon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import me.prexorjustin.prexorcloud.common.config.YamlConfigLoader;
import me.prexorjustin.prexorcloud.common.logging.LoggingSetup;
import me.prexorjustin.prexorcloud.common.util.ClassWarmup;
import me.prexorjustin.prexorcloud.common.util.VersionInfo;
import me.prexorjustin.prexorcloud.daemon.bootstrap.BootstrapManager;
import me.prexorjustin.prexorcloud.daemon.config.DaemonConfig;
import me.prexorjustin.prexorcloud.daemon.config.ModuleSigningDaemonConfig;
import me.prexorjustin.prexorcloud.daemon.event.DaemonEventBus;
import me.prexorjustin.prexorcloud.daemon.grpc.DaemonGrpcClient;
import me.prexorjustin.prexorcloud.daemon.grpc.MessageDispatcher;
import me.prexorjustin.prexorcloud.daemon.grpc.ReconnectManager;
import me.prexorjustin.prexorcloud.daemon.health.HealthServer;
import me.prexorjustin.prexorcloud.daemon.module.DaemonCapabilityRegistryImpl;
import me.prexorjustin.prexorcloud.daemon.module.DaemonModuleContext;
import me.prexorjustin.prexorcloud.daemon.module.DaemonModuleHost;
import me.prexorjustin.prexorcloud.daemon.module.DaemonModuleManager;
import me.prexorjustin.prexorcloud.daemon.module.DaemonModuleStore;
import me.prexorjustin.prexorcloud.daemon.module.DaemonTaskScheduler;
import me.prexorjustin.prexorcloud.daemon.observability.DaemonGrpcLogAppender;
import me.prexorjustin.prexorcloud.daemon.observability.DaemonLogPublisher;
import me.prexorjustin.prexorcloud.daemon.process.ProcessManager;
import me.prexorjustin.prexorcloud.daemon.resource.ResourceMonitor;
import me.prexorjustin.prexorcloud.daemon.setup.InteractiveSetup;
import me.prexorjustin.prexorcloud.daemon.template.ArtifactCache;
import me.prexorjustin.prexorcloud.daemon.template.JarCache;
import me.prexorjustin.prexorcloud.daemon.template.PaperBootstrapCache;
import me.prexorjustin.prexorcloud.daemon.template.TemplateCache;
import me.prexorjustin.prexorcloud.modules.runtime.CapabilityRegistry;
import me.prexorjustin.prexorcloud.security.signing.PlatformModuleSignatureVerifier;
import me.prexorjustin.prexorcloud.security.tls.ClientTlsCredentials;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PrexorDaemon {

    private static final Logger logger = LoggerFactory.getLogger(PrexorDaemon.class);
    private static final Path CONFIG_PATH = Path.of("config", "daemon.yml");
    private static final String DEFAULT_CONFIG = "defaults/daemon.yml";

    private final DaemonConfig config;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    private DaemonGrpcClient grpcClient;
    private ReconnectManager reconnectManager;
    private ProcessManager processManager;
    private HealthServer healthServer;
    private DaemonModuleManager daemonModuleManager;
    private java.util.concurrent.ScheduledExecutorService daemonModuleScheduler;
    private me.prexorjustin.prexorcloud.daemon.observability.DaemonTelemetry telemetry;

    public PrexorDaemon(DaemonConfig config) {
        this.config = config;
    }

    public void start() throws Exception {
        Logger logger = LoggerFactory.getLogger(PrexorDaemon.class);
        VersionInfo version = VersionInfo.get();

        logger.info(
                "PrexorCloud Daemon v{} (Java {}) - node: {}",
                version.version(),
                version.javaVersion(),
                config.nodeId());

        long maxMemory = config.resources().effectiveMaxMemoryMb();

        // --- Telemetry (Track D) --- no-op unless telemetry.enabled; built early so any later
        // component can be handed the tracer. Flushed and closed in shutdown().
        telemetry = me.prexorjustin.prexorcloud.daemon.observability.DaemonTelemetry.create(
                config.telemetry(), config.nodeId());

        // --- Bootstrap ---
        var bootstrapManager = new BootstrapManager(
                config.controller().host(),
                config.controller().grpcPort(),
                Path.of(config.security().certificateDir()));

        if (!bootstrapManager.isBootstrapped()) {
            String joinToken = config.security().joinToken();
            if (joinToken == null || joinToken.isBlank()) {
                logger.error("No certificate found and no join token configured. Set security.joinToken in daemon.yml");
                System.exit(1);
            }
            bootstrapManager.bootstrap(joinToken, config.nodeId());
        }

        // --- TLS ---
        io.grpc.netty.shaded.io.netty.handler.ssl.SslContext sslContext = null;
        if (Files.exists(bootstrapManager.nodePkcs12Path())) {
            char[] password =
                    Files.readString(bootstrapManager.passwordPath()).trim().toCharArray();
            sslContext = ClientTlsCredentials.build(
                    bootstrapManager.nodePkcs12Path(), password, bootstrapManager.caPemPath());
            logger.debug("mTLS configured with node certificate");
        }

        // --- Caches ---
        var templateCache = new TemplateCache(Path.of("cache", "templates"));
        var jarCache = new JarCache(Path.of("cache", "jars"));
        var artifactCache = new ArtifactCache(Path.of("cache", "artifacts"));
        var paperBootstrapCache = new PaperBootstrapCache(Path.of("cache", "paper-bootstrap"));

        // --- Process manager ---
        Path instancesDir = Path.of(config.instances().directory());
        Files.createDirectories(instancesDir);

        // --- gRPC client ---
        var resourceMonitor = new ResourceMonitor(instancesDir);
        var dispatcher = new MessageDispatcher();
        dispatcher.setTracer(telemetry.tracer()); // continues the controller's trace (Track D.3)

        grpcClient = new DaemonGrpcClient(
                config.controller().host(),
                config.controller().grpcPort(),
                config.nodeId(),
                config.advertiseAddress(),
                maxMemory,
                config.labels(),
                sslContext,
                resourceMonitor,
                dispatcher);
        dispatcher.setClient(grpcClient);
        DaemonLogPublisher.get().bind(grpcClient);
        DaemonGrpcLogAppender.attachToRoot();
        processManager = new ProcessManager(
                instancesDir,
                config.instances(),
                grpcClient,
                templateCache,
                jarCache,
                artifactCache,
                paperBootstrapCache,
                config.nodeId());
        dispatcher.setProcessManager(processManager);
        dispatcher.setTemplateCache(templateCache);
        dispatcher.setCaches(jarCache, artifactCache, paperBootstrapCache);
        dispatcher.setShutdownCallback(() -> initiateShutdown(logger));
        grpcClient.setProcessInfo(
                processManager::instanceCount, processManager::usedPorts, processManager::getRunningInstances);

        // --- Reconnect manager (event-driven, triggered by onError/onCompleted) ---
        reconnectManager = new ReconnectManager(grpcClient, config.reconnect());
        grpcClient.setReconnectManager(reconnectManager);

        // --- Daemon-side platform-module host (Layer 7) ---
        daemonModuleScheduler = java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "prexor-daemon-module-scheduler");
            t.setDaemon(true);
            return t;
        });
        var daemonTaskScheduler = new DaemonTaskScheduler(daemonModuleScheduler);
        var daemonEventBus = new DaemonEventBus(grpcClient);
        reconnectManager.addReconnectListener(daemonEventBus::onReconnect);
        var daemonCapabilityRuntime = new CapabilityRegistry();
        var daemonCapabilityRegistry = new DaemonCapabilityRegistryImpl(daemonCapabilityRuntime);
        var daemonModuleStore = new DaemonModuleStore(Path.of("cache", "modules"));
        var daemonModuleHost = new DaemonModuleHost();
        var moduleSignatureVerifier =
                buildDaemonSignatureVerifier(config.modules().signing());
        daemonModuleManager = new DaemonModuleManager(
                daemonModuleStore,
                daemonModuleHost,
                daemonCapabilityRegistry,
                (manifest, jarPath, previousVersion, capabilities, _) -> new DaemonModuleContext(
                        manifest, jarPath, previousVersion, capabilities, daemonEventBus, daemonTaskScheduler),
                DaemonModuleManager.reporterTo(grpcClient::sendMessage),
                moduleSignatureVerifier);
        dispatcher.setDaemonModuleManager(daemonModuleManager);
        dispatcher.setDaemonEventBus(daemonEventBus);
        processManager.setDaemonModuleHost(daemonModuleHost);

        grpcClient.connect();

        if (config.health().enabled()) {
            healthServer = new HealthServer(
                    config.health(),
                    config.nodeId(),
                    config.controller().host(),
                    config.controller().grpcPort(),
                    () -> grpcClient != null && grpcClient.isConnected(),
                    () -> processManager != null ? processManager.instanceCount() : 0);
            healthServer.start();
        }

        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            logger.info("Shutting down...");
                            shutdown();
                        },
                        "shutdown-hook"));

        logger.info(
                "Daemon ready - controller {}:{} | max memory {} MB",
                config.controller().host(),
                config.controller().grpcPort(),
                maxMemory);
    }

    private static PlatformModuleSignatureVerifier buildDaemonSignatureVerifier(ModuleSigningDaemonConfig signing) {
        if (signing == null || !signing.required()) {
            return PlatformModuleSignatureVerifier.NOOP;
        }
        String trustRoot = signing.trustRoot();
        if (trustRoot == null || trustRoot.isBlank()) {
            return PlatformModuleSignatureVerifier.failClosed();
        }
        Path trustPath = Path.of(trustRoot);
        return switch (signing.mode()) {
            case KEYED -> PlatformModuleSignatureVerifier.TrustRootVerifier.fromPemBundle(trustPath);
            case COSIGN_BUNDLE -> PlatformModuleSignatureVerifier.CosignBundleVerifier.fromPemBundle(trustPath);
        };
    }

    /**
     * Initiates a graceful shutdown, typically triggered by the controller after a
     * node drain completes. Runs on a virtual thread to avoid blocking the gRPC
     * dispatch thread.
     */
    private void initiateShutdown(Logger logger) {
        Thread.startVirtualThread(() -> {
            logger.info("Controller requested shutdown (drain completed), shutting down...");
            shutdown();
        });
    }

    public void shutdown() {
        try {
            if (daemonModuleManager != null) daemonModuleManager.stopAll();
        } catch (Throwable _) {
        }
        try {
            if (processManager != null) processManager.stopAll();
        } catch (Throwable _) {
        }
        try {
            if (reconnectManager != null) reconnectManager.stop();
        } catch (Throwable _) {
        }
        try {
            if (daemonModuleScheduler != null) daemonModuleScheduler.shutdownNow();
        } catch (Throwable _) {
        }
        try {
            DaemonLogPublisher.get().unbind();
        } catch (Throwable _) {
        }
        try {
            if (grpcClient != null) grpcClient.disconnect();
        } catch (Throwable _) {
        }
        try {
            if (healthServer != null) healthServer.close();
        } catch (Throwable _) {
        }
        try {
            if (telemetry != null) telemetry.close();
        } catch (Throwable _) {
        }
        shutdownLatch.countDown();
    }

    public void awaitShutdown() throws InterruptedException {
        shutdownLatch.await();
    }

    public DaemonConfig config() {
        return config;
    }

    /** Test-only accessor for the daemon-side platform-module manager. */
    public DaemonModuleManager daemonModuleManager() {
        return daemonModuleManager;
    }

    /**
     * Register gRPC's default load-balancer and name-resolver providers explicitly.
     *
     * <p>These ship in grpc-core as {@code META-INF/services} entries, but the shaded fat jar drops
     * grpc-core's service files during the shadow merge. On a server JVM gRPC discovers providers via
     * {@link java.util.ServiceLoader} only (the hard-coded fallback list is Android-only), so without
     * this the channel fails with "Could not find policy 'pick_first'". Registration is idempotent —
     * if the service files are ever fixed this simply re-registers the same providers.</p>
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
        Thread.currentThread().setContextClassLoader(PrexorDaemon.class.getClassLoader());
        registerGrpcProviders();

        try {
            DaemonConfig config;

            if (InteractiveSetup.isNeeded(CONFIG_PATH)) {
                config = InteractiveSetup.run(CONFIG_PATH);
            } else {
                config = YamlConfigLoader.load(CONFIG_PATH, DaemonConfig.class, DEFAULT_CONFIG);
            }

            LoggingSetup.configure(config.logging(), "daemon");
            ClassWarmup.loadErrorPathClasses();

            PrexorDaemon daemon = new PrexorDaemon(config);
            daemon.start();
            daemon.awaitShutdown();
        } catch (Exception e) {
            logger.error("Failed to start PrexorCloud Daemon: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
