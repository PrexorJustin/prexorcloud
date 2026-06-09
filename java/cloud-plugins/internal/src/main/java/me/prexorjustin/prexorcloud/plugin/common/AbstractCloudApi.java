package me.prexorjustin.prexorcloud.plugin.common;

import java.util.Collection;
import java.util.Optional;

import me.prexorjustin.prexorcloud.api.CloudApi;
import me.prexorjustin.prexorcloud.api.CloudApiProvider;
import me.prexorjustin.prexorcloud.api.domain.GroupView;
import me.prexorjustin.prexorcloud.api.domain.InstanceView;
import me.prexorjustin.prexorcloud.api.event.EventBus;
import me.prexorjustin.prexorcloud.api.module.cluster.ClusterView;
import me.prexorjustin.prexorcloud.api.plugin.CloudPluginContext;

/**
 * Base {@link CloudApi} implementation backed by {@link CloudStateCache}.
 * Platform-specific subclasses wire up the player manager and register the
 * context factory on startup.
 */
public abstract class AbstractCloudApi implements CloudApi {

    protected final CloudStateCache stateCache;
    protected final CloudEventBusImpl eventBus;
    protected final AbstractCommandRegistry commandRegistry;

    protected AbstractCloudApi(CloudStateCache stateCache, AbstractCommandRegistry commandRegistry) {
        this.stateCache = stateCache;
        this.eventBus = new CloudEventBusImpl();
        this.commandRegistry = commandRegistry;
    }

    public void start() {
        stateCache.start();
        CloudApiProvider.set(this);
        CloudApiProvider.setContextFactory(this::createPluginContext);
    }

    public void stop() {
        stateCache.stop();
    }

    @Override
    public EventBus events() {
        return eventBus;
    }

    @Override
    public ClusterView cluster() {
        return new CacheBackedClusterView(stateCache);
    }

    @Override
    public String version() {
        return "2.0.0";
    }

    /** Called by {@link CloudApiProvider} for every plugin that enables. */
    protected abstract CloudPluginContext createPluginContext(Object platformPlugin);

    /** Exposed so {@code AbstractPluginContext} can build instance snapshots. */
    public CloudStateCache exposedStateCache() {
        return stateCache;
    }

    /** Exposed so {@code AbstractPluginContext} can return the same registry from {@code commands()}. */
    public AbstractCommandRegistry exposedCommandRegistry() {
        return commandRegistry;
    }

    // ── ClusterView backed by CloudStateCache ──────────────────────────────────

    private static final class CacheBackedClusterView implements ClusterView {

        private final CloudStateCache cache;

        CacheBackedClusterView(CloudStateCache cache) {
            this.cache = cache;
        }

        @Override
        public Collection<GroupView> groups() {
            return cache.getAllGroups();
        }

        @Override
        public Optional<GroupView> group(String name) {
            return cache.getGroup(name);
        }

        @Override
        public Collection<InstanceView> instances() {
            return cache.getAllInstances();
        }

        @Override
        public Collection<InstanceView> instancesOfGroup(String group) {
            return cache.getInstancesByGroup(group);
        }

        @Override
        public Optional<InstanceView> instance(String instanceId) {
            return cache.getInstance(instanceId);
        }

        @Override
        public java.util.Collection<me.prexorjustin.prexorcloud.api.domain.NodeView> nodes() {
            return java.util.List.of();
        }

        @Override
        public Optional<me.prexorjustin.prexorcloud.api.domain.NodeView> node(String nodeId) {
            return Optional.empty();
        }

        @Override
        public java.util.Collection<me.prexorjustin.prexorcloud.api.domain.PlayerView> onlinePlayers() {
            return java.util.List.of();
        }

        @Override
        public int totalOnlineCount() {
            return cache.getAllInstances().stream()
                    .mapToInt(InstanceView::playerCount)
                    .sum();
        }
    }
}
