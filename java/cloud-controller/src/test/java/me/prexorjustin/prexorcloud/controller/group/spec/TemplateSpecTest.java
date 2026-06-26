package me.prexorjustin.prexorcloud.controller.group.spec;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import me.prexorjustin.prexorcloud.controller.template.TemplateConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TemplateSpec")
class TemplateSpecTest {

    @Test
    @DisplayName("resolves down to the five-field TemplateConfig the running system serves")
    void resolvesToLegacyTemplateConfig() {
        TemplateSpec spec = new TemplateSpec(
                "lobby",
                "the lobby layer",
                "PAPER",
                "deadbeef",
                4096L,
                List.of(),
                List.of(new TemplateSpec.Include("https://cdn/p.jar", "plugins/p.jar", "abc")),
                new TemplateSpec.InstallHook("bash", List.of("echo hi"), 60),
                List.of(new ConfigRule(
                        "config.yml", ConfigRule.Format.YAML, "servers.*.address", ConfigRule.Op.SET, "0.0.0.0")),
                "sig",
                "prov",
                new TemplateSpec.StorageRef(TemplateSpec.StorageRef.Backend.S3, "manifest-1"));

        TemplateConfig c = spec.toTemplateConfig();

        assertEquals("lobby", c.name());
        assertEquals("the lobby layer", c.description());
        assertEquals("PAPER", c.platform());
        assertEquals("deadbeef", c.hash());
        assertEquals(4096L, c.sizeBytes());

        // v2-only depth is carried on the spec but intentionally absent from the legacy config.
        assertEquals(1, spec.includes().size());
        assertEquals(TemplateSpec.StorageRef.Backend.S3, spec.storage().backend());
    }
}
