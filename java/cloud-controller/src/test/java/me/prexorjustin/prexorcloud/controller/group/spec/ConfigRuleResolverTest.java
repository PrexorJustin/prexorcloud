package me.prexorjustin.prexorcloud.controller.group.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.group.spec.ConfigRule.Format;
import me.prexorjustin.prexorcloud.controller.group.spec.ConfigRule.Op;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ConfigRuleResolver")
class ConfigRuleResolverTest {

    private static ConfigRule set(String file, String path, String value) {
        return new ConfigRule(file, null, path, Op.SET, value);
    }

    @Test
    @DisplayName("a later SET for the same (file, path) supersedes an earlier one, last value + position wins")
    void laterSetWins() {
        var rules = List.of(set("server.properties", "motd", "base"), set("server.properties", "motd", "platform"));

        var resolved = ConfigRuleResolver.resolve(rules, Map.of());

        assertEquals(1, resolved.size());
        assertEquals("platform", resolved.getFirst().value());
    }

    @Test
    @DisplayName("group configPatches override template SET rules (highest-precedence layer)")
    void groupPatchesOverrideTemplateRules() {
        var rules = List.of(set("server.properties", "motd", "from-template"));
        var groupPatches = Map.of("server.properties", Map.of("motd", "from-group"));

        var resolved = ConfigRuleResolver.resolve(rules, groupPatches);

        assertEquals(1, resolved.size());
        assertEquals("from-group", resolved.getFirst().value());
    }

    @Test
    @DisplayName("group configPatches add new file/key targets that no template declared")
    void groupPatchesAddNewTargets() {
        var resolved =
                ConfigRuleResolver.resolve(List.of(), Map.of("spigot.yml", Map.of("settings.bungeecord", "true")));

        assertEquals(1, resolved.size());
        ConfigRule rule = resolved.getFirst();
        assertEquals("spigot.yml", rule.file());
        assertEquals("settings.bungeecord", rule.path());
        assertEquals("true", rule.value());
        assertEquals(Op.SET, rule.op());
    }

    @Test
    @DisplayName("REGEX and REPLACE rules are kept in order and never collapsed")
    void regexAndReplacePreservedInOrder() {
        var rules = List.of(
                new ConfigRule("config.yml", Format.YAML, "online-mode:\\s*true", Op.REGEX, "online-mode: false"),
                new ConfigRule("config.yml", Format.YAML, "online-mode:\\s*true", Op.REGEX, "online-mode: false"),
                new ConfigRule("config.yml", Format.YAML, "ip-forward", Op.REPLACE, "true"));

        var resolved = ConfigRuleResolver.resolve(rules, Map.of());

        assertEquals(3, resolved.size());
        assertEquals(Op.REGEX, resolved.get(0).op());
        assertEquals(Op.REGEX, resolved.get(1).op());
        assertEquals(Op.REPLACE, resolved.get(2).op());
    }

    @Test
    @DisplayName("a null format is inferred from the file extension during resolution")
    void infersFormatFromExtension() {
        var resolved = ConfigRuleResolver.resolve(List.of(set("velocity.toml", "bind", "0.0.0.0:25577")), Map.of());

        assertEquals(Format.TOML, resolved.getFirst().format());
    }
}
