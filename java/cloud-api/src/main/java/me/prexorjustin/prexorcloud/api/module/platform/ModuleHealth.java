package me.prexorjustin.prexorcloud.api.module.platform;

import java.util.Objects;

/**
 * A module's self-reported liveness, returned from {@link PlatformModule#healthCheck()}.
 *
 * <p>Health is <em>advisory</em> and orthogonal to the lifecycle {@code ModuleState}: a module can
 * be {@code ACTIVE} (started without error) yet report {@link Status#UNHEALTHY} because a backing
 * service it depends on is down. The controller polls active modules periodically and surfaces the
 * latest result over REST and as a metric — it does not act on it automatically.
 *
 * <p>The default {@link PlatformModule#healthCheck()} returns {@link #unknown()}, so a module that
 * doesn't opt in simply reports {@link Status#UNKNOWN} — distinguishable from a deliberate health
 * signal. {@code detail} is a short human-readable note ({@code ""} when there's nothing to add).
 */
public record ModuleHealth(Status status, String detail) {

    public enum Status {
        /** Fully operational. */
        HEALTHY,
        /** Operational but impaired (e.g. running on a fallback, elevated error rate). */
        DEGRADED,
        /** Not operational — a dependency is down or the module cannot serve. */
        UNHEALTHY,
        /** No health signal (module didn't override {@link PlatformModule#healthCheck()}). */
        UNKNOWN
    }

    public ModuleHealth {
        Objects.requireNonNull(status, "status");
        if (detail == null) {
            detail = "";
        }
    }

    public static ModuleHealth healthy() {
        return new ModuleHealth(Status.HEALTHY, "");
    }

    public static ModuleHealth healthy(String detail) {
        return new ModuleHealth(Status.HEALTHY, detail);
    }

    public static ModuleHealth degraded(String detail) {
        return new ModuleHealth(Status.DEGRADED, detail);
    }

    public static ModuleHealth unhealthy(String detail) {
        return new ModuleHealth(Status.UNHEALTHY, detail);
    }

    public static ModuleHealth unknown() {
        return new ModuleHealth(Status.UNKNOWN, "");
    }
}
