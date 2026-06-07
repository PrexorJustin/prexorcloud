package me.prexorjustin.prexorcloud.controller.config;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CorsConfig(@JsonProperty("allowedOrigins") List<String> allowedOrigins) {

    private static final List<String> DEFAULT_ORIGINS =
            List.of("http://localhost:3000", "http://localhost:3001", "http://localhost:3002", "http://localhost:3003");

    public CorsConfig {
        if (allowedOrigins == null) allowedOrigins = DEFAULT_ORIGINS;
    }

    public CorsConfig() {
        this(DEFAULT_ORIGINS);
    }
}
