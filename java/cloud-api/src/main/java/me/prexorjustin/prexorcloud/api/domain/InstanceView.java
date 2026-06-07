package me.prexorjustin.prexorcloud.api.domain;

import java.time.Instant;

/**
 * Read-only snapshot of a running server instance. Unified type used by both
 * plugin developers (via {@link me.prexorjustin.prexorcloud.api.CloudApi}) and
 * module developers (via
 * {@link me.prexorjustin.prexorcloud.api.module.cluster.ClusterView}).
 *
 * @param instanceId
 *            unique instance identifier (e.g. {@code "lobby-1"})
 * @param group
 *            the group this instance belongs to
 * @param nodeId
 *            the daemon node hosting this instance
 * @param nodeAddress
 *            routable IP or hostname of the hosting node
 * @param state
 *            current lifecycle state
 * @param port
 *            TCP port the instance listens on
 * @param playerCount
 *            number of players currently connected
 * @param uptimeMs
 *            milliseconds since the instance reached RUNNING state
 * @param startedAt
 *            timestamp when the instance reached RUNNING state
 */
public record InstanceView(
        String instanceId,
        String group,
        String nodeId,
        String nodeAddress,
        InstanceState state,
        int port,
        int playerCount,
        long uptimeMs,
        Instant startedAt) {}
