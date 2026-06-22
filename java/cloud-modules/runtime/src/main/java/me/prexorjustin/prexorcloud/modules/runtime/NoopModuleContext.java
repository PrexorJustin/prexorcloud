package me.prexorjustin.prexorcloud.modules.runtime;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import me.prexorjustin.prexorcloud.api.ScheduledTask;
import me.prexorjustin.prexorcloud.api.event.CloudEvent;
import me.prexorjustin.prexorcloud.api.event.CustomCloudEvent;
import me.prexorjustin.prexorcloud.api.event.EventBus;
import me.prexorjustin.prexorcloud.api.event.EventHandler;
import me.prexorjustin.prexorcloud.api.event.EventSubscription;
import me.prexorjustin.prexorcloud.api.event.EventSubscriptionBuilder;
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
 * No-op {@link ModuleContext} for tests and harness scenarios where a module's
 * lifecycle is exercised but the surrounding infrastructure (event bus,
 * scheduler) is not under test.
 *
 * <p>
 * {@link #events()} accepts subscriptions but never publishes;
 * {@link #scheduler()} returns a no-op scheduler that returns immediately
 * cancelled tasks; {@link #httpClient()} and {@link #json()} return the real
 * shared singletons (HTTP and JSON are pure-IO and don't need stubbing).
 * </p>
 */
final class NoopModuleContext implements ModuleContext {

    private static final EventBus NOOP_BUS = new NoopEventBus();
    private static final TaskScheduler NOOP_SCHEDULER = new NoopScheduler();

    private final PlatformModuleManifest manifest;
    private final Path jarPath;
    private final String previousVersion;
    private final Map<String, Object> capabilities;
    private final PlatformModuleStorage storage;
    private final Logger logger;

    NoopModuleContext(
            PlatformModuleManifest manifest,
            Path jarPath,
            String previousVersion,
            Map<String, Object> capabilities,
            PlatformModuleStorage storage) {
        this.manifest = Objects.requireNonNull(manifest);
        this.jarPath = Objects.requireNonNull(jarPath);
        this.previousVersion = previousVersion == null ? "" : previousVersion;
        this.capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
        this.storage = storage == null ? PlatformModuleStorage.none(manifest.id(), manifest.storage()) : storage;
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
        Object handle = capabilities.get(capabilityId);
        if (handle == null) return Optional.empty();
        if (handle instanceof CapabilityHandleResolver resolver) return Optional.of(resolver.resolve(type));
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
        return NOOP_BUS;
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public TaskScheduler scheduler() {
        return NOOP_SCHEDULER;
    }

    @Override
    public HttpClient httpClient() {
        return HttpClients.defaultClient();
    }

    @Override
    public ObjectMapper json() {
        return ObjectMappers.standard();
    }

    private static final class NoopEventBus implements EventBus {
        private static final EventSubscription NOOP_SUB = () -> {};

        @Override
        public <T extends CloudEvent> EventSubscriptionBuilder<T> on(Class<T> eventType) {
            return new EventSubscriptionBuilder<>() {
                @Override
                public EventSubscriptionBuilder<T> filter(java.util.function.Predicate<T> predicate) {
                    return this;
                }

                @Override
                public EventSubscription subscribe(EventHandler<T> handler) {
                    return NOOP_SUB;
                }
            };
        }

        @Override
        public <T extends CloudEvent> EventSubscription subscribe(Class<T> eventType, EventHandler<T> handler) {
            return NOOP_SUB;
        }

        @Override
        public EventSubscription subscribeByType(String type, EventHandler<CustomCloudEvent> handler) {
            return NOOP_SUB;
        }

        @Override
        public EventSubscription subscribeAll(EventHandler<CloudEvent> handler) {
            return NOOP_SUB;
        }

        @Override
        public void publish(CloudEvent event) {
            // discarded
        }
    }

    private static final class NoopScheduler implements TaskScheduler {
        private static final ScheduledTask CANCELLED = new ScheduledTask() {
            @Override
            public void cancel() {}

            @Override
            public boolean isCancelled() {
                return true;
            }
        };

        @Override
        public ScheduledTask schedule(Runnable task) {
            return CANCELLED;
        }

        @Override
        public ScheduledTask scheduleDelayed(Duration delay, Runnable task) {
            return CANCELLED;
        }

        @Override
        public ScheduledTask scheduleAtFixedRate(Duration initialDelay, Duration period, Runnable task) {
            return CANCELLED;
        }

        @Override
        public ScheduledTask scheduleAt(Instant when, Runnable task) {
            return CANCELLED;
        }

        @Override
        public <T> CompletableFuture<T> submit(Callable<T> task) {
            return CompletableFuture.failedFuture(new IllegalStateException("noop scheduler cannot run tasks"));
        }
    }
}
