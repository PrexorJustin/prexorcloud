package me.prexorjustin.prexorcloud.daemon.module;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import me.prexorjustin.prexorcloud.api.module.platform.DaemonCapabilityRegistry;
import me.prexorjustin.prexorcloud.modules.runtime.CapabilityRegistry;

/**
 * Daemon-side {@link DaemonCapabilityRegistry} that adapts the lifted
 * {@link CapabilityRegistry} (the same runtime class the controller uses for lifecycle
 * binding) onto the node-local module-facing contract. Listener fan-out goes here so
 * multiple daemon modules can subscribe; the underlying {@link CapabilityRegistry} only
 * supports a single listener.
 */
public final class DaemonCapabilityRegistryImpl implements DaemonCapabilityRegistry {

    private final CapabilityRegistry runtime;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public DaemonCapabilityRegistryImpl(CapabilityRegistry runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        runtime.setListener(new CapabilityRegistry.Listener() {
            @Override
            public void onCapabilityRegistered(String capabilityId, String version, String moduleId) {
                for (Listener listener : listeners) {
                    safe(() -> listener.onCapabilityRegistered(capabilityId, version, moduleId));
                }
            }

            @Override
            public void onCapabilityUnregistered(String capabilityId, String moduleId) {
                for (Listener listener : listeners) {
                    safe(() -> listener.onCapabilityUnregistered(capabilityId, moduleId));
                }
            }

            @Override
            public void onCapabilityProviderChanged(
                    String capabilityId, String moduleId, String fromVersion, String toVersion) {
                for (Listener listener : listeners) {
                    safe(() -> listener.onCapabilityProviderChanged(capabilityId, moduleId, fromVersion, toVersion));
                }
            }
        });
    }

    @Override
    public List<CapabilityBinding> activeBindings() {
        return runtime.activeBindings().stream()
                .map(b -> new CapabilityBinding(b.capabilityId(), b.version().toString(), b.moduleId()))
                .toList();
    }

    @Override
    public Subscription addListener(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Underlying runtime registry used by the lifecycle manager for binding/resolution. */
    public CapabilityRegistry runtimeRegistry() {
        return runtime;
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (Exception _) {
            // Listener exceptions must not poison the broadcast.
        }
    }
}
