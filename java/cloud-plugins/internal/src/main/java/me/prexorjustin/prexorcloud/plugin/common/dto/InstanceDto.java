package me.prexorjustin.prexorcloud.plugin.common.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InstanceDto(
        @JsonProperty("instanceId") String instanceId,
        @JsonProperty("group") String group,
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("nodeAddress") String nodeAddress,
        @JsonProperty("state") String state,
        @JsonProperty("port") int port,
        @JsonProperty("playerCount") int playerCount,
        @JsonProperty("uptimeMs") long uptimeMs,
        @JsonProperty("startedAt") Instant startedAt,
        @JsonProperty("warm") boolean warm) {}
