package me.prexorjustin.prexorcloud.controller.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import me.prexorjustin.prexorcloud.common.config.YamlConfigLoader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ModulesConfig registries")
class ModulesConfigRegistriesTest {

    @Test
    @DisplayName("defaults registries to empty and stays backward-compatible with the 3-arg constructor")
    void defaultsEmpty() {
        assertTrue(new ModulesConfig().registries().isEmpty());
        // 3-arg form used by pre-registry call sites still compiles and defaults registries.
        var legacy = new ModulesConfig("modules", "modules/data", new ModuleSigningConfig());
        assertTrue(legacy.registries().isEmpty());
    }

    @Test
    @DisplayName("deserialises registries from YAML")
    void parsesFromYaml() throws Exception {
        var mapper = YamlConfigLoader.mapper();
        String yaml = """
                directory: modules
                registries:
                  - https://registry.prexorcloud.dev/index.json
                  - https://team.example/modules/index.json
                """;
        ModulesConfig cfg = mapper.readValue(yaml, ModulesConfig.class);
        assertEquals(
                List.of("https://registry.prexorcloud.dev/index.json", "https://team.example/modules/index.json"),
                cfg.registries());
    }
}
