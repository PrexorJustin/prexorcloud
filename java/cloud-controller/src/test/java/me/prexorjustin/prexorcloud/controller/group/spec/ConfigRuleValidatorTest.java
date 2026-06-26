package me.prexorjustin.prexorcloud.controller.group.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import me.prexorjustin.prexorcloud.controller.group.spec.ConfigRule.Format;
import me.prexorjustin.prexorcloud.controller.group.spec.ConfigRule.Op;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ConfigRuleValidator")
class ConfigRuleValidatorTest {

    @Test
    @DisplayName("well-formed rules pass")
    void wellFormedRulesPass() {
        var rules = List.of(
                new ConfigRule("server.properties", Format.PROPERTIES, "online-mode", Op.SET, "false"),
                new ConfigRule("config.yml", Format.YAML, "online-mode:\\s*true", Op.REGEX, "online-mode: false"));

        assertEquals(List.of(), ConfigRuleValidator.validateRules(rules));
    }

    @Test
    @DisplayName("rejects a blank file and a blank path")
    void rejectsBlankFileAndPath() {
        var rules = List.of(
                new ConfigRule("", Format.YAML, "a.b", Op.SET, "x"),
                new ConfigRule("config.yml", Format.YAML, "  ", Op.SET, "x"));

        var errors = ConfigRuleValidator.validateRules(rules);

        assertEquals(2, errors.size(), errors::toString);
        assertTrue(errors.stream().anyMatch(e -> e.contains("file")), errors::toString);
        assertTrue(errors.stream().anyMatch(e -> e.contains("path")), errors::toString);
    }

    @Test
    @DisplayName("rejects a REGEX rule whose path is not a compilable pattern")
    void rejectsUncompilableRegex() {
        var rules = List.of(new ConfigRule("config.yml", Format.YAML, "online-mode:\\s*[", Op.REGEX, "x"));

        var errors = ConfigRuleValidator.validateRules(rules);

        assertEquals(1, errors.size(), errors::toString);
        assertTrue(errors.getFirst().contains("REGEX"), errors::toString);
    }
}
