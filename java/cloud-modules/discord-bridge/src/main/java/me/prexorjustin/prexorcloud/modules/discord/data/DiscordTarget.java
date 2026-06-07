package me.prexorjustin.prexorcloud.modules.discord.data;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A persisted Discord incoming-webhook target. {@code url} is a Discord webhook URL
 * (https://discord.com/api/webhooks/...); {@code username} optionally overrides the
 * displayed sender name; {@code events} is a list of wire-name strings (e.g.
 * {@code "instance_crashed"}) — an empty list means "all events".
 */
public record DiscordTarget(
        @JsonProperty("url") String url,
        @JsonProperty("username") String username,
        @JsonProperty("events") List<String> events) {

    public DiscordTarget {
        if (url == null) url = "";
        if (username == null) username = "";
        if (events == null) events = List.of();
    }
}
