package me.prexorjustin.prexorcloud.daemon.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ControllerConnectionConfig(
        @JsonProperty("host") String host,
        @JsonProperty("grpcPort") int grpcPort) {

    public ControllerConnectionConfig {
        if (host == null) host = "127.0.0.1";
        if (grpcPort <= 0) grpcPort = 9090;
    }

    public ControllerConnectionConfig() {
        this("127.0.0.1", 9090);
    }
}
