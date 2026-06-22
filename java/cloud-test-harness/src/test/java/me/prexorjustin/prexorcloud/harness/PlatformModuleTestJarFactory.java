package me.prexorjustin.prexorcloud.harness;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import me.prexorjustin.prexorcloud.api.module.data.Query;
import me.prexorjustin.prexorcloud.api.module.data.Update;
import me.prexorjustin.prexorcloud.api.module.platform.CapabilityHandle;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleContext;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModule;
import me.prexorjustin.prexorcloud.modules.runtime.PlatformModuleManifestParser;

public final class PlatformModuleTestJarFactory {

    public static final String EVENT_DIR_PROPERTY = "prexor.test.platformModule.eventsDir";
    private static final String CAPABILITY_ID = "player-profile";

    private PlatformModuleTestJarFactory() {}

    public static Path createConsumerJar(Path jarPath) {
        return createModuleJar(jarPath, """
                manifestVersion: 1
                id: queue
                version: 1.0.0
                backend:
                  entrypoint: me.prexorjustin.prexorcloud.harness.PlatformModuleTestJarFactory$ConsumerModule
                capabilities:
                  requires:
                    - id: player-profile
                      versionRange: "[1.0,3.0)"
                """, ConsumerModule.class);
    }

    public static Path createProviderV1Jar(Path jarPath) {
        return createModuleJar(jarPath, """
                manifestVersion: 1
                id: profile
                version: 1.0.0
                backend:
                  entrypoint: me.prexorjustin.prexorcloud.harness.PlatformModuleTestJarFactory$ProviderModuleV1
                capabilities:
                  provides:
                    - id: player-profile
                      version: 1.0.0
                """, ProviderModuleV1.class);
    }

    public static Path createProviderV2Jar(Path jarPath) {
        return createModuleJar(jarPath, """
                manifestVersion: 1
                id: profile
                version: 2.0.0
                backend:
                  entrypoint: me.prexorjustin.prexorcloud.harness.PlatformModuleTestJarFactory$ProviderModuleV2
                capabilities:
                  provides:
                    - id: player-profile
                      version: 2.0.0
                """, ProviderModuleV2.class);
    }

    public static Path createStorageProbeJar(Path jarPath, String moduleId) {
        return createModuleJar(jarPath, """
                manifestVersion: 1
                id: %s
                version: 1.0.0
                backend:
                  entrypoint: me.prexorjustin.prexorcloud.harness.PlatformModuleTestJarFactory$StorageProbeModule
                storage:
                  mongo: true
                """.formatted(moduleId), StorageProbeModule.class);
    }

    public static Path createFrontendExtensionJar(Path jarPath) {
        return createModuleJar(
                jarPath,
                """
                        manifestVersion: 1
                        id: ops-ui
                        version: 1.0.0
                        backend:
                          entrypoint: me.prexorjustin.prexorcloud.harness.PlatformModuleTestJarFactory$FrontendExtensionModule
                        frontend:
                          sdkVersion: 1
                          entry: index.js
                        extensions:
                          - id: ops-default
                            target: server/paper
                            activation: default-enabled
                            variants:
                              - id: paper-default
                                mcVersionRange: "*"
                                runtimeApiVersion: 1
                                artifact: "extensions/default/ops-default.jar"
                                sha256: "1111111111111111111111111111111111111111111111111111111111111111"
                                installPath: "plugins/"
                          - id: ops-matchmaking
                            target: server/paper
                            activation: explicit-group-attach
                            variants:
                              - id: paper-1-21
                                mcVersionRange: "[1.21.0,1.22.0)"
                                runtimeApiVersion: 1
                                artifact: "extensions/paper/ops-matchmaking-1.21.jar"
                                sha256: "2222222222222222222222222222222222222222222222222222222222222222"
                                installPath: "plugins/"
                              - id: paper-1-21-1
                                mcVersionRange: "[1.21.1,1.21.2)"
                                runtimeApiVersion: 1
                                artifact: "extensions/paper/ops-matchmaking-1.21.1.jar"
                                sha256: "3333333333333333333333333333333333333333333333333333333333333333"
                                installPath: "plugins/"
                        """,
                Map.of(
                        "META-INF/frontend/module-frontend.json",
                        frontendManifestJson().getBytes(StandardCharsets.UTF_8),
                        "META-INF/frontend/index.js",
                        "console.log('ops-ui frontend');".getBytes(StandardCharsets.UTF_8),
                        "META-INF/frontend/styles.css",
                        ".ops-ui { color: #24577a; }".getBytes(StandardCharsets.UTF_8),
                        "extensions/default/ops-default.jar",
                        "ops-default".getBytes(StandardCharsets.UTF_8),
                        "extensions/paper/ops-matchmaking-1.21.jar",
                        "ops-matchmaking-1.21".getBytes(StandardCharsets.UTF_8),
                        "extensions/paper/ops-matchmaking-1.21.1.jar",
                        "ops-matchmaking-1.21.1".getBytes(StandardCharsets.UTF_8)),
                FrontendExtensionModule.class);
    }

