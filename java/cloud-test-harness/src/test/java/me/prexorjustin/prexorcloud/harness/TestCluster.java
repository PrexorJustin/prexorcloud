package me.prexorjustin.prexorcloud.harness;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import me.prexorjustin.prexorcloud.common.config.LoggingConfig;
import me.prexorjustin.prexorcloud.common.io.FileTrees;
import me.prexorjustin.prexorcloud.controller.PrexorCloudBootstrap;
import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.config.*;
import me.prexorjustin.prexorcloud.daemon.PrexorDaemon;
import me.prexorjustin.prexorcloud.daemon.config.*;

/**
 * Boots a real PrexorController + N PrexorDaemon instances in-process for integration testing.
 * Uses temp directories and random ports to avoid conflicts.
 */
public final class TestCluster implements AutoCloseable {

    private final Path workDir;
    private volatile int httpPort;
    private final int grpcPort;
    private final String jwtSecret;
    private final String adminPassword;
    private final String databaseName;
    private final String mongoUri;
    private final Map<String, ManagedDaemon> managedDaemons = new ConcurrentHashMap<>();
    private final List<ManagedController> managedControllers = new CopyOnWriteArrayList<>();
    private final Path sharedControllerDir;
    private final boolean controllerProxyEnabled;

    private PrexorController controller;
    private PrexorCloudBootstrap controllerBootstrap;
    private final List<PrexorDaemon> daemons = new CopyOnWriteArrayList<>();
    private String adminJwtToken;
    private ManagedController activeController;
    private SwitchableTcpProxy grpcProxy;
    private me.prexorjustin.prexorcloud.controller.config.ModuleSigningConfig signingOverride;

    private static final class ManagedDaemon {
        private final String nodeId;
        private final Path daemonDir;
        private final DaemonConfig config;
        private volatile PrexorDaemon daemon;
        private volatile Thread thread;

        private ManagedDaemon(String nodeId, Path daemonDir, DaemonConfig config) {
            this.nodeId = nodeId;
            this.daemonDir = daemonDir;
            this.config = config;
        }
    }

    private static final class ManagedController {
        private final String controllerId;
        private final Path controllerDir;
        private final int httpPort;
        private final int grpcPort;
        private volatile PrexorController controller;
        private volatile PrexorCloudBootstrap bootstrap;

        private ManagedController(String controllerId, Path controllerDir, int httpPort, int grpcPort) {
            this.controllerId = controllerId;
            this.controllerDir = controllerDir;
            this.httpPort = httpPort;
            this.grpcPort = grpcPort;
        }
    }

    private static final class SwitchableTcpProxy implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final AtomicReference<InetSocketAddress> backend = new AtomicReference<>();
        private final List<Socket> openSockets = new CopyOnWriteArrayList<>();
        private final Thread acceptThread;

        private SwitchableTcpProxy(int listenPort, InetSocketAddress initialBackend) throws IOException {
            this.serverSocket = new ServerSocket(listenPort);
            this.serverSocket.setReuseAddress(true);
            this.backend.set(initialBackend);
            this.acceptThread =
                    Thread.ofVirtual().name("testcluster-grpc-proxy").start(this::acceptLoop);
        }

        void switchBackend(InetSocketAddress newBackend) {
            backend.set(newBackend);
        }

        private void acceptLoop() {
            try {
                while (!serverSocket.isClosed()) {
                    Socket client = serverSocket.accept();
                    openSockets.add(client);
                    InetSocketAddress target = backend.get();
                    Socket upstream = new Socket();
                    upstream.connect(target, 5_000);
                    openSockets.add(upstream);
                    Thread.startVirtualThread(() -> pipe(client, upstream));
                    Thread.startVirtualThread(() -> pipe(upstream, client));
                }
            } catch (IOException _) {
                // Socket closed during shutdown.
            }
        }

