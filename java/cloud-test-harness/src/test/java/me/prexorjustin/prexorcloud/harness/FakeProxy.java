package me.prexorjustin.prexorcloud.harness;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Simulates a Velocity/BungeeCord proxy plugin calling the controller's /api/proxy/* endpoints.
 */
public final class FakeProxy {

    private static final String SEQUENCE_HEADER = "X-Prexor-Sequence";

    private final RestClient client;
    private final String pluginToken;
    private long nextSequence = 1L;

    public FakeProxy(String baseUrl, String pluginToken) {
        this.client = new RestClient(baseUrl, null);
        this.pluginToken = pluginToken;
    }

    public RestClient.Response playerJoin(UUID uuid, String name, String instanceId, String group) throws Exception {
        return client.postWithToken(
                "/api/proxy/player-join",
                pluginToken,
                Map.of(
                        "uuid", uuid.toString(),
                        "name", name,
                        "instanceId", instanceId,
                        "group", group),
                nextHeaders());
    }

    public RestClient.Response playerLeave(UUID uuid) throws Exception {
        return client.postWithToken(
                "/api/proxy/player-leave", pluginToken, Map.of("uuid", uuid.toString()), nextHeaders());
    }

    public RestClient.Response getInstances() throws Exception {
        return client.getWithToken("/api/proxy/instances", pluginToken);
    }

    public RestClient.Response getGroups() throws Exception {
        return client.getWithToken("/api/proxy/groups", pluginToken);
    }

    public RestClient.Response getPlayers() throws Exception {
        return client.getWithToken("/api/proxy/players", pluginToken);
    }

    public RestClient.Response getPendingTransfers() throws Exception {
        return client.getWithToken("/api/proxy/pending-transfers", pluginToken);
    }

    public RestClient.Response getPendingTransfers(String proxyId) throws Exception {
        return client.getWithToken("/api/proxy/pending-transfers?proxyId=" + proxyId, pluginToken);
    }

    public RestClient.Response ackTransfer(UUID playerUuid) throws Exception {
        return client.postWithToken("/api/proxy/transfer-ack/" + playerUuid, pluginToken, Map.of(), nextHeaders());
    }

    public RestClient.Response fireEvent(String type, Map<String, Object> data) throws Exception {
        return client.postWithToken(
                "/api/proxy/events",
                pluginToken,
                Map.of(
                        "type", type,
                        "data", data),
                nextHeaders());
    }

    public RestClient.Response reportMetrics(
            long memUsedMb, long memMaxMb, long uptimeMs, int totalPlayers, List<Map<String, Object>> pings)
            throws Exception {
        return client.postWithToken(
                "/api/proxy/metrics",
                pluginToken,
                Map.of(
                        "proxyMemoryUsedMb", memUsedMb,
                        "proxyMemoryMaxMb", memMaxMb,
                        "proxyUptimeMs", uptimeMs,
                        "totalNetworkPlayers", totalPlayers,
                        "playerPings", pings),
                nextHeaders());
    }

    private Map<String, String> nextHeaders() {
        return Map.of(SEQUENCE_HEADER, Long.toString(nextSequence++));
    }
}
