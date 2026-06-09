package me.prexorjustin.prexorcloud.api.module.platform;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import me.prexorjustin.prexorcloud.api.ScheduledTask;
import me.prexorjustin.prexorcloud.api.event.CloudEvent;
import me.prexorjustin.prexorcloud.api.event.CustomCloudEvent;
import me.prexorjustin.prexorcloud.api.event.EventBus;
import me.prexorjustin.prexorcloud.api.event.EventHandler;
import me.prexorjustin.prexorcloud.api.event.EventSubscription;
import me.prexorjustin.prexorcloud.api.event.EventSubscriptionBuilder;
import me.prexorjustin.prexorcloud.api.module.data.ModuleDataStore;
import me.prexorjustin.prexorcloud.api.module.scheduling.TaskScheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static factories for {@link ModuleContext} suitable for unit tests and
 * other harnesses where a full controller / daemon process is not running.
 *
 * <p>
 * Production code constructs a {@code ControllerModuleContext} (in the
 * controller) or {@code DaemonModuleContext} (in the daemon) directly — this
 * class exists only to spare test code from re-implementing the symmetric
 * primitives every time.
 * </p>
 */
public final class ModuleContexts {

    private ModuleContexts() {}

    /**
     * Builds a {@link ModuleContext} with no-op event bus, no-op scheduler,
     * and JDK-default {@link HttpClient} / {@link ObjectMapper}. Suitable for
     * tests that exercise a module's lifecycle hooks without exercising the
     * surrounding cluster.
     */
    public static ModuleContext forTest(
            PlatformModuleManifest manifest,
            Path jarPath,
            String previousVersion,
            Map<String, Object> capabilities,
            PlatformModuleStorage storage) {
        return new TestModuleContext(manifest, jarPath, previousVersion, capabilities, storage);
    }

    private static final class TestModuleContext implements ModuleContext {

        private static final EventBus NOOP_BUS = new NoopEventBus();
        private static final TaskScheduler NOOP_SCHEDULER = new NoopScheduler();
        private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
        private static final ObjectMapper JSON = new ObjectMapper();

        private final PlatformModuleManifest manifest;
        private final Path jarPath;
        private final String previousVersion;
        private final Map<String, Object> capabilities;
        private final PlatformModuleStorage storage;
        private final Logger logger;

        TestModuleContext(
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
                throw new IllegalStateException(
                        "capability '" + capabilityId + "' is not assignable to " + type.getName());
            }
            return Optional.of(type.cast(handle));
        }

        @Override
        public <T> T requireCapability(String capabilityId, Class<T> type) {
            return findCapability(capabilityId, type)
                    .orElseThrow(
                            () -> new IllegalStateException("required capability is not available: " + capabilityId));
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
        public Optional<PlatformRedisStorage> findRedisStorage() {
            return storage.redis();
        }

        @Override
        public PlatformRedisStorage requireRedisStorage() {
            return storage.requireRedis();
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
            return HTTP_CLIENT;
        }

        @Override
        public ObjectMapper json() {
            return JSON;
        }
    }

    private static final class NoopEventBus implements EventBus {
        private static final EventSubscription NOOP_SUB = () -> {};

        @Override
        public <T extends CloudEvent> EventSubscriptionBuilder<T> on(Class<T> eventType) {
            return new EventSubscriptionBuilder<>() {
                @Override
                public EventSubscriptionBuilder<T> filter(Predicate<T> predicate) {
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
