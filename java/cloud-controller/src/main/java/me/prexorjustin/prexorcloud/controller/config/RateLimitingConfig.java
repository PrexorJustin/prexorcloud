package me.prexorjustin.prexorcloud.controller.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RateLimitingConfig(
        @JsonProperty("perIpPerMinute") int perIpPerMinute,
        @JsonProperty("perUserPerMinute") int perUserPerMinute,
        @JsonProperty("failOpenOnRedisError") boolean failOpenOnRedisError) {

    public RateLimitingConfig {
        if (perIpPerMinute <= 0) perIpPerMinute = 100;
        if (perUserPerMinute <= 0) perUserPerMinute = 300;
    }

    public RateLimitingConfig(int perIpPerMinute, int perUserPerMinute) {
        this(perIpPerMinute, perUserPerMinute, false);
    }

    public RateLimitingConfig() {
        this(100, 300, false);
    }
}
