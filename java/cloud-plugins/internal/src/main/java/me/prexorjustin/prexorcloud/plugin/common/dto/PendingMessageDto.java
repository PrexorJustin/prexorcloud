package me.prexorjustin.prexorcloud.plugin.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PendingMessageDto(
        @JsonProperty("id") String id,
        @JsonProperty("toUuid") String toUuid,
        @JsonProperty("toName") String toName,
        @JsonProperty("fromUuid") String fromUuid,
        @JsonProperty("fromName") String fromName,
        @JsonProperty("content") String content,
        @JsonProperty("replyToId") String replyToId) {}
