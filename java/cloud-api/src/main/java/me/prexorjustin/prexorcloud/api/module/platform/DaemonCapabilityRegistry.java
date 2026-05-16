package me.prexorjustin.prexorcloud.api.module.platform;

import java.util.List;

/**
 * Node-local capability registry exposed to daemon modules.
 *
 * <p>Mirrors the controller-side {@code CapabilityRegistry} contract that daemon modules
 * need: snapshot {@link #activeBindings()} of capability/version/provider tuples and a
 * change-notification {@link Listener}. Cross-node capability sharing is intentionally
 * out of scope for v1 — only daemon modules running on the same node see each other's
 * bindings here. v2 may broaden this to a cluster-wide view via the controller bridge.
 */
public interface DaemonCapabilityRegistry {

    /** Single active capability binding on this daemon node. */
    record CapabilityBinding(String capabilityId, String version, String moduleId) {}

    /** Snapshot of every active binding on this daemon node. */
    List<CapabilityBinding> activeBindings();

    /**
     * Subscribe to bind/unbind/replace events. Returns a handle that detaches the listener
     * when {@link Subscription#unsubscribe()} is called.
     */
    Subscription addListener(Listener listener);

    interface Listener {
        void onCapabilityRegistered(String capabilityId, String version, String moduleId);

        void onCapabilityUnregistered(String capabilityId, String moduleId);

        void onCapabilityProviderChanged(String capabilityId, String moduleId, String fromVersion, String toVersion);
    }

    interface Subscription {
        void unsubscribe();
    }
}
