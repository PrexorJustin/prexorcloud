package me.prexorjustin.prexorcloud.harness.module;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore;
import me.prexorjustin.prexorcloud.common.config.LoggingConfig;
import me.prexorjustin.prexorcloud.controller.PrexorCloudBootstrap;
import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.config.BackupConfig;
import me.prexorjustin.prexorcloud.controller.config.ControllerConfig;
import me.prexorjustin.prexorcloud.controller.config.CorsConfig;
import me.prexorjustin.prexorcloud.controller.config.CrashConfig;
import me.prexorjustin.prexorcloud.controller.config.DashboardConfig;
import me.prexorjustin.prexorcloud.controller.config.DatabaseConfig;
import me.prexorjustin.prexorcloud.controller.config.GrpcConfig;
import me.prexorjustin.prexorcloud.controller.config.HeartbeatConfig;
import me.prexorjustin.prexorcloud.controller.config.HttpConfig;
import me.prexorjustin.prexorcloud.controller.config.MaintenanceConfig;
import me.prexorjustin.prexorcloud.controller.config.MetricsConfig;
import me.prexorjustin.prexorcloud.controller.config.ModuleSigningConfig;
import me.prexorjustin.prexorcloud.controller.config.ModulesConfig;
import me.prexorjustin.prexorcloud.controller.config.NetworkConfig;
import me.prexorjustin.prexorcloud.controller.config.RateLimitingConfig;
import me.prexorjustin.prexorcloud.controller.config.RedisConfig;
import me.prexorjustin.prexorcloud.controller.config.RuntimeConfig;
import me.prexorjustin.prexorcloud.controller.config.SchedulerConfig;
import me.prexorjustin.prexorcloud.controller.config.SecurityControllerConfig;
import me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleManager;
import me.prexorjustin.prexorcloud.controller.module.runtime.MongoModuleDataStore;
import me.prexorjustin.prexorcloud.modules.runtime.CapabilityRegistry;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Hermetic integration-test harness for {@code PlatformModule} authors.
 *
 * <p>Boots ephemeral MongoDB + Redis via Testcontainers, then starts an
 * in-process PrexorCloud controller pointed at them. The harness is the
 * lighter sibling of {@code TestCluster} — no daemon, no gRPC chatter, no
 * external-service prerequisite. Module authors get real Mongo persistence
 * and a real Javalin REST surface to exercise their module against.
 *
 * <p>Typical usage from a module's {@code src/test/java}:
 * <pre>{@code
 * Assumptions.assumeTrue(ModuleTestHarness.isDockerAvailable());
 * try (var harness = ModuleTestHarness.start()) {
 *     var installed = harness.installFromJar(Path.of(System.getProperty("module.jar")));
 *     var store = harness.dataStoreFor(installed.moduleId());
 *     // assertions against real Mongo, real REST, real capability registry
 * }
 * }</pre>
 *
 * <p>The harness installs modules via the production
 * {@link PlatformModuleManager#install(Path)} path — same jar prep, same
 * classloader isolation, same capability activation that real installs use.
 * Module signing is in {@code allowUnsignedDevelopment} mode for tests.
 */
public final class ModuleTestHarness implements AutoCloseable {

    private static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:7.0");
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");

    private final MongoDBContainer mongoContainer;
    private final GenericContainer<?> redisContainer;
    private final Path controllerDir;
    private final PrexorCloudBootstrap bootstrap;
    private final PrexorController controller;
    private final MongoClient harnessMongoClient;
    private final String databaseName;
    private final ObjectMapper storageMapper;
    private final int httpPort;
    private final String adminPassword;
    private volatile String cachedJwt;

    private ModuleTestHarness(
            MongoDBContainer mongoContainer,
            GenericContainer<?> redisContainer,
            Path controllerDir,
            PrexorCloudBootstrap bootstrap,
            PrexorController controller,
            MongoClient harnessMongoClient,
            String databaseName,
            ObjectMapper storageMapper,
            int httpPort,
            String adminPassword) {
        this.mongoContainer = mongoContainer;
        this.redisContainer = redisContainer;
        this.controllerDir = controllerDir;
        this.bootstrap = bootstrap;
        this.controller = controller;
        this.harnessMongoClient = harnessMongoClient;
        this.databaseName = databaseName;
        this.storageMapper = storageMapper;
        this.httpPort = httpPort;
        this.adminPassword = adminPassword;
    }

    /** True when Docker is reachable. Pair with {@code Assumptions.assumeTrue} to skip gracefully. */
    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Boot a fresh harness. Allocates ephemeral ports, starts containers, starts the controller.
     * Caller must {@link #close()} (use try-with-resources).
     */
    public static ModuleTestHarness start() throws Exception {
        MongoDBContainer mongo = new MongoDBContainer(MONGO_IMAGE);
        GenericContainer<?> redis = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
        mongo.start();
        redis.start();

        String mongoUri = mongo.getConnectionString();
        String redisUri = "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
        String databaseName =
                "prexor_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        Path controllerDir = Files.createTempDirectory("prexor-module-harness-");
        Files.createDirectories(controllerDir.resolve("config/security"));
        Files.createDirectories(controllerDir.resolve("data"));
        Files.createDirectories(controllerDir.resolve("templates"));
        Files.createDirectories(controllerDir.resolve("groups"));
        Files.createDirectories(controllerDir.resolve("modules/data"));
        Files.createDirectories(controllerDir.resolve("backups"));

        int httpPort = findFreePort();
        int grpcPort = findFreePort();

        byte[] secretBytes = new byte[32];
        new SecureRandom().nextBytes(secretBytes);
        String jwtSecret = Base64.getEncoder().encodeToString(secretBytes);
        String adminPassword = "harness-admin-" + UUID.randomUUID().toString().substring(0, 8);

        ControllerConfig config = new ControllerConfig(
                UUID.randomUUID().toString(),
                new HttpConfig("127.0.0.1", httpPort, new CorsConfig()),
                new GrpcConfig("127.0.0.1", grpcPort),
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
                        new ModuleSigningConfig()),
                new MaintenanceConfig(false, null),
                new DashboardConfig(),
                new BackupConfig(controllerDir.resolve("backups").toString(), 10),
                new me.prexorjustin.prexorcloud.controller.config.ShareConfig(),
                new RedisConfig(redisUri));

        String previousUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", controllerDir.toString());
        PrexorCloudBootstrap bootstrap;
        PrexorController controller;
        try {
            bootstrap = new PrexorCloudBootstrap(config);
            controller = bootstrap.start();
        } finally {
            System.setProperty("user.dir", previousUserDir);
        }

        MongoClient mongoClient = MongoClients.create(mongoUri);
        ObjectMapper storageMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return new ModuleTestHarness(
                mongo,
                redis,
                controllerDir,
                bootstrap,
                controller,
                mongoClient,
                databaseName,
                storageMapper,
                httpPort,
                adminPassword);
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    /** The live controller. Power-user escape hatch for tests that need access to internal services. */
    public PrexorController controller() {
        return controller;
    }

    /** Base URL of the controller's REST server, e.g. {@code http://127.0.0.1:54321}. */
    public String restBaseUrl() {
        return "http://127.0.0.1:" + httpPort;
    }

    /**
     * Issued admin JWT, lazily fetched via {@code POST /api/v1/auth/login}. Use in
     * {@code Authorization: Bearer <token>} headers for REST round-trip assertions.
     */
    public String adminJwt() throws Exception {
        if (cachedJwt == null) {
            cachedJwt = loginAsAdmin();
        }
        return cachedJwt;
    }

    /** The platform capability registry. Useful for asserting bindings the module advertised. */
    public CapabilityRegistry capabilities() {
        return controller.moduleRegistry().platformManager().capabilityRegistry();
    }

    // ── Module install + storage ─────────────────────────────────────────────

    /**
     * Install a module from its shadow JAR. Runs the same prep, signature
     * check, classloader bind, and lifecycle sequence the production controller
     * runs. Returns the managed snapshot the controller now tracks.
     */
    public PlatformModuleManager.ManagedPlatformModule installFromJar(Path jar) {
        return controller.moduleRegistry().platformManager().install(jar);
    }

    /**
     * Open a fresh {@link ModuleDataStore} view for the given module id.
     * Collection naming matches the production {@code platform_<sanitized>_*}
     * convention used by {@code PlatformModuleStorageManager}, so a module
     * installed via {@link #installFromJar} sees the same collections through
     * its own {@code ModuleContext.requireMongoStorage()}.
     */
    public ModuleDataStore dataStoreFor(String moduleId) {
        String sanitized = moduleId.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return MongoModuleDataStore.withCollectionPrefix(
                "platform_" + sanitized + "_",
                harnessMongoClient.getDatabase(databaseName),
                harnessMongoClient,
                storageMapper);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void close() {
        try {
            bootstrap.close();
        } catch (RuntimeException ignored) {
            // best-effort: continue tearing down containers + temp dir
        }
        try {
            harnessMongoClient.close();
        } catch (RuntimeException ignored) {
            // best-effort
        }
        try {
            redisContainer.stop();
        } catch (RuntimeException ignored) {
            // best-effort
        }
        try {
            mongoContainer.stop();
        } catch (RuntimeException ignored) {
            // best-effort
        }
        deleteRecursively(controllerDir);
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private String loginAsAdmin() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(restBaseUrl() + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"admin\",\"password\":\"" + adminPassword + "\"}"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "admin login failed: HTTP " + response.statusCode() + " " + response.body());
        }
        return new ObjectMapper().readTree(response.body()).get("token").asText();
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("no free port available", e);
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
