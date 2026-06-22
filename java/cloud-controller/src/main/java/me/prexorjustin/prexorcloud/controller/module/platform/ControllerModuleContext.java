package me.prexorjustin.prexorcloud.controller.module.platform;

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
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleStorage;
import me.prexorjustin.prexorcloud.api.module.scheduling.TaskScheduler;
import me.prexorjustin.prexorcloud.common.io.HttpClients;
import me.prexorjustin.prexorcloud.common.io.ObjectMappers;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller-side {@link ModuleContext}. Wires every primitive declared on the
 * interface to the controller's live infrastructure: the unified
 * {@code controller.event.EventBus}, a {@link ControllerTaskScheduler}, the
 * shared {@link HttpClients#defaultClient()}, and the standard
 * {@link ObjectMappers#standard()} Jackson config.
 *
 * <p>
 * Constructed once per module install/upgrade by {@code ModuleLifecycleManager}.
 * </p>
 */
public final class ControllerModuleContext implements ModuleContext {

    private final PlatformModuleManifest manifest;
    private final Path jarPath;
    private final String previousVersion;
    private final Map<String, Object> capabilities;
    private final PlatformModuleStorage storage;
    private final EventBus eventBus;
    private final TaskScheduler scheduler;
    private final Logger logger;

    public ControllerModuleContext(
            PlatformModuleManifest manifest,
            Path jarPath,
            String previousVersion,
            Map<String, Object> capabilities,
            PlatformModuleStorage storage,
            EventBus eventBus,
            TaskScheduler scheduler) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.jarPath = Objects.requireNonNull(jarPath, "jarPath");
        this.previousVersion = previousVersion == null ? "" : previousVersion;
        this.capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
        this.storage = storage == null ? PlatformModuleStorage.none(manifest.id(), manifest.storage()) : storage;
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
        return ModuleHost.CONTROLLER;
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
        return storage.mongo();
    }

    @Override
    public ModuleDataStore requireMongoStorage() {
        return storage.requireMongo();
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
