package me.prexorjustin.prexorcloud.controller.module.registry;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The on-the-wire shape of a PrexorCloud module registry: a static JSON index
 * (hosted on GitHub Pages, S3, or any HTTP server) listing the modules a
 * registry offers. Deliberately a thin index — not an npm-style backend — so a
 * registry is just a file an author publishes alongside their signed JARs.
 *
 * <p>Unknown fields are ignored so the schema can grow (e.g. download counts,
 * deprecation flags) without breaking older controllers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistryIndex(
        @JsonProperty("name") String name,
        @JsonProperty("schemaVersion") Integer schemaVersion,
        @JsonProperty("modules") List<RegistryModuleEntry> modules) {

    public RegistryIndex {
        modules = modules == null ? List.of() : List.copyOf(modules);
    }
}
