package me.prexorjustin.prexorcloud.plugin.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PlayerDto(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("instanceId") String instanceId,
        @JsonProperty("group") String group) {}