    public static List<String> readEvents(Path eventsDir, String fileName) throws IOException {
        Path file = eventsDir.resolve(fileName);
        if (!Files.exists(file)) {
            return List.of();
        }
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    private static Path createModuleJar(Path jarPath, String manifestYaml, Class<?>... moduleClasses) {
        return createModuleJar(jarPath, manifestYaml, Map.of(), moduleClasses);
    }

    private static Path createModuleJar(
            Path jarPath, String manifestYaml, Map<String, byte[]> extraEntries, Class<?>... moduleClasses) {
        try {
            Files.createDirectories(jarPath.getParent());
            Set<Class<?>> classesToPackage = new LinkedHashSet<>();
            classesToPackage.add(PlatformModuleTestJarFactory.class);
            classesToPackage.addAll(List.of(moduleClasses));

            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jarPath))) {
                output.putNextEntry(new JarEntry(PlatformModuleManifestParser.FILE_NAME));
                output.write(manifestYaml.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();

                for (Map.Entry<String, byte[]> entry : extraEntries.entrySet()) {
                    output.putNextEntry(new JarEntry(entry.getKey()));
                    output.write(entry.getValue());
                    output.closeEntry();
                }

                for (Class<?> type : classesToPackage) {
                    writeClassEntry(output, type);
                }
            }
            return jarPath;
        } catch (IOException e) {
            throw new IllegalStateException("failed to create platform test module jar", e);
        }
    }

    private static void writeClassEntry(JarOutputStream output, Class<?> type) throws IOException {
        String resourcePath = type.getName().replace('.', '/') + ".class";
        output.putNextEntry(new JarEntry(resourcePath));
        try (InputStream input = type.getClassLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IOException("missing compiled class resource: " + resourcePath);
            }
            input.transferTo(output);
        }
        output.closeEntry();
    }

    private static void appendEvent(String fileName, String event) {
        String configuredDir = System.getProperty(EVENT_DIR_PROPERTY);
        if (configuredDir == null || configuredDir.isBlank()) {
            return;
        }

        try {
            Path eventsDir = Path.of(configuredDir);
            Files.createDirectories(eventsDir);
            Files.writeString(
                    eventsDir.resolve(fileName),
                    event + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("failed to append module test event", e);
        }
    }

    public static final class ConsumerModule implements PlatformModule {

        private volatile Supplier<String> supplier;
        private volatile String lastObservedProfile;
        private volatile Thread watcherThread;

        @Override
        public void onLoad(ModuleContext context) {
            appendEvent("consumer.log", "load");
        }

        @Override
        @SuppressWarnings("unchecked")
        public void onStart(ModuleContext context) {
            supplier = context.requireCapability(CAPABILITY_ID, Supplier.class);
            lastObservedProfile = supplier.get();
            appendEvent("consumer.log", "start:" + lastObservedProfile);
            startWatcher();
        }

        @Override
        public void onStop(ModuleContext context) {
            stopWatcher();
            supplier = null;
            appendEvent("consumer.log", "stop");
        }

        @Override
        public void onUnload(ModuleContext context) {
            appendEvent("consumer.log", "unload");
        }

        private void startWatcher() {
            if (watcherThread != null && watcherThread.isAlive()) {
                return;
            }
            watcherThread = Thread.startVirtualThread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Supplier<String> currentSupplier = supplier;
                        if (currentSupplier != null) {
                            String currentProfile = currentSupplier.get();
                            if (!Objects.equals(currentProfile, lastObservedProfile)) {
                                lastObservedProfile = currentProfile;
                                appendEvent("consumer.log", "rebind:" + currentProfile);
                            }
                        }
                        Thread.sleep(25);
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (IllegalStateException _) {
                        try {
                            Thread.sleep(25);
                        } catch (InterruptedException _) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            });
        }

        private void stopWatcher() {
            Thread thread = watcherThread;
            watcherThread = null;
            if (thread == null) {
                return;
            }
            thread.interrupt();
            try {
                thread.join(1_000);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String frontendManifestJson() {
        return """
                {
                  "version": 1,
                  "displayName": "Ops UI",
                  "entry": "index.js",
                  "css": "styles.css",
                  "icon": "wrench",
                  "permissions": ["modules.view"],
                  "routes": [
                    {
                      "path": "/ops-ui",
                      "component": "OpsUiPage",
                      "title": "Ops UI",
                      "icon": "wrench",
                      "nav": true,
                      "navGroup": "Operations",
                      "navGroupOrder": 2,
                      "adminOnly": false
                    }
                  ],
                  "events": ["MODULE_LOADED"]
                }
                """;
    }

    public static final class ProviderModuleV1 implements PlatformModule {

        @Override
        public void onLoad(ModuleContext context) {
            appendEvent("provider.log", "v1:load");
        }

        @Override
        public void onStart(ModuleContext context) {
            appendEvent("provider.log", "v1:start");
        }

        @Override
        public void onStop(ModuleContext context) {
            appendEvent("provider.log", "v1:stop");
        }

        @Override
        public void onUnload(ModuleContext context) {
            appendEvent("provider.log", "v1:unload");
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public List<CapabilityHandle<?>> capabilityHandles() {
            return List.of(
                    CapabilityHandle.of(CAPABILITY_ID, (Class) Supplier.class, (Supplier<String>) () -> "profile-v1"));
        }
    }

    public static final class ProviderModuleV2 implements PlatformModule {

        @Override
        public void onLoad(ModuleContext context) {
            appendEvent("provider.log", "v2:load");
        }

        @Override
        public void onUpgrade(ModuleContext context) {
            appendEvent("provider.log", "v2:upgrade:" + context.previousVersion());
        }

        @Override
        public void onStart(ModuleContext context) {
            appendEvent("provider.log", "v2:start");
        }

        @Override
        public void onStop(ModuleContext context) {
            appendEvent("provider.log", "v2:stop");
        }

        @Override
        public void onUnload(ModuleContext context) {
            appendEvent("provider.log", "v2:unload");
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public List<CapabilityHandle<?>> capabilityHandles() {
            return List.of(
                    CapabilityHandle.of(CAPABILITY_ID, (Class) Supplier.class, (Supplier<String>) () -> "profile-v2"));
        }
    }

    public static final class FrontendExtensionModule implements PlatformModule {}

    public static final class StorageProbeModule implements PlatformModule {

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public void onLoad(ModuleContext context) {
            String moduleId = context.manifest().id();
            var mongo = context.requireMongoStorage();

            mongo.ensureCollection("state");
            var ownerDocument = mongo.findOne("state", Query.where("key").eq("owner"), Map.class);
            if (ownerDocument.isEmpty()) {
                mongo.upsertOne(
                        "state",
                        Query.where("key").eq("owner"),
                        Update.setOnInsert("key", "owner").andSetOnInsert("value", moduleId));
                ownerDocument = mongo.findOne("state", Query.where("key").eq("owner"), Map.class);
            }

            String mongoOwner =
                    Objects.toString(ownerDocument.orElse(Map.of()).getOrDefault("value", "missing"), "missing");

            appendEvent(
                    moduleId + ".log",
                    "load:mongo=" + mongoOwner + ",mongoPrefix=" + mongo.collectionPrefix());
        }
    }
}
