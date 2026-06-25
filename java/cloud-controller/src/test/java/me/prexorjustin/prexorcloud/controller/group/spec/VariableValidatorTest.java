package me.prexorjustin.prexorcloud.controller.group.spec;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef.Scope;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef.Validation;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef.VarType;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef.Visibility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("VariableValidator")
class VariableValidatorTest {

    private static VariableDef def(String key, VarType type, String def, boolean required, Validation v) {
        return new VariableDef(key, type, def, required, v, Scope.GROUP, Visibility.OPERATOR, "");
    }

    @Test
    @DisplayName("applies the default when no value is provided")
    void appliesDefault() {
        var defs = List.of(def("MOTD", VarType.STRING, "Welcome", false, null));
        var result = VariableValidator.validate(defs, Map.of());

        assertTrue(result.ok());
        assertEquals("Welcome", result.resolved().get("MOTD"));
    }

    @Test
    @DisplayName("a provided value overrides the default")
    void providedOverridesDefault() {
        var defs = List.of(def("MOTD", VarType.STRING, "Welcome", false, null));
        var result = VariableValidator.validate(defs, Map.of("MOTD", "Hi"));

        assertEquals("Hi", result.resolved().get("MOTD"));
    }

    @Test
    @DisplayName("a required variable with no value and no default is an error")
    void requiredMissing() {
        var defs = List.of(def("WORLD", VarType.STRING, null, true, null));
        var result = VariableValidator.validate(defs, Map.of());

        assertFalse(result.ok());
        assertEquals(1, result.errors().size());
    }

    @Test
    @DisplayName("INT respects min/max bounds")
    void intRange() {
        var defs = List.of(def("SLOTS", VarType.INT, null, true, new Validation(null, 1L, 100L, null)));

        assertTrue(VariableValidator.validate(defs, Map.of("SLOTS", "50")).ok());
        assertFalse(VariableValidator.validate(defs, Map.of("SLOTS", "0")).ok());
        assertFalse(VariableValidator.validate(defs, Map.of("SLOTS", "101")).ok());
        assertFalse(VariableValidator.validate(defs, Map.of("SLOTS", "abc")).ok());
    }

    @Test
    @DisplayName("BOOL accepts only true/false (case-insensitive)")
    void boolType() {
        var defs = List.of(def("PVP", VarType.BOOL, null, true, null));

        assertTrue(VariableValidator.validate(defs, Map.of("PVP", "TRUE")).ok());
        assertFalse(VariableValidator.validate(defs, Map.of("PVP", "yes")).ok());
    }

    @Test
    @DisplayName("ENUM accepts only listed values")
    void enumType() {
        var defs = List.of(def(
                "MODE", VarType.ENUM, null, true, new Validation(null, null, null, List.of("survival", "creative"))));

        assertTrue(VariableValidator.validate(defs, Map.of("MODE", "creative")).ok());
        assertFalse(
                VariableValidator.validate(defs, Map.of("MODE", "spectator")).ok());
    }

    @Test
    @DisplayName("STRING enforces a regex when set")
    void stringRegex() {
        var defs = List.of(def("ID", VarType.STRING, null, true, new Validation("[a-z]+", null, null, null)));

        assertTrue(VariableValidator.validate(defs, Map.of("ID", "lobby")).ok());
        assertFalse(VariableValidator.validate(defs, Map.of("ID", "Lobby1")).ok());
    }

    @Test
    @DisplayName("SECRET values pass through unchecked (resolved from the backend later)")
    void secretPassthrough() {
        var defs = List.of(def("TOKEN", VarType.SECRET, null, true, null));
        var result = VariableValidator.validate(defs, Map.of("TOKEN", "vault://x"));

        assertTrue(result.ok());
        assertEquals("vault://x", result.resolved().get("TOKEN"));
    }

    @Test
    @DisplayName("validateDefinitions: well-formed definitions pass; a required var may omit its default")
    void definitionsValid() {
        var defs = List.of(
                def("MOTD", VarType.STRING, "Welcome", false, null),
                def("WORLD", VarType.STRING, null, true, null), // required, no default — value supplied later
                def(
                        "MODE",
                        VarType.ENUM,
                        "survival",
                        false,
                        new Validation(null, null, null, List.of("survival", "creative"))));

        assertEquals(List.of(), VariableValidator.validateDefinitions(defs));
    }

    @Test
    @DisplayName("validateDefinitions: rejects duplicate keys, ENUM without values, and bad defaults")
    void definitionsRejected() {
        var defs = List.of(
                def("SLOTS", VarType.INT, "abc", false, null), // default is not an integer
                def("MODE", VarType.ENUM, null, false, null), // ENUM declares no enumValues
                def("MOTD", VarType.STRING, "a", false, null),
                def("MOTD", VarType.STRING, "b", false, null)); // duplicate key

        var errors = VariableValidator.validateDefinitions(defs);

        assertEquals(3, errors.size(), errors::toString);
        assertTrue(errors.stream().anyMatch(e -> e.contains("SLOTS")), errors::toString);
        assertTrue(errors.stream().anyMatch(e -> e.contains("MODE") && e.contains("enumValues")), errors::toString);
        assertTrue(errors.stream().anyMatch(e -> e.contains("duplicate") && e.contains("MOTD")), errors::toString);
    }
}
