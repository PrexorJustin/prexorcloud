package me.prexorjustin.prexorcloud.controller.scheduler.composition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableResolver;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableValidator;
import me.prexorjustin.prexorcloud.controller.group.spec.secret.SecretResolutionException;
import me.prexorjustin.prexorcloud.controller.group.spec.secret.SecretResolver;
import me.prexorjustin.prexorcloud.controller.state.StateStore;

/**
 * The one place that turns a group into its effective typed variables. It gathers the
 * {@link VariableDef}s declared across the group's whole template chain (base → platform → group →
 * user, deduped — see {@link InstanceCompositionPlanner#templateChainNames}) and resolves/validates
 * supplied group + instance values against them via {@link VariableResolver}.
 *
 * <p>Both the dispatch path (start-spec assembly) and the REST surfaces (validate-on-set) resolve
 * through this, so a value is judged identically everywhere — there is no second resolution path.
 */
public final class GroupVariableResolver {

    private GroupVariableResolver() {}

    /** The typed variable definitions a group declares across its full template chain, deduped by key. */
    public static List<VariableDef> defsForGroup(GroupConfig group, StateStore stateStore) {
        List<VariableDef> defs = new ArrayList<>();
        for (String templateName : InstanceCompositionPlanner.templateChainNames(group)) {
            defs.addAll(stateStore.getTemplateVariableDefs(templateName));
        }
        return VariableResolver.mergeChain(defs);
    }

    /**
     * Resolve a group's effective variables from its template defaults (lowest), the group values, and
     * the per-instance overrides (highest), enforcing each variable's declared scope and type.
     */
    public static VariableValidator.Result resolve(
            GroupConfig group,
            StateStore stateStore,
            Map<String, String> groupValues,
            Map<String, String> instanceValues) {
        return VariableResolver.resolve(defsForGroup(group, stateStore), groupValues, instanceValues);
    }

    /**
     * As {@link #resolve}, but additionally fetches every {@code SECRET}-typed variable's reference
     * through the {@link SecretResolver} so the daemon receives the plaintext value, not the reference.
     * This is the dispatch-only path: the plaintext lives solely in the transient start message, never
     * in the persisted plan, group config, or audit (which keep the {@code scheme://…} reference). The
     * plain {@link #resolve} stays secret-free so validate-on-set never has to reach a secret backend.
     *
     * <p>A secret that cannot be resolved is dropped from the map and recorded as an error rather than
     * blocking placement — consistent with the rest of resolution, a misconfigured secret must never
     * wedge a start; the operator sees the loud warning and a server missing that one value.
     */
    public static VariableValidator.Result resolveForDispatch(
            GroupConfig group,
            StateStore stateStore,
            Map<String, String> groupValues,
            Map<String, String> instanceValues,
            SecretResolver secretResolver) {

        List<VariableDef> defs = defsForGroup(group, stateStore);
        VariableValidator.Result base = VariableResolver.resolve(defs, groupValues, instanceValues);

        Map<String, String> resolved = new LinkedHashMap<>(base.resolved());
        List<String> errors = new ArrayList<>(base.errors());
        for (VariableDef def : defs) {
            if (def.type() != VariableDef.VarType.SECRET || !resolved.containsKey(def.key())) {
                continue;
            }
            try {
                resolved.put(def.key(), secretResolver.resolve(resolved.get(def.key())));
            } catch (SecretResolutionException e) {
                resolved.remove(def.key());
                errors.add("secret variable '" + def.key() + "' could not be resolved: " + e.getMessage());
            }
        }
        return new VariableValidator.Result(resolved, List.copyOf(errors));
    }
}
