package me.prexorjustin.prexorcloud.controller.rest.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import me.prexorjustin.prexorcloud.api.event.events.ModuleLoadedEvent;
import me.prexorjustin.prexorcloud.api.module.platform.ActivationPolicy;
import me.prexorjustin.prexorcloud.api.module.platform.CapabilityDeclaration;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleStorageRequest;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleManifest;
import me.prexorjustin.prexorcloud.api.module.platform.RuntimeTarget;
import me.prexorjustin.prexorcloud.api.module.platform.WorkloadExtensionManifest;
import me.prexorjustin.prexorcloud.api.module.platform.WorkloadExtensionVariant;
import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.auth.Role;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.module.ModuleFrontendManager;
import me.prexorjustin.prexorcloud.controller.module.ModuleRegistry;
import me.prexorjustin.prexorcloud.controller.module.platform.ExtensionRegistry;
import me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleManager;
import me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleStorageManager;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.modules.runtime.CapabilityRegistry;
import me.prexorjustin.prexorcloud.modules.runtime.ModuleLifecycleManager;
import me.prexorjustin.prexorcloud.modules.runtime.PlatformModuleManifestParser;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("PlatformModuleRoutes")
class PlatformModuleRoutesTest {

    @TempDir
    Path tempDir;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private Javalin app;
    private String baseUrl;
    private PlatformModuleManager platformManager;
    private StateStore stateStore;
    private ModuleFrontendManager frontendManager;
    private EventBus eventBus;
    private GroupManager groupManager;

