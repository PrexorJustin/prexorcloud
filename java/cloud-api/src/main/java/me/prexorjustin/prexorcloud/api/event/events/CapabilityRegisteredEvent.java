package me.prexorjustin.prexorcloud.api.event.events;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/**
 * Fired when a platform-module capability is bound to an active provider —
 * either a module activating with a {@code provides:} entry or the controller
 * registering a built-in handle. Consumers (dashboard, modules, daemon hosts)
 * use this to invalidate cached handles and refresh their view of the
 * capability graph.
 */
public record CapabilityRegisteredEvent(String capabilityId, String version, String moduleId) implements CloudEvent {

    @Override
    public String type() {
        return "CAPABILITY_REGISTERED";
    }
}