        private void pipe(Socket source, Socket target) {
            try (source;
                    target;
                    InputStream in = source.getInputStream();
                    OutputStream out = target.getOutputStream()) {
                in.transferTo(out);
            } catch (IOException _) {
                // Connection dropped or proxy switched.
            } finally {
                openSockets.remove(source);
                openSockets.remove(target);
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            for (Socket socket : openSockets) {
                try {
                    socket.close();
                } catch (IOException _) {
                }
            }
        }
    }

    private TestCluster(
            Path workDir,
            int httpPort,
            int grpcPort,
            String jwtSecret,
            String adminPassword,
            String databaseName,
            String mongoUri,
            Path sharedControllerDir,
            boolean controllerProxyEnabled) {
        this.workDir = workDir;
        this.httpPort = httpPort;
        this.grpcPort = grpcPort;
        this.jwtSecret = jwtSecret;
        this.adminPassword = adminPassword;
        this.databaseName = databaseName;
        this.mongoUri = mongoUri;
        this.sharedControllerDir = sharedControllerDir;
        this.controllerProxyEnabled = controllerProxyEnabled;
    }

    public static TestCluster start() throws Exception {
        return start(0);
    }

    /**
     * Start a test cluster with the specified number of daemons.
     */
    public static TestCluster start(int daemonCount) throws Exception {
        Path workDir = Files.createTempDirectory("prexorcloud-test-");
        int httpPort = findFreePort();
        int grpcPort = findFreePort();
        // Generate a valid base64-encoded 256-bit JWT secret
        byte[] secretBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(secretBytes);
        String jwtSecret = java.util.Base64.getEncoder().encodeToString(secretBytes);
        String adminPassword = "testadmin123";
        String databaseName = "prexorcloud-harness-" + UUID.randomUUID();
        String mongoUri = resolveMongoUri();

        var cluster = new TestCluster(
                workDir,
                httpPort,
                grpcPort,
                jwtSecret,
                adminPassword,
                databaseName,
                mongoUri,
                workDir.resolve("controller"),
                false);
        cluster.startPrimaryController(httpPort, grpcPort);

        for (int i = 0; i < daemonCount; i++) {
            cluster.addDaemon("test-node-" + (i + 1));
        }

        return cluster;
    }

    /**
     * Start a test cluster with a custom module signing configuration. Used by integration
     * tests that exercise the cosign-bundle verifier through the real REST upload path.
     */
    public static TestCluster startWithSigning(
            me.prexorjustin.prexorcloud.controller.config.ModuleSigningConfig signing) throws Exception {
        Path workDir = Files.createTempDirectory("prexorcloud-test-");
        int httpPort = findFreePort();
        int grpcPort = findFreePort();
        byte[] secretBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(secretBytes);
        String jwtSecret = java.util.Base64.getEncoder().encodeToString(secretBytes);
        String adminPassword = "testadmin123";
        String databaseName = "prexorcloud-harness-" + UUID.randomUUID();
        String mongoUri = resolveMongoUri();

        var cluster = new TestCluster(
                workDir,
                httpPort,
                grpcPort,
                jwtSecret,
                adminPassword,
                databaseName,
                mongoUri,
                workDir.resolve("controller"),
                false);
        cluster.signingOverride = Objects.requireNonNull(signing, "signing");
        cluster.startPrimaryController(httpPort, grpcPort);
        return cluster;
    }

