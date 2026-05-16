package me.prexorjustin.prexorcloud.modules.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import me.prexorjustin.prexorcloud.api.module.SemverRange;
import me.prexorjustin.prexorcloud.api.module.Version;
import me.prexorjustin.prexorcloud.api.module.platform.CapabilityDeclaration;
import me.prexorjustin.prexorcloud.api.module.platform.CapabilityHandle;
import me.prexorjustin.prexorcloud.api.module.platform.CapabilityHandleResolver;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleManifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks active platform-module capability providers and resolves requirements.
 */
public final class CapabilityRegistry {

    private static final Logger logger = LoggerFactory.getLogger(CapabilityRegistry.class);

    /**
     * Sentinel module id reserved for capabilities the controller registers itself
     * (e.g. {@code prexor.player.journey}). Real platform-module ids never start
     * with {@code @}, so a normal {@link #activateModule}/{@link #deactivateModule}
     * call cannot clobber a built-in binding.
     */
    public static final String BUILTIN_PROVIDER_ID = "@controller";

    /**
     * Active binding. {@code deprecatedSince}/{@code removedIn} are nullable and
     * come from the providing manifest's {@code capabilities.provides[]} entry —
     * set, they cause {@link #resolveRequiredHandles} and
     * {@link #unresolvedRequirements} to warn for any consumer that resolves
     * against this capability.
     */
    public record CapabilityBinding(
            String capabilityId,
            Version version,
            String moduleId,
            Object handle,
            String deprecatedSince,
            String removedIn) {

        /** Backward-compat 4-arg constructor for callers that pre-date the deprecation fields. */
        public CapabilityBinding(String capabilityId, Version version, String moduleId, Object handle) {
            this(capabilityId, version, moduleId, handle, null, null);
        }

        public boolean isDeprecated() {
            return deprecatedSince != null;
        }
    }

    public record UnresolvedRequirement(String moduleId, String capabilityId, String versionRange, String reason) {}

    public record MetricsSnapshot(
            long resolutionCount,
            long unresolvedRequirementCount,
            long rebindingEventCount,
            long deprecatedProviderResolutionCount,
            Duration lastResolutionLatency) {}

    private final Map<String, CapabilityBinding> activeBindingsByCapability = new LinkedHashMap<>();
    private final Map<String, Set<String>> activeCapabilitiesByModule = new LinkedHashMap<>();
    private final Map<String, DynamicCapabilityHandle> dynamicHandlesByCapability = new LinkedHashMap<>();

    private long resolutionCount;
    private long unresolvedRequirementCount;
    private long rebindingEventCount;
    private long deprecatedProviderResolutionCount;
    private Duration lastResolutionLatency = Duration.ZERO;

    /**
     * Notified when a capability binding is created, replaced, or released.
     * The bootstrap wires this to the controller event bus so REST/SSE
     * consumers (notably the dashboard's {@code useCapability}) see the
     * graph change in real time. CapabilityRegistry holds at most one
     * listener — the bootstrap fans out from there.
     */
    public interface Listener {
        void onCapabilityRegistered(String capabilityId, String version, String moduleId);

        void onCapabilityUnregistered(String capabilityId, String moduleId);

        void onCapabilityProviderChanged(String capabilityId, String moduleId, String fromVersion, String toVersion);
    }

    private volatile Listener listener = NULL_LISTENER;

    private static final Listener NULL_LISTENER = new Listener() {
        @Override
        public void onCapabilityRegistered(String capabilityId, String version, String moduleId) {}

        @Override
        public void onCapabilityUnregistered(String capabilityId, String moduleId) {}

        @Override
        public void onCapabilityProviderChanged(
                String capabilityId, String moduleId, String fromVersion, String toVersion) {}
    };

    public void setListener(Listener listener) {
        this.listener = listener == null ? NULL_LISTENER : listener;
    }

    public void activateModule(PlatformModuleManifest manifest, List<CapabilityHandle<?>> handles) {
        List<Runnable> notifications = activateModuleLocked(manifest, handles);
        notifications.forEach(Runnable::run);
    }

