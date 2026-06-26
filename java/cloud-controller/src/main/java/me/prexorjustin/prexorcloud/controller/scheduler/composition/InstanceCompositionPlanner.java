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
import me.prexorjustin.prexorcloud.controller.group.spec.ConfigRule;
import me.prexorjustin.prexorcloud.controller.group.spec.ConfigRuleResolver;
import me.prexorjustin.prexorcloud.controller.group.spec.PlatformConfigDefaults;
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

    /**
     * Supplies the cluster's controller REST seed URLs (e.g. {@code http://10.0.0.3:8080}) injected as
     * {@code CLOUD_CONTROLLER_SEEDS} so the in-server plugin can follow the leader across a failover.
     * Evaluated per-plan (membership is dynamic). Defaults to empty — single-controller behaviour — until
     * bootstrap wires the cluster membership view via {@link #setControllerSeedSupplier}.
     */
    private volatile java.util.function.Supplier<List<String>> controllerSeedSupplier = () -> List.of();

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

    /**
     * Wires the supplier of controller REST seed URLs injected as {@code CLOUD_CONTROLLER_SEEDS}.
     * Null is ignored (the empty default stays). See {@link #controllerSeedSupplier}.
     */
    public void setControllerSeedSupplier(java.util.function.Supplier<List<String>> supplier) {
        if (supplier != null) {
            this.controllerSeedSupplier = supplier;
        }
    }

    public InstanceCompositionPlan plan(
            GroupConfig group,
            String instanceId,
            String nodeId,
            int port,
            String controllerHttpUrl,
            Map<String, String> variableOverrides) {
        try (var ignored = CorrelationContext.open(Map.of(
                "groupName", group.name(),
                "instanceId", instanceId,
                "nodeId", nodeId))) {
            try {
                return planWithContext(group, instanceId, nodeId, port, controllerHttpUrl, variableOverrides);
            } catch (RuntimeException e) {
                if (metricsCollector != null) {
                    metricsCollector.recordCompositionPlanningFailure();
                }
                throw e;
            }
        }
    }

    private InstanceCompositionPlan planWithContext(
            GroupConfig group,
            String instanceId,
            String nodeId,
            int port,
            String controllerHttpUrl,
            Map<String, String> variableOverrides) {
        InstanceCompositionPlan.ResolvedRuntime resolvedRuntime = resolveRuntime(group);
        List<InstanceCompositionPlan.ResolvedTemplate> templates = resolveTemplates(group, resolvedRuntime);
        List<InstanceCompositionPlan.ResolvedExtension> extensions =
                resolveExtensions(group, resolvedRuntime, controllerHttpUrl);
        List<InstanceCompositionPlan.ResolvedConfigPatch> configPatches =
                resolveConfigPatches(group, resolvedRuntime);

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
            planHash = planHash(
                    planHash, configPatch.op(), configPatch.file(), configPatch.path(), configPatch.value());
        }

        // Controller REST seed list for the in-server plugin's leader-following client. Injected AFTER
        // planHash on purpose: the seed list tracks live controller membership, which must NOT feed the
        // plan hash (a controller joining/leaving would otherwise change every running instance's hash and
        // churn re-composition). The seed list is reach-the-controller metadata, not composed identity.
        // A resolution failure must never block placement — fall back to the single CLOUD_CONTROLLER_URL.
        try {
            List<String> controllerSeeds = controllerSeedSupplier.get();
            if (controllerSeeds != null && !controllerSeeds.isEmpty()) {
                env.put("CLOUD_CONTROLLER_SEEDS", String.join(",", controllerSeeds));
            }
        } catch (RuntimeException e) {
            logger.warn(
                    "Failed to resolve controller seed list for {} -- plugin falls back to single controller: {}",
                    instanceId,
                    e.getMessage());
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
                variableOverrides,
                planHash,
                createdAt);
    }

    private List<InstanceCompositionPlan.ResolvedTemplate> resolveTemplates(
            GroupConfig group, InstanceCompositionPlan.ResolvedRuntime resolvedRuntime) {
        List<InstanceCompositionPlan.ResolvedTemplate> templates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : templateChainNames(group)) {
            addTemplate(templates, seen, name, sourceFor(name, group));
        }
        return List.copyOf(templates);
    }

    /**
     * The ordered, de-duplicated template chain a group composes from: the shared {@code base}, the
     * platform base {@code base-<platform>}, the group's own template, then any user templates. The
     * single source of truth for "which templates back a group" — used both for composition here and
     * for resolving a group's typed variable definitions (so the two never drift apart).
     */
    public static List<String> templateChainNames(GroupConfig group) {
        Set<String> names = new LinkedHashSet<>();
        names.add("base");
        names.add("base-" + group.platform().toLowerCase(Locale.ROOT));
        names.add(group.name());
        names.addAll(group.templates());
        return List.copyOf(names);
    }

    private static String sourceFor(String name, GroupConfig group) {
        if (name.equals("base")) return "base";
        if (name.equals("base-" + group.platform().toLowerCase(Locale.ROOT))) return "platform-base";
        if (name.equals(group.name())) return "group";
        return "user";
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

    /**
     * Resolve the instance's ordered, data-driven config-rule set into wire patches. The rule chain is
     * the platform's {@link PlatformConfigDefaults base-platform defaults} plus any dynamic Geyser remote
     * rules, collapsed against the group's {@code configPatches} (the highest-precedence SET layer) by
     * {@link ConfigRuleResolver}. Per-instance scalars (port, max-players, MOTD) are intentionally absent
     * here -- they ride the shipped files' {@code %VAR%} placeholders, substituted on the daemon.
     */
    private List<InstanceCompositionPlan.ResolvedConfigPatch> resolveConfigPatches(
            GroupConfig group, InstanceCompositionPlan.ResolvedRuntime resolvedRuntime) {
        List<ConfigRule> chain =
                new ArrayList<>(PlatformConfigDefaults.forConfigFormat(resolvedRuntime.configFormat()));
        chain.addAll(geyserRemoteRules(group, resolvedRuntime));
        return ConfigRuleResolver.resolve(chain, group.configPatches()).stream()
                .map(rule -> new InstanceCompositionPlan.ResolvedConfigPatch(
                        rule.file(), rule.path(), rule.op(), rule.value()))
                .toList();
    }

    /**
     * The dynamic Geyser {@code remote.*} rules, resolved from a live instance of the group's
     * {@code bedrockProxyGroup}. Empty (config default kept) when this is not a Geyser instance, no proxy
     * group is configured, no resolver is wired, or nothing is running yet. {@code REPLACE} so an absent
     * key is left at its shipped default rather than appended at the wrong nesting.
     */
    private List<ConfigRule> geyserRemoteRules(
            GroupConfig group, InstanceCompositionPlan.ResolvedRuntime resolvedRuntime) {
        if (!"geyser".equalsIgnoreCase(resolvedRuntime.configFormat())) {
            return List.of();
        }
        String proxyGroup = group.bedrockProxyGroup();
        if (proxyGroup == null || proxyGroup.isBlank()) {
            logger.warn("Geyser group {} has no bedrockProxyGroup set; remote stays at config default", group.name());
            return List.of();
        }
        if (bedrockRemoteResolver == null) {
            return List.of();
        }
        Optional<BedrockRemoteResolver.Endpoint> endpoint = bedrockRemoteResolver.resolve(proxyGroup);
        if (endpoint.isEmpty()) {
            logger.warn(
                    "Geyser group {} fronts proxy group {} but no running instance was found; "
                            + "remote stays at config default until restart",
                    group.name(),
                    proxyGroup);
            return List.of();
        }
        return List.of(
                new ConfigRule(
                        "config.yml",
                        ConfigRule.Format.YAML,
                        "remote.address",
                        ConfigRule.Op.REPLACE,
                        endpoint.get().host()),
                new ConfigRule(
                        "config.yml",
                        ConfigRule.Format.YAML,
                        "remote.port",
                        ConfigRule.Op.REPLACE,
                        Integer.toString(endpoint.get().port())));
    }

    /**
     * The MOTD for an instance: the group's first configured MOTD, else a default naming the group and
     * instance. Injected as the {@code %MOTD%} variable (see {@code InstancePlacementCoordinator}) so it
     * flows through the same placeholder-substitution path as {@code %PORT%}/{@code %MAX_PLAYERS%}.
     */
    public static String selectMotd(GroupConfig group, String instanceId) {
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
