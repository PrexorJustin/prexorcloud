package me.prexorjustin.prexorcloud.controller.group;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Full server group configuration matching doc §3.2.
 */
public record GroupConfig(
        // Identity & inheritance
        @JsonProperty("name") String name,
        @JsonProperty("parent") String parent,
        @JsonProperty("platform") String platform,
        @JsonProperty("platformVersion") String platformVersion,
        @JsonProperty("jarFile") String jarFile,
        @JsonProperty("templates") List<String> templates,
        // Scaling
        @JsonProperty("scalingMode") String scalingMode,
        @JsonProperty("minInstances") int minInstances,
        @JsonProperty("maxInstances") int maxInstances,
        @JsonProperty("maxPlayers") int maxPlayers,
        @JsonProperty("scaleUpThreshold") double scaleUpThreshold,
        @JsonProperty("scaleDownAfterSeconds") int scaleDownAfterSeconds,
        @JsonProperty("scaleCooldownSeconds") int scaleCooldownSeconds,
        // Reserved/dropped (Phase 45 M0): kept as positional record components for
        // internal backwards-compat with existing callers, but suppressed from the
        // public JSON/OpenAPI contract. Jackson ignores them on read and write.
        @JsonIgnore boolean predictiveScaling,
        @JsonIgnore double scaleUpMargin,
        @JsonIgnore int burstCeiling,
        @JsonIgnore String routing,
        // Networking
        @JsonProperty("portRangeStart") int portRangeStart,
        @JsonProperty("portRangeEnd") int portRangeEnd,
        // Lifecycle
        @JsonProperty("startupTimeoutSeconds") int startupTimeoutSeconds,
        @JsonProperty("shutdownGraceSeconds") int shutdownGraceSeconds,
        @JsonIgnore boolean drainOnShutdown,
        @JsonProperty("maxLifetimeSeconds") int maxLifetimeSeconds,
        // Static / persistence
        @JsonProperty("static") boolean isStatic,
        @JsonProperty("staticInstanceNames") List<String> staticInstanceNames,
        @JsonProperty("protectedPaths") List<String> protectedPaths,
        // Orchestration
        @JsonProperty("fallbackGroup") String fallbackGroup,
        @JsonProperty("defaultGroup") boolean defaultGroup,
        @JsonProperty("dependsOn") List<String> dependsOn,
        @JsonProperty("startupWeight") int startupWeight,
        // Operations
        @JsonProperty("maintenance") boolean maintenance,
        @JsonProperty("maintenanceMessage") String maintenanceMessage,
        @JsonProperty("maintenanceBypass") List<String> maintenanceBypass,
        @JsonProperty("updateStrategy") String updateStrategy,
        // Placement
        @JsonProperty("nodeAffinity") List<String> nodeAffinity,
        @JsonProperty("nodeAntiAffinity") List<String> nodeAntiAffinity,
        @JsonProperty("spreadConstraint") String spreadConstraint,
        @JsonProperty("priority") int priority,
        // Resources
        @JsonProperty("memoryMb") int memoryMb,
        @JsonProperty("cpuReservation") double cpuReservation,
        @JsonProperty("diskReservationMb") long diskReservationMb,
        @JsonProperty("jvmArgs") List<String> jvmArgs,
        @JsonProperty("env") Map<String, String> env,
        // MOTD
        @JsonProperty("motds") List<String> motds,
        @JsonProperty("motdMode") String motdMode,
        @JsonProperty("motdIntervalSeconds") int motdIntervalSeconds,
        // Module / extension policy
        @JsonProperty("attachedModules") List<String> attachedModules,
        @JsonProperty("enabledModules") List<String> enabledModules,
        @JsonProperty("disabledModules") List<String> disabledModules,
        @JsonProperty("attachedExtensions") List<String> attachedExtensions,
        @JsonProperty("enabledExtensions") List<String> enabledExtensions,
        @JsonProperty("disabledExtensions") List<String> disabledExtensions,
        @JsonProperty("configPatches") Map<String, Map<String, String>> configPatches,
        // Bedrock: for a Geyser (GEYSER platform) group, the proxy group it fronts. The controller
        // resolves a live instance of this group and injects its host:port as Geyser's remote at
        // provision time. Empty for non-Geyser groups.
        @JsonProperty("bedrockProxyGroup") String bedrockProxyGroup) {

    public GroupConfig {
        if (name == null) name = "";
        if (platform == null) platform = "PAPER";
        else platform = platform.toUpperCase();
        if (platformVersion == null) platformVersion = "";
        if (jarFile == null) jarFile = "server.jar";
        if (templates == null) templates = List.of();
        if (scalingMode == null) scalingMode = "DYNAMIC";
        else scalingMode = scalingMode.toUpperCase(Locale.ROOT);
        if (maxInstances <= 0) maxInstances = 10;
        if (maxPlayers <= 0) maxPlayers = 100;
        if (scaleUpThreshold <= 0) scaleUpThreshold = 0.8;
        if (scaleDownAfterSeconds <= 0) scaleDownAfterSeconds = 300;
        if (scaleCooldownSeconds <= 0) scaleCooldownSeconds = 60;
        if (scaleUpMargin <= 0) scaleUpMargin = 0.2;
        if (routing == null) routing = "LOWEST_PLAYERS";
        if (portRangeStart <= 0) portRangeStart = 30000;
        if (portRangeEnd <= 0) portRangeEnd = 30100;
        if (startupTimeoutSeconds <= 0) startupTimeoutSeconds = 120;
        if (shutdownGraceSeconds <= 0) shutdownGraceSeconds = 30;
        if (staticInstanceNames == null) staticInstanceNames = List.of();
        if (protectedPaths == null) protectedPaths = List.of();
        if (dependsOn == null) dependsOn = List.of();
        if (maintenanceMessage == null) maintenanceMessage = "";
        if (maintenanceBypass == null) maintenanceBypass = List.of();
        if (updateStrategy == null) updateStrategy = "ROLLING";
        if (nodeAffinity == null) nodeAffinity = List.of();
        if (nodeAntiAffinity == null) nodeAntiAffinity = List.of();
        if (spreadConstraint == null) spreadConstraint = "";
        if (memoryMb <= 0) memoryMb = 1024;
        if (cpuReservation < 0) cpuReservation = 0;
        if (diskReservationMb < 0) diskReservationMb = 0;
        if (jvmArgs == null) jvmArgs = List.of();
        if (env == null) env = Map.of();
        if (motds == null) motds = List.of();
        if (motdMode == null) motdMode = "STATIC";
        if (motdIntervalSeconds <= 0) motdIntervalSeconds = 30;
        if (attachedModules == null) attachedModules = List.of();
        if (enabledModules == null) enabledModules = List.of();
        if (disabledModules == null) disabledModules = List.of();
        if (attachedExtensions == null) attachedExtensions = List.of();
        if (enabledExtensions == null) enabledExtensions = List.of();
        if (disabledExtensions == null) disabledExtensions = List.of();
        if (configPatches == null) {
            configPatches = Map.of();
        } else {
            configPatches = configPatches.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue() == null ? Map.of() : Map.copyOf(entry.getValue())));
        }
        if (bedrockProxyGroup == null) bedrockProxyGroup = "";
    }

    public GroupConfig(
            String name,
            String parent,
            String platform,
            String platformVersion,
            String jarFile,
            List<String> templates,
            String scalingMode,
            int minInstances,
            int maxInstances,
            int maxPlayers,
            double scaleUpThreshold,
            int scaleDownAfterSeconds,
            int scaleCooldownSeconds,
            boolean predictiveScaling,
            double scaleUpMargin,
            int burstCeiling,
            String routing,
            int portRangeStart,
            int portRangeEnd,
            int startupTimeoutSeconds,
            int shutdownGraceSeconds,
            boolean drainOnShutdown,
            int maxLifetimeSeconds,
            boolean isStatic,
            List<String> staticInstanceNames,
            List<String> protectedPaths,
            String fallbackGroup,
            boolean defaultGroup,
            List<String> dependsOn,
            int startupWeight,
            boolean maintenance,
            String maintenanceMessage,
            List<String> maintenanceBypass,
            String updateStrategy,
            List<String> nodeAffinity,
            List<String> nodeAntiAffinity,
            String spreadConstraint,
            int priority,
            int memoryMb,
            double cpuReservation,
            long diskReservationMb,
            List<String> jvmArgs,
            Map<String, String> env,
            List<String> motds,
            String motdMode,
            int motdIntervalSeconds,
            List<String> attachedExtensions,
            List<String> enabledExtensions,
            List<String> disabledExtensions,
            Map<String, Map<String, String>> configPatches) {
        this(
                name,
                parent,
                platform,
                platformVersion,
                jarFile,
                templates,
                scalingMode,
                minInstances,
                maxInstances,
                maxPlayers,
                scaleUpThreshold,
                scaleDownAfterSeconds,
                scaleCooldownSeconds,
                predictiveScaling,
                scaleUpMargin,
                burstCeiling,
                routing,
                portRangeStart,
                portRangeEnd,
                startupTimeoutSeconds,
                shutdownGraceSeconds,
                drainOnShutdown,
                maxLifetimeSeconds,
                isStatic,
                staticInstanceNames,
                protectedPaths,
                fallbackGroup,
                defaultGroup,
                dependsOn,
                startupWeight,
                maintenance,
                maintenanceMessage,
                maintenanceBypass,
                updateStrategy,
                nodeAffinity,
                nodeAntiAffinity,
                spreadConstraint,
                priority,
                memoryMb,
                cpuReservation,
                diskReservationMb,
                jvmArgs,
                env,
                motds,
                motdMode,
                motdIntervalSeconds,
                List.of(),
                List.of(),
                List.of(),
                attachedExtensions,
                enabledExtensions,
                disabledExtensions,
                configPatches,
                "");
    }

    public GroupConfig(
            String name,
            String parent,
            String platform,
            String platformVersion,
            String jarFile,
            List<String> templates,
            String scalingMode,
            int minInstances,
            int maxInstances,
            int maxPlayers,
            double scaleUpThreshold,
            int scaleDownAfterSeconds,
            int scaleCooldownSeconds,
            boolean predictiveScaling,
            double scaleUpMargin,
            int burstCeiling,
            String routing,
            int portRangeStart,
            int portRangeEnd,
            int startupTimeoutSeconds,
            int shutdownGraceSeconds,
            boolean drainOnShutdown,
            int maxLifetimeSeconds,
            boolean isStatic,
            List<String> staticInstanceNames,
            List<String> protectedPaths,
            String fallbackGroup,
            boolean defaultGroup,
            List<String> dependsOn,
            int startupWeight,
            boolean maintenance,
            String maintenanceMessage,
            List<String> maintenanceBypass,
            String updateStrategy,
            List<String> nodeAffinity,
            List<String> nodeAntiAffinity,
            String spreadConstraint,
            int priority,
            int memoryMb,
            List<String> jvmArgs,
            Map<String, String> env,
            List<String> motds,
            String motdMode,
            int motdIntervalSeconds,
            List<String> attachedExtensions,
            List<String> enabledExtensions,
            List<String> disabledExtensions,
            Map<String, Map<String, String>> configPatches) {
        this(
                name,
                parent,
                platform,
                platformVersion,
                jarFile,
                templates,
                scalingMode,
                minInstances,
                maxInstances,
                maxPlayers,
                scaleUpThreshold,
                scaleDownAfterSeconds,
                scaleCooldownSeconds,
                predictiveScaling,
                scaleUpMargin,
                burstCeiling,
                routing,
                portRangeStart,
                portRangeEnd,
                startupTimeoutSeconds,
                shutdownGraceSeconds,
                drainOnShutdown,
                maxLifetimeSeconds,
                isStatic,
                staticInstanceNames,
                protectedPaths,
                fallbackGroup,
                defaultGroup,
                dependsOn,
                startupWeight,
                maintenance,
                maintenanceMessage,
                maintenanceBypass,
                updateStrategy,
                nodeAffinity,
                nodeAntiAffinity,
                spreadConstraint,
                priority,
                memoryMb,
                0.0,
                0,
                jvmArgs,
                env,
                motds,
                motdMode,
                motdIntervalSeconds,
                attachedExtensions,
                enabledExtensions,
                disabledExtensions,
                configPatches);
    }

    public GroupConfig(
            String name,
            String parent,
            String platform,
            String platformVersion,
            String jarFile,
            List<String> templates,
            String scalingMode,
            int minInstances,
            int maxInstances,
            int maxPlayers,
            double scaleUpThreshold,
            int scaleDownAfterSeconds,
            int scaleCooldownSeconds,
            boolean predictiveScaling,
            double scaleUpMargin,
            int burstCeiling,
            String routing,
            int portRangeStart,
            int portRangeEnd,
            int startupTimeoutSeconds,
            int shutdownGraceSeconds,
            boolean drainOnShutdown,
            int maxLifetimeSeconds,
            boolean isStatic,
            List<String> staticInstanceNames,
            List<String> protectedPaths,
            String fallbackGroup,
            boolean defaultGroup,
            List<String> dependsOn,
            int startupWeight,
            boolean maintenance,
            String maintenanceMessage,
            List<String> maintenanceBypass,
            String updateStrategy,
            List<String> nodeAffinity,
            List<String> nodeAntiAffinity,
            String spreadConstraint,
            int priority,
            int memoryMb,
            List<String> jvmArgs,
            Map<String, String> env,
            List<String> motds,
            String motdMode,
            int motdIntervalSeconds,
            List<String> attachedModules,
            List<String> enabledModules,
            List<String> disabledModules,
            List<String> attachedExtensions,
            List<String> enabledExtensions,
            List<String> disabledExtensions,
            Map<String, Map<String, String>> configPatches) {
        this(
                name,
                parent,
                platform,
                platformVersion,
                jarFile,
                templates,
                scalingMode,
                minInstances,
                maxInstances,
                maxPlayers,
                scaleUpThreshold,
                scaleDownAfterSeconds,
                scaleCooldownSeconds,
                predictiveScaling,
                scaleUpMargin,
                burstCeiling,
                routing,
                portRangeStart,
                portRangeEnd,
                startupTimeoutSeconds,
                shutdownGraceSeconds,
                drainOnShutdown,
                maxLifetimeSeconds,
                isStatic,
                staticInstanceNames,
                protectedPaths,
                fallbackGroup,
                defaultGroup,
                dependsOn,
                startupWeight,
                maintenance,
                maintenanceMessage,
                maintenanceBypass,
                updateStrategy,
                nodeAffinity,
                nodeAntiAffinity,
                spreadConstraint,
                priority,
                memoryMb,
                0.0,
                0,
                jvmArgs,
                env,
                motds,
                motdMode,
                motdIntervalSeconds,
                attachedModules,
                enabledModules,
                disabledModules,
                attachedExtensions,
                enabledExtensions,
                disabledExtensions,
                configPatches,
                "");
    }

    public GroupRuntimeTarget runtimeTarget() {
        return new GroupRuntimeTarget(platform, platformVersion, GroupRuntimeFamily.fromPlatform(platform));
    }
}
