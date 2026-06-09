package me.prexorjustin.prexorcloud.controller.state;

import java.time.Instant;
import java.util.List;

/**
 * Latest metrics snapshot reported by a proxy plugin via POST
 * /api/proxy/metrics. Immutable -- replaced on every report.
 */
public record ProxyMetrics(
        String instanceId,

        // JVM
        long proxyMemoryUsedMb,
        long proxyMemoryMaxMb,

        // Network
        long proxyUptimeMs,
        int totalNetworkPlayers,

        // Per-player latency samples with usernames for display.
        List<PlayerPingSample> playerPings,

        Instant collectedAt) {

    public record PlayerPingSample(String uuid, String username, int ping) {}
}
