package me.prexorjustin.prexorcloud.controller.scheduler.composition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import me.prexorjustin.prexorcloud.api.module.platform.RuntimeTarget;
import me.prexorjustin.prexorcloud.common.logging.CorrelationContext;
import me.prexorjustin.prexorcloud.controller.catalog.CatalogStore;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.group.GroupRuntimeResolver;
import me.prexorjustin.prexorcloud.controller.metrics.MetricsCollector;
import me.prexorjustin.prexorcloud.controller.module.platform.ExtensionRegistry;
import me.prexorjustin.prexorcloud.controller.module.platform.ExtensionRegistryException;
import me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleManager;
import me.prexorjustin.prexorcloud.controller.template.TemplateManager;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves controller-side instance composition inputs before dispatch.
 */
public final class InstanceCompositionPlanner {

    private static final Logger logger = LoggerFactory.getLogger(InstanceCompositionPlanner.class);
    private static final ObjectMapper HASH_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final TemplateManager templateManager;
    private final CatalogStore catalogStore;
    private final PlatformModuleManager platformModuleManager;
    private final MetricsCollector metricsCollector;
    private final BedrockRemoteResolver bedrockRemoteResolver;

    public InstanceCompositionPlanner(
            TemplateManager templateManager, CatalogStore catalogStore, PlatformModuleManager platformModuleManager) {
        this(templateManager, catalogStore, platformModuleManager, null);
    }

    public InstanceCompositionPlanner(
            TemplateManager templateManager,
            CatalogStore catalogStore,
            PlatformModuleManager platformModuleManager,
            MetricsCollector metricsCollector) {
        this(templateManager, catalogStore, platformModuleManager, metricsCollector, null);
    }

    public InstanceCompositionPlanner(
            TemplateManager templateManager,
            CatalogStore catalogStore,
            PlatformModuleManager platformModuleManager,
            MetricsCollector metricsCollector,
            BedrockRemoteResolver bedrockRemoteResolver) {
        this.templateManager = templateManager;
        this.catalogStore = catalogStore;
        this.platformModuleManager = platformModuleManager;
        this.metricsCollector = metricsCollector;
        this.bedrockRemoteResolver = bedrockRemoteResolver;
    }

    public InstanceCompositionPlan plan(
            GroupConfig group, String instanceId, String nodeId, int port, String controllerHttpUrl) {
        try (var ignored = CorrelationContext.open(Map.of(
                "groupName", group.name(),
                "instanceId", instanceId,
                "nodeId", nodeId))) {
            try {
                return planWithContext(group, instanceId, nodeId, port, controllerHttpUrl);
            } catch (RuntimeException e) {
                if (metricsCollector != null) {
                    metricsCollector.recordCompositionPlanningFailure();
                }
                throw e;
            }
        }
    }

    private InstanceCompositionPlan planWithContext(
            GroupConfig group, String instanceId, String nodeId, int port, String controllerHttpUrl) {
        InstanceCompositionPlan.ResolvedRuntime resolvedRuntime = resolveRuntime(group);
        List<InstanceCompositionPlan.ResolvedTemplate> templates = resolveTemplates(group, resolvedRuntime);
        List<InstanceCompositionPlan.ResolvedExtension> extensions =
                resolveExtensions(group, resolvedRuntime, controllerHttpUrl);
        List<InstanceCompositionPlan.ResolvedConfigPatch> configPatches =
                resolveConfigPatches(group, resolvedRuntime, instanceId, port);

        Map<String, String> env = new LinkedHashMap<>(group.env());
        env.put("CLOUD_CONTROLLER_URL", controllerHttpUrl);

        Instant createdAt = Instant.now();
        String planHash = planHash(
                instanceId,
                group.name(),
                nodeId,
                port,
                group.memoryMb(),
                new InstanceCompositionPlan.RuntimeIsolation(group.cpuReservation(), group.diskReservationMb()),
                group.jvmArgs(),
                env,
                group.isStatic(),
                group.protectedPaths(),
                templates,
                resolvedRuntime,
                extensions);
        for (InstanceCompositionPlan.ResolvedConfigPatch configPatch : configPatches) {
            planHash = planHash(planHash, configPatch.file(), configPatch.key(), configPatch.value());
        }

        try (var ignored = CorrelationContext.open("compositionPlanId", planHash)) {
            logger.debug(
                    "Composed instance plan {} with {} templates, {} extensions, and {} config patches",
                    planHash,
                    templates.size(),
                    extensions.size(),
                    configPatches.size());
        }

        return new InstanceCompositionPlan(
                instanceId,
                group.name(),
                nodeId,
                port,
                group.memoryMb(),
                new InstanceCompositionPlan.RuntimeIsolation(group.cpuReservation(), group.diskReservationMb()),
                group.jvmArgs(),
                env,
                group.isStatic(),
                group.protectedPaths(),
                templates,
                resolvedRuntime,
                extensions,
                configPatches,
                planHash,
                createdAt);
    }

