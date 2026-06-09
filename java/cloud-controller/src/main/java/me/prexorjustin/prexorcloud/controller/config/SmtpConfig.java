package me.prexorjustin.prexorcloud.controller.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * SMTP transport for the password-reset {@code Mailer}. {@code host} blank
 * means SMTP is disabled and the controller falls back to {@code LogMailer}.
 */
public record SmtpConfig(
        @JsonProperty("host") String host,
        @JsonProperty("port") int port,
        @JsonProperty("startTls") boolean startTls,
        @JsonProperty("implicitTls") boolean implicitTls,
        @JsonProperty("username") String username,
        @JsonProperty("password") String password,
        @JsonProperty("from") String from,
        @JsonProperty("connectTimeoutMs") int connectTimeoutMs,
        @JsonProperty("readTimeoutMs") int readTimeoutMs) {

    public SmtpConfig {
        if (host == null) host = "";
        if (port <= 0) port = 587;
        if (username == null) username = "";
        if (password == null) password = "";
        if (from == null) from = "";
        if (connectTimeoutMs <= 0) connectTimeoutMs = 10_000;
        if (readTimeoutMs <= 0) readTimeoutMs = 10_000;
    }

    public SmtpConfig() {
        this("", 587, true, false, "", "", "", 10_000, 10_000);
    }

    public boolean enabled() {
        return host != null && !host.isBlank();
    }
}