    @BeforeEach
    void setUp() {
        platformManager = mock(PlatformModuleManager.class);
        stateStore = mock(StateStore.class);
        frontendManager = spy(new ModuleFrontendManager(new ObjectMapper(), tempDir.resolve("module-data")));
        eventBus = mock(EventBus.class);
        groupManager = mock(GroupManager.class);
        when(platformManager.listModules()).thenReturn(List.of());

        ModuleRegistry moduleRegistry = new ModuleRegistry(frontendManager, platformManager);

        PrexorController controller = mock(PrexorController.class);
        when(controller.moduleRegistry()).thenReturn(moduleRegistry);
        when(controller.stateStore()).thenReturn(stateStore);
        when(controller.eventBus()).thenReturn(eventBus);
        when(controller.groupManager()).thenReturn(groupManager);
        when(controller.catalogStore()).thenReturn(null);
        when(groupManager.getAll()).thenReturn(List.of());

        app = Javalin.create(config -> config.routes.apiBuilder(() -> {
            io.javalin.apibuilder.ApiBuilder.before("/api/v1/*", ctx -> {
                ctx.attribute("username", "tester");
                ctx.attribute("role", Role.ADMIN);
            });
            new ModuleRoutes(controller).register();
        }));
        app.start(0);
        baseUrl = "http://127.0.0.1:" + app.port();
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    @DisplayName("platform upload returns created module payload")
    void platformUploadReturnsCreatedModulePayload() throws Exception {
        when(platformManager.install(any(Path.class))).thenReturn(managedModule("queue", "1.0.0"));
        when(frontendManager.getFrontend("queue")).thenReturn(java.util.Optional.empty());

        HttpResponse<String> response = postMultipart(
                "/api/v1/modules/platform/upload",
                createModuleJar(tempDir.resolve("queue.jar"), moduleManifestYaml("queue", "1.0.0")));

        assertEquals(201, response.statusCode());
        assertTrue(response.body().contains("\"moduleId\":\"queue\""));
        assertTrue(response.body().contains("\"version\":\"1.0.0\""));
        assertTrue(response.body().contains("\"compatibility\""));
        verify(frontendManager).removeFrontend("queue");
        verify(stateStore).audit("tester", "platform-module.upload", "module", "queue", "{}", "127.0.0.1");
    }

    @Test
    @DisplayName("platform upload writes cosign sidecar next to the jar before install")
    void platformUploadWritesCosignSidecarNextToJar() throws Exception {
        java.util.concurrent.atomic.AtomicReference<byte[]> sidecarSeen =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(platformManager.install(any(Path.class))).thenAnswer(invocation -> {
            Path jar = invocation.getArgument(0, Path.class);
            Path bundle = jar.resolveSibling(jar.getFileName() + ".cosign.bundle");
            if (Files.isRegularFile(bundle)) {
                sidecarSeen.set(Files.readAllBytes(bundle));
            }
            return managedModule("queue", "1.0.0");
        });
        when(frontendManager.getFrontend("queue")).thenReturn(java.util.Optional.empty());

        byte[] sidecarBytes = "{\"base64Signature\":\"abc\"}".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> response = postMultipart(
                "/api/v1/modules/platform/upload",
                createModuleJar(tempDir.resolve("queue.jar"), moduleManifestYaml("queue", "1.0.0")),
                "queue.jar.cosign.bundle",
                sidecarBytes);

        assertEquals(201, response.statusCode(), response.body());
        assertTrue(sidecarSeen.get() != null, "verifier did not see sidecar next to jar");
        assertEquals(
                new String(sidecarBytes, StandardCharsets.UTF_8),
                new String(sidecarSeen.get(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("platform upload rejects sidecar with unknown filename suffix")
    void platformUploadRejectsUnknownSidecarSuffix() throws Exception {
        HttpResponse<String> response = postMultipart(
                "/api/v1/modules/platform/upload",
                createModuleJar(tempDir.resolve("queue.jar"), moduleManifestYaml("queue", "1.0.0")),
                "queue.jar.weird",
                new byte[] {1, 2, 3});

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("INVALID_SIGNATURE"));
        verify(platformManager, never()).install(any(Path.class));
    }

    @Test
    @DisplayName("platform upload extracts frontend bundles and serves frontend assets")
    void platformUploadWithFrontendExtractsAndServesAssets() throws Exception {
        Path moduleJar = createModuleJar(
                tempDir.resolve("queue-ui.jar"),
                moduleManifestYamlWithFrontend("queue-ui", "1.0.0", "index.js"),
                Map.of(
                        "META-INF/frontend/module-frontend.json",
                        frontendManifestJson(),
                        "META-INF/frontend/index.js",
                        "console.log('queue ui');",
                        "META-INF/frontend/styles.css",
                        ".queue-ui { color: #123456; }"));

        when(platformManager.install(any(Path.class)))
                .thenAnswer(invocation -> managedModuleWithFrontend(
                        "queue-ui", "1.0.0", invocation.getArgument(0, Path.class), "index.js"));

        HttpResponse<String> uploadResponse = postMultipart("/api/v1/modules/platform/upload", moduleJar);
        HttpResponse<String> modulesResponse = get("/api/v1/modules");
        HttpResponse<String> assetResponse = get("/api/v1/modules/queue-ui/frontend/index.js");

        assertEquals(201, uploadResponse.statusCode(), uploadResponse.body());
        assertTrue(uploadResponse.body().contains("\"moduleId\":\"queue-ui\""));
        assertTrue(uploadResponse.body().contains("\"frontend\""));
        assertTrue(uploadResponse.body().contains("\"entry\":\"index.js\""));

        assertEquals(200, modulesResponse.statusCode(), modulesResponse.body());
        assertTrue(modulesResponse.body().contains("\"name\":\"queue-ui\""));
        assertTrue(modulesResponse.body().contains("\"displayName\":\"Queue UI\""));
        assertTrue(modulesResponse.body().contains("/api/v1/modules/queue-ui/frontend/index.js?v="));
        assertTrue(frontendManager.getFrontend("queue-ui").isPresent());

        assertEquals(200, assetResponse.statusCode(), assetResponse.body());
        assertEquals("console.log('queue ui');", assetResponse.body());
        assertTrue(
                assetResponse.headers().firstValue("Content-Type").orElse("").contains("application/javascript"),
                () -> "unexpected content-type: " + assetResponse.headers().map());
        verify(eventBus)
                .publish(argThat(event -> event instanceof ModuleLoadedEvent loaded
                        && loaded.moduleName().equals("queue-ui")
                        && loaded.hasFrontend()));
        verify(stateStore).audit("tester", "platform-module.upload", "module", "queue-ui", "{}", "127.0.0.1");
    }

    @Test
    @DisplayName("platform upgrade returns upgraded module payload")
    void platformUpgradeReturnsUpgradedModulePayload() throws Exception {
        when(platformManager.snapshot("queue")).thenReturn(java.util.Optional.of(managedModule("queue", "1.0.0")));
        when(platformManager.install(any(Path.class))).thenReturn(managedModule("queue", "1.1.0"));
        when(frontendManager.getFrontend("queue")).thenReturn(java.util.Optional.empty());

        HttpResponse<String> response = postMultipart(
                "/api/v1/modules/platform/queue/upgrade",
                createModuleJar(tempDir.resolve("queue-upgrade.jar"), moduleManifestYaml("queue", "1.1.0")));

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"moduleId\":\"queue\""));
        assertTrue(response.body().contains("\"version\":\"1.1.0\""));
        assertTrue(response.body().contains("\"compatibility\""));
        verify(frontendManager).removeFrontend("queue");
        verify(stateStore).audit("tester", "platform-module.upgrade", "module", "queue", "{}", "127.0.0.1");
    }

    @Test
    @DisplayName("platform upgrade rejects candidate that breaks referenced group runtime compatibility")
    void platformUpgradeRejectsIncompatibleRuntimeChange() throws Exception {
        var existing = managedModule(
                "queue",
                "1.0.0",
                CapabilityDeclaration.EMPTY,
                List.of(extension(
                        "queue-paper",
                        RuntimeTarget.parse("server/paper"),
                        variant("paper-1-20", "[1.20.0,1.21.0)", "extensions/paper/queue.jar"))),
                List.of());
        var group = groupConfig("lobby", "PAPER", "1.20.4", List.of("queue"), List.of(), List.of());
        when(platformManager.snapshot("queue")).thenReturn(java.util.Optional.of(existing));
        when(platformManager.listModules()).thenReturn(List.of(existing));
        when(groupManager.getAll()).thenReturn(List.of(group));
        when(groupManager.resolveGroup("lobby")).thenReturn(group);

        HttpResponse<String> response = postMultipart(
                "/api/v1/modules/platform/queue/upgrade",
                createModuleJar(
                        tempDir.resolve("queue-incompatible.jar"),
                        moduleManifestYamlWithExtension(
                                "queue",
                                "1.1.0",
                                "queue-paper",
                                "explicit-group-attach",
                                "server/paper",
                                "[1.21.0,1.22.0)",
                                "extensions/paper/queue.jar")));

        assertEquals(409, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"code\":\"PLATFORM_UPGRADE_FAILED\""));
        assertTrue(response.body().contains("\"compatibility\""));
        assertTrue(response.body().contains("\"groupName\":\"lobby\""));
        assertTrue(response.body().contains("failed to resolve compatible extensions"));
        assertTrue(response.body().contains("no compatible variant for extension 'queue-paper'"));
        verify(platformManager, never()).install(any(Path.class));
        verify(stateStore, never()).audit(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("platform upgrade rejects uploaded jar with the wrong module id")
    void platformUpgradeRejectsWrongModuleId() throws Exception {
        when(platformManager.snapshot("queue")).thenReturn(java.util.Optional.of(managedModule("queue", "1.0.0")));

        HttpResponse<String> response = postMultipart(
                "/api/v1/modules/platform/queue/upgrade",
                createModuleJar(tempDir.resolve("wrong.jar"), moduleManifestYaml("profile", "1.1.0")));

        assertEquals(422, response.statusCode());
        assertTrue(response.body().contains("\"code\":\"VALIDATION_ERROR\""));
        assertTrue(response.body().contains("does not match requested module 'queue'"));
        verify(platformManager, never()).install(any(Path.class));
    }

    @Test
    @DisplayName("platform upload maps manager conflicts to 409 responses")
    void platformUploadMapsManagerConflictsTo409() throws Exception {
        when(platformManager.install(any(Path.class)))
                .thenThrow(
                        new IllegalStateException("platform module mutation is already owned by another controller"));

        HttpResponse<String> response = postMultipart(
                "/api/v1/modules/platform/upload",
                createModuleJar(tempDir.resolve("queue.jar"), moduleManifestYaml("queue", "1.0.0")));

        assertEquals(409, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"code\":\"PLATFORM_INSTALL_FAILED\""));
        verify(stateStore, never()).audit(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("platform snapshot exposes module state and metrics")
    void platformSnapshotExposesModuleStateAndMetrics() throws Exception {
        CapabilityRegistry capabilityRegistry = new CapabilityRegistry();
        PlatformModuleManager.ManagedPlatformModule module = managedModule(
                "example",
                "1.0.0",
                new CapabilityDeclaration(
                        List.of(new CapabilityDeclaration.Provides("example-playtime-query", "1.0.0")), List.of()),
                List.of(),
                List.of());
        capabilityRegistry.activateModule(
                module.manifest(),
                List.of(me.prexorjustin.prexorcloud.api.module.platform.CapabilityHandle.of(
                        "example-playtime-query", Object.class, new Object())));
        when(platformManager.listModules()).thenReturn(List.of(module));
        when(platformManager.capabilityRegistry()).thenReturn(capabilityRegistry);

        HttpResponse<String> response = get("/api/v1/modules/platform");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"moduleId\":\"example\""));
        assertTrue(response.body().contains("\"state\":\"ACTIVE\""));
        assertTrue(response.body().contains("\"capabilityMetrics\""));
        assertTrue(response.body().contains("\"example-playtime-query\""));
    }

    @Test
    @DisplayName("platform capabilities expose bindings and unresolved requirements")
    void platformCapabilitiesExposeBindingsAndUnresolvedRequirements() throws Exception {
        CapabilityRegistry capabilityRegistry = new CapabilityRegistry();
        PlatformModuleManager.ManagedPlatformModule provider = managedModule(
                "example",
                "1.0.0",
                new CapabilityDeclaration(
                        List.of(new CapabilityDeclaration.Provides("example-playtime-query", "1.0.0")), List.of()),
                List.of(),
                List.of());
        PlatformModuleManager.ManagedPlatformModule consumer = managedModule(
                "playtime-consumer",
                "1.0.0",
                new CapabilityDeclaration(
                        List.of(),
                        List.of(
                                new CapabilityDeclaration.Requires("example-playtime-query", "[1.0.0,2.0.0)"),
                                new CapabilityDeclaration.Requires("missing-capability", "[1.0.0,2.0.0)"))),
                List.of(),
                List.of(new CapabilityRegistry.UnresolvedRequirement(
                        "playtime-consumer", "missing-capability", "[1.0.0,2.0.0)", "missing provider")));
        capabilityRegistry.activateModule(
                provider.manifest(),
                List.of(me.prexorjustin.prexorcloud.api.module.platform.CapabilityHandle.of(
                        "example-playtime-query", Object.class, new Object())));
        when(platformManager.listModules()).thenReturn(List.of(provider, consumer));
        when(platformManager.capabilityRegistry()).thenReturn(capabilityRegistry);

        HttpResponse<String> response = get("/api/v1/modules/platform/capabilities");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"moduleId\":\"example\""));
        assertTrue(response.body().contains("\"active\":true"));
        assertTrue(response.body().contains("\"moduleId\":\"playtime-consumer\""));
        assertTrue(response.body().contains("\"binding\""));
        assertTrue(response.body().contains("\"reason\":\"missing provider\""));
    }

