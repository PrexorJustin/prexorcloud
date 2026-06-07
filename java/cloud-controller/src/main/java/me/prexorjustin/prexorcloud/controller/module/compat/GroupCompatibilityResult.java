package me.prexorjustin.prexorcloud.controller.module.compat;

import java.util.List;

/**
 * Per-group outcome of a platform-module compatibility evaluation.
 *
 * <p>One result per group that's *affected* by the proposed manifest swap —
 * groups whose extension resolution doesn't change at all are omitted from
 * the report (see {@link ModuleCompatibilityChecker} for the
 * "affected" rules).
 *
 * @param groupName        the group's id
 * @param runtimeTarget    wire-format runtime target, e.g. "server:paper"
 * @param runtimeVersion   the group's runtime platform version, e.g. "1.21.4"
 * @param compatible       true iff the candidate manifest still produces a
 *                         valid extension resolution for this group
 * @param issue            human-readable failure reason when {@code !compatible},
 *                         null otherwise
 * @param variantChanges   per-extension variant transitions; empty when no
 *                         change but the group is otherwise affected (e.g.
 *                         the candidate module is referenced by name)
 */
public record GroupCompatibilityResult(
        String groupName,
        String runtimeTarget,
        String runtimeVersion,
        boolean compatible,
        String issue,
        List<VariantChange> variantChanges) {}
