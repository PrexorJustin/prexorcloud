package me.prexorjustin.prexorcloud.daemon.process;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import me.prexorjustin.prexorcloud.common.util.HashUtil;
import me.prexorjustin.prexorcloud.daemon.config.InstancesConfig;
import me.prexorjustin.prexorcloud.daemon.grpc.DaemonGrpcClient;
import me.prexorjustin.prexorcloud.daemon.template.ArtifactCache;
import me.prexorjustin.prexorcloud.daemon.template.JarCache;
import me.prexorjustin.prexorcloud.daemon.template.PaperBootstrapCache;
import me.prexorjustin.prexorcloud.daemon.template.TemplateCache;
import me.prexorjustin.prexorcloud.protocol.CompositionPlan;
import me.prexorjustin.prexorcloud.protocol.ConfigFormat;
import me.prexorjustin.prexorcloud.protocol.ExtensionArtifact;
import me.prexorjustin.prexorcloud.protocol.InstanceCategory;
import me.prexorjustin.prexorcloud.protocol.RuntimeArtifact;
import me.prexorjustin.prexorcloud.protocol.StartFailureDisposition;
import me.prexorjustin.prexorcloud.protocol.StartInstance;
import me.prexorjustin.prexorcloud.protocol.StartPreparationStage;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ProcessManager")
class ProcessManagerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("replaceDirectory swaps staged content into the live instance dir")
    void replaceDirectoryReplacesExistingTarget() throws Exception {
        Path source = tempDir.resolve("staging").resolve("instance");
        Path target = tempDir.resolve("live").resolve("instance");
        Files.createDirectories(source);
        Files.createDirectories(target);
        Files.writeString(source.resolve("new.txt"), "new");
        Files.writeString(target.resolve("old.txt"), "old");

        ProcessManager.replaceDirectory(source, target);

        assertFalse(Files.exists(source));
        assertTrue(Files.exists(target.resolve("new.txt")));
        assertFalse(Files.exists(target.resolve("old.txt")));
    }

    @Test
    @DisplayName("replaceDirectory restores the live dir when staged promotion fails")
    void replaceDirectoryRestoresExistingTargetOnFailure() throws Exception {
        Path missingSource = tempDir.resolve("staging").resolve("missing");
        Path target = tempDir.resolve("live").resolve("instance");
        Files.createDirectories(target);
        Files.writeString(target.resolve("old.txt"), "old");

        IOException exception =
                assertThrows(IOException.class, () -> ProcessManager.replaceDirectory(missingSource, target));

        assertTrue(exception.getMessage().contains("missing"));
        assertTrue(Files.exists(target.resolve("old.txt")));
    }

    @Test
    @DisplayName("deleteDirectoryTree removes nested preparation directories")
    void deleteDirectoryTreeRemovesNestedContent() throws Exception {
        Path dir = tempDir.resolve("nested");
        Files.createDirectories(dir.resolve("a").resolve("b"));
        Files.writeString(dir.resolve("a").resolve("b").resolve("marker.txt"), "x");

        assertTrue(ProcessManager.deleteDirectoryTree(dir));
        assertFalse(Files.exists(dir));
    }

    @Test
    @DisplayName("copyDirectoryTree clones nested static instance content into a staging dir")
    void copyDirectoryTreeClonesNestedContent() throws Exception {
        Path source = tempDir.resolve("live").resolve("instance");
        Path target = tempDir.resolve("staging").resolve("instance");
        Files.createDirectories(source.resolve("plugins"));
        Files.writeString(source.resolve("server.properties"), "server-port=25565");
        Files.writeString(source.resolve("plugins").resolve("marker.txt"), "present");

        ProcessManager.copyDirectoryTree(source, target);

        assertTrue(Files.exists(target.resolve("server.properties")));
        assertEquals("server-port=25565", Files.readString(target.resolve("server.properties")));
        assertEquals("present", Files.readString(target.resolve("plugins").resolve("marker.txt")));
    }

    @Test
    @DisplayName("stageStaticWorkspace restores the live dir if staging copy fails")
    void stageStaticWorkspaceRestoresLiveDirOnCopyFailure() throws Exception {
        Path instanceDir = tempDir.resolve("live").resolve("instance");
        Path badParent = tempDir.resolve("bad-parent");
        Path workingDir = badParent.resolve("instance");
        Path backupDir = tempDir.resolve("staging").resolve("instance-backup");
        Files.createDirectories(instanceDir);
        Files.writeString(instanceDir.resolve("server.properties"), "server-port=25565");
        Files.writeString(badParent, "not a directory");

        IOException exception = assertThrows(
                IOException.class, () -> ProcessManager.stageStaticWorkspace(instanceDir, workingDir, backupDir));

        assertTrue(exception.getMessage().contains("bad-parent")
                || exception.getMessage().contains("instance"));
        assertTrue(Files.exists(instanceDir.resolve("server.properties")));
        assertFalse(Files.exists(backupDir));
    }

    @Test
    @DisplayName("withPreparationRetries retries transient IO failures and then succeeds")
    void withPreparationRetriesRetriesIoFailures() throws Exception {
        AtomicInteger attempts = new AtomicInteger();

        String result = ProcessManager.withPreparationRetries("test operation", () -> {
            if (attempts.getAndIncrement() < 2) {
                throw new IOException("transient");
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(3, attempts.get());
    }

    @Test
    @DisplayName("withPreparationRetries fails fast for non-retryable errors")
    void withPreparationRetriesDoesNotRetrySecurityFailures() {
        AtomicInteger attempts = new AtomicInteger();

        SecurityException exception = assertThrows(
                SecurityException.class,
                () -> ProcessManager.withPreparationRetries("test operation", () -> {
                    attempts.incrementAndGet();
                    throw new SecurityException("hash mismatch");
                }));

        assertEquals("hash mismatch", exception.getMessage());
        assertEquals(1, attempts.get());
    }

    @Test
    @DisplayName("startInstance rejects extension artifacts with a hash mismatch")
    void startInstanceRejectsExtensionHashMismatch() throws Exception {
        byte[] runtimeBytes = "runtime-jar".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] extensionBytes = "extension-jar".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String runtimeHash = HashUtil.sha256(runtimeBytes);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(
                "/runtime.jar", exchange -> writeResponse(exchange.getResponseBody(), exchange, runtimeBytes));
        server.createContext(
                "/extensions/matchmaking.jar",
                exchange -> writeResponse(exchange.getResponseBody(), exchange, extensionBytes));
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ProcessManager manager = new ProcessManager(
                    tempDir.resolve("instances"),
                    new InstancesConfig(tempDir.resolve("instances").toString(), 30, 10, 500, 200),
                    mock(DaemonGrpcClient.class),
                    new TemplateCache(tempDir.resolve("cache").resolve("templates")),
                    new JarCache(tempDir.resolve("cache").resolve("jars")),
                    new ArtifactCache(tempDir.resolve("cache").resolve("artifacts")),
                    new PaperBootstrapCache(tempDir.resolve("cache").resolve("paper-bootstrap")),
                    "node-1");
            StartInstance request = StartInstance.newBuilder()
                    .setInstanceId("lobby-1")
                    .setGroup("lobby")
                    .setPort(25565)
                    .setMemoryMb(512)
                    .setPluginToken("plugin-token")
                    .setCompositionPlan(CompositionPlan.newBuilder()
                            .setPlanHash("plan-ext-hash-mismatch")
                            .setRuntime(RuntimeArtifact.newBuilder()
                                    .setJarFile("server.jar")
                                    .setDownloadUrl(baseUrl + "/runtime.jar")
                                    .setSha256(runtimeHash)
                                    .setCategory(InstanceCategory.SERVER)
                                    .setConfigFormat(ConfigFormat.CONFIG_FORMAT_UNSPECIFIED))
                            .addExtensions(ExtensionArtifact.newBuilder()
                                    .setModuleId("matchmaking-module")
                                    .setExtensionId("matchmaking-paper")
                                    .setVariantId("paper-1-20")
                                    .setFileName("matchmaking.jar")
                                    .setDownloadUrl(baseUrl + "/extensions/matchmaking.jar")
                                    .setSha256("0".repeat(64))
                                    .setInstallPath("plugins/")))
                    .build();
            CompletableFuture<ProcessManager.StartResult> resultFuture = new CompletableFuture<>();

            manager.startInstance(request, resultFuture::complete);
            ProcessManager.StartResult result = resultFuture.get(10, TimeUnit.SECONDS);

            assertFalse(result.accepted());
            assertEquals("EXTENSION_PROVISION_FAILED", result.errorCode());
            assertEquals("plan-ext-hash-mismatch", result.planHash());
            assertEquals(StartPreparationStage.EXTENSION_PROVISION, result.stage());
            assertEquals(StartFailureDisposition.PERMANENT, result.failureDisposition());
            assertEquals(0, result.retryAfterSeconds());
            assertFalse(manager.getProcess("lobby-1").isPresent());
            assertFalse(Files.exists(tempDir.resolve("instances")
                    .resolve("lobby")
                    .resolve("lobby-1")
                    .resolve("plugins")
                    .resolve("matchmaking.jar")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("server process exposes isolation hints through environment variables")
    void serverProcessPublishesIsolationHintsToEnvironment() throws Exception {
        var process = new ServerProcess(
                "lobby-1",
                "lobby",
                tempDir,
                128,
                200,
                mock(DaemonGrpcClient.class),
                30000,
                1024,
                0.25,
                4096,
                List.of(),
                Map.of(),
                "server.jar",
                "SERVER",
                "plugin-token",
                "node-1",
                30,
                10,
                crashed -> {});

        var envField = ServerProcess.class.getDeclaredField("env");
        envField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> env = (Map<String, String>) envField.get(process);

        assertEquals("plugin-token", env.get("CLOUD_PLUGIN_TOKEN"));
        assertEquals("0.25", env.get("CLOUD_CPU_RESERVATION"));
        assertEquals("4096", env.get("CLOUD_DISK_RESERVATION_MB"));
    }

    // ──────────────────────────────────────────────────────────────────
    // Failure-path coverage — one test per StartPreparationStage that
    // emits a StartResult. Together with the existing extension hash
    // test, this is the regression net for any future ProcessManager
    // refactor: every named failure stage has a test that asserts the
    // (stage, errorCode, disposition, retryAfter) tuple.
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("startInstance rejects requests without a composition plan synchronously")
    void startInstanceRejectsMissingCompositionPlan() throws Exception {
        ProcessManager manager = newManager();
        StartInstance request = StartInstance.newBuilder()
                .setInstanceId("lobby-1")
                .setGroup("lobby")
                .setPort(25565)
                .setMemoryMb(512)
                .setPluginToken("plugin-token")
                .build(); // no compositionPlan
        CompletableFuture<ProcessManager.StartResult> resultFuture = new CompletableFuture<>();
        manager.startInstance(request, resultFuture::complete);

        // Synchronous emit — we don't need the 10-second budget the async tests use.
        ProcessManager.StartResult result = resultFuture.get(1, TimeUnit.SECONDS);
        assertFalse(result.accepted());
        assertEquals("MISSING_COMPOSITION_PLAN", result.errorCode());
        assertEquals("", result.planHash());
        assertEquals(StartPreparationStage.VALIDATION, result.stage());
        assertEquals(StartFailureDisposition.PERMANENT, result.failureDisposition());
        assertEquals(0, result.retryAfterSeconds());
    }

    @Test
    @DisplayName("startInstance rejects a duplicate start while the first is in flight")
    void startInstanceRejectsDuplicateConcurrentStart() throws Exception {
        // Stand up an HTTP mirror that hangs on the runtime download so the
        // first start sits in startingInstances long enough for the second
        // call to race in and hit the duplicate branch.
        java.util.concurrent.CountDownLatch firstRequestArrived = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseFirstRequest = new java.util.concurrent.CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/runtime.jar", exchange -> {
            firstRequestArrived.countDown();
            try {
                releaseFirstRequest.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            // Respond with 404 so the first start fails cleanly after we release it.
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ProcessManager manager = newManager();
            StartInstance request =
                    newRequest("lobby-1", "lobby", baseUrl + "/runtime.jar", "0".repeat(64), "plan-dup");

            CompletableFuture<ProcessManager.StartResult> firstFuture = new CompletableFuture<>();
            manager.startInstance(request, firstFuture::complete);
            // Wait until the first start is mid-flight (download hanging).
            assertTrue(firstRequestArrived.await(5, TimeUnit.SECONDS), "first start should reach the runtime download");

            // Second concurrent start — must be rejected at VALIDATION as a duplicate.
            CompletableFuture<ProcessManager.StartResult> secondFuture = new CompletableFuture<>();
            manager.startInstance(request, secondFuture::complete);
            ProcessManager.StartResult duplicate = secondFuture.get(2, TimeUnit.SECONDS);

            assertFalse(duplicate.accepted());
            assertEquals("INSTANCE_ALREADY_STARTING", duplicate.errorCode());
            assertEquals(StartPreparationStage.VALIDATION, duplicate.stage());
            assertEquals(StartFailureDisposition.PERMANENT, duplicate.failureDisposition());

            // Release the first request so the test doesn't leak a virtual thread.
            releaseFirstRequest.countDown();
            firstFuture.get(10, TimeUnit.SECONDS); // drain
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("startInstance rejects a runtime artifact with a hash mismatch")
    void startInstanceRejectsRuntimeHashMismatch() throws Exception {
        byte[] runtimeBytes = "runtime-jar".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // Deliberately advertise a wrong sha256 in the request.
        String wrongHash = "0".repeat(64);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(
                "/runtime.jar", exchange -> writeResponse(exchange.getResponseBody(), exchange, runtimeBytes));
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ProcessManager manager = newManager();
            StartInstance request = newRequest("lobby-1", "lobby", baseUrl + "/runtime.jar", wrongHash, "plan-rt-hash");

            CompletableFuture<ProcessManager.StartResult> resultFuture = new CompletableFuture<>();
            manager.startInstance(request, resultFuture::complete);
            ProcessManager.StartResult result = resultFuture.get(10, TimeUnit.SECONDS);

            assertFalse(result.accepted());
            assertEquals("RUNTIME_PROVISION_FAILED", result.errorCode());
            assertEquals("plan-rt-hash", result.planHash());
            assertEquals(StartPreparationStage.RUNTIME_PROVISION, result.stage());
            assertEquals(StartFailureDisposition.PERMANENT, result.failureDisposition());
            assertEquals(0, result.retryAfterSeconds(), "permanent failures should not request a retry");
            assertFalse(manager.getProcess("lobby-1").isPresent());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("startInstance treats a runtime network failure as TRANSIENT")
    void startInstanceTreatsRuntimeNetworkFailureAsTransient() throws Exception {
        // Grab a port, then stop the server so the connect attempt is refused
        // at TCP level. The Java HTTP client surfaces this as IOException,
        // which `isRetryablePreparationFailure` classifies TRANSIENT — separate
        // from hash mismatches (SecurityException → PERMANENT) and validation
        // errors (IllegalArgumentException → PERMANENT).
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        server.start();
        server.stop(0);
        // Note: connection-refused on the closed port is the failure under test.
        String baseUrl = "http://127.0.0.1:" + port;
        ProcessManager manager = newManager();
        StartInstance request = newRequest("lobby-1", "lobby", baseUrl + "/runtime.jar", "0".repeat(64), "plan-rt-net");

        CompletableFuture<ProcessManager.StartResult> resultFuture = new CompletableFuture<>();
        manager.startInstance(request, resultFuture::complete);
        ProcessManager.StartResult result = resultFuture.get(15, TimeUnit.SECONDS);

        assertFalse(result.accepted());
        assertEquals(StartPreparationStage.RUNTIME_PROVISION, result.stage());
        assertEquals(StartFailureDisposition.TRANSIENT, result.failureDisposition());
        assertTrue(
                result.retryAfterSeconds() > 0,
                "transient failures should request a retry — got " + result.retryAfterSeconds());
        assertFalse(manager.getProcess("lobby-1").isPresent());
    }

    @Test
    @DisplayName("after a runtime hash mismatch the staged instance dir is fully cleaned up")
    void runtimeHashMismatchCleansUpStagedDir() throws Exception {
        byte[] runtimeBytes = "runtime-jar".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(
                "/runtime.jar", exchange -> writeResponse(exchange.getResponseBody(), exchange, runtimeBytes));
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ProcessManager manager = newManager();
            StartInstance request =
                    newRequest("lobby-1", "lobby", baseUrl + "/runtime.jar", "0".repeat(64), "plan-clean");

            CompletableFuture<ProcessManager.StartResult> resultFuture = new CompletableFuture<>();
            manager.startInstance(request, resultFuture::complete);
            resultFuture.get(10, TimeUnit.SECONDS);

            // The instance working dir under instances/<group>/<instanceId> may
            // exist as a husk after a permanent failure, but it must contain no
            // runtime artefact (the failure was post-download, so the partial
            // download must be deleted) and no fully-promoted files.
            Path instanceDir = tempDir.resolve("instances").resolve("lobby").resolve("lobby-1");
            if (Files.exists(instanceDir)) {
                assertFalse(
                        Files.exists(instanceDir.resolve("server.jar")),
                        "permanent failure must not leave the runtime jar on disk");
            }
        } finally {
            server.stop(0);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers — keep test bodies focused on the assertion, not on the
    // 30-line ProcessManager constructor wiring.
    // ──────────────────────────────────────────────────────────────────

    private ProcessManager newManager() {
        return new ProcessManager(
                tempDir.resolve("instances"),
                new InstancesConfig(tempDir.resolve("instances").toString(), 30, 10, 500, 200),
                mock(DaemonGrpcClient.class),
                new TemplateCache(tempDir.resolve("cache").resolve("templates")),
                new JarCache(tempDir.resolve("cache").resolve("jars")),
                new ArtifactCache(tempDir.resolve("cache").resolve("artifacts")),
                new PaperBootstrapCache(tempDir.resolve("cache").resolve("paper-bootstrap")),
                "node-1");
    }

    private static StartInstance newRequest(
            String instanceId, String group, String runtimeUrl, String runtimeSha256, String planHash) {
        return StartInstance.newBuilder()
                .setInstanceId(instanceId)
                .setGroup(group)
                .setPort(25565)
                .setMemoryMb(512)
                .setPluginToken("plugin-token")
                .setCompositionPlan(CompositionPlan.newBuilder()
                        .setPlanHash(planHash)
                        .setRuntime(RuntimeArtifact.newBuilder()
                                .setJarFile("server.jar")
                                .setDownloadUrl(runtimeUrl)
                                .setSha256(runtimeSha256)
                                .setCategory(InstanceCategory.SERVER)
                                .setConfigFormat(ConfigFormat.CONFIG_FORMAT_UNSPECIFIED)))
                .build();
    }

    private static void writeResponse(
            OutputStream responseBody, com.sun.net.httpserver.HttpExchange exchange, byte[] body) throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        try (exchange;
                responseBody) {
            responseBody.write(body);
        }
    }
}
