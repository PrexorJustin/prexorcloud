package me.prexorjustin.prexorcloud.controller.module.platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import me.prexorjustin.prexorcloud.api.module.SemverRange;
import me.prexorjustin.prexorcloud.api.module.Version;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleManifest;
import me.prexorjustin.prexorcloud.api.module.platform.RuntimeTarget;
import me.prexorjustin.prexorcloud.api.module.platform.WorkloadExtensionManifest;
import me.prexorjustin.prexorcloud.api.module.platform.WorkloadExtensionVariant;

/**
 * Controller-side registry for logical workload extensions and deterministic
 * variant resolution.
 */
public final class ExtensionRegistry {

    public record RegisteredExtension(String moduleId, WorkloadExtensionManifest extension) {}

    public record ResolvedVariant(
            String moduleId, WorkloadExtensionManifest extension, WorkloadExtensionVariant variant, SemverRange range) {

        public String extensionId() {
            return extension.id();
        }
    }

    private final Map<String, RegisteredExtension> extensionsById;

    public ExtensionRegistry(Collection<PlatformModuleManifest> manifests) {
        Map<String, RegisteredExtension> indexed = new LinkedHashMap<>();
        for (PlatformModuleManifest manifest : manifests) {
            for (WorkloadExtensionManifest extension : manifest.extensions()) {
                RegisteredExtension existing =
                        indexed.putIfAbsent(extension.id(), new RegisteredExtension(manifest.id(), extension));
                if (existing != null) {
                    throw new ExtensionRegistryException("duplicate logical extension id '"
                            + extension.id()
                            + "' declared by modules '"
                            + existing.moduleId()
                            + "' and '"
                            + manifest.id()
                            + "'");
                }
            }
        }
        this.extensionsById = Map.copyOf(indexed);
    }

    public Optional<RegisteredExtension> find(String extensionId) {
        return Optional.ofNullable(extensionsById.get(extensionId));
    }

    public List<RegisteredExtension> listExtensions() {
        return List.copyOf(extensionsById.values());
    }

    public List<RegisteredExtension> listExtensions(RuntimeTarget runtimeTarget) {
        List<RegisteredExtension> matching = new ArrayList<>();
        for (RegisteredExtension registered : extensionsById.values()) {
            if (registered.extension().target().equals(runtimeTarget)) {
                matching.add(registered);
            }
        }
        return List.copyOf(matching);
    }

    public List<ResolvedVariant> listCompatibleVariants(RuntimeTarget runtimeTarget, String runtimeVersion) {
        return listCompatibleVariants(runtimeTarget, Version.parse(runtimeVersion));
    }

    public List<ResolvedVariant> listCompatibleVariants(RuntimeTarget runtimeTarget, Version runtimeVersion) {
        List<ResolvedVariant> resolved = new ArrayList<>();
        for (RegisteredExtension registered : extensionsById.values()) {
            if (!registered.extension().target().equals(runtimeTarget)) {
                continue;
            }
            List<ResolvedVariant> compatible =
                    collectCompatibleVariants(registered.extension().id(), registered, runtimeTarget, runtimeVersion);
            if (!compatible.isEmpty()) {
                resolved.add(selectMostSpecific(registered.extension().id(), compatible));
            }
        }
        return List.copyOf(resolved);
    }

    public ResolvedVariant resolveVariant(String extensionId, RuntimeTarget runtimeTarget, String runtimeVersion) {
        return resolveVariant(extensionId, runtimeTarget, Version.parse(runtimeVersion));
    }

    public ResolvedVariant resolveVariant(String extensionId, RuntimeTarget runtimeTarget, Version runtimeVersion) {
        RegisteredExtension registered = extensionsById.get(extensionId);
        if (registered == null) {
            throw new ExtensionRegistryException("unknown workload extension: " + extensionId);
        }
        WorkloadExtensionManifest extension = registered.extension();
        if (!extension.target().equals(runtimeTarget)) {
            throw new ExtensionRegistryException(
                    "extension '" + extensionId + "' targets " + extension.target() + ", not " + runtimeTarget);
        }

        List<ResolvedVariant> compatible =
                collectCompatibleVariants(extensionId, registered, runtimeTarget, runtimeVersion);

        if (compatible.isEmpty()) {
            throw new ExtensionRegistryException("no compatible variant for extension '"
                    + extensionId
                    + "' and runtime "
                    + runtimeTarget
                    + " @ "
                    + runtimeVersion.raw());
        }

        return selectMostSpecific(extensionId, compatible);
    }

    private static List<ResolvedVariant> collectCompatibleVariants(
            String extensionId, RegisteredExtension registered, RuntimeTarget runtimeTarget, Version runtimeVersion) {
        WorkloadExtensionManifest extension = registered.extension();
        if (!extension.target().equals(runtimeTarget)) {
            return List.of();
        }

        List<ResolvedVariant> compatible = new ArrayList<>();
        for (WorkloadExtensionVariant variant : extension.variants()) {
            SemverRange range = parseVariantRange(extensionId, variant);
            if (range.contains(runtimeVersion)) {
                compatible.add(new ResolvedVariant(registered.moduleId(), extension, variant, range));
            }
        }
        return compatible;
    }

