package me.prexorjustin.prexorcloud.api;

import java.util.concurrent.atomic.AtomicReference;

import me.prexorjustin.prexorcloud.api.plugin.CloudPluginContext;

/**
 * Singleton holder for the {@link CloudApi} instance and the plugin-context
 * factory.
 *
 * <p>
 * The infrastructure (e.g. PrexorCloudPaper, PrexorCloudVelocity) calls
 * {@link #set(CloudApi)} and {@link #setContextFactory(PluginContextFactory)}
 * on startup. Generated bridge classes call
 * {@link #createPluginContext(Object)} — plugin developers never interact with
 * this class directly.
 */
public final class CloudApiProvider {

    private static final AtomicReference<CloudApi> INSTANCE = new AtomicReference<>();
    private static final AtomicReference<PluginContextFactory> CONTEXT_FACTORY = new AtomicReference<>();

    private CloudApiProvider() {}

    public static CloudApi get() {
        CloudApi api = INSTANCE.get();
        if (api == null) throw new IllegalStateException("CloudApi is not initialized — is PrexorCloud running?");
        return api;
    }

    public static void set(CloudApi api) {
        if (!INSTANCE.compareAndSet(null, api)) throw new IllegalStateException("CloudApi is already initialized");
    }

    public static void setContextFactory(PluginContextFactory factory) {
        CONTEXT_FACTORY.set(factory);
    }

    /**
     * Creates a {@link CloudPluginContext} for the given platform plugin instance.
     * Called by generated bridge classes — not intended for direct use.
     *
     * @param platformPlugin
     *            the raw platform plugin object (e.g. {@code JavaPlugin}, Velocity
     *            {@code @Plugin})
     */
    public static CloudPluginContext createPluginContext(Object platformPlugin) {
        PluginContextFactory factory = CONTEXT_FACTORY.get();
        if (factory == null)
            throw new IllegalStateException(
                    "CloudApi context factory not set — is the PrexorCloud infrastructure plugin loaded?");
        return factory.create(platformPlugin);
    }

    @FunctionalInterface
    public interface PluginContextFactory {

        CloudPluginContext create(Object platformPlugin);
    }
}
