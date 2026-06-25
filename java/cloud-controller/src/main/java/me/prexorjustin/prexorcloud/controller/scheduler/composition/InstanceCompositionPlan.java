package me.prexorjustin.prexorcloud.controller.scheduler.composition;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Controller-resolved instance composition plan.
 */
public record InstanceCompositionPlan(
        String instanceId,
        String groupName,
        String nodeId,
        int port,
        int memoryMb,
        RuntimeIsolation isolation,
        List<String> jvmArgs,
        Map<String, String> env,
        boolean staticInstance,
        List<String> protectedPaths,
        List<ResolvedTemplate> templates,
        ResolvedRuntime runtime,
        List<ResolvedExtension> extensions,
        List<ResolvedConfigPatch> configPatches,
        Map<String, String> variableOverrides,
        String planHash,
        Instant createdAt) {

    public InstanceCompositionPlan {
        isolation = isolation == null ? new RuntimeIsolation(0.0, 0).normalized() : isolation.normalized();
        jvmArgs = jvmArgs == null ? List.of() : List.copyOf(jvmArgs);
        env = env == null ? Map.of() : Map.copyOf(env);
        protectedPaths = protectedPaths == null ? List.of() : List.copyOf(protectedPaths);
        templates = templates == null ? List.of() : List.copyOf(templates);
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
        configPatches = configPatches == null ? List.of() : List.copyOf(configPatches);
        variableOverrides = variableOverrides == null ? Map.of() : Map.copyOf(variableOverrides);
    }

    public record ResolvedTemplate(String name, String hash, String source) {}

    public record ResolvedRuntime(
            String jarFile,
            String downloadUrl,
            String sha256,
            String platform,
            String platformVersion,
            String category,
            String configFormat,
            String runtimeTarget) {}

    public record ResolvedExtension(
            String moduleId,
            String extensionId,
            String target,
            String activation,
            String variantId,
            String mcVersionRange,
            int runtimeApiVersion,
            String artifact,
            String downloadUrl,
            String sha256,
            String installPath) {}

    public record ResolvedConfigPatch(String file, String key, String value) {}

    public record RuntimeIsolation(double cpuReservation, long diskReservationMb) {
        RuntimeIsolation normalized() {
            return new RuntimeIsolation(Math.max(0.0, cpuReservation), Math.max(0, diskReservationMb));
        }
    }
}
