package me.prexorjustin.prexorcloud.controller.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GrpcConfig(
        @JsonProperty("host") String host,
        @JsonProperty("port") int port) {

    public GrpcConfig {
        if (host == null) host = "0.0.0.0";
        if (port <= 0) port = 9090;
    }

    public GrpcConfig() {
        this("0.0.0.0", 9090);
    }
}