    @Test
    @DisplayName("platform extension endpoints expose registry and resolved variants")
    void platformExtensionEndpointsExposeRegistryAndResolvedVariants() throws Exception {
        RuntimeTarget paper = RuntimeTarget.parse("server/paper");
        PlatformModuleManager.ManagedPlatformModule module = managedModule(
                "matchmaking",
                "1.0.0",
                CapabilityDeclaration.EMPTY,
                List.of(extension(
                        "matchmaking-paper",
                        paper,
                        variant("paper-1-20", "[1.20.0,1.21.0)", "extensions/paper/matchmaking.jar"))),
                List.of());
        ExtensionRegistry extensionRegistry = new ExtensionRegistry(List.of(module.manifest()));
        when(platformManager.extensionRegistry()).thenReturn(extensionRegistry);

        HttpResponse<String> registryResponse = get("/api/v1/modules/platform/extensions?target=server/paper");
        HttpResponse<String> resolveResponse =
                get("/api/v1/modules/platform/extensions/resolve?target=server/paper&version=1.20.4");

        assertEquals(200, registryResponse.statusCode());
        assertTrue(registryResponse.body().contains("\"id\":\"matchmaking-paper\""));
        assertTrue(registryResponse.body().contains("\"target\":\"server/paper\""));
        assertEquals(200, resolveResponse.statusCode());
        assertTrue(resolveResponse.body().contains("\"extensionId\":\"matchmaking-paper\""));
        assertTrue(resolveResponse.body().contains("\"variantId\":\"paper-1-20\""));
    }

