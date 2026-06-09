package me.prexorjustin.prexorcloud.api.event.events;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;

/**
 * Fired when the Raft cluster_config state machine commits a new active version
 * — either a forward patch ({@code action="PATCH"}) or a rollback to an earlier
 * version ({@code action="ROLLBACK"}). The event lands on every controller's
 * local EventBus as soon as Ratis delivers the entry to the state machine, so
 * subscribers (CorsAllowList, JwtManager, RateLimiter, dashboard SSE, …) can
 * pick up cluster-shared config changes without a Redis round-trip.
 *
 * <p>For patches, {@code parentVersion} is the version this patch was built
 * against; for rollbacks {@code parentVersion} is {@code -1} (a rollback doesn't
 * have a single parent — it sets the active pointer to an earlier version).
 *
 * <p>The event carries only metadata. Subscribers that need the actual config
 * values read them from the {@code ClusterControlPlane} snapshot — keeps the
 * event payload small and avoids re-broadcasting masked / sensitive fields.
 */
public record ClusterConfigChangedEvent(int version, int parentVersion, String mutator, String action)
        implements CloudEvent {

    public static final String ACTION_PATCH = "PATCH";
    public static final String ACTION_ROLLBACK = "ROLLBACK";

    @Override
    public String type() {
        return "CLUSTER_CONFIG_CHANGED";
    }
}
