package me.prexorjustin.prexorcloud.controller.cluster.reload;

import java.util.Map;

/**
 * An in-process subsystem that re-reads its slice of the cluster-shared config
 * when a new {@code cluster_config} version is committed through Raft.
 *
 * <p>Implementations receive the fully folded effective config (see
 * {@link ClusterConfigProjection}) and pull only the keys they own. They must be
 * idempotent: {@link ClusterConfigReloadCoordinator} also invokes them once at
 * startup to adopt the cluster-authoritative values, and may re-invoke them for
 * any commit even when the subscriber's own slice is unchanged.
 */
@FunctionalInterface
public interface ClusterConfigSubscriber {

    /** Short identifier used in reload log lines. */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * Apply the effective cluster config. Called off the Raft apply thread (on a
     * virtual thread via the EventBus), so implementations must be thread-safe
     * against concurrent request traffic.
     *
     * @param effectiveConfig folded cluster config; may be empty if no version is active
     */
    void onClusterConfig(Map<String, Object> effectiveConfig);
}