    private List<InstanceCompositionPlan.ResolvedTemplate> resolveTemplates(
            GroupConfig group, InstanceCompositionPlan.ResolvedRuntime resolvedRuntime) {
        List<InstanceCompositionPlan.ResolvedTemplate> templates = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        addTemplate(templates, seen, "base", "base");
        addTemplate(templates, seen, "base-" + group.platform().toLowerCase(Locale.ROOT), "platform-base");
        addTemplate(templates, seen, group.name(), "group");
        for (String templateName : group.templates()) {
            addTemplate(templates, seen, templateName, "user");
        }

        return List.copyOf(templates);
    }

    private void addTemplate(
            List<InstanceCompositionPlan.ResolvedTemplate> templates, Set<String> seen, String name, String source) {
        if (!seen.add(name)) {
            return;
        }

        Optional<me.prexorjustin.prexorcloud.controller.template.TemplateConfig> config = templateManager.get(name);
        if (config.isEmpty()) {
            logger.warn("Template '{}' not found during composition planning -- skipping", name);
            return;
        }

        templates.add(
                new InstanceCompositionPlan.ResolvedTemplate(name, config.get().hash(), source));
    }

    private InstanceCompositionPlan.ResolvedRuntime resolveRuntime(GroupConfig group) {
        var runtimeResolution = GroupRuntimeResolver.resolve(group, catalogStore);
        String platform = runtimeResolution.target().platform();
        String platformVersion = runtimeResolution.target().platformVersion();
        String category = defaultIfBlank(runtimeResolution.category(), "SERVER");
        String configFormat = defaultIfBlank(runtimeResolution.configFormat(), "");
        String runtimeTarget = toRuntimeTargetWireValue(category, platform)
                .map(RuntimeTarget::wireValue)
                .orElse("");
        return new InstanceCompositionPlan.ResolvedRuntime(
                group.jarFile(),
                defaultIfBlank(runtimeResolution.downloadUrl(), ""),
                defaultIfBlank(runtimeResolution.sha256(), ""),
                platform,
                defaultIfBlank(platformVersion, ""),
                category,
                configFormat,
                runtimeTarget);
    }

