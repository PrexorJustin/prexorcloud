package me.prexorjustin.prexorcloud.controller.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ModulesConfig(
        @JsonProperty("directory") String directory,
        @JsonProperty("dataDirectory") String dataDirectory,
        @JsonProperty("signing") ModuleSigningConfig signing) {

    public ModulesConfig {
        if (directory == null) directory = "modules";
        if (dataDirectory == null) dataDirectory = "modules/data";
        if (signing == null) signing = new ModuleSigningConfig();
    }

    public ModulesConfig() {
        this("modules", "modules/data", new ModuleSigningConfig());
    }
}
