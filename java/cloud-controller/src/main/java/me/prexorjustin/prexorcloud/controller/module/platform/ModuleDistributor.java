package me.prexorjustin.prexorcloud.controller.module.platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarFile;

import me.prexorjustin.prexorcloud.api.module.platform.ModuleHost;
import me.prexorjustin.prexorcloud.controller.session.NodeSession;
import me.prexorjustin.prexorcloud.controller.session.NodeSessionManager;
import me.prexorjustin.prexorcloud.modules.runtime.PlatformModuleManifestParser;
import me.prexorjustin.prexorcloud.protocol.ControllerMessage;
import me.prexorjustin.prexorcloud.protocol.ModuleInstall;
import me.prexorjustin.prexorcloud.protocol.ModuleUninstall;

import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pushes daemon-host platform modules to connected daemons.
 *
 * <p>Wired into {@link PlatformModuleManager} via {@link ModuleDistributorHook} so successful
 * install/upgrade/uninstall fires fan-out to every connected daemon. On daemon handshake the
 * controller calls {@link #syncDaemon(String)} to re-push every currently stored daemon-host
 * module so daemons that reconnect after a restart catch up automatically.
 *
 * <p>Signature sidecars are not persisted in the controller store yet — PR 7b ships modules
 * without {@code signature_bytes}; daemon-side cosign verification arrives with PR 7c/7d.
 */
public final class ModuleDistributor implements ModuleDistributorHook {

    private static final Logger logger = LoggerFactory.getLogger(ModuleDistributor.class);

    private final PlatformModuleStore store;
    private final NodeSessionManager sessionManager;

    public ModuleDistributor(PlatformModuleStore store, NodeSessionManager sessionManager) {
        this.store = Objects.requireNonNull(store, "store");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
    }

    @Override
    public void onInstalled(PlatformModuleStore.StoredModule storedModule, boolean isUpgrade, String previousVersion) {
        Objects.requireNonNull(storedModule, "storedModule");
        if (!isDaemonHost(storedModule)) {
            return;
        }
        ModuleInstall install = buildInstall(storedModule, isUpgrade, previousVersion);
        ControllerMessage envelope =
                ControllerMessage.newBuilder().setModuleInstall(install).build();
        for (NodeSession session : sessionManager.allSessions()) {
            sendQuietly(session, envelope, "ModuleInstall");
        }
    }

    @Override
    public void onUninstalled(String moduleId) {
        Objects.requireNonNull(moduleId, "moduleId");
        ControllerMessage envelope = ControllerMessage.newBuilder()
                .setModuleUninstall(ModuleUninstall.newBuilder().setModuleId(moduleId))
                .build();
        for (NodeSession session : sessionManager.allSessions()) {
            sendQuietly(session, envelope, "ModuleUninstall");
        }
    }

    /**
     * Re-push every currently stored daemon-host module to a freshly connected daemon. Called
     * by {@code DaemonServiceImpl} after a successful handshake so the daemon converges to the
     * controller's set of installed daemon-host modules without operator intervention.
     */
    public void syncDaemon(String nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        NodeSession session = sessionManager.getByNodeId(nodeId).orElse(null);
        if (session == null) {
            logger.debug("syncDaemon: no active session for node {}", nodeId);
            return;
        }
        List<PlatformModuleStore.StoredModule> stored = store.list();
        for (PlatformModuleStore.StoredModule storedModule : stored) {
            if (!isDaemonHost(storedModule)) {
                continue;
            }
            ModuleInstall install = buildInstall(storedModule, false, null);
            ControllerMessage envelope =
                    ControllerMessage.newBuilder().setModuleInstall(install).build();
            sendQuietly(session, envelope, "ModuleInstall(sync)");
        }
    }

    private static boolean isDaemonHost(PlatformModuleStore.StoredModule storedModule) {
        return storedModule.manifest().hosts().contains(ModuleHost.DAEMON);
    }

    private static ModuleInstall buildInstall(
            PlatformModuleStore.StoredModule storedModule, boolean isUpgrade, String previousVersion) {
        byte[] jarBytes;
        try {
            jarBytes = Files.readAllBytes(storedModule.jarPath());
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "failed to read stored module jar for distribution: " + storedModule.jarPath(), e);
        }
        String manifestYaml = readManifestYaml(storedModule);
        ModuleInstall.Builder builder = ModuleInstall.newBuilder()
                .setModuleId(storedModule.moduleId())
                .setVersion(storedModule.version())
                .setSha256(storedModule.sha256())
                .setJarBytes(ByteString.copyFrom(jarBytes))
                .setManifestYaml(manifestYaml)
                .setIsUpgrade(isUpgrade);
        if (storedModule.sidecarPath() != null && storedModule.sidecarKind() != null) {
            byte[] sidecarBytes;
            try {
                sidecarBytes = Files.readAllBytes(storedModule.sidecarPath());
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "failed to read stored module sidecar for distribution: " + storedModule.sidecarPath(), e);
            }
            builder.setSignatureKind(storedModule.sidecarKind()).setSignatureBytes(ByteString.copyFrom(sidecarBytes));
        } else {
            builder.setSignatureKind("");
        }
        if (previousVersion != null) {
            builder.setPreviousVersion(previousVersion);
        }
        return builder.build();
    }

    private static String readManifestYaml(PlatformModuleStore.StoredModule storedModule) {
        try (JarFile jarFile = new JarFile(storedModule.jarPath().toFile())) {
            var manifestEntry = jarFile.getJarEntry(PlatformModuleManifestParser.FILE_NAME);
            if (manifestEntry == null) {
                throw new IllegalStateException("stored module '" + storedModule.moduleId() + "' has no "
                        + PlatformModuleManifestParser.FILE_NAME);
            }
            try (var input = jarFile.getInputStream(manifestEntry)) {
                return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "failed to read manifest yaml from stored module: " + storedModule.jarPath(), e);
        }
    }

    private static void sendQuietly(NodeSession session, ControllerMessage message, String label) {
        try {
            session.send(message);
        } catch (Exception e) {
            // A wedged stream observer must not abort the install — every other daemon
            // gets the message; the broken session is reaped by the heartbeat path.
            logger.warn(
                    "failed to send {} to node {} (session {}): {}",
                    label,
                    session.nodeId(),
                    session.sessionId(),
                    e.getMessage());
        }
    }
}
