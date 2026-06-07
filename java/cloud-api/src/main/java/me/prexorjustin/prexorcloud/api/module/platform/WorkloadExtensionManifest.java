package me.prexorjustin.prexorcloud.api.module.platform;

import java.util.List;

/**
 * Logical extension owned by a platform module.
 */
public record WorkloadExtensionManifest(
        String id,
        RuntimeTarget target,
        ActivationPolicy activationPolicy,
        List<String> conflicts,
        List<WorkloadExtensionVariant> variants) {

    public WorkloadExtensionManifest {
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        variants = variants == null ? List.of() : List.copyOf(variants);
    }
}
