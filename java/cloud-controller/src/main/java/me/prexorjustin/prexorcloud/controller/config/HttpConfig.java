package me.prexorjustin.prexorcloud.controller.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record HttpConfig(
        @JsonProperty("host") String host,
        @JsonProperty("port") int port,
        @JsonProperty("cors") CorsConfig cors) {

    public HttpConfig {
        if (host == null) host = "0.0.0.0";
        if (port <= 0) port = 8080;
        if (cors == null) cors = new CorsConfig();
    }

    public HttpConfig() {
        this("0.0.0.0", 8080, new CorsConfig());
    }
}
