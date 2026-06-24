package me.prexorjustin.prexorcloud.controller.group.spec;

import java.util.List;

/**
 * VariableDef v2 -- a typed, validated variable definition (Group/Template v2, Phase 2 foundation;
 * proposal in code, nothing consumes it yet).
 *
 * <p>This replaces today's untyped {@code TemplateVariable{key, value, description}} (a plain stored
 * string) with the Pterodactyl-class surface the assessment found missing: a declared type, a default,
 * a required flag, a validation rule, a scope, and operator-vs-admin visibility. Resolution order at
 * apply time (Phase 2): builtin → template → group → instance override → secret backend.
 *
 * <p>{@code SECRET}-typed variables carry no value here; their value is fetched from the secret backend
 * at apply time and never serialised into a plan, a template snapshot, or an audit record.
 */
public record VariableDef(
        String key,
        VarType type,
        String defaultValue,
        boolean required,
        Validation validation,
        Scope scope,
        Visibility visibility,
        String description) {

    public enum VarType { STRING, INT, BOOL, ENUM, SECRET }

    /** Where a variable may be set/overridden. */
    public enum Scope { TEMPLATE, GROUP, INSTANCE }

    /** Who may see/edit the value in the dashboard/CLI. */
    public enum Visibility { ADMIN, OPERATOR }

    /**
     * Optional validation. {@code regex} for STRING; {@code min}/{@code max} for INT (inclusive);
     * {@code enumValues} for ENUM. Any field may be null when not applicable.
     */
    public record Validation(String regex, Long min, Long max, List<String> enumValues) {}
}
