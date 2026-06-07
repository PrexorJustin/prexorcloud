package me.prexorjustin.prexorcloud.controller.module.platform;

/**
 * Callback the {@link PlatformModuleManager} fires on install/upgrade/uninstall so the
 * controller's gRPC layer can push daemon-host modules to connected daemons. Modeled on
 * the {@code ModuleRouteRegistry.Hook} pattern: lifecycle code stays decoupled from
 * gRPC distribution, and tests can pass {@link #NOOP_HOOK}.
 */
public interface ModuleDistributorHook {

    ModuleDistributorHook NOOP_HOOK = new ModuleDistributorHook() {
        @Override
        public void onInstalled(
                PlatformModuleStore.StoredModule storedModule, boolean isUpgrade, String previousVersion) {}

        @Override
        public void onUninstalled(String moduleId) {}
    };

    /**
     * Fires when a module has been committed to the controller's store and accepted by
     * the lifecycle manager. {@code isUpgrade} is {@code true} when an existing version
     * was replaced; {@code previousVersion} is non-null only in that case.
     */
    void onInstalled(PlatformModuleStore.StoredModule storedModule, boolean isUpgrade, String previousVersion);

    /** Fires when a module has been removed from the controller's store. */
    void onUninstalled(String moduleId);
}
