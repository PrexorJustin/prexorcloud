package me.prexorjustin.prexorcloud.server.shared;

import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.plugin.common.BaseControllerClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-specific controller client. Extends the shared base with endpoints for
 * ready signaling, player events, metrics, and transfers.
 */
public final class ServerControllerClient extends BaseControllerClient {

    private static final Logger logger = LoggerFactory.getLogger(ServerControllerClient.class);

    public ServerControllerClient(String controllerUrl, String pluginToken) {
        super(controllerUrl, pluginToken);
    }

    @Override
    protected String apiPrefix() {
        return "/api/plugin";
    }

    /**
     * Signal that this server has finished startup and is ready to accept players.
     */
    public void reportReady() {
        postAsync(apiPrefix() + "/ready", "{}");
    }

    /** Report a player joining this specific server instance. */
    public void reportPlayerJoin(UUID uuid, String name, String group) {
        try {
            String body =
                    objectMapper.writeValueAsString(Map.of("uuid", uuid.toString(), "name", name, "group", group));
            postAsync(apiPrefix() + "/player-join", body);
        } catch (Exception e) {
            logger.warn("Failed to serialize player join: {}", e.getMessage());
        }
    }

    /** Report a player leaving this specific server instance. */
    public void reportPlayerLeave(UUID uuid) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("uuid", uuid.toString()));
            postAsync(apiPrefix() + "/player-leave", body);
        } catch (Exception e) {
            logger.warn("Failed to serialize player leave: {}", e.getMessage());
        }
    }

    /** Send a full metrics snapshot to the controller. */
    public void reportMetrics(InstanceMetricsPayload payload) {
        try {
            String body = objectMapper.writeValueAsString(payload);
            postAsync(apiPrefix() + "/metrics", body);
        } catch (Exception e) {
            logger.warn("Failed to serialize metrics: {}", e.getMessage());
        }
    }

    /** Queue a transfer request. The proxy picks it up and moves the player. */
    public void requestTransfer(UUID playerUuid, String targetInstanceId) {
        try {
            String body = objectMapper.writeValueAsString(
                    Map.of("playerUuid", playerUuid.toString(), "targetInstanceId", targetInstanceId));
            postAsync(apiPrefix() + "/transfer", body);
        } catch (Exception e) {
            logger.warn("Failed to request transfer: {}", e.getMessage());
        }
    }

    /**
     * Queue a group-based transfer. The controller selects the best instance in the
     * group and queues the transfer for the proxy.
     */
    public void requestTransferToGroup(UUID playerUuid, String group) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("playerUuid", playerUuid.toString(), "group", group));
            postAsync(apiPrefix() + "/transfer-to-group", body);
        } catch (Exception e) {
            logger.warn("Failed to request group transfer: {}", e.getMessage());
        }
    }

    /**
     * Send a cross-network message via the controller's message module. The message
     * is persisted and delivered to the recipient's proxy within 1s.
     *
     * @param replyToId
     *            optional ID of the message being replied to, or {@code null}
     */
    public void sendNetworkMessage(
            UUID fromUuid, String fromName, UUID toUuid, String toName, String content, Long replyToId) {
        try {
            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("fromUuid", fromUuid.toString());
            payload.put("fromName", fromName);
            payload.put("toUuid", toUuid.toString());
            payload.put("toName", toName);
            payload.put("content", content);
            if (replyToId != null) {
                payload.put("replyToId", replyToId);
            }
            postAsync(apiPrefix() + "/message/send", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            logger.warn("Failed to send network message from {}: {}", fromUuid, e.getMessage());
        }
    }
}
