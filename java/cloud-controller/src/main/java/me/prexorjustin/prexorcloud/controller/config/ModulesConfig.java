package me.prexorjustin.prexorcloud.controller.config;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ModulesConfig(
        @JsonProperty("directory") String directory,
        @JsonProperty("dataDirectory") String dataDirectory,
        @JsonProperty("signing") ModuleSigningConfig signing,
        @JsonProperty("registries") List<String> registries,
        @JsonProperty("quotas") Map<String, ModuleQuota> quotas) {

    public ModulesConfig {
        if (directory == null) directory = "modules";
        if (dataDirectory == null) dataDirectory = "modules/data";
        if (signing == null) signing = new ModuleSigningConfig();
        // The registry index URLs an operator trusts for `install-from-registry`.
        // Empty by default — registry install is opt-in and an unconfigured controller
        // simply has nothing to browse or pull from.
        registries = registries == null ? List.of() : List.copyOf(registries);
        // Per-module soft quotas keyed by module id (Track C.2 stage 2). Empty by default —
        // an unconfigured module has no limits and the enforcer simply skips it.
        quotas = quotas == null ? Map.of() : Map.copyOf(quotas);
    }

    public ModulesConfig() {
        this("modules", "modules/data", new ModuleSigningConfig(), List.of(), Map.of());
    }

    /** Backwards-compatible 3-arg form for call sites that predate registry support. */
    public ModulesConfig(String directory, String dataDirectory, ModuleSigningConfig signing) {
        this(directory, dataDirectory, signing, List.of(), Map.of());
    }

    /** Backwards-compatible 4-arg form for call sites that predate per-module quotas. */
    public ModulesConfig(String directory, String dataDirectory, ModuleSigningConfig signing, List<String> registries) {
        this(directory, dataDirectory, signing, registries, Map.of());
    }
}
