package me.prexorjustin.prexorcloud.api.event.events;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired when a module is disabled and unloaded by the controller. */
public record ModuleUnloadedEvent(String moduleName) implements CloudEvent {

    @Override
    public String type() {
        return "MODULE_UNLOADED";
    }
}