    public static TestCluster startHa(int daemonCount, int controllerCount) throws Exception {
        if (controllerCount < 2) {
            throw new IllegalArgumentException("HA test clusters require at least 2 controllers");
        }
        Path workDir = Files.createTempDirectory("prexorcloud-ha-test-");
        int stableHttpPort = findFreePort();
        int stableGrpcPort = findFreePort();
        byte[] secretBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(secretBytes);
        String jwtSecret = java.util.Base64.getEncoder().encodeToString(secretBytes);
        String adminPassword = "testadmin123";
        String databaseName = "prexorcloud-harness-" + UUID.randomUUID();
        String mongoUri = resolveMongoUri();
        Path sharedControllerDir = workDir.resolve("controller-shared");

        var cluster = new TestCluster(
                workDir,
                stableHttpPort,
                stableGrpcPort,
                jwtSecret,
                adminPassword,
                databaseName,
                mongoUri,
                sharedControllerDir,
                true);
        cluster.startPrimaryController(findFreePort(), findFreePort());
        cluster.grpcProxy = new SwitchableTcpProxy(
                stableGrpcPort, new InetSocketAddress("127.0.0.1", cluster.activeController.grpcPort));

        for (int i = 1; i < controllerCount; i++) {
            cluster.startAdditionalController(findFreePort(), findFreePort());
        }

        for (int i = 0; i < daemonCount; i++) {
            cluster.addDaemon("test-node-" + (i + 1));
        }
        return cluster;
    }

    public static boolean mongoAvailable() {
        return socketAvailable(resolveMongoUri(), 27017, 500);
    }

    private void startPrimaryController(int directHttpPort, int directGrpcPort) throws Exception {
        ManagedController runtime = startControllerInstance(directHttpPort, directGrpcPort);
        activeController = runtime;
        controllerBootstrap = runtime.bootstrap;
        controller = runtime.controller;
        httpPort = runtime.httpPort;
        adminJwtToken = loginAsAdmin();
    }

    private ManagedController startAdditionalController(int directHttpPort, int directGrpcPort) throws Exception {
        return startControllerInstance(directHttpPort, directGrpcPort);
    }

    private ManagedController startControllerInstance(int directHttpPort, int directGrpcPort) throws Exception {
        waitForMongoAvailability(mongoUri, 10_000);

        Path controllerDir = sharedControllerDir;
        Files.createDirectories(controllerDir);

        Files.createDirectories(controllerDir.resolve("config"));
        Files.createDirectories(controllerDir.resolve("config/security"));
        Files.createDirectories(controllerDir.resolve("data"));
        Files.createDirectories(controllerDir.resolve("templates"));
        Files.createDirectories(controllerDir.resolve("groups"));
        Files.createDirectories(controllerDir.resolve("modules"));
        Files.createDirectories(controllerDir.resolve("modules/data"));

        String controllerId = UUID.randomUUID().toString();
        // Each controller instance gets its own Raft port and data dir. The default
        // RaftConfig hardcodes port 9190 and dir data/raft, so an HA cluster (two
        // concurrent controllers) or two single-controller tests in the same JVM
        // would both try to bind 9190 and share one Raft store — the second peer's
        // control plane fails to start and awaitLeader() times out after 15s.
        // The Raft dir is keyed by controllerId rather than the shared controllerDir
        // so that the on-disk backup catalog still survives a stopController() +
        // startControllerAfterStop() restart (see startControllerAfterStop). Each
        // controller stays its own single-node group (empty joinAddrs) — these tests
        // exercise failover via the gRPC proxy and shared Mongo, not Raft quorum.
        int raftPort = findFreePort();
        Path raftDataDir = controllerDir.resolve("data").resolve("raft-" + controllerId);
        var config = new ControllerConfig(
                controllerId,
                new HttpConfig("127.0.0.1", directHttpPort, new CorsConfig()),
                new GrpcConfig("127.0.0.1", directGrpcPort),
                new NetworkConfig(),
                new DatabaseConfig(mongoUri, databaseName),
                new LoggingConfig("WARN", LoggingConfig.LogFormat.HUMAN),
                new SchedulerConfig(2, 30, 30, 30),
                new HeartbeatConfig(5000, 3),
                new RuntimeConfig(RuntimeConfig.DEVELOPMENT),
                new SecurityControllerConfig(jwtSecret, 60, adminPassword, new RateLimitingConfig()),
                new CrashConfig(),
                new MetricsConfig(true, 168, 30),
                new ModulesConfig(
                        controllerDir.resolve("modules").toString(),
                        controllerDir.resolve("modules/data").toString(),
                        signingOverride),
                new me.prexorjustin.prexorcloud.controller.config.MaintenanceConfig(false, null),
                new DashboardConfig(),
                new me.prexorjustin.prexorcloud.controller.config.BackupConfig(
                        controllerDir.resolve("backups").toString(), 10),
                new me.prexorjustin.prexorcloud.controller.config.ShareConfig(),
                List.of(),
                List.of(),
                null,
                new RaftConfig("127.0.0.1", raftPort, raftDataDir.toString(), List.of()));

        String previousDir = System.getProperty("user.dir");
        System.setProperty("user.dir", controllerDir.toString());
        try {
            var bootstrap = new PrexorCloudBootstrap(config);
            var startedController = bootstrap.start();
            var runtime = new ManagedController(controllerId, controllerDir, directHttpPort, directGrpcPort);
            runtime.bootstrap = bootstrap;
            runtime.controller = startedController;
            managedControllers.add(runtime);
            return runtime;
        } finally {
            System.setProperty("user.dir", previousDir);
        }
    }

