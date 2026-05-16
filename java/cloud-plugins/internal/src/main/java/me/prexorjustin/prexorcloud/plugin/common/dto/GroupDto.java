package me.prexorjustin.prexorcloud.plugin.common.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GroupDto(
        @JsonProperty("name") String name,
        @JsonProperty("platform") String platform,
        @JsonProperty("minInstances") int minInstances,
        @JsonProperty("maxInstances") int maxInstances,
        @JsonProperty("maxPlayers") int maxPlayers,
        @JsonProperty("onlineCount") int onlineCount,
        @JsonProperty("isMaintenance") boolean isMaintenance,
        @JsonProperty("maintenanceMessage") String maintenanceMessage,
        @JsonProperty("maintenanceBypass") List<String> maintenanceBypass,
        @JsonProperty("isStatic") boolean isStatic,
        @JsonProperty("defaultGroup") boolean defaultGroup,
        @JsonProperty("memoryMb") int memoryMb,
        @JsonProperty("cpuReservation") double cpuReservation,
        @JsonProperty("diskReservationMb") long diskReservationMb,
        @JsonProperty("jvmArgs") List<String> jvmArgs,
        @JsonProperty("env") Map<String, String> env,
        @JsonProperty("nodeAffinity") List<String> nodeAffinity,
        // Proxy-only MOTD fields
        @JsonProperty("motds") List<String> motds,
        @JsonProperty("motdMode") String motdMode,
        @JsonProperty("motdIntervalSeconds") int motdIntervalSeconds) {}
