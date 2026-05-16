package me.prexorjustin.prexorcloud.modules.webhook.data;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Persisted webhook endpoint config. {@code events} is a list of wire-name
 * strings (e.g. {@code "node_connected"}); empty list means all events.
 */
public record WebhookConfig(
        @JsonProperty("url") String url, @JsonProperty("events") List<String> events) {

    public WebhookConfig {
        if (url == null) url = "";
        if (events == null) events = List.of();
    }
}
