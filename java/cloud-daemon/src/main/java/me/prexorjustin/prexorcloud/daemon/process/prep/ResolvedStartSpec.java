package me.prexorjustin.prexorcloud.daemon.process.prep;

import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.daemon.template.ServerConfigPatcher;
import me.prexorjustin.prexorcloud.protocol.TemplateRef;

/**
 * The daemon's internal "ready to provision" view of a {@code StartInstance}
 * gRPC message — every input the preparation pipeline needs, materialised
 * from protocol types into plain values.
 *
 * <p>Built once per start by {@code ProcessManager#resolveSpec(StartInstance)};
 * passed read-only to every preparation stage from there. Kept as a record
 * so the preparation code can pattern-match on its component fields without
 * re-reading the protocol message.
 */
public record ResolvedStartSpec(
        String instanceId,
        String group,
        int port,
        int memoryMb,
        double cpuReservation,
        long diskReservationMb,
        List<String> jvmArgs,
        Map<String, String> env,
        String jarFile,
        String pluginToken,
        boolean staticInstance,
        List<String> protectedPaths,
        int shutdownGraceSeconds,
        int maxPlayers,
        String category,
        String configFormat,
        String platform,
        String platformVersion,
        String runtimeDownloadUrl,
        String runtimeSha256,
        List<TemplateRef> templates,
        List<ResolvedExtensionSpec> extensions,
        List<ServerConfigPatcher.ConfigPatch> configPatches,
        String planHash) {}
