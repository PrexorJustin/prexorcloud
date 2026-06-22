package me.prexorjustin.prexorcloud.daemon.module;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import me.prexorjustin.prexorcloud.api.event.EventBus;
import me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore;
import me.prexorjustin.prexorcloud.api.module.platform.CapabilityHandleResolver;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleContext;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleHost;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleManifest;
import me.prexorjustin.prexorcloud.api.module.scheduling.TaskScheduler;
import me.prexorjustin.prexorcloud.common.io.HttpClients;
import me.prexorjustin.prexorcloud.common.io.ObjectMappers;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Daemon-side {@link ModuleContext}. Mirror of {@code ControllerModuleContext} but with
 * {@link #host()} returning {@link ModuleHost#DAEMON} and Mongo storage unavailable —
 * daemon modules are node-local and have no persistent backing store in v1. Redis storage
 * stays unavailable too; daemons don't carry a Lettuce client.
 */
public final class DaemonModuleContext implements ModuleContext {

    private final PlatformModuleManifest manifest;
    private final Path jarPath;
    private final String previousVersion;
    private final Map<String, Object> capabilities;
    private final EventBus eventBus;
    private final TaskScheduler scheduler;
    private final Logger logger;

    public DaemonModuleContext(
            PlatformModuleManifest manifest,
            Path jarPath,
            String previousVersion,
            Map<String, Object> capabilities,
            EventBus eventBus,
            TaskScheduler scheduler) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.jarPath = Objects.requireNonNull(jarPath, "jarPath");
        this.previousVersion = previousVersion == null ? "" : previousVersion;
        this.capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = LoggerFactory.getLogger("module:" + manifest.id());
    }

    @Override
    public PlatformModuleManifest manifest() {
        return manifest;
    }

    @Override
    public Path jarPath() {
        return jarPath;
    }

    @Override
    public String previousVersion() {
        return previousVersion;
    }

    @Override
    public ModuleHost host() {
        return ModuleHost.DAEMON;
    }

    @Override
    public <T> Optional<T> findCapability(String capabilityId, Class<T> type) {
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(type, "type");
        Object handle = capabilities.get(capabilityId);
        if (handle == null) {
            return Optional.empty();
        }
        if (handle instanceof CapabilityHandleResolver resolver) {
            return Optional.of(resolver.resolve(type));
        }
        if (!type.isInstance(handle)) {
            throw new IllegalStateException("capability '" + capabilityId + "' is not assignable to " + type.getName());
        }
        return Optional.of(type.cast(handle));
    }

    @Override
    public <T> T requireCapability(String capabilityId, Class<T> type) {
        return findCapability(capabilityId, type)
                .orElseThrow(() -> new IllegalStateException("required capability is not available: " + capabilityId));
    }

    @Override
    public Optional<ModuleDataStore> findMongoStorage() {
        return Optional.empty();
    }

    @Override
    public ModuleDataStore requireMongoStorage() {
        throw new IllegalStateException("daemon modules have no Mongo storage");
    }

    @Override
    public EventBus events() {
        return eventBus;
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public TaskScheduler scheduler() {
        return scheduler;
    }

    @Override
    public HttpClient httpClient() {
        return HttpClients.defaultClient();
    }

    @Override
    public ObjectMapper json() {
        return ObjectMappers.standard();
    }
}
