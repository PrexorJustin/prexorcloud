package me.prexorjustin.prexorcloud.plugin.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PendingTransferDto(
        @JsonProperty("playerUuid") String playerUuid,
        @JsonProperty("targetInstanceId") String targetInstanceId,
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("nodeAddress") String nodeAddress,
        @JsonProperty("port") int port) {

    /**
     * Returns the routable address, falling back to nodeId if nodeAddress is
     * absent.
     */
    public String routableAddress() {
        return nodeAddress != null && !nodeAddress.isBlank() ? nodeAddress : nodeId;
    }
}
