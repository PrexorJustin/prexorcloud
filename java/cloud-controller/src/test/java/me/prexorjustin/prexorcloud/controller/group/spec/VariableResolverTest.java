package me.prexorjustin.prexorcloud.controller.group.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef.Scope;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef.Validation;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef.VarType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("VariableResolver")
class VariableResolverTest {

    private static VariableDef def(String key, VarType type, String def, boolean required, Validation v, Scope scope) {
        return new VariableDef(key, type, def, required, v, scope, VariableDef.Visibility.OPERATOR, key);
    }

    private static final VariableDef BRAND = def("brand", VarType.STRING, "Prexor", false, null, Scope.TEMPLATE);
    private static final VariableDef MOTD = def("motd", VarType.STRING, "Welcome", false, null, Scope.GROUP);
    private static final VariableDef MAXP =
            def("maxp", VarType.INT, "20", false, new Validation(null, 1L, 100L, null), Scope.INSTANCE);
    private static final List<VariableDef> DEFS = List.of(BRAND, MOTD, MAXP);

    @Test
    @DisplayName("applies declared defaults when nothing is overridden")
    void defaultsApplied() {
        var r = VariableResolver.resolve(DEFS, Map.of(), Map.of());
        assertTrue(r.ok(), () -> r.errors().toString());
        assertEquals("Prexor", r.resolved().get("brand"));
        assertEquals("Welcome", r.resolved().get("motd"));
        assertEquals("20", r.resolved().get("maxp"));
    }

    @Test
    @DisplayName("instance override wins over group for an INSTANCE-scoped variable")
    void instanceWinsOverGroup() {
        var r = VariableResolver.resolve(DEFS, Map.of("maxp", "50"), Map.of("maxp", "80"));
        assertTrue(r.ok(), () -> r.errors().toString());
        assertEquals("80", r.resolved().get("maxp"));
    }

    @Test
    @DisplayName("a group value is accepted for an INSTANCE-scoped variable (narrowing allowed)")
    void groupAllowedForInstanceScoped() {
        var r = VariableResolver.resolve(DEFS, Map.of("maxp", "50", "motd", "Hi"), Map.of());
        assertTrue(r.ok(), () -> r.errors().toString());
        assertEquals("50", r.resolved().get("maxp"));
        assertEquals("Hi", r.resolved().get("motd"));
    }

    @Test
    @DisplayName("setting a TEMPLATE-scoped variable at group scope is an error")
    void templateScopedNotOverridable() {
        var r = VariableResolver.resolve(DEFS, Map.of("brand", "Other"), Map.of());
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("brand") && e.contains("GROUP")), r.errors()::toString);
    }

    @Test
    @DisplayName("setting a GROUP-scoped variable per instance is an error")
    void groupScopedNotInstanceOverridable() {
        var r = VariableResolver.resolve(DEFS, Map.of(), Map.of("motd", "Hi"));
        assertFalse(r.ok());
        assertTrue(
                r.errors().stream().anyMatch(e -> e.contains("motd") && e.contains("INSTANCE")), r.errors()::toString);
    }

    @Test
    @DisplayName("an undeclared variable is rejected")
    void unknownVariableRejected() {
        var r = VariableResolver.resolve(DEFS, Map.of("ghost", "1"), Map.of());
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("unknown") && e.contains("ghost")), r.errors()::toString);
    }

    @Test
    @DisplayName("type/range validation still applies to the merged value")
    void typeValidationApplies() {
        var r = VariableResolver.resolve(DEFS, Map.of(), Map.of("maxp", "500"));
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("maxp") && e.contains("<= 100")), r.errors()::toString);
    }

    @Test
    @DisplayName("a required variable with no value or default is reported")
    void requiredMissingReported() {
        var token = def("token", VarType.STRING, null, true, null, Scope.GROUP);
        var r = VariableResolver.resolve(List.of(token), Map.of(), Map.of());
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("token") && e.contains("required")), r.errors()::toString);
    }
}