    private synchronized List<Runnable> activateModuleLocked(
            PlatformModuleManifest manifest, List<CapabilityHandle<?>> handles) {
        Objects.requireNonNull(manifest, "manifest");
        Map<String, Object> normalizedHandles = indexHandles(handles);

        List<Runnable> notifications = new ArrayList<>();

        for (CapabilityDeclaration.Provides provided : manifest.capabilities().provides()) {
            Version version = parseVersion(provided.id(), provided.version());
            CapabilityBinding existing = activeBindingsByCapability.get(provided.id());
            if (existing != null && !existing.moduleId().equals(manifest.id())) {
                throw new IllegalStateException("capability '" + provided.id() + "' is already provided by module '"
                        + existing.moduleId()
                        + "'");
            }

            boolean firstBind = existing == null;
            CapabilityBinding binding = new CapabilityBinding(
                    provided.id(),
                    version,
                    manifest.id(),
                    normalizedHandles.get(provided.id()),
                    provided.deprecatedSince(),
                    provided.removedIn());
            activeBindingsByCapability.put(provided.id(), binding);
            activeCapabilitiesByModule
                    .computeIfAbsent(manifest.id(), ignored -> new LinkedHashSet<>())
                    .add(provided.id());
            dynamicHandlesByCapability
                    .computeIfAbsent(provided.id(), DynamicCapabilityHandle::new)
                    .setDelegate(normalizedHandles.get(provided.id()));
            if (firstBind) {
                rebindingEventCount++;
                notifications.add(
                        () -> listener.onCapabilityRegistered(provided.id(), version.toString(), manifest.id()));
            } else if (!existing.version().equals(version)) {
                notifications.add(() -> listener.onCapabilityProviderChanged(
                        provided.id(), manifest.id(), existing.version().toString(), version.toString()));
            }
        }
        return notifications;
    }

    public void deactivateModule(String moduleId) {
        List<Runnable> notifications = deactivateModuleLocked(moduleId);
        notifications.forEach(Runnable::run);
    }

    private synchronized List<Runnable> deactivateModuleLocked(String moduleId) {
        Set<String> capabilityIds = activeCapabilitiesByModule.remove(moduleId);
        if (capabilityIds == null || capabilityIds.isEmpty()) {
            return List.of();
        }
        List<Runnable> notifications = new ArrayList<>(capabilityIds.size());
        for (String capabilityId : capabilityIds) {
            activeBindingsByCapability.remove(capabilityId);
            DynamicCapabilityHandle dynamicHandle = dynamicHandlesByCapability.get(capabilityId);
            if (dynamicHandle != null) {
                dynamicHandle.setDelegate(null);
            }
            notifications.add(() -> listener.onCapabilityUnregistered(capabilityId, moduleId));
        }
        return notifications;
    }

    public void replaceModuleBindings(PlatformModuleManifest manifest, List<CapabilityHandle<?>> handles) {
        List<Runnable> notifications = replaceModuleBindingsLocked(manifest, handles);
        notifications.forEach(Runnable::run);
    }