    public void restartController() throws Exception {
        stopController();
        startControllerAfterStop();
    }

    /**
     * Shut down the active controller bootstrap without starting a replacement.
     * Pair with {@link #startControllerAfterStop()} when intermediate state
     * (e.g. wiping datastores for a DR drill) needs to happen in between.
     */
    public void stopController() throws Exception {
        ManagedController current = activeController;
        if (current != null && current.bootstrap != null) {
            current.bootstrap.close();
            current.bootstrap = null;
            current.controller = null;
            managedControllers.remove(current);
        }
        controllerBootstrap = null;
        controller = null;
        Thread.sleep(500);
    }

    /**
     * Bring the controller back after a {@link #stopController()} call. Reuses
     * the same shared controller directory (and therefore the on-disk backup
     * catalog), so a wipe of Mongo between the two calls leaves the
     * filesystem-side bundles intact.
     */
    public void startControllerAfterStop() throws Exception {
        if (controllerProxyEnabled) {
            startPrimaryController(findFreePort(), findFreePort());
            if (grpcProxy != null && activeController != null) {
                grpcProxy.switchBackend(new InetSocketAddress("127.0.0.1", activeController.grpcPort));
            }
        } else {
            startPrimaryController(httpPort, grpcPort);
        }
    }

    public void failoverController() throws Exception {
        if (!controllerProxyEnabled) {
            throw new IllegalStateException("Failover requires an HA cluster started with startHa");
        }
        ManagedController current = activeController;
        ManagedController standby = managedControllers.stream()
                .filter(runtime -> runtime != current)
                .filter(runtime -> runtime.bootstrap != null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No standby controller is available"));

        if (current != null && current.bootstrap != null) {
            current.bootstrap.close();
            current.bootstrap = null;
            current.controller = null;
            managedControllers.remove(current);
        }
        activeController = standby;
        controllerBootstrap = standby.bootstrap;
        controller = standby.controller;
        httpPort = standby.httpPort;
        if (grpcProxy != null) {
            grpcProxy.switchBackend(new InetSocketAddress("127.0.0.1", standby.grpcPort));
        }
        Thread.sleep(500);
        adminJwtToken = loginAsAdmin();
    }

    /**
     * Add a daemon to the running cluster. Creates a join token, bootstraps, and connects.
     */
    public void addDaemon(String nodeId) throws Exception {
        if (managedDaemons.containsKey(nodeId)) {
            throw new IllegalArgumentException("Daemon " + nodeId + " is already managed by this cluster");
        }
        var daemon = new ManagedDaemon(nodeId, daemonDirectory(nodeId), createDaemonConfig(nodeId));
        managedDaemons.put(nodeId, daemon);
        startManagedDaemon(daemon);
    }

    public void stopDaemon(String nodeId) throws Exception {
        var daemon = managedDaemons.get(nodeId);
        if (daemon == null || daemon.daemon == null) {
            throw new IllegalArgumentException("Daemon " + nodeId + " is not running");
        }
        daemon.daemon.shutdown();
        Thread thread = daemon.thread;
        if (thread != null) {
            thread.join(10_000);
        }
        daemon.daemon = null;
        daemon.thread = null;
        waitForNodeAbsent(nodeId, 10_000);
    }

    public void restartDaemon(String nodeId) throws Exception {
        var daemon = managedDaemons.get(nodeId);
        if (daemon == null) {
            throw new IllegalArgumentException("Daemon " + nodeId + " is not managed by this cluster");
        }
        if (daemon.daemon != null) {
            stopDaemon(nodeId);
        }
        startManagedDaemon(daemon);
    }

    public void waitForNode(String nodeId, long timeoutMs) throws InterruptedException {
        waitForCondition(
                "node " + nodeId + " to appear in cluster state",
                timeoutMs,
                () -> controller != null
                        && controller.clusterState().getNode(nodeId).isPresent());
    }

    public void waitForNodeAbsent(String nodeId, long timeoutMs) throws InterruptedException {
        waitForCondition(
                "node " + nodeId + " to leave cluster state",
                timeoutMs,
                () -> controller == null
                        || controller.clusterState().getNode(nodeId).isEmpty());
    }

    public void waitForCondition(String description, long timeoutMs, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new RuntimeException("Timed out waiting for " + description + " within " + timeoutMs + "ms");
    }

    private String loginAsAdmin() throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(restBaseUrl() + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"admin\",\"password\":\"" + adminPassword + "\"}"))
                .build();

