package me.prexorjustin.prexorcloud.api.module.platform;

/**
 * Concrete workload artifact for a runtime/version range.
 */
public record WorkloadExtensionVariant(
        String id, String mcVersionRange, int runtimeApiVersion, String artifact, String sha256, String installPath) {}