    private synchronized List<Runnable> replaceModuleBindingsLocked(
            PlatformModuleManifest manifest, List<CapabilityHandle<?>> handles) {
        Objects.requireNonNull(manifest, "manifest");
        Map<String, Object> normalizedHandles = indexHandles(handles);
        Set<String> previousCapabilities =
                new LinkedHashSet<>(activeCapabilitiesByModule.getOrDefault(manifest.id(), Set.of()));
        Set<String> nextCapabilities = new LinkedHashSet<>();

        List<Runnable> notifications = new ArrayList<>();

        for (CapabilityDeclaration.Provides provided : manifest.capabilities().provides()) {
            Version version = parseVersion(provided.id(), provided.version());
            CapabilityBinding existing = activeBindingsByCapability.get(provided.id());
            if (existing != null && !existing.moduleId().equals(manifest.id())) {
                throw new IllegalStateException("capability '" + provided.id() + "' is already provided by module '"
                        + existing.moduleId()
                        + "'");
            }

            CapabilityBinding binding = new CapabilityBinding(
                    provided.id(),
                    version,
                    manifest.id(),
                    normalizedHandles.get(provided.id()),
                    provided.deprecatedSince(),
                    provided.removedIn());
            activeBindingsByCapability.put(provided.id(), binding);
            dynamicHandlesByCapability
                    .computeIfAbsent(provided.id(), DynamicCapabilityHandle::new)
                    .setDelegate(normalizedHandles.get(provided.id()));
            nextCapabilities.add(provided.id());
            if (existing == null) {
                notifications.add(
                        () -> listener.onCapabilityRegistered(provided.id(), version.toString(), manifest.id()));
            } else {
                rebindingEventCount++;
                if (!existing.version().equals(version)) {
                    notifications.add(() -> listener.onCapabilityProviderChanged(
                            provided.id(), manifest.id(), existing.version().toString(), version.toString()));
                }
            }
        }

        previousCapabilities.removeAll(nextCapabilities);
        for (String removedCapability : previousCapabilities) {
            CapabilityBinding existing = activeBindingsByCapability.get(removedCapability);
            if (existing != null && existing.moduleId().equals(manifest.id())) {
                activeBindingsByCapability.remove(removedCapability);
                DynamicCapabilityHandle dynamicHandle = dynamicHandlesByCapability.get(removedCapability);
                if (dynamicHandle != null) {
                    dynamicHandle.setDelegate(null);
                }
                final String capId = removedCapability;
                notifications.add(() -> listener.onCapabilityUnregistered(capId, manifest.id()));
            }
        }

        if (nextCapabilities.isEmpty()) {
            activeCapabilitiesByModule.remove(manifest.id());
        } else {
            activeCapabilitiesByModule.put(manifest.id(), nextCapabilities);
        }
        return notifications;
    }

    public void registerBuiltinHandle(String capabilityId, String version, Object handle) {
        Runnable notification = registerBuiltinHandleLocked(capabilityId, version, handle);
        notification.run();
    }

    private synchronized Runnable registerBuiltinHandleLocked(String capabilityId, String version, Object handle) {
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(handle, "handle");
        Version parsedVersion = parseVersion(capabilityId, version);
        CapabilityBinding existing = activeBindingsByCapability.get(capabilityId);
        if (existing != null) {
            throw new IllegalStateException(
                    "capability '" + capabilityId + "' is already provided by '" + existing.moduleId() + "'");
        }
        CapabilityBinding binding = new CapabilityBinding(capabilityId, parsedVersion, BUILTIN_PROVIDER_ID, handle);
        activeBindingsByCapability.put(capabilityId, binding);
        activeCapabilitiesByModule
                .computeIfAbsent(BUILTIN_PROVIDER_ID, ignored -> new LinkedHashSet<>())
                .add(capabilityId);
        dynamicHandlesByCapability
                .computeIfAbsent(capabilityId, DynamicCapabilityHandle::new)
                .setDelegate(handle);
        final String version0 = parsedVersion.toString();
        return () -> listener.onCapabilityRegistered(capabilityId, version0, BUILTIN_PROVIDER_ID);
    }

    public synchronized Optional<CapabilityBinding> find(String capabilityId) {
        return Optional.ofNullable(activeBindingsByCapability.get(capabilityId));
    }

    /**
     * Snapshot of every active binding — used by REST consumers that need the
     * full graph including built-in {@code @controller} capabilities (which
     * the per-module view doesn't see).
     */
    public synchronized List<CapabilityBinding> activeBindings() {
        return List.copyOf(activeBindingsByCapability.values());
    }

    public synchronized boolean requirementsSatisfied(PlatformModuleManifest manifest) {
        return unresolvedRequirements(manifest).isEmpty();
    }

    public synchronized Map<String, Object> resolveRequiredHandles(PlatformModuleManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");

        Map<String, Object> handles = new LinkedHashMap<>();
        for (CapabilityDeclaration.Requires requirement :
                manifest.capabilities().requires()) {
            CapabilityBinding binding = activeBindingsByCapability.get(requirement.id());
            if (binding == null) {
                continue;
            }

            SemverRange range = parseRange(requirement.id(), requirement.versionRange());
            if (range.contains(binding.version())) {
                if (binding.isDeprecated()) {
                    warnDeprecatedResolution(manifest.id(), requirement, binding);
                }
                if (binding.handle() != null) {
                    handles.put(
                            requirement.id(),
                            dynamicHandlesByCapability
                                    .computeIfAbsent(requirement.id(), DynamicCapabilityHandle::new)
                                    .withInitialDelegate(binding.handle()));
                }
            }
        }
        return Map.copyOf(handles);
    }

