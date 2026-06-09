package me.prexorjustin.prexorcloud.controller.config;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NetworkConfig(@JsonProperty("allowedSubnets") List<String> allowedSubnets) {

    public NetworkConfig {
        if (allowedSubnets == null) allowedSubnets = List.of("0.0.0.0/0", "::/0");
    }

    public NetworkConfig() {
        this(List.of("0.0.0.0/0", "::/0"));
    }
}
