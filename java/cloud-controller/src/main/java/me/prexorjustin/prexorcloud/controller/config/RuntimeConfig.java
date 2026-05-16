package me.prexorjustin.prexorcloud.controller.config;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RuntimeConfig(@JsonProperty("profile") String profile) {

    public static final String DEVELOPMENT = "development";
    public static final String PRODUCTION = "production";

    public RuntimeConfig {
        if (profile == null || profile.isBlank()) {
            profile = DEVELOPMENT;
        } else {
            profile = profile.trim().toLowerCase(Locale.ROOT);
        }
    }

    public RuntimeConfig() {
        this(DEVELOPMENT);
    }

    public boolean development() {
        return DEVELOPMENT.equals(profile);
    }

    public boolean production() {
        return PRODUCTION.equals(profile);
    }

    public boolean supported() {
        return development() || production();
    }
}
