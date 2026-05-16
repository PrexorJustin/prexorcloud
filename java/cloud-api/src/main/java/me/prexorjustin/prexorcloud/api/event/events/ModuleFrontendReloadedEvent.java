package me.prexorjustin.prexorcloud.api.event.events;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/**
 * Fired when a module's frontend bundle is re-staged in isolation from the
 * platform module's classloader — i.e. only the dashboard assets changed.
 *
 * <p>Carries the new content hash so the dashboard can bust its bundle cache
 * and re-import the entry without unmounting the entire module's REST/data
 * surface. Distinct from {@link ModuleLoadedEvent}, which implies a full
 * lifecycle reload.
 */
public record ModuleFrontendReloadedEvent(String moduleName, String contentHash) implements CloudEvent {

    @Override
    public String type() {
        return "MODULE_FRONTEND_RELOADED";
    }
}