    private void warnDeprecatedResolution(
            String consumerId, CapabilityDeclaration.Requires requirement, CapabilityBinding binding) {
        deprecatedProviderResolutionCount++;
        logger.warn(
                "Module '{}' resolved capability '{}' (range {}) against deprecated provider '{}@{}' "
                        + "(deprecatedSince={}{}). Migrate before the capability is removed.",
                consumerId,
                requirement.id(),
                requirement.versionRange(),
                binding.moduleId(),
                binding.version(),
                binding.deprecatedSince(),
                binding.removedIn() == null ? "" : ", removedIn=" + binding.removedIn());
    }

    public synchronized List<UnresolvedRequirement> unresolvedRequirements(PlatformModuleManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");

        Instant startedAt = Instant.now();
        List<UnresolvedRequirement> unresolved = new ArrayList<>();
        for (CapabilityDeclaration.Requires requirement :
                manifest.capabilities().requires()) {
            CapabilityBinding binding = activeBindingsByCapability.get(requirement.id());
            if (binding == null) {
                unresolved.add(new UnresolvedRequirement(
                        manifest.id(), requirement.id(), requirement.versionRange(), "missing provider"));
                continue;
            }

            SemverRange range = parseRange(requirement.id(), requirement.versionRange());
            if (!range.contains(binding.version())) {
                unresolved.add(new UnresolvedRequirement(
                        manifest.id(),
                        requirement.id(),
                        requirement.versionRange(),
                        "version mismatch: active provider " + binding.moduleId() + "@" + binding.version()));
            }
        }

        resolutionCount++;
        unresolvedRequirementCount += unresolved.size();
        lastResolutionLatency = Duration.between(startedAt, Instant.now());
        return List.copyOf(unresolved);
    }

    public synchronized MetricsSnapshot metrics() {
        return new MetricsSnapshot(
                resolutionCount,
                unresolvedRequirementCount,
                rebindingEventCount,
                deprecatedProviderResolutionCount,
                lastResolutionLatency);
    }

    synchronized int dynamicHandleProxyCacheSizeForTesting(String capabilityId) {
        DynamicCapabilityHandle handle = dynamicHandlesByCapability.get(capabilityId);
        return handle == null ? -1 : handle.proxyCacheSizeForTesting();
    }

    synchronized Object dynamicHandleDelegateForTesting(String capabilityId) {
        DynamicCapabilityHandle handle = dynamicHandlesByCapability.get(capabilityId);
        return handle == null ? null : handle.delegateForTesting();
    }

    public void validateNoCycles(Collection<PlatformModuleManifest> manifests) {
        Map<String, PlatformModuleManifest> manifestsById = new LinkedHashMap<>();
        Map<String, String> providerByCapability = new LinkedHashMap<>();

        for (PlatformModuleManifest manifest : manifests) {
            PlatformModuleManifest existing = manifestsById.putIfAbsent(manifest.id(), manifest);
            if (existing != null) {
                throw new IllegalStateException("duplicate module id in capability graph: " + manifest.id());
            }
            for (CapabilityDeclaration.Provides provided :
                    manifest.capabilities().provides()) {
                String existingProvider = providerByCapability.putIfAbsent(provided.id(), manifest.id());
                if (existingProvider != null && !existingProvider.equals(manifest.id())) {
                    throw new IllegalStateException("capability '"
                            + provided.id()
                            + "' is provided by multiple modules: '"
                            + existingProvider
                            + "' and '"
                            + manifest.id()
                            + "'");
                }
            }
        }

        Map<String, Set<String>> edges = new LinkedHashMap<>();
        for (PlatformModuleManifest manifest : manifests) {
            Set<String> dependencies = new LinkedHashSet<>();
            for (CapabilityDeclaration.Requires requirement :
                    manifest.capabilities().requires()) {
                String provider = providerByCapability.get(requirement.id());
                if (provider != null && !provider.equals(manifest.id())) {
                    dependencies.add(provider);
                }
            }
            edges.put(manifest.id(), dependencies);
        }

        detectCycle(edges);
    }

