package me.prexorjustin.prexorcloud.harness;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Simulates a Minecraft game server plugin calling the controller's /api/plugin/* endpoints.
 */
public final class FakeMinecraftServer {

    private static final String SEQUENCE_HEADER = "X-Prexor-Sequence";

    private final RestClient client;
    private final String pluginToken;
    private long nextSequence = 1L;

    public FakeMinecraftServer(String baseUrl, String pluginToken) {
        this.client = new RestClient(baseUrl, null);
        this.pluginToken = pluginToken;
    }

    public RestClient.Response ready() throws Exception {
        return client.postWithToken("/api/plugin/ready", pluginToken, Map.of(), nextHeaders());
    }

    public RestClient.Response playerJoin(UUID uuid, String name) throws Exception {
        return client.postWithToken(
                "/api/plugin/player-join", pluginToken, Map.of("uuid", uuid.toString(), "name", name), nextHeaders());
    }

    public RestClient.Response playerJoin(UUID uuid, String name, String group) throws Exception {
        return client.postWithToken(
                "/api/plugin/player-join",
                pluginToken,
                Map.of(
                        "uuid", uuid.toString(),
                        "name", name,
                        "group", group),
                nextHeaders());
    }

    public RestClient.Response playerLeave(UUID uuid) throws Exception {
        return client.postWithToken(
                "/api/plugin/player-leave", pluginToken, Map.of("uuid", uuid.toString()), nextHeaders());
    }

    public RestClient.Response fireEvent(String type, Map<String, Object> data) throws Exception {
        return client.postWithToken(
                "/api/plugin/events",
                pluginToken,
                Map.of(
                        "type", type,
                        "data", data),
                nextHeaders());
    }

    public RestClient.Response reportMetrics(
            double tps, long heapUsedMb, long heapMaxMb, int playerCount, int maxPlayers) throws Exception {
        var payload = new java.util.HashMap<String, Object>();
        payload.put("tps1m", tps);
        payload.put("tps5m", tps);
        payload.put("tps15m", tps);
        payload.put("msptAvg", 50.0 / tps);
        payload.put("heapUsedMb", heapUsedMb);
        payload.put("heapMaxMb", heapMaxMb);
        payload.put("heapCommittedMb", heapMaxMb);
        payload.put("gcCollections", 100);
        payload.put("gcTimeMs", 500);
        payload.put("threadCount", 50);
        payload.put("daemonThreadCount", 40);
        payload.put("playerCount", playerCount);
        payload.put("maxPlayers", maxPlayers);
        payload.put("worldCount", 3);
        payload.put("totalEntities", 500);
        payload.put("totalChunks", 1000);
        payload.put(
                "worlds",
                List.of(
                        Map.of(
                                "name",
                                "world",
                                "environment",
                                "NORMAL",
                                "entityCount",
                                300,
                                "chunkCount",
                                600,
                                "playerCount",
                                playerCount),
                        Map.of(
                                "name",
                                "world_nether",
                                "environment",
                                "NETHER",
                                "entityCount",
                                100,
                                "chunkCount",
                                200,
                                "playerCount",
                                0),
                        Map.of(
                                "name",
                                "world_the_end",
                                "environment",
                                "THE_END",
                                "entityCount",
                                100,
                                "chunkCount",
                                200,
                                "playerCount",
                                0)));
        payload.put("serverVersion", "1.21.1-Paper");
        payload.put("pluginCount", 5);
        payload.put("uptimeMs", 60000);
        return client.postWithToken("/api/plugin/metrics", pluginToken, payload, nextHeaders());
    }

    private Map<String, String> nextHeaders() {
        return Map.of(SEQUENCE_HEADER, Long.toString(nextSequence++));
    }
}
