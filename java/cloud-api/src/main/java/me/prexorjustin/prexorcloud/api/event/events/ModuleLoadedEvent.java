package me.prexorjustin.prexorcloud.api.event.events;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/** Fired when a module is loaded and enabled by the controller. */
public record ModuleLoadedEvent(String moduleName, boolean hasFrontend) implements CloudEvent {

    @Override
    public String type() {
        return "MODULE_LOADED";
    }
}