    private static void detectCycle(Map<String, Set<String>> edges) {
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> path = new ArrayDeque<>();

        for (String moduleId : edges.keySet()) {
            if (!visited.contains(moduleId)) {
                visit(moduleId, edges, visiting, visited, path);
            }
        }
    }

    private static void visit(
            String moduleId,
            Map<String, Set<String>> edges,
            Set<String> visiting,
            Set<String> visited,
            Deque<String> path) {
        if (visiting.contains(moduleId)) {
            List<String> cycle = new ArrayList<>(path);
            cycle.add(moduleId);
            throw new IllegalStateException("capability dependency cycle detected: " + String.join(" -> ", cycle));
        }
        if (visited.contains(moduleId)) {
            return;
        }

        visiting.add(moduleId);
        path.addLast(moduleId);
        for (String dependency : edges.getOrDefault(moduleId, Set.of())) {
            visit(dependency, edges, visiting, visited, path);
        }
        path.removeLast();
        visiting.remove(moduleId);
        visited.add(moduleId);
    }

    private static Map<String, Object> indexHandles(List<CapabilityHandle<?>> handles) {
        if (handles == null || handles.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> indexed = new LinkedHashMap<>();
        for (CapabilityHandle<?> handle : handles) {
            if (indexed.put(handle.id(), handle.value()) != null) {
                throw new IllegalArgumentException("duplicate capability handle id in provider: " + handle.id());
            }
        }
        return indexed;
    }

    private static Version parseVersion(String capabilityId, String version) {
        try {
            return Version.parse(version);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "capability '" + capabilityId + "' declares invalid version '" + version + "'", e);
        }
    }

    private static SemverRange parseRange(String capabilityId, String versionRange) {
        try {
            return SemverRange.parse(versionRange);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "capability '" + capabilityId + "' declares invalid version range '" + versionRange + "'", e);
        }
    }

    private static final class DynamicCapabilityHandle implements CapabilityHandleResolver {

        private final String capabilityId;
        private final ConcurrentHashMap<Class<?>, Object> proxies = new ConcurrentHashMap<>();
        private volatile Object delegate;

        private DynamicCapabilityHandle(String capabilityId) {
            this.capabilityId = capabilityId;
        }

        private DynamicCapabilityHandle withInitialDelegate(Object delegate) {
            setDelegate(delegate);
            return this;
        }

        private void setDelegate(Object delegate) {
            this.delegate = delegate;
            if (delegate == null) {
                // Drop cached Proxy instances and their Class<?> keys so a deactivated
                // provider's classloader (or a consumer's, if the consumer resolved
                // against a type it owned) is not pinned by this registry.
                proxies.clear();
            }
        }

        int proxyCacheSizeForTesting() {
            return proxies.size();
        }

        Object delegateForTesting() {
            return delegate;
        }

        @Override
        public <T> T resolve(Class<T> type) {
            Objects.requireNonNull(type, "type");
            if (!type.isInterface()) {
                Object current = requireDelegate(type);
                return type.cast(current);
            }
            return type.cast(proxies.computeIfAbsent(type, this::createProxy));
        }

        private Object createProxy(Class<?> type) {
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "toString" -> "CapabilityProxy[" + capabilityId + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> method.invoke(this, args);
                    };
                }

                Object current = requireDelegate(type);
                try {
                    return method.invoke(current, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        }

        private Object requireDelegate(Class<?> type) {
            Object current = delegate;
            if (current == null) {
                throw new IllegalStateException("required capability is not available: " + capabilityId);
            }
            if (!type.isInstance(current)) {
                throw new IllegalStateException(
                        "capability '" + capabilityId + "' is not assignable to " + type.getName());
            }
            return current;
        }
    }
}
