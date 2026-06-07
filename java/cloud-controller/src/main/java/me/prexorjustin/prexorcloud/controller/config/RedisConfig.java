package me.prexorjustin.prexorcloud.controller.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RedisConfig(@JsonProperty("uri") String uri) {

    public RedisConfig {
        if (uri == null) uri = "redis://localhost:6379";
    }

    public RedisConfig() {
        this("redis://localhost:6379");
    }
}