    private List<InstanceCompositionPlan.ResolvedExtension> resolveExtensions(
            GroupConfig group, InstanceCompositionPlan.ResolvedRuntime resolvedRuntime, String controllerHttpUrl) {
        Optional<RuntimeTarget> runtimeTarget =
                toRuntimeTargetWireValue(resolvedRuntime.category(), resolvedRuntime.platform());
        if (runtimeTarget.isEmpty() || resolvedRuntime.platformVersion().isBlank()) {
            return List.of();
        }

        try {
            ExtensionRegistry extensionRegistry = platformModuleManager.extensionRegistry();
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
                throw new IllegalStateException("group '" + group.name() + "' both attaches and disables modules: "
                        + conflictingModulePolicies);
            }
            conflictingModulePolicies = new LinkedHashSet<>(enabledModules);
            conflictingModulePolicies.retainAll(disabledModules);
            if (!conflictingModulePolicies.isEmpty()) {
                throw new IllegalStateException(
                        "group '" + group.name() + "' both enables and disables modules: " + conflictingModulePolicies);
            }
            Set<String> conflictingPolicies = new LinkedHashSet<>(attachedExtensions);
            conflictingPolicies.retainAll(disabledExtensions);
            if (!conflictingPolicies.isEmpty()) {
                throw new IllegalStateException(
                        "group '" + group.name() + "' both attaches and disables extensions: " + conflictingPolicies);
            }
            conflictingPolicies = new LinkedHashSet<>(enabledExtensions);
            conflictingPolicies.retainAll(disabledExtensions);
            if (!conflictingPolicies.isEmpty()) {
                throw new IllegalStateException(
                        "group '" + group.name() + "' both enables and disables extensions: " + conflictingPolicies);
            }
            List<String> extensionIds = new ArrayList<>();
            List<ExtensionRegistry.RegisteredExtension> registeredExtensions =
                    extensionRegistry.listExtensions(runtimeTarget.get()).stream()
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
                                    "group '" + group.name() + "' cannot disable always-on module '" + moduleId
                                            + "' because it contributes always-on extension '" + extensionId + "'");
                        }
                        if (disabled) {
                            throw new IllegalStateException("group '" + group.name()
                                    + "' cannot disable always-on extension '" + extensionId + "'");
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
                throw new IllegalStateException(
                        "group '" + group.name() + "' references unknown or incompatible modules: " + unknownModules);
            }
            if (!attachedExtensions.isEmpty()) {
                throw new IllegalStateException("group '" + group.name()
                        + "' attaches unknown or incompatible extensions: " + attachedExtensions);
            }
            if (!disabledExtensions.isEmpty()) {
                throw new IllegalStateException("group '" + group.name()
                        + "' disables unknown or incompatible extensions: " + disabledExtensions);
            }
            if (hasEnabledAllowlist && !enabledExtensions.isEmpty()) {
                throw new IllegalStateException("group '" + group.name()
                        + "' enables unknown or incompatible extensions: " + enabledExtensions);
            }
            if (extensionIds.isEmpty()) {
                return List.of();
            }

            List<String> orderedExtensionIds =
                    extensionIds.stream().distinct().sorted().toList();
            return extensionRegistry
                    .resolveVariants(orderedExtensionIds, runtimeTarget.get(), resolvedRuntime.platformVersion())
                    .stream()
                    .map(resolved -> new InstanceCompositionPlan.ResolvedExtension(
                            resolved.moduleId(),
                            resolved.extensionId(),
                            resolved.extension().target().wireValue(),
                            resolved.extension().activationPolicy().wireValue(),
                            resolved.variant().id(),
                            resolved.variant().mcVersionRange(),
                            resolved.variant().runtimeApiVersion(),
                            resolved.variant().artifact(),
                            extensionDownloadUrl(
                                    controllerHttpUrl,
                                    resolved.moduleId(),
                                    resolved.variant().artifact()),
                            resolved.variant().sha256(),
                            resolved.variant().installPath()))
                    .toList();
        } catch (ExtensionRegistryException e) {
            throw new IllegalStateException("failed to resolve platform extensions", e);
        }
    }

    private List<InstanceCompositionPlan.ResolvedConfigPatch> resolveConfigPatches(
            GroupConfig group, InstanceCompositionPlan.ResolvedRuntime resolvedRuntime, String instanceId, int port) {
        Map<String, Map<String, String>> merged = new LinkedHashMap<>();
        autoConfigPatches(group, resolvedRuntime, instanceId, port)
                .forEach((file, patches) -> merged.put(file, new LinkedHashMap<>(patches)));
        group.configPatches().forEach((file, patches) -> {
            Map<String, String> mergedFile = new LinkedHashMap<>(merged.getOrDefault(file, Map.of()));
            mergedFile.putAll(patches);
            merged.put(file, mergedFile);
        });

        return merged.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(fileEntry -> fileEntry.getValue().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(patchEntry -> new InstanceCompositionPlan.ResolvedConfigPatch(
                                fileEntry.getKey(), patchEntry.getKey(), patchEntry.getValue())))
                .toList();
    }

    private Map<String, Map<String, String>> autoConfigPatches(
            GroupConfig group, InstanceCompositionPlan.ResolvedRuntime resolvedRuntime, String instanceId, int port) {
        Map<String, Map<String, String>> patches = new LinkedHashMap<>();
        String motd = selectMotd(group, instanceId);
        int maxPlayers = group.maxPlayers() > 0 ? group.maxPlayers() : 100;
        switch (resolvedRuntime.configFormat().toLowerCase(Locale.ROOT)) {
            case "paper", "spigot" -> {
                Map<String, String> serverProperties = new LinkedHashMap<>();
                serverProperties.put("server-port", Integer.toString(port));
                serverProperties.put("max-players", Integer.toString(maxPlayers));
                if (!motd.isBlank()) {
                    serverProperties.put("motd", motd);
                }
                patches.put("server.properties", serverProperties);
            }
            case "velocity" -> {
                Map<String, String> velocityToml = new LinkedHashMap<>();
                velocityToml.put("bind", "0.0.0.0:" + port);
                velocityToml.put("show-max-players", Integer.toString(maxPlayers));
                if (!motd.isBlank()) {
                    velocityToml.put("motd", motd);
                }
                patches.put("velocity.toml", velocityToml);
            }
            case "bungeecord" -> {
                Map<String, String> bungeeConfig = new LinkedHashMap<>();
                bungeeConfig.put("host", "0.0.0.0:" + port);
                bungeeConfig.put("max_players", Integer.toString(maxPlayers));
                if (!motd.isBlank()) {
                    bungeeConfig.put("motd", motd);
                }
                patches.put("config.yml", bungeeConfig);
            }
            case "geyser" -> {
                // The Bedrock listen port comes from %PORT% substitution in the shipped config. The
                // remote (Java proxy) endpoint is resolved dynamically from a live instance of the
                // group's bedrockProxyGroup; if none is running yet the config default is kept.
                Map<String, String> geyserConfig = geyserRemotePatches(group);
                if (!geyserConfig.isEmpty()) {
                    patches.put("config.yml", geyserConfig);
                }
            }
            default -> {}
        }
        return Map.copyOf(patches);
    }

    /**
     * Builds the dynamic Geyser {@code remote.*} patches by resolving a live instance of the group's
     * {@code bedrockProxyGroup}. Returns empty (config default kept) when no proxy group is configured,
     * no resolver is wired, or nothing is running yet. Keys are dotted paths consumed by the daemon's
     * section-aware Geyser patcher.
     */
    private Map<String, String> geyserRemotePatches(GroupConfig group) {
        String proxyGroup = group.bedrockProxyGroup();
        if (proxyGroup == null || proxyGroup.isBlank()) {
            logger.warn("Geyser group {} has no bedrockProxyGroup set; remote stays at config default", group.name());
            return Map.of();
        }
        if (bedrockRemoteResolver == null) {
            return Map.of();
        }
        Optional<BedrockRemoteResolver.Endpoint> endpoint = bedrockRemoteResolver.resolve(proxyGroup);
        if (endpoint.isEmpty()) {
            logger.warn(
                    "Geyser group {} fronts proxy group {} but no running instance was found; "
                            + "remote stays at config default until restart",
                    group.name(),
                    proxyGroup);
            return Map.of();
        }
        Map<String, String> remote = new LinkedHashMap<>();
        remote.put("remote.address", endpoint.get().host());
        remote.put("remote.port", Integer.toString(endpoint.get().port()));
        return remote;
    }

    private static String selectMotd(GroupConfig group, String instanceId) {
        if (!group.motds().isEmpty()) {
            return group.motds().getFirst();
        }
        return "PrexorCloud - " + group.name() + "/" + instanceId;
    }

    private static Optional<RuntimeTarget> toRuntimeTargetWireValue(String category, String platform) {
        if (platform == null || platform.isBlank()) {
            return Optional.empty();
        }

        String workloadType = "PROXY".equalsIgnoreCase(category) ? "proxy" : "server";
        return Optional.of(new RuntimeTarget(workloadType, platform.toLowerCase(Locale.ROOT)));
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String extensionDownloadUrl(String controllerHttpUrl, String moduleId, String artifactPath) {
        String baseUrl = controllerHttpUrl.endsWith("/")
                ? controllerHttpUrl.substring(0, controllerHttpUrl.length() - 1)
                : controllerHttpUrl;
        return baseUrl + "/api/v1/modules/platform/" + encodePathSegment(moduleId) + "/artifacts/"
                + encodePathPreservingSeparators(artifactPath);
    }

    private static String encodePathPreservingSeparators(String value) {
        return java.util.Arrays.stream(value.split("/"))
                .map(InstanceCompositionPlanner::encodePathSegment)
                .collect(Collectors.joining("/"));
    }

    private static String encodePathSegment(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String planHash(Object... components) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object component : components) {
                byte[] bytes = HASH_MAPPER.writeValueAsString(component).getBytes(StandardCharsets.UTF_8);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("failed to compute plan hash", e);
        }
    }
}
