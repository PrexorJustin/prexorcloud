package me.prexorjustin.prexorcloud.controller.template;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Template metadata. Templates are pure file packages -- no runtime info.
 * Content is hashed (SHA-256) and versioned.
 */
public record TemplateConfig(
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("platform") String platform,
        @JsonProperty("hash") String hash,
        @JsonProperty("sizeBytes") long sizeBytes) {

    public TemplateConfig {
        if (name == null) name = "";
        if (description == null) description = "";
        if (platform == null) platform = "";
        if (hash == null) hash = "";
    }
}
