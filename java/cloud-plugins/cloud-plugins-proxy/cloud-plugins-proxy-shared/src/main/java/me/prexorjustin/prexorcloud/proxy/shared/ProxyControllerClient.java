package me.prexorjustin.prexorcloud.proxy.shared;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.plugin.common.BaseControllerClient;
import me.prexorjustin.prexorcloud.plugin.common.dto.PendingMessageDto;
import me.prexorjustin.prexorcloud.plugin.common.dto.PendingTransferDto;

import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Proxy-specific controller client. Extends the shared base with endpoints for
 * player join/leave reporting, pending transfer polling, and proxy metrics.
 */
public final class ProxyControllerClient extends BaseControllerClient {

    private static final Logger logger = LoggerFactory.getLogger(ProxyControllerClient.class);

    private final String instanceId;

    public ProxyControllerClient(String controllerUrl, String pluginToken) {
        this(controllerUrl, pluginToken, "");
    }

    public ProxyControllerClient(String controllerUrl, String pluginToken, String instanceId) {
        super(controllerUrl, pluginToken);
        this.instanceId = instanceId;
    }

    @Override
    protected String apiPrefix() {
        return "/api/proxy";
    }

    public void reportReady() {
        postAsync(apiPrefix() + "/ready", "{}");
    }

    public void reportPlayerJoin(UUID playerUuid, String playerName, String instanceId, String group) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "uuid", playerUuid.toString(), "name", playerName, "instanceId", instanceId, "group", group));
            postAsync(apiPrefix() + "/player-join", body);
        } catch (Exception e) {
            logger.warn("Failed to serialize player join for {}: {}", playerUuid, e.getMessage());
        }
    }

    public void reportPlayerLeave(UUID playerUuid) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("uuid", playerUuid.toString()));
            postAsync(apiPrefix() + "/player-leave", body);
        } catch (Exception e) {
            logger.warn("Failed to serialize player leave for {}: {}", playerUuid, e.getMessage());
        }
    }

    public List<PendingTransferDto> fetchPendingTransfers() {
        String path = apiPrefix() + "/pending-transfers";
        if (!instanceId.isEmpty()) {
            path += "?proxyId=" + instanceId;
        }
        return get(path, new TypeReference<>() {});
    }

    /**
     * Report proxy-level health metrics and per-player pings to the controller.
     */
    public void reportProxyMetrics(int totalNetworkPlayers, List<PlayerPingSample> playerPings) {
        try {
            Runtime rt = Runtime.getRuntime();
            long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long maxMb = rt.maxMemory() / (1024 * 1024);
            long uptimeMs =
                    java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();

            List<Map<String, Object>> pings = playerPings.stream()
                    .<Map<String, Object>>map(s -> Map.of(
                            "uuid", s.uuid().toString(),
                            "username", s.username(),
                            "ping", s.pingMs()))
                    .toList();

            String body = objectMapper.writeValueAsString(Map.of(
                    "proxyMemoryUsedMb",
                    usedMb,
                    "proxyMemoryMaxMb",
                    maxMb,
                    "proxyUptimeMs",
                    uptimeMs,
                    "totalNetworkPlayers",
                    totalNetworkPlayers,
                    "playerPings",
                    pings));
            postAsync(apiPrefix() + "/metrics", body);
        } catch (Exception e) {
            logger.warn("Failed to serialize proxy metrics: {}", e.getMessage());
        }
    }

    public void ackTransfer(UUID playerUuid) {
        postAsync(apiPrefix() + "/transfer-ack/" + playerUuid, "");
    }

    /** Returns messages queued for players currently on this proxy. */
    public List<PendingMessageDto> fetchPendingMessages() {
        String path = apiPrefix() + "/messages/pending";
        if (!instanceId.isEmpty()) {
            path += "?proxyId=" + instanceId;
        }
        return get(path, new TypeReference<>() {});
    }

    /** Acknowledge that a message has been delivered in-game. */
    public void ackMessage(String messageId) {
        postAsync(apiPrefix() + "/messages/" + messageId + "/ack", "");
    }
}
