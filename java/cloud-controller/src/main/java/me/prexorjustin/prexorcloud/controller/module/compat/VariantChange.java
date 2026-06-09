package me.prexorjustin.prexorcloud.controller.module.compat;

/**
 * One extension's variant transition between the current cluster state and a
 * candidate platform-module manifest swap.
 *
 * <p>Emitted by {@link ModuleCompatibilityChecker}; surfaced to operators via
 * the platform-module install/upgrade compatibility report (POST
 * {@code /api/v1/modules/platform/.../install}).
 *
 * @param extensionId    the {@code @Extension} id (e.g. {@code prexor.team-color})
 * @param moduleId       the module providing the extension after the change
 * @param fromVariantId  variant id selected by the *current* state, or {@code null}
 *                       if the extension wasn't resolved before the change
 * @param toVariantId    variant id selected by the *candidate* state, or {@code null}
 *                       if the candidate manifest no longer resolves it
 */
public record VariantChange(String extensionId, String moduleId, String fromVariantId, String toVariantId) {}
