package me.prexorjustin.prexorcloud.controller.group.spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef.Scope;

/**
 * Unified, scope-aware variable resolution for Group/Template v2 (Phase 2).
 *
 * <p>Layers value sources in precedence order — template default (lowest) → group → instance
 * override (highest) — but applies a layer only where each variable's declared {@link Scope} permits
 * an override there, then validates the merged values against their typed {@link VariableDef}s via
 * {@link VariableValidator}. A value supplied at a scope the variable does not allow, or for a key no
 * template declares, is a hard error rather than a silent drop, so a typo or a forbidden override
 * surfaces at set/apply time instead of producing a server that quietly ignores an operator's setting.
 *
 * <p>{@code SECRET}-typed variables are resolved from the secret backend at apply time (a later
 * increment) and are intentionally not fetched here — their declared default/reference passes through
 * validation untouched so the real value never lands in a plan, snapshot, or audit record.
 */
public final class VariableResolver {

    private VariableResolver() {}

    /**
     * Collapse variable definitions gathered across a template inheritance chain (lowest-precedence
     * template first, e.g. {@code base → base-platform → group → user}) into one definition per key,
     * the most-specific (last) declaration winning. A later template may thus re-type or re-default a
     * key an ancestor template already declared. Insertion order of first appearance is preserved.
     */
    public static List<VariableDef> mergeChain(List<VariableDef> defsInChainOrder) {
        Map<String, VariableDef> byKey = new LinkedHashMap<>();
        for (VariableDef def : defsInChainOrder) {
            byKey.put(def.key(), def);
        }
        return List.copyOf(byKey.values());
    }

    /**
     * Resolve the effective value map for an instance from its template's declared variable
     * definitions and the group/instance value layers.
     *
     * @param defs
     *            the typed variable definitions declared by the template (authoritative set of keys)
     * @param groupValues
     *            values set at group scope (may be null/empty)
     * @param instanceValues
     *            per-instance overrides (may be null/empty)
     * @return validated resolved values, or the collected scope + type errors
     */
    public static VariableValidator.Result resolve(
            List<VariableDef> defs, Map<String, String> groupValues, Map<String, String> instanceValues) {

        Map<String, String> group = groupValues == null ? Map.of() : groupValues;
        Map<String, String> instance = instanceValues == null ? Map.of() : instanceValues;

        Set<String> declared = new LinkedHashSet<>();
        for (VariableDef def : defs) {
            declared.add(def.key());
        }

        List<String> errors = new ArrayList<>();
        Map<String, String> provided = new LinkedHashMap<>();

        for (VariableDef def : defs) {
            String key = def.key();
            if (group.containsKey(key)) {
                if (allowsOverrideAt(def, Scope.GROUP)) {
                    provided.put(key, group.get(key));
                } else {
                    errors.add(
                            "variable '" + key + "' cannot be set at GROUP scope (declared scope " + def.scope() + ")");
                }
            }
            if (instance.containsKey(key)) {
                if (allowsOverrideAt(def, Scope.INSTANCE)) {
                    provided.put(key, instance.get(key));
                } else {
                    errors.add("variable '" + key + "' cannot be set at INSTANCE scope (declared scope " + def.scope()
                            + ")");
                }
            }
        }

        for (String key : group.keySet()) {
            if (!declared.contains(key)) errors.add("unknown variable '" + key + "' set at GROUP scope");
        }
        for (String key : instance.keySet()) {
            if (!declared.contains(key)) errors.add("unknown variable '" + key + "' set at INSTANCE scope");
        }

        VariableValidator.Result validated = VariableValidator.validate(defs, provided);
        if (errors.isEmpty()) {
            return validated;
        }
        errors.addAll(validated.errors());
        return new VariableValidator.Result(validated.resolved(), List.copyOf(errors));
    }

    /**
     * Whether a variable declared with scope {@code def.scope()} may be overridden at {@code layer}.
     * A variable may be overridden at its own scope and at any narrower (later) scope:
     * TEMPLATE vars are fixed (default only); GROUP vars may be set at group; INSTANCE vars may be
     * set at group or per instance.
     */
    private static boolean allowsOverrideAt(VariableDef def, Scope layer) {
        return switch (def.scope()) {
            case TEMPLATE -> false;
            case GROUP -> layer == Scope.GROUP;
            case INSTANCE -> layer == Scope.GROUP || layer == Scope.INSTANCE;
        };
    }
}
