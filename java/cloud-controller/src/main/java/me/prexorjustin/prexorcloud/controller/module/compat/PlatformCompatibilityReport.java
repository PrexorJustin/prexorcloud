package me.prexorjustin.prexorcloud.controller.module.compat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregate report produced by {@link ModuleCompatibilityChecker} for a
 * platform-module install/upgrade pre-check.
 *
 * <p>{@link #checkedGroups()} is the total group count the controller
 * evaluated; {@link #affectedGroups()} is the subset whose extension
 * resolution actually changes (or fails) under the candidate manifest.
 *
 * @param checkedGroups   total groups checked (i.e. all groups visible to
 *                        the controller's GroupManager at evaluation time)
 * @param affectedGroups  per-group results — only groups affected by the
 *                        candidate manifest (no-op groups are omitted)
 */
public record PlatformCompatibilityReport(int checkedGroups, List<GroupCompatibilityResult> affectedGroups) {

    public boolean compatible() {
        return affectedGroups.stream().allMatch(GroupCompatibilityResult::compatible);
    }

    public int incompatibleGroups() {
        return (int)
                affectedGroups.stream().filter(result -> !result.compatible()).count();
    }

    /**
     * Render the report as the JSON envelope returned by the install /
     * upgrade routes. Kept here (next to the record) so the route handler
     * stays small and the wire format lives next to the data shape.
     */
    public Map<String, Object> toJson() {
        var body = new LinkedHashMap<String, Object>();
        body.put("checkedGroups", checkedGroups);
        body.put("affectedGroups", affectedGroups.size());
        body.put("incompatibleGroups", incompatibleGroups());
        body.put(
                "groups",
                affectedGroups.stream()
                        .map(result -> {
                            var group = new LinkedHashMap<String, Object>();
                            group.put("groupName", result.groupName());
                            group.put("runtimeTarget", result.runtimeTarget());
                            group.put("runtimeVersion", result.runtimeVersion());
                            group.put("compatible", result.compatible());
                            group.put("issue", result.issue());
                            group.put(
                                    "variantChanges",
                                    result.variantChanges().stream()
                                            .map(change -> Map.of(
                                                    "extensionId", change.extensionId(),
                                                    "moduleId", change.moduleId(),
                                                    "fromVariantId", change.fromVariantId(),
                                                    "toVariantId", change.toVariantId()))
                                            .toList());
                            return group;
                        })
                        .toList());
        return body;
    }
}