    private static ResolvedVariant selectMostSpecific(String extensionId, List<ResolvedVariant> compatible) {
        ResolvedVariant best = compatible.getFirst();
        for (int i = 1; i < compatible.size(); i++) {
            ResolvedVariant candidate = compatible.get(i);
            int specificity = compareSpecificity(candidate.range(), best.range());
            if (specificity > 0) {
                best = candidate;
                continue;
            }
            if (specificity == 0) {
                throw new ExtensionRegistryException("ambiguous compatible variants for extension '"
                        + extensionId
                        + "': '"
                        + best.variant().id()
                        + "' and '"
                        + candidate.variant().id()
                        + "'");
            }
        }
        return best;
    }

    public List<ResolvedVariant> resolveVariants(
            Collection<String> extensionIds, RuntimeTarget runtimeTarget, String runtimeVersion) {
        Version version = Version.parse(runtimeVersion);
        LinkedHashSet<String> distinctIds = new LinkedHashSet<>(extensionIds);
        List<ResolvedVariant> resolved = new ArrayList<>(distinctIds.size());
        for (String extensionId : distinctIds) {
            resolved.add(resolveVariant(extensionId, runtimeTarget, version));
        }
        validateConflicts(resolved);
        validateInstallPathCollisions(resolved);
        return List.copyOf(resolved);
    }

    private static SemverRange parseVariantRange(String extensionId, WorkloadExtensionVariant variant) {
        try {
            return SemverRange.parse(variant.mcVersionRange());
        } catch (IllegalArgumentException e) {
            throw new ExtensionRegistryException("extension '" + extensionId + "' variant '" + variant.id()
                    + "' has invalid range: " + e.getMessage());
        }
    }

    private static void validateConflicts(List<ResolvedVariant> resolved) {
        Map<String, ResolvedVariant> byId = new LinkedHashMap<>();
        for (ResolvedVariant variant : resolved) {
            byId.put(variant.extensionId(), variant);
        }
        for (ResolvedVariant variant : resolved) {
            for (String conflict : variant.extension().conflicts()) {
                if (byId.containsKey(conflict)) {
                    throw new ExtensionRegistryException("extension '"
                            + variant.extensionId()
                            + "' conflicts with resolved extension '"
                            + conflict
                            + "'");
                }
            }
        }
    }

    private static void validateInstallPathCollisions(List<ResolvedVariant> resolved) {
        Map<String, ResolvedVariant> byDestination = new LinkedHashMap<>();
        for (ResolvedVariant variant : resolved) {
            String destinationKey = normalizedDestination(variant.variant());
            ResolvedVariant existing = byDestination.putIfAbsent(destinationKey, variant);
            if (existing != null) {
                throw new ExtensionRegistryException("resolved extensions '"
                        + existing.extensionId()
                        + "' and '"
                        + variant.extensionId()
                        + "' both install to '"
                        + destinationKey
                        + "'");
            }
        }
    }

    private static String normalizedDestination(WorkloadExtensionVariant variant) {
        String fileName = Path.of(variant.artifact()).getFileName().toString().toLowerCase(Locale.ROOT);
        return variant.installPath().toLowerCase(Locale.ROOT) + "/" + fileName;
    }

    private static int compareSpecificity(SemverRange left, SemverRange right) {
        if (left.isExact() != right.isExact()) {
            return left.isExact() ? 1 : -1;
        }

        int lowerPresence = comparePresence(left.lowerBound(), right.lowerBound());
        if (lowerPresence != 0) {
            return lowerPresence;
        }

        int lowerValue = compareLowerValue(left.lowerBound(), right.lowerBound());
        if (lowerValue != 0) {
            return lowerValue;
        }

        int lowerInclusivity = compareLowerInclusivity(left.lowerBound(), right.lowerBound());
        if (lowerInclusivity != 0) {
            return lowerInclusivity;
        }

        int upperPresence = comparePresence(left.upperBound(), right.upperBound());
        if (upperPresence != 0) {
            return upperPresence;
        }

        int upperValue = compareUpperValue(left.upperBound(), right.upperBound());
        if (upperValue != 0) {
            return upperValue;
        }

        int upperInclusivity = compareUpperInclusivity(left.upperBound(), right.upperBound());
        if (upperInclusivity != 0) {
            return upperInclusivity;
        }

        return Integer.compare(left.constraints().size(), right.constraints().size());
    }

    private static int comparePresence(Optional<?> left, Optional<?> right) {
        return Boolean.compare(left.isPresent(), right.isPresent());
    }

    private static int compareLowerValue(Optional<SemverRange.Bound> left, Optional<SemverRange.Bound> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        return left.get().value().compareTo(right.get().value());
    }

    private static int compareLowerInclusivity(Optional<SemverRange.Bound> left, Optional<SemverRange.Bound> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        if (left.get().value().compareTo(right.get().value()) != 0) {
            return 0;
        }
        return left.get().inclusive() == right.get().inclusive()
                ? 0
                : (left.get().inclusive() ? -1 : 1);
    }

    private static int compareUpperValue(Optional<SemverRange.Bound> left, Optional<SemverRange.Bound> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        return right.get().value().compareTo(left.get().value());
    }

    private static int compareUpperInclusivity(Optional<SemverRange.Bound> left, Optional<SemverRange.Bound> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        if (left.get().value().compareTo(right.get().value()) != 0) {
            return 0;
        }
        return left.get().inclusive() == right.get().inclusive()
                ? 0
                : (left.get().inclusive() ? -1 : 1);
    }
}
