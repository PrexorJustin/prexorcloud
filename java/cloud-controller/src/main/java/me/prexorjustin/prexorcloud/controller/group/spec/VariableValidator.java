package me.prexorjustin.prexorcloud.controller.group.spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates and resolves user-supplied variable values against their typed {@link VariableDef}s
 * (Group/Template v2, Phase 2). This is the typed gate today's untyped {@code TemplateVariable}
 * lacks: it applies defaults, enforces {@code required}, and checks the declared type plus any
 * validation rule before a value is allowed to reach a template at apply time.
 *
 * <p>{@code SECRET} values are references resolved from the secret backend at apply time, so they are
 * carried through without inspection here.
 */
public final class VariableValidator {

    /** Resolved values (keyed by variable name) and any validation errors. */
    public record Result(Map<String, String> resolved, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private VariableValidator() {}

    public static Result validate(List<VariableDef> defs, Map<String, String> provided) {
        Map<String, String> resolved = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        for (VariableDef def : defs) {
            String raw = provided.get(def.key());
            String value = (raw == null || raw.isBlank()) ? def.defaultValue() : raw;

            if (value == null || value.isBlank()) {
                if (def.required()) {
                    errors.add("variable '" + def.key() + "' is required");
                }
                continue;
            }

            String error = checkType(def, value);
            if (error != null) {
                errors.add("variable '" + def.key() + "': " + error);
            } else {
                resolved.put(def.key(), value);
            }
        }

        return new Result(Map.copyOf(resolved), List.copyOf(errors));
    }

    private static String checkType(VariableDef def, String value) {
        VariableDef.Validation v = def.validation();
        switch (def.type()) {
            case INT -> {
                long n;
                try {
                    n = Long.parseLong(value.trim());
                } catch (NumberFormatException e) {
                    return "expected an integer, got '" + value + "'";
                }
                if (v != null) {
                    if (v.min() != null && n < v.min()) return "must be >= " + v.min();
                    if (v.max() != null && n > v.max()) return "must be <= " + v.max();
                }
            }
            case BOOL -> {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                    return "expected true or false, got '" + value + "'";
                }
            }
            case ENUM -> {
                List<String> allowed = v == null ? null : v.enumValues();
                if (allowed == null || !allowed.contains(value)) {
                    return "must be one of " + (allowed == null ? "[]" : allowed);
                }
            }
            case STRING -> {
                if (v != null && v.regex() != null && !value.matches(v.regex())) {
                    return "must match " + v.regex();
                }
            }
            case SECRET -> {
                // A reference resolved from the secret backend at apply time; not inspected here.
            }
        }
        return null;
    }
}
