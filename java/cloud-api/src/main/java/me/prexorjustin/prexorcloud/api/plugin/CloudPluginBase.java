package me.prexorjustin.prexorcloud.api.plugin;

import me.prexorjustin.prexorcloud.api.client.version.VersionDispatcher;

/**
 * Platform-agnostic base class for all Cloud plugins. Does NOT extend
 * JavaPlugin.
 */
public abstract class CloudPluginBase {

    private VersionDispatcher versionDispatcher;

    /** Called by the generated bridge — not for direct use by plugin developers. */
    public final void initVersionDispatcher(VersionDispatcher dispatcher) {
        this.versionDispatcher = dispatcher;
    }

    /**
     * Tier-1 dispatch: scans nested classes in {@code type} itself for the best
     * {@link me.prexorjustin.prexorcloud.api.client.version.ForVersion} match.
     */
    protected final <T> T adapt(Class<T> type) {
        if (versionDispatcher == null)
            throw new IllegalStateException(
                    "VersionDispatcher not initialized — generated bridge must call initVersionDispatcher() before onEnable.");
        return versionDispatcher.resolve(type);
    }

    /**
     * Tier-1 dispatch: scans nested classes in {@code container} for the best
     * {@link me.prexorjustin.prexorcloud.api.client.version.ForVersion} match
     * implementing {@code type}.
     */
    protected final <T> T adapt(Class<T> type, Class<?> container) {
        if (versionDispatcher == null)
            throw new IllegalStateException(
                    "VersionDispatcher not initialized — generated bridge must call initVersionDispatcher() before onEnable.");
        return versionDispatcher.resolve(type, container);
    }

    protected final VersionDispatcher versions() {
        return versionDispatcher;
    }

    public abstract void onEnable(CloudPluginContext ctx);

    public void onDisable() {}

    public void onReload(CloudPluginContext ctx) {}
}
