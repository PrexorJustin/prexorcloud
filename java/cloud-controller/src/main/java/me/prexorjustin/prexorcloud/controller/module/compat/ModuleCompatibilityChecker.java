package me.prexorjustin.prexorcloud.controller.module.compat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleManifest;
import me.prexorjustin.prexorcloud.api.module.platform.RuntimeTarget;
import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.catalog.CatalogStore;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.group.GroupRuntimeResolver;
import me.prexorjustin.prexorcloud.controller.module.platform.ExtensionRegistry;
import me.prexorjustin.prexorcloud.controller.module.platform.ExtensionRegistryException;
import me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleManager;

/**
 * Pre-flight compatibility checker for platform-module install/upgrade
 * operations.
 *
 * <p>The check answers: "if I swap this module's manifest into the cluster,
 * which existing groups would still resolve their extension graph cleanly,
 * which would now produce a different variant choice, and which would fail
 * outright?"
 *
 * <p>The same logic was previously inlined in {@code ModuleRoutes.java}
 * (~250 LOC). It's been extracted here so the routes file stays focused on
 * HTTP wiring and so the compatibility logic is unit-testable in isolation.
 *
 * <p>Stateless apart from the constructor-injected collaborators; safe to
 * call from any thread that the HTTP route is dispatched on.
 */
public final class ModuleCompatibilityChecker {

    private final PrexorController controller;
    private final PlatformModuleManager platformManager;

    public ModuleCompatibilityChecker(PrexorController controller, PlatformModuleManager platformManager) {
        this.controller = controller;
        this.platformManager = platformManager;
    }

