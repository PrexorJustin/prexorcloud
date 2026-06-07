package me.prexorjustin.prexorcloud.api.module.platform;

import java.util.List;

/**
 * Capability linkage declared by a platform module. {@code provides} entries
 * are offered to other modules; {@code requires} entries must resolve before
 * the owning module transitions to ACTIVE.
 */
public record CapabilityDeclaration(List<Provides> provides, List<Requires> requires) {

    public static final CapabilityDeclaration EMPTY = new CapabilityDeclaration(List.of(), List.of());

    public CapabilityDeclaration {
        provides = provides == null ? List.of() : List.copyOf(provides);
        requires = requires == null ? List.of() : List.copyOf(requires);
    }

    public boolean isEmpty() {
        return provides.isEmpty() && requires.isEmpty();
    }

    /**
     * Capability advertised by a module. {@code deprecatedSince} and {@code removedIn}
     * are nullable and were added in {@code manifestVersion: 2}; both are semver-shaped
     * strings naming the *provider* version where the capability entered (and will
     * exit) the deprecation window. A non-null {@code deprecatedSince} is the signal
     * the controller uses to warn consumers still resolving against this capability.
     */
    public record Provides(String id, String version, String deprecatedSince, String removedIn) {

        /** Two-arg shorthand used by callers that pre-date the deprecation fields. */
        public Provides(String id, String version) {
            this(id, version, null, null);
        }

        public boolean isDeprecated() {
            return deprecatedSince != null;
        }
    }

    public record Requires(String id, String versionRange) {}
}
