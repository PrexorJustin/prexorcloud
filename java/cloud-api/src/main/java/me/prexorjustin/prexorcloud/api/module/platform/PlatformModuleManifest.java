package me.prexorjustin.prexorcloud.api.module.platform;

import java.util.List;

/**
 * Manifest for the replacement platform module system.
 *
 * <p>{@code hosts} declares which process(es) the module runs in. {@link Backend} carries
 * a per-host {@link EntrypointSpec}; for any host listed in {@code hosts}, the corresponding
 * {@code Backend} field must be non-null. When the manifest YAML omits {@code hosts}, the
 * parser defaults to {@code [CONTROLLER]} to preserve the shape of pre-Layer-7 modules.
 */
public record PlatformModuleManifest(
        int manifestVersion,
        String id,
        String version,
        Backend backend,
        Frontend frontend,
        CapabilityDeclaration capabilities,
        ModuleStorageRequest storage,
        List<WorkloadExtensionManifest> extensions,
        List<ModuleHost> hosts) {

    /**
     * Newest manifest schema version this controller understands. Older versions
     * down to {@link #MIN_MANIFEST_VERSION} are still accepted for backward
     * compatibility; fields introduced past their {@code minVersion} (e.g.
     * {@code capabilities.provides[].deprecatedSince} requires v2) are rejected
     * by the parser when declared against an older schema.
     */
    public static final int CURRENT_MANIFEST_VERSION = 2;

    /** Oldest manifest schema version this controller still accepts. */
    public static final int MIN_MANIFEST_VERSION = 1;

    public PlatformModuleManifest {
        capabilities = capabilities == null ? CapabilityDeclaration.EMPTY : capabilities;
        storage = storage == null ? ModuleStorageRequest.NONE : storage;
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
        hosts = hosts == null || hosts.isEmpty() ? List.of(ModuleHost.CONTROLLER) : List.copyOf(hosts);
    }

    /**
     * Eight-arg compat constructor for callers that predate the {@code hosts} field. Defaults
     * {@code hosts} to {@code [CONTROLLER]} via the canonical constructor.
     */
    public PlatformModuleManifest(
            int manifestVersion,
            String id,
            String version,
            Backend backend,
            Frontend frontend,
            CapabilityDeclaration capabilities,
            ModuleStorageRequest storage,
            List<WorkloadExtensionManifest> extensions) {
        this(manifestVersion, id, version, backend, frontend, capabilities, storage, extensions, null);
    }

    /**
     * Backend entrypoints split by host. At least one of {@code controller} or {@code daemon}
     * must be non-null; whichever hosts the module declares in {@link #hosts()} must have
     * the matching field populated.
     */
    public record Backend(EntrypointSpec controller, EntrypointSpec daemon) {

        /**
         * Convenience for legacy single-host (controller-only) callers — equivalent to
         * {@code new Backend(new EntrypointSpec(entrypoint), null)}.
         */
        public Backend(String controllerEntrypoint) {
            this(controllerEntrypoint == null ? null : new EntrypointSpec(controllerEntrypoint), null);
        }
    }

    /**
     * Single-host entrypoint coordinates. {@code reloadable} (manifestVersion 2+, default
     * {@code false}) opts the controller-hosted module into the {@code RELOADING} fast
     * path — see ADR 28 and {@link PlatformModule#onReload}.
     */
    public record EntrypointSpec(String entrypoint, boolean reloadable) {

        /** Compat shorthand for callers that pre-date the {@code reloadable} flag. */
        public EntrypointSpec(String entrypoint) {
            this(entrypoint, false);
        }
    }

    public record Frontend(int sdkVersion, String entry) {}
}
