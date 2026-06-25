package me.prexorjustin.prexorcloud.controller.scheduler.composition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableDef;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableResolver;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableValidator;
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
}