        var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Admin login failed: " + response.statusCode() + " " + response.body());
        }

        // Parse token from response
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var json = mapper.readTree(response.body());
        return json.get("token").asText();
    }

    // --- Accessors ---

    public String restBaseUrl() {
        return "http://127.0.0.1:" + httpPort;
    }

    public int httpPort() {
        return httpPort;
    }

    public int grpcPort() {
        return grpcPort;
    }

    public String mongoUri() {
        return mongoUri;
    }

    public String databaseName() {
        return databaseName;
    }

    /**
     * Drop the controller's Mongo database associated with this cluster. Intended
     * to be called between {@link #close()}-ing the controller bootstrap and
     * {@link #restartController()} to simulate a full datastore loss for the DR
     * drill harness.
     */
    public void wipeDatastores() {
        try (var mongo = com.mongodb.client.MongoClients.create(mongoUri)) {
            mongo.getDatabase(databaseName).drop();
        }
    }

    public String adminJwtToken() {
        return adminJwtToken;
    }

    public String adminPassword() {
        return adminPassword;
    }

    public PrexorController controller() {
        return controller;
    }

    public List<PrexorDaemon> daemons() {
        return Collections.unmodifiableList(daemons);
    }

    public Path workDir() {
        return workDir;
    }

    /**
     * Create a new JWT token for a user with a specific role by logging in.
     */
    public String loginAs(String username, String password) throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(restBaseUrl() + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .build();

        var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Login failed for " + username + ": " + response.statusCode());
        }

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        return mapper.readTree(response.body()).get("token").asText();
    }

    // --- Lifecycle ---

    @Override
    public void close() {
        for (var daemon : managedDaemons.values()) {
            if (daemon.daemon != null) {
                daemon.daemon.shutdown();
            }
        }

        for (var managedController : managedControllers) {
            if (managedController.bootstrap != null) {
                managedController.bootstrap.close();
            }
        }

        if (grpcProxy != null) {
            try {
                grpcProxy.close();
            } catch (IOException _) {
            }
        }

        try {
            Thread.sleep(500);
        } catch (InterruptedException _) {
        }

        // Cleanup temp directory
        try {
            deleteRecursively(workDir);
        } catch (IOException _) {
            // Best effort cleanup
        }
    }

    // --- Utilities ---

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("No free port available", e);
        }
    }

    private Path daemonDirectory(String nodeId) {
        return workDir.resolve("daemon-" + nodeId);
    }

    private DaemonConfig createDaemonConfig(String nodeId) throws Exception {
        var tokenResult = controller.joinTokenStore().create(nodeId, 3600);
        String joinToken = tokenResult.plaintextToken();

        Path daemonDir = daemonDirectory(nodeId);
        Files.createDirectories(daemonDir);
        Files.createDirectories(daemonDir.resolve("config"));
        Files.createDirectories(daemonDir.resolve("config/security"));
        Files.createDirectories(daemonDir.resolve("instances"));
        Files.createDirectories(daemonDir.resolve("cache/templates"));
        Files.createDirectories(daemonDir.resolve("cache/jars"));
        Files.createDirectories(daemonDir.resolve("cache/paper-bootstrap"));

        return new DaemonConfig(
                nodeId,
                "127.0.0.1",
                new ControllerConnectionConfig("127.0.0.1", grpcPort),
                new HealthConfig(false, "127.0.0.1", findFreePort()),
                new SecurityDaemonConfig(daemonDir.resolve("config/security").toString(), joinToken),
                new InstancesConfig(daemonDir.resolve("instances").toString(), 10, 5, 100, 100),
                new ResourcesConfig(4096),
                new LoggingConfig("WARN", LoggingConfig.LogFormat.HUMAN),
                new ReconnectConfig(),
                daemonModulesConfig,
                new TelemetryDaemonConfig(),
                Map.of());
    }

    private DaemonConfig.ModulesDaemonConfig daemonModulesConfig = new DaemonConfig.ModulesDaemonConfig();

    /**
     * Override the modules.signing block of any daemon spun up after this call. Used by
     * the cosign integration test to enforce signature verification on the daemon side.
     */
    public void setDaemonModuleSigning(ModuleSigningDaemonConfig signing) {
        this.daemonModulesConfig = new DaemonConfig.ModulesDaemonConfig(signing);
    }

    private void startManagedDaemon(ManagedDaemon managedDaemon) throws Exception {
        CountDownLatch started = new CountDownLatch(1);

        Thread daemonThread = Thread.ofVirtual()
                .name("test-daemon-" + managedDaemon.nodeId)
                .start(() -> {
                    String prev = System.getProperty("user.dir");
                    System.setProperty("user.dir", managedDaemon.daemonDir.toString());
                    try {
                        var daemon = new PrexorDaemon(managedDaemon.config);
                        managedDaemon.daemon = daemon;
                        managedDaemon.thread = Thread.currentThread();
                        daemons.add(daemon);
                        daemon.start();
                        started.countDown();
                        daemon.awaitShutdown();
                    } catch (Exception e) {
                        started.countDown();
                        throw new RuntimeException("Daemon " + managedDaemon.nodeId + " failed to start", e);
                    } finally {
                        System.setProperty("user.dir", prev);
                    }
                });

        managedDaemon.thread = daemonThread;
        if (!started.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Daemon " + managedDaemon.nodeId + " did not start within 30 seconds");
        }
        waitForNode(managedDaemon.nodeId, 10_000);
    }

    private static String resolveMongoUri() {
        String systemProperty = System.getProperty("prexor.test.mongoUri");
        if (systemProperty != null && !systemProperty.isBlank()) {
            return systemProperty;
        }
        String environment = System.getenv("PREXOR_TEST_MONGO_URI");
        if (environment != null && !environment.isBlank()) {
            return environment;
        }
        return "mongodb://127.0.0.1:27017";
    }

    private static void waitForMongoAvailability(String mongoUri, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (socketAvailable(mongoUri, 27017, 1_000)) {
                return;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("MongoDB test dependency is not reachable at " + mongoUri
                + ". Start MongoDB locally or set PREXOR_TEST_MONGO_URI / -Dprexor.test.mongoUri.");
    }

    private static boolean socketAvailable(String endpointUri, int defaultPort, int timeoutMs) {
        URI uri = URI.create(endpointUri);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : defaultPort;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException _) {
            return false;
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        FileTrees.deleteRecursivelyTolerant(path);
    }
}
