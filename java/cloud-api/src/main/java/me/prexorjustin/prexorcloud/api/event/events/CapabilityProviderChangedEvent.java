package me.prexorjustin.prexorcloud.api.event.events;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/**
 * Fired when an active capability binding changes provider in a single atomic
 * step (e.g. {@code replaceModuleBindings} swaps an existing binding's
 * declared version on module upgrade without going through unregister →
 * register). For provider switches across different modules, the registry
 * emits {@link CapabilityUnregisteredEvent} + {@link CapabilityRegisteredEvent}
 * instead — this event is reserved for in-place same-module rebindings.
 */
public record CapabilityProviderChangedEvent(String capabilityId, String moduleId, String fromVersion, String toVersion)
        implements CloudEvent {

    @Override
    public String type() {
        return "CAPABILITY_PROVIDER_CHANGED";
    }
}
