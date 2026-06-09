package me.prexorjustin.prexorcloud.controller.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DatabaseConfig(
        @JsonProperty("uri") String uri,
        @JsonProperty("database") String database) {

    public DatabaseConfig {
        if (uri == null) uri = "mongodb://localhost:27017";
        if (database == null) database = "prexorcloud";
    }

    public DatabaseConfig() {
        this("mongodb://localhost:27017", "prexorcloud");
    }
}
