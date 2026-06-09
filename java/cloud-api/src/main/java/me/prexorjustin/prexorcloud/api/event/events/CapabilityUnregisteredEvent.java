package me.prexorjustin.prexorcloud.api.event.events;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/**
 * Fired when an active platform-module capability binding is released — when
 * the providing module deactivates (or its {@code provides:} list shrinks on
 * upgrade). Pairs with {@link CapabilityRegisteredEvent}: a provider switch
 * appears as UNREGISTERED followed by REGISTERED for the same {@code capabilityId}.
 */
public record CapabilityUnregisteredEvent(String capabilityId, String moduleId) implements CloudEvent {

    @Override
    public String type() {
        return "CAPABILITY_UNREGISTERED";
    }
}