    @Test
    @DisplayName("platform extension resolution rejects incompatible runtime versions")
    void platformExtensionResolutionRejectsIncompatibleRuntimeVersion() throws Exception {
        RuntimeTarget paper = RuntimeTarget.parse("server/paper");
        PlatformModuleManager.ManagedPlatformModule module = managedModule(
                "matchmaking",
                "1.0.0",
                CapabilityDeclaration.EMPTY,
                List.of(extension(
                        "matchmaking-paper",
                        paper,
                        variant("paper-1-20", "[1.20.0,1.21.0)", "extensions/paper/matchmaking.jar"))),
                List.of());
        when(platformManager.extensionRegistry()).thenReturn(new ExtensionRegistry(List.of(module.manifest())));

        HttpResponse<String> response = get(
                "/api/v1/modules/platform/extensions/resolve?target=server/paper&version=1.21.4&extensionId=matchmaking-paper");

        assertEquals(409, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"code\":\"EXTENSION_RESOLUTION_FAILED\""));
        assertTrue(response.body().contains("no compatible variant for extension 'matchmaking-paper'"));
        assertTrue(response.body().contains("server/paper @ 1.21.4"));
    }

    @Test
    @DisplayName("platform upload installs multi-version Paper extensions and resolves the most specific artifact")
    void platformUploadWithMultiVersionPaperExtension() throws Exception {
        RuntimeTarget paper = RuntimeTarget.parse("server/paper");
        WorkloadExtensionVariant universal =
                variant("paper-universal", "*", "extensions/paper/matchmaking-universal.jar");
        WorkloadExtensionVariant paper120 =
                variant("paper-1-20", "[1.20.0,1.21.0)", "extensions/paper/matchmaking-1.20.jar");
        WorkloadExtensionVariant paper1204 =
                variant("paper-1-20-4", "[1.20.4,1.20.5)", "extensions/paper/matchmaking-1.20.4.jar");
        Path moduleJar = createModuleJar(
                tempDir.resolve("matchmaking-paper.jar"),
                moduleManifestYamlWithExtensionVariants(
                        "matchmaking",
                        "1.0.0",
                        "matchmaking-paper",
                        "explicit-group-attach",
                        "server/paper",
                        universal,
                        paper120,
                        paper1204),
                Map.of(
                        universal.artifact(), "universal-bytes",
                        paper120.artifact(), "paper-1.20-bytes",
                        paper1204.artifact(), "paper-1.20.4-bytes"));
        PlatformModuleManager.ManagedPlatformModule module = managedModule(
                "matchmaking",
                "1.0.0",
                CapabilityDeclaration.EMPTY,
                List.of(extension("matchmaking-paper", paper, universal, paper120, paper1204)),
                List.of());
        when(platformManager.install(any(Path.class))).thenReturn(module);
        when(platformManager.listModules()).thenReturn(List.of(module));
        when(platformManager.extensionRegistry()).thenReturn(new ExtensionRegistry(List.of(module.manifest())));
        when(platformManager.readArtifact("matchmaking", "extensions/paper/matchmaking-1.20.4.jar"))
                .thenReturn(java.util.Optional.of(
                        new me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleStore.ArtifactContent(
                                "matchmaking-1.20.4.jar", "paper-1.20.4-bytes".getBytes(StandardCharsets.UTF_8))));

        HttpResponse<String> uploadResponse = postMultipart("/api/v1/modules/platform/upload", moduleJar);
        HttpResponse<String> registryResponse = get("/api/v1/modules/platform/extensions?target=server/paper");
        HttpResponse<String> resolveResponse = get(
                "/api/v1/modules/platform/extensions/resolve?target=server/paper&version=1.20.4&extensionId=matchmaking-paper");
        HttpResponse<String> artifactResponse =
                get("/api/v1/modules/platform/matchmaking/artifacts/extensions/paper/matchmaking-1.20.4.jar");

        assertEquals(201, uploadResponse.statusCode(), uploadResponse.body());
        assertTrue(uploadResponse.body().contains("\"moduleId\":\"matchmaking\""));
        assertTrue(uploadResponse.body().contains("\"id\":\"matchmaking-paper\""));
        assertTrue(uploadResponse.body().contains("\"variants\""));
        assertTrue(uploadResponse.body().contains("\"paper-1-20-4\""));

        assertEquals(200, registryResponse.statusCode(), registryResponse.body());
        assertTrue(registryResponse.body().contains("\"id\":\"matchmaking-paper\""));
        assertTrue(registryResponse.body().contains("\"paper-universal\""));
        assertTrue(registryResponse.body().contains("\"paper-1-20\""));
        assertTrue(registryResponse.body().contains("\"paper-1-20-4\""));

        assertEquals(200, resolveResponse.statusCode(), resolveResponse.body());
        assertTrue(resolveResponse.body().contains("\"extensionId\":\"matchmaking-paper\""));
        assertTrue(resolveResponse.body().contains("\"variantId\":\"paper-1-20-4\""));
        assertTrue(resolveResponse.body().contains("\"artifact\":\"extensions/paper/matchmaking-1.20.4.jar\""));

        assertEquals(200, artifactResponse.statusCode(), artifactResponse.body());
        assertEquals("paper-1.20.4-bytes", artifactResponse.body());
        assertTrue(
                artifactResponse
                        .headers()
                        .firstValue("Content-Disposition")
                        .orElse("")
                        .contains("matchmaking-1.20.4.jar"),
                () -> "unexpected disposition: " + artifactResponse.headers().map());
        verify(stateStore).audit("tester", "platform-module.upload", "module", "matchmaking", "{}", "127.0.0.1");
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postMultipart(String path, Path file) throws IOException, InterruptedException {
        return postMultipart(path, file, null, null);
    }

    private HttpResponse<String> postMultipart(String path, Path file, String signatureFilename, byte[] signatureBytes)
            throws IOException, InterruptedException {
        String boundary = "----PrexorBoundary" + UUID.randomUUID();
        byte[] requestBody = multipartBody(boundary, file, signatureFilename, signatureBytes);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] multipartBody(String boundary, Path file, String signatureFilename, byte[] signatureBytes)
            throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getFileName() + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write("Content-Type: application/java-archive\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(Files.readAllBytes(file));
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        if (signatureFilename != null && signatureBytes != null) {
            body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(
                    ("Content-Disposition: form-data; name=\"signature\"; filename=\"" + signatureFilename + "\"\r\n")
                            .getBytes(StandardCharsets.UTF_8));
            body.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            body.write(signatureBytes);
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
    }

    private static Path createModuleJar(Path jarPath, String manifest) throws IOException {
        return createModuleJar(jarPath, manifest, Map.of());
    }

    private static Path createModuleJar(Path jarPath, String manifest, Map<String, String> extraEntries)
            throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jarPath))) {
            out.putNextEntry(new JarEntry(PlatformModuleManifestParser.FILE_NAME));
            out.write(manifest.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (var entry : extraEntries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jarPath;
    }

    private static String moduleManifestYaml(String moduleId, String version) {
        return """
                manifestVersion: 1
                id: %s
                version: %s
                backend:
                  entrypoint: example.%sModule
                """.formatted(moduleId, version, Character.toUpperCase(moduleId.charAt(0)) + moduleId.substring(1));
    }

    private static String moduleManifestYamlWithFrontend(String moduleId, String version, String entry) {
        return """
                manifestVersion: 1
                id: %s
                version: %s
                backend:
                  entrypoint: example.%sModule
                frontend:
                  sdkVersion: 1
                  entry: %s
                """.formatted(
                        moduleId, version, Character.toUpperCase(moduleId.charAt(0)) + moduleId.substring(1), entry);
    }

    private static String frontendManifestJson() {
        return """
                {
                  "version": 1,
                  "displayName": "Queue UI",
                  "entry": "index.js",
                  "css": "styles.css",
                  "icon": "layers",
                  "permissions": ["modules.view"],
                  "routes": [
                    {
                      "path": "/queue",
                      "component": "QueuePage",
                      "title": "Queue",
                      "icon": "layers",
                      "nav": true,
                      "navGroup": "Operations",
                      "navGroupOrder": 1,
                      "adminOnly": false
                    }
                  ],
                  "events": ["MODULE_LOADED"]
                }
                """;
    }

    private static String moduleManifestYamlWithExtension(
            String moduleId,
            String version,
            String extensionId,
            String activation,
            String target,
            String mcVersionRange,
            String artifact) {
        return moduleManifestYamlWithExtensionVariants(
                moduleId,
                version,
                extensionId,
                activation,
                target,
                new WorkloadExtensionVariant(
                        extensionId + "-variant", mcVersionRange, 1, artifact, "a".repeat(64), "plugins/"));
    }

    private static String moduleManifestYamlWithExtensionVariants(
            String moduleId,
            String version,
            String extensionId,
            String activation,
            String target,
            WorkloadExtensionVariant... variants) {
        StringBuilder yaml = new StringBuilder("""
                manifestVersion: 1
                id: %s
                version: %s
                backend:
                  entrypoint: example.%sModule
                extensions:
                  - id: %s
                    target: %s
                    activation: %s
                    variants:
                """.formatted(
                        moduleId,
                        version,
                        Character.toUpperCase(moduleId.charAt(0)) + moduleId.substring(1),
                        extensionId,
                        target,
                        activation));
        for (WorkloadExtensionVariant variant : variants) {
            yaml.append("""
                          - id: %s
                            mcVersionRange: "%s"
                            runtimeApiVersion: %d
                            artifact: "%s"
                            sha256: "%s"
                            installPath: "%s"
                    """.formatted(
                            variant.id(),
                            variant.mcVersionRange(),
                            variant.runtimeApiVersion(),
                            variant.artifact(),
                            variant.sha256(),
                            variant.installPath()));
        }
        return yaml.toString();
    }

    private static GroupConfig groupConfig(
            String name,
            String platform,
            String platformVersion,
            List<String> attachedModules,
            List<String> attachedExtensions,
            List<String> enabledExtensions) {
        return new GroupConfig(
                name,
                null,
                platform,
                platformVersion,
                "server.jar",
                List.of(),
                "DYNAMIC",
                1,
                2,
                100,
                0.8,
                300,
                60,
                false,
                0.2,
                2,
                "LOWEST_PLAYERS",
                30000,
                30100,
                120,
                30,
                false,
                0,
                false,
                List.of(),
                List.of(),
                null,
                false,
                List.of(),
                0,
                false,
                "",
                List.of(),
                "ROLLING",
                List.of(),
                List.of(),
                "",
                0,
                1024,
                List.of(),
                java.util.Map.of(),
                List.of(),
                "STATIC",
                30,
                attachedModules,
                List.of(),
                List.of(),
                attachedExtensions,
                enabledExtensions,
                List.of(),
                java.util.Map.of());
    }

    private static PlatformModuleManager.ManagedPlatformModule managedModule(String moduleId, String version) {
        return managedModule(moduleId, version, CapabilityDeclaration.EMPTY, List.of(), List.of());
    }

    private static PlatformModuleManager.ManagedPlatformModule managedModule(
            String moduleId,
            String version,
            CapabilityDeclaration capabilities,
            List<WorkloadExtensionManifest> extensions,
            List<CapabilityRegistry.UnresolvedRequirement> unresolvedRequirements) {
        return managedModule(
                moduleId,
                version,
                Path.of("modules", moduleId + ".jar"),
                null,
                capabilities,
                extensions,
                unresolvedRequirements);
    }

    private static PlatformModuleManager.ManagedPlatformModule managedModuleWithFrontend(
            String moduleId, String version, Path jarPath, String entry) {
        return managedModule(
                moduleId,
                version,
                jarPath,
                new PlatformModuleManifest.Frontend(1, entry),
                CapabilityDeclaration.EMPTY,
                List.of(),
                List.of());
    }

    private static PlatformModuleManager.ManagedPlatformModule managedModule(
            String moduleId,
            String version,
            Path jarPath,
            PlatformModuleManifest.Frontend frontend,
            CapabilityDeclaration capabilities,
            List<WorkloadExtensionManifest> extensions,
            List<CapabilityRegistry.UnresolvedRequirement> unresolvedRequirements) {
        return new PlatformModuleManager.ManagedPlatformModule(
                moduleId,
                version,
                "a".repeat(64),
                jarPath,
                128,
                Instant.parse("2026-04-16T12:00:00Z"),
                new PlatformModuleManifest(
                        PlatformModuleManifest.CURRENT_MANIFEST_VERSION,
                        moduleId,
                        version,
                        new PlatformModuleManifest.Backend("example." + moduleId + ".Module"),
                        frontend,
                        capabilities,
                        ModuleStorageRequest.NONE,
                        extensions),
                new PlatformModuleStorageManager.StorageAllocation(
                        moduleId, false, false, null, null, 0),
                ModuleLifecycleManager.ModuleState.ACTIVE,
                null,
                unresolvedRequirements);
    }

    private static WorkloadExtensionManifest extension(
            String id, RuntimeTarget target, WorkloadExtensionVariant... variants) {
        return new WorkloadExtensionManifest(
                id, target, ActivationPolicy.EXPLICIT_GROUP_ATTACH, List.of(), List.of(variants));
    }

    private static WorkloadExtensionVariant variant(String id, String range, String artifact) {
        return new WorkloadExtensionVariant(id, range, 1, artifact, "a".repeat(64), "plugins/");
    }
}