    /**
     * Evaluate the compatibility of a candidate platform-module manifest
     * against every group currently visible to the controller.
     *
     * @param candidateManifest the manifest the operator wants to install
     *                          (or replace an existing module with)
     * @return a non-null report; an empty report (zero affected groups)
     *         means the change is fully transparent to existing groups
     */
    public PlatformCompatibilityReport evaluate(PlatformModuleManifest candidateManifest) {
        GroupManager groupManager = controller.groupManager();
        if (groupManager == null) {
            return new PlatformCompatibilityReport(0, List.of());
        }

        List<PlatformModuleManifest> currentManifests = platformManager.listModules().stream()
                .map(PlatformModuleManager.ManagedPlatformModule::manifest)
                .toList();
        ExtensionRegistry currentRegistry = new ExtensionRegistry(currentManifests);
        ExtensionRegistry candidateRegistry =
                new ExtensionRegistry(replaceManifest(currentManifests, candidateManifest));
        List<String> candidateExtensionIds = candidateManifest.extensions().stream()
                .map(extension -> extension.id())
                .toList();

        List<GroupCompatibilityResult> affectedGroups = new ArrayList<>();
        for (GroupConfig group : groupManager.getAll()) {
            GroupConfig resolved = groupManager.resolveGroup(group.name());
            GroupCompatibilityResult result = evaluateGroupCompatibility(
                    resolved,
                    currentRegistry,
                    candidateRegistry,
                    controller.catalogStore(),
                    candidateManifest.id(),
                    candidateExtensionIds);
            if (result != null) {
                affectedGroups.add(result);
            }
        }
        affectedGroups.sort(Comparator.comparing(GroupCompatibilityResult::groupName));
        return new PlatformCompatibilityReport(groupManager.getAll().size(), List.copyOf(affectedGroups));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Per-group evaluation
    // ──────────────────────────────────────────────────────────────────────

    private static GroupCompatibilityResult evaluateGroupCompatibility(
            GroupConfig group,
            ExtensionRegistry currentRegistry,
            ExtensionRegistry candidateRegistry,
            CatalogStore catalogStore,
            String candidateModuleId,
            List<String> candidateExtensionIds) {
        var runtimeResolution = GroupRuntimeResolver.resolve(group, catalogStore);
        String runtimeVersion = runtimeResolution.target().platformVersion();
        RuntimeTarget runtimeTarget = toRuntimeTarget(
                runtimeResolution.category(), runtimeResolution.target().platform());
        if (runtimeTarget == null || runtimeVersion == null || runtimeVersion.isBlank()) {
            return null;
        }

        List<ExtensionRegistry.ResolvedVariant> currentResolved =
                safeResolveGroupVariants(group, currentRegistry, runtimeTarget, runtimeVersion);
        try {
            List<ExtensionRegistry.ResolvedVariant> candidateResolved =
                    resolveGroupVariants(group, candidateRegistry, runtimeTarget, runtimeVersion);
            List<VariantChange> variantChanges = diffVariants(currentResolved, candidateResolved);
            boolean affectsGroup = !variantChanges.isEmpty()
                    || referencesCandidateModule(group, candidateModuleId)
                    || referencesCandidateExtensions(group, candidateExtensionIds)
                    || candidateResolved.stream()
                            .anyMatch(resolved -> resolved.moduleId().equals(candidateModuleId))
                    || currentResolved.stream()
                            .anyMatch(resolved -> resolved.moduleId().equals(candidateModuleId));
            if (!affectsGroup) {
                return null;
            }
            return new GroupCompatibilityResult(
                    group.name(), runtimeTarget.wireValue(), runtimeVersion, true, null, variantChanges);
        } catch (IllegalStateException e) {
            if (currentResolved.isEmpty()
                    && !referencesCandidateModule(group, candidateModuleId)
                    && !referencesCandidateExtensions(group, candidateExtensionIds)) {
                return null;
            }
            try {
                resolveGroupVariants(group, currentRegistry, runtimeTarget, runtimeVersion);
                return new GroupCompatibilityResult(
                        group.name(), runtimeTarget.wireValue(), runtimeVersion, false, e.getMessage(), List.of());
            } catch (IllegalStateException _) {
                return null;
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Variant resolution (the heaviest method — kept verbatim from the
    // ModuleRoutes original so behaviour is bit-for-bit unchanged)
    // ──────────────────────────────────────────────────────────────────────

    private static List<ExtensionRegistry.ResolvedVariant> safeResolveGroupVariants(
            GroupConfig group, ExtensionRegistry registry, RuntimeTarget runtimeTarget, String runtimeVersion) {
        try {
            return resolveGroupVariants(group, registry, runtimeTarget, runtimeVersion);
        } catch (IllegalStateException _) {
            return List.of();
        }
    }

    private static List<ExtensionRegistry.ResolvedVariant> resolveGroupVariants(
            GroupConfig group,
            ExtensionRegistry extensionRegistry,
            RuntimeTarget runtimeTarget,
            String runtimeVersion) {
        Set<String> attachedModules = new LinkedHashSet<>(group.attachedModules());
        Set<String> enabledModules = new LinkedHashSet<>(group.enabledModules());
        Set<String> disabledModules = new LinkedHashSet<>(group.disabledModules());
        Set<String> disabledExtensions = new LinkedHashSet<>(group.disabledExtensions());
        Set<String> attachedExtensions = new LinkedHashSet<>(group.attachedExtensions());
        Set<String> enabledExtensions = new LinkedHashSet<>(group.enabledExtensions());
        boolean hasEnabledAllowlist = !enabledExtensions.isEmpty();
        boolean hasEnabledModuleAllowlist = !enabledModules.isEmpty();

        Set<String> conflictingModulePolicies = new LinkedHashSet<>(attachedModules);
        conflictingModulePolicies.retainAll(disabledModules);
        if (!conflictingModulePolicies.isEmpty()) {
            throw new IllegalStateException("group '%s' both attaches and disables modules: %s"
                    .formatted(group.name(), conflictingModulePolicies));
        }
        conflictingModulePolicies = new LinkedHashSet<>(enabledModules);
        conflictingModulePolicies.retainAll(disabledModules);
        if (!conflictingModulePolicies.isEmpty()) {
            throw new IllegalStateException("group '%s' both enables and disables modules: %s"
                    .formatted(group.name(), conflictingModulePolicies));
        }

        Set<String> conflictingPolicies = new LinkedHashSet<>(attachedExtensions);
        conflictingPolicies.retainAll(disabledExtensions);
        if (!conflictingPolicies.isEmpty()) {
            throw new IllegalStateException("group '%s' both attaches and disables extensions: %s"
                    .formatted(group.name(), conflictingPolicies));
        }
        conflictingPolicies = new LinkedHashSet<>(enabledExtensions);
        conflictingPolicies.retainAll(disabledExtensions);
        if (!conflictingPolicies.isEmpty()) {
            throw new IllegalStateException(
                    "group '%s' both enables and disables extensions: %s".formatted(group.name(), conflictingPolicies));
        }

        List<String> extensionIds = new ArrayList<>();
        List<ExtensionRegistry.RegisteredExtension> registeredExtensions =
                extensionRegistry.listExtensions(runtimeTarget).stream()
                        .sorted(Comparator.comparing(
                                registered -> registered.extension().id()))
                        .toList();
        Set<String> compatibleModuleIds = registeredExtensions.stream()
                .map(ExtensionRegistry.RegisteredExtension::moduleId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (ExtensionRegistry.RegisteredExtension registered : registeredExtensions) {
            String moduleId = registered.moduleId();
            String extensionId = registered.extension().id();
            boolean moduleAttached = attachedModules.contains(moduleId);
            boolean moduleEnabled = enabledModules.contains(moduleId);
            boolean moduleDisabled = disabledModules.contains(moduleId);
            boolean attached = attachedExtensions.remove(extensionId);
            boolean enabled = enabledExtensions.remove(extensionId);
            boolean disabled = disabledExtensions.remove(extensionId);
            switch (registered.extension().activationPolicy()) {
                case ALWAYS -> {
                    if (moduleDisabled) {
                        throw new IllegalStateException(
                                "group '%s' cannot disable always-on module '%s' because it contributes always-on extension '%s'"
                                        .formatted(group.name(), moduleId, extensionId));
                    }
                    if (disabled) {
                        throw new IllegalStateException("group '%s' cannot disable always-on extension '%s'"
                                .formatted(group.name(), extensionId));
                    }
                    extensionIds.add(extensionId);
                }
                case DEFAULT_ENABLED -> {
                    if (!moduleDisabled
                            && !disabled
                            && (!hasEnabledModuleAllowlist || moduleEnabled || moduleAttached)
                            && (!hasEnabledAllowlist || enabled || attached)) {
                        extensionIds.add(extensionId);
                    }
                }
                case EXPLICIT_GROUP_ATTACH -> {
                    if (!moduleDisabled && (attached || moduleAttached)) {
                        extensionIds.add(extensionId);
                    }
                }
            }
        }

        Set<String> unknownModules = new LinkedHashSet<>(attachedModules);
        unknownModules.addAll(enabledModules);
        unknownModules.addAll(disabledModules);
        unknownModules.removeAll(compatibleModuleIds);
        if (!unknownModules.isEmpty()) {
            throw new IllegalStateException("group '%s' references unknown or incompatible modules: %s"
                    .formatted(group.name(), unknownModules));
        }
        if (!attachedExtensions.isEmpty()) {
            throw new IllegalStateException("group '%s' attaches unknown or incompatible extensions: %s"
                    .formatted(group.name(), attachedExtensions));
        }
        if (!disabledExtensions.isEmpty()) {
            throw new IllegalStateException("group '%s' disables unknown or incompatible extensions: %s"
                    .formatted(group.name(), disabledExtensions));
        }
        if (hasEnabledAllowlist && !enabledExtensions.isEmpty()) {
            throw new IllegalStateException("group '%s' enables unknown or incompatible extensions: %s"
                    .formatted(group.name(), enabledExtensions));
        }
        if (extensionIds.isEmpty()) {
            return List.of();
        }

        try {
            return extensionRegistry.resolveVariants(
                    extensionIds.stream().distinct().sorted().toList(), runtimeTarget, runtimeVersion);
        } catch (ExtensionRegistryException e) {
            throw new IllegalStateException(
                    "group '%s' failed to resolve compatible extensions: %s".formatted(group.name(), e.getMessage()),
                    e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Variant diffing
    // ──────────────────────────────────────────────────────────────────────

    private static List<VariantChange> diffVariants(
            List<ExtensionRegistry.ResolvedVariant> currentResolved,
            List<ExtensionRegistry.ResolvedVariant> candidateResolved) {
        Map<String, ExtensionRegistry.ResolvedVariant> currentByExtension = currentResolved.stream()
                .collect(Collectors.toMap(
                        ExtensionRegistry.ResolvedVariant::extensionId,
                        resolved -> resolved,
                        (left, right) -> right,
                        LinkedHashMap::new));
        Map<String, ExtensionRegistry.ResolvedVariant> candidateByExtension = candidateResolved.stream()
                .collect(Collectors.toMap(
                        ExtensionRegistry.ResolvedVariant::extensionId,
                        resolved -> resolved,
                        (left, right) -> right,
                        LinkedHashMap::new));
        Set<String> extensionIds = new LinkedHashSet<>(currentByExtension.keySet());
        extensionIds.addAll(candidateByExtension.keySet());

        List<VariantChange> changes = new ArrayList<>();
        for (String extensionId : extensionIds) {
            var current = currentByExtension.get(extensionId);
            var candidate = candidateByExtension.get(extensionId);
            String fromVariantId = current != null ? current.variant().id() : null;
            String toVariantId = candidate != null ? candidate.variant().id() : null;
            String fromModuleId = current != null ? current.moduleId() : null;
            String toModuleId = candidate != null ? candidate.moduleId() : null;
            if (!Objects.equals(fromVariantId, toVariantId) || !Objects.equals(fromModuleId, toModuleId)) {
                changes.add(new VariantChange(
                        extensionId,
                        candidate != null ? candidate.moduleId() : fromModuleId,
                        fromVariantId,
                        toVariantId));
            }
        }
        return List.copyOf(changes);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Small helpers
    // ──────────────────────────────────────────────────────────────────────

    private static boolean referencesCandidateModule(GroupConfig group, String candidateModuleId) {
        return group.attachedModules().contains(candidateModuleId)
                || group.enabledModules().contains(candidateModuleId)
                || group.disabledModules().contains(candidateModuleId);
    }

    private static boolean referencesCandidateExtensions(GroupConfig group, List<String> candidateExtensionIds) {
        Set<String> extensionIds = new LinkedHashSet<>(group.attachedExtensions());
        extensionIds.addAll(group.enabledExtensions());
        extensionIds.addAll(group.disabledExtensions());
        return candidateExtensionIds.stream().anyMatch(extensionIds::contains);
    }

    private static List<PlatformModuleManifest> replaceManifest(
            List<PlatformModuleManifest> manifests, PlatformModuleManifest candidateManifest) {
        List<PlatformModuleManifest> replaced = manifests.stream()
                .filter(manifest -> !manifest.id().equals(candidateManifest.id()))
                .collect(Collectors.toCollection(ArrayList::new));
        replaced.add(candidateManifest);
        return List.copyOf(replaced);
    }

    private static RuntimeTarget toRuntimeTarget(String category, String platform) {
        if (platform == null || platform.isBlank()) {
            return null;
        }
        String workloadType = "PROXY".equalsIgnoreCase(category) ? "proxy" : "server";
        return new RuntimeTarget(workloadType, platform.toLowerCase(Locale.ROOT));
    }
}
