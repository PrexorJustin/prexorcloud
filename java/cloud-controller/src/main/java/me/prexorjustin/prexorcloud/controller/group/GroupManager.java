package me.prexorjustin.prexorcloud.controller.group;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import me.prexorjustin.prexorcloud.controller.template.TemplateConfig;
import me.prexorjustin.prexorcloud.controller.template.TemplateManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory CRUD for server groups with validation. Groups reference ordered
 * template layers and support parent inheritance.
 */
public final class GroupManager {

    private static final Logger logger = LoggerFactory.getLogger(GroupManager.class);
    private static final int MAX_INHERITANCE_DEPTH = 10;
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;
    private static final Set<String> SCALING_MODES = Set.of("DYNAMIC", "STATIC", "MANUAL");

    private final Map<String, GroupConfig> groups = new ConcurrentHashMap<>();
    private final TemplateManager templateManager;
    private volatile GroupStore groupStore;

    public GroupManager(TemplateManager templateManager) {
        this.templateManager = templateManager;
    }

    public void setGroupStore(GroupStore groupStore) {
        this.groupStore = groupStore;
    }

    public void create(GroupConfig config) {
        validate(config);
        if (groups.containsKey(config.name())) {
            throw new IllegalArgumentException("Group already exists: " + config.name());
        }
        ensureGroupTemplate(config.name(), config.platform());
        groups.put(config.name(), config);
        logger.debug(
                "Group created: {} (scalingMode={}, templates={}, min={}, max={})",
                config.name(),
                config.scalingMode(),
                config.templates(),
                config.minInstances(),
                config.maxInstances());
    }

    public void update(GroupConfig config) {
        validate(config);
        if (!groups.containsKey(config.name())) {
            throw new IllegalArgumentException("Group not found: " + config.name());
        }
        groups.put(config.name(), config);
        logger.info("Group updated: {}", config.name());
    }

    public GroupConfig patch(String name, GroupConfig update, Set<String> sentFields) {
        GroupConfig existing = groups.get(name);
        if (existing == null) {
            throw new IllegalArgumentException("Group not found: " + name);
        }
        GroupConfig merged = mergePatch(name, existing, update, sentFields);
        validate(merged);
        groups.put(name, merged);
        logger.info("Group updated: {}", name);
        return merged;
    }

    public void delete(String name) {
        // Check if any other group references this group as parent or in dependsOn
        var dependents = groups.values().stream()
                .filter(g -> !g.name().equals(name))
                .filter(g -> name.equals(g.parent()) || g.dependsOn().contains(name))
                .map(GroupConfig::name)
                .toList();

        if (!dependents.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot delete group '" + name + "': referenced by " + String.join(", ", dependents));
        }

        if (groups.remove(name) != null) {
            logger.info("Group deleted: {}", name);
        }
    }

    public Optional<GroupConfig> get(String name) {
        return Optional.ofNullable(groups.get(name));
    }

    public Collection<GroupConfig> getAll() {
        return Collections.unmodifiableCollection(groups.values());
    }

    public boolean exists(String name) {
        return groups.containsKey(name);
    }

    /**
     * Resolve a group config with parent inheritance applied. Child values override
     * parent. List fields are replaced, map fields are merged (child wins).
     */
    public GroupConfig resolveGroup(String name) {
        var chain = new ArrayList<GroupConfig>();
        var visited = new HashSet<String>();
        String current = name;

        while (current != null) {
            if (!visited.add(current)) {
                throw new IllegalStateException("Circular group inheritance detected at: " + current);
            }
            if (visited.size() > MAX_INHERITANCE_DEPTH) {
                throw new IllegalStateException(
                        "Group inheritance depth exceeds maximum of " + MAX_INHERITANCE_DEPTH + " at: " + current);
            }
            GroupConfig config = groups.get(current);
            if (config == null) {
                throw new IllegalArgumentException("Group not found: " + current);
            }
            chain.addFirst(config);
            current = config.parent();
        }

        if (chain.size() == 1) return chain.getFirst();

        // Merge from root to leaf
        GroupConfig resolved = chain.getFirst();
        for (int i = 1; i < chain.size(); i++) {
            resolved = merge(resolved, chain.get(i));
        }
        return resolved;
    }

    private static GroupConfig merge(GroupConfig parent, GroupConfig child) {
        // Child overrides parent for scalar fields; list fields replaced; map fields
        // merged
        var mergedEnv = new HashMap<>(parent.env());
        mergedEnv.putAll(child.env());
        var mergedVariableValues = new HashMap<>(parent.variableValues());
        mergedVariableValues.putAll(child.variableValues());
        var mergedConfigPatches = new LinkedHashMap<String, Map<String, String>>();
        parent.configPatches().forEach((file, patches) -> mergedConfigPatches.put(file, new LinkedHashMap<>(patches)));
        child.configPatches().forEach((file, patches) -> {
            var mergedFilePatches = new LinkedHashMap<>(mergedConfigPatches.getOrDefault(file, Map.of()));
            mergedFilePatches.putAll(patches);
            mergedConfigPatches.put(file, mergedFilePatches);
        });

        return new GroupConfig(
                child.name(),
                child.parent(),
                child.platform().isEmpty() ? parent.platform() : child.platform(),
                child.platformVersion().isEmpty() ? parent.platformVersion() : child.platformVersion(),
                child.jarFile().equals("server.jar") && !parent.jarFile().equals("server.jar")
                        ? parent.jarFile()
                        : child.jarFile(),
                child.templates().isEmpty() ? parent.templates() : child.templates(),
                child.scalingMode(),
                child.minInstances(),
                child.maxInstances(),
                child.maxPlayers(),
                child.scaleUpThreshold(),
                child.scaleDownAfterSeconds(),
                child.scaleCooldownSeconds(),
                child.predictiveScaling(),
                child.scaleUpMargin(),
                child.burstCeiling(),
                child.routing(),
                child.portRangeStart(),
                child.portRangeEnd(),
                child.startupTimeoutSeconds(),
                child.shutdownGraceSeconds(),
                child.drainOnShutdown(),
                child.maxLifetimeSeconds(),
                child.isStatic(),
                child.staticInstanceNames().isEmpty() ? parent.staticInstanceNames() : child.staticInstanceNames(),
                child.protectedPaths().isEmpty() ? parent.protectedPaths() : child.protectedPaths(),
                child.fallbackGroup(),
                child.defaultGroup(),
                child.dependsOn().isEmpty() ? parent.dependsOn() : child.dependsOn(),
                child.startupWeight(),
                child.maintenance(),
                child.maintenanceMessage().isEmpty() ? parent.maintenanceMessage() : child.maintenanceMessage(),
                child.maintenanceBypass().isEmpty() ? parent.maintenanceBypass() : child.maintenanceBypass(),
                child.updateStrategy(),
                child.nodeAffinity().isEmpty() ? parent.nodeAffinity() : child.nodeAffinity(),
                child.nodeAntiAffinity().isEmpty() ? parent.nodeAntiAffinity() : child.nodeAntiAffinity(),
                child.spreadConstraint().isEmpty() ? parent.spreadConstraint() : child.spreadConstraint(),
                child.priority(),
                child.memoryMb(),
                child.cpuReservation() <= 0 ? parent.cpuReservation() : child.cpuReservation(),
                child.diskReservationMb() <= 0 ? parent.diskReservationMb() : child.diskReservationMb(),
                child.jvmArgs().isEmpty() ? parent.jvmArgs() : child.jvmArgs(),
                Map.copyOf(mergedEnv),
                child.motds().isEmpty() ? parent.motds() : child.motds(),
                child.motdMode(),
                child.motdIntervalSeconds(),
                child.attachedModules().isEmpty() ? parent.attachedModules() : child.attachedModules(),
                child.enabledModules().isEmpty() ? parent.enabledModules() : child.enabledModules(),
                child.disabledModules().isEmpty() ? parent.disabledModules() : child.disabledModules(),
                child.attachedExtensions().isEmpty() ? parent.attachedExtensions() : child.attachedExtensions(),
                child.enabledExtensions().isEmpty() ? parent.enabledExtensions() : child.enabledExtensions(),
                child.disabledExtensions().isEmpty() ? parent.disabledExtensions() : child.disabledExtensions(),
                Map.copyOf(mergedConfigPatches),
                child.bedrockProxyGroup().isEmpty() ? parent.bedrockProxyGroup() : child.bedrockProxyGroup(),
                child.warmPoolMinPrepared(),
                Map.copyOf(mergedVariableValues));
    }

    private static GroupConfig mergePatch(
            String name, GroupConfig existing, GroupConfig update, Set<String> sentFields) {
        var mergedEnv = new HashMap<>(existing.env());
        if (sentFields.contains("env") && !update.env().isEmpty()) {
            mergedEnv.putAll(update.env());
        }
        var mergedVariableValues = new HashMap<>(existing.variableValues());
        if (sentFields.contains("variableValues") && !update.variableValues().isEmpty()) {
            mergedVariableValues.putAll(update.variableValues());
        }

        return new GroupConfig(
                name,
                sentFields.contains("parent") ? update.parent() : existing.parent(),
                sentFields.contains("platform") ? update.platform() : existing.platform(),
                sentFields.contains("platformVersion") ? update.platformVersion() : existing.platformVersion(),
                sentFields.contains("jarFile") ? update.jarFile() : existing.jarFile(),
                sentFields.contains("templates") ? update.templates() : existing.templates(),
                sentFields.contains("scalingMode") ? update.scalingMode() : existing.scalingMode(),
                sentFields.contains("minInstances") ? update.minInstances() : existing.minInstances(),
                sentFields.contains("maxInstances") ? update.maxInstances() : existing.maxInstances(),
                sentFields.contains("maxPlayers") ? update.maxPlayers() : existing.maxPlayers(),
                sentFields.contains("scaleUpThreshold") ? update.scaleUpThreshold() : existing.scaleUpThreshold(),
                sentFields.contains("scaleDownAfterSeconds")
                        ? update.scaleDownAfterSeconds()
                        : existing.scaleDownAfterSeconds(),
                sentFields.contains("scaleCooldownSeconds")
                        ? update.scaleCooldownSeconds()
                        : existing.scaleCooldownSeconds(),
                sentFields.contains("predictiveScaling") ? update.predictiveScaling() : existing.predictiveScaling(),
                sentFields.contains("scaleUpMargin") ? update.scaleUpMargin() : existing.scaleUpMargin(),
                sentFields.contains("burstCeiling") ? update.burstCeiling() : existing.burstCeiling(),
                sentFields.contains("routing") ? update.routing() : existing.routing(),
                sentFields.contains("portRangeStart") ? update.portRangeStart() : existing.portRangeStart(),
                sentFields.contains("portRangeEnd") ? update.portRangeEnd() : existing.portRangeEnd(),
                sentFields.contains("startupTimeoutSeconds")
                        ? update.startupTimeoutSeconds()
                        : existing.startupTimeoutSeconds(),
                sentFields.contains("shutdownGraceSeconds")
                        ? update.shutdownGraceSeconds()
                        : existing.shutdownGraceSeconds(),
                sentFields.contains("drainOnShutdown") ? update.drainOnShutdown() : existing.drainOnShutdown(),
                sentFields.contains("maxLifetimeSeconds") ? update.maxLifetimeSeconds() : existing.maxLifetimeSeconds(),
                sentFields.contains("static") ? update.isStatic() : existing.isStatic(),
                sentFields.contains("staticInstanceNames")
                        ? update.staticInstanceNames()
                        : existing.staticInstanceNames(),
                sentFields.contains("protectedPaths") ? update.protectedPaths() : existing.protectedPaths(),
                sentFields.contains("fallbackGroup") ? update.fallbackGroup() : existing.fallbackGroup(),
                sentFields.contains("defaultGroup") ? update.defaultGroup() : existing.defaultGroup(),
                sentFields.contains("dependsOn") ? update.dependsOn() : existing.dependsOn(),
                sentFields.contains("startupWeight") ? update.startupWeight() : existing.startupWeight(),
                sentFields.contains("maintenance") ? update.maintenance() : existing.maintenance(),
                sentFields.contains("maintenanceMessage") ? update.maintenanceMessage() : existing.maintenanceMessage(),
                sentFields.contains("maintenanceBypass") ? update.maintenanceBypass() : existing.maintenanceBypass(),
                sentFields.contains("updateStrategy") ? update.updateStrategy() : existing.updateStrategy(),
                sentFields.contains("nodeAffinity") ? update.nodeAffinity() : existing.nodeAffinity(),
                sentFields.contains("nodeAntiAffinity") ? update.nodeAntiAffinity() : existing.nodeAntiAffinity(),
                sentFields.contains("spreadConstraint") ? update.spreadConstraint() : existing.spreadConstraint(),
                sentFields.contains("priority") ? update.priority() : existing.priority(),
                sentFields.contains("memoryMb") ? update.memoryMb() : existing.memoryMb(),
                sentFields.contains("cpuReservation") ? update.cpuReservation() : existing.cpuReservation(),
                sentFields.contains("diskReservationMb") ? update.diskReservationMb() : existing.diskReservationMb(),
                sentFields.contains("jvmArgs") ? update.jvmArgs() : existing.jvmArgs(),
                Map.copyOf(mergedEnv),
                sentFields.contains("motds") ? update.motds() : existing.motds(),
                sentFields.contains("motdMode") ? update.motdMode() : existing.motdMode(),
                sentFields.contains("motdIntervalSeconds")
                        ? update.motdIntervalSeconds()
                        : existing.motdIntervalSeconds(),
                sentFields.contains("attachedModules") ? update.attachedModules() : existing.attachedModules(),
                sentFields.contains("enabledModules") ? update.enabledModules() : existing.enabledModules(),
                sentFields.contains("disabledModules") ? update.disabledModules() : existing.disabledModules(),
                sentFields.contains("attachedExtensions") ? update.attachedExtensions() : existing.attachedExtensions(),
                sentFields.contains("enabledExtensions") ? update.enabledExtensions() : existing.enabledExtensions(),
                sentFields.contains("disabledExtensions") ? update.disabledExtensions() : existing.disabledExtensions(),
                sentFields.contains("configPatches") ? update.configPatches() : existing.configPatches(),
                sentFields.contains("bedrockProxyGroup") ? update.bedrockProxyGroup() : existing.bedrockProxyGroup(),
                sentFields.contains("warmPoolMinPrepared")
                        ? update.warmPoolMinPrepared()
                        : existing.warmPoolMinPrepared(),
                Map.copyOf(mergedVariableValues));
    }

    private void validate(GroupConfig config) {
        if (config.name() == null || config.name().isBlank()) {
            throw new IllegalArgumentException("Group name cannot be blank");
        }
        if (config.name().length() > 32) {
            throw new IllegalArgumentException("Group name too long (max 32): " + config.name());
        }
        if (!config.name().matches("[a-z0-9_][a-z0-9_-]*")) {
            throw new IllegalArgumentException(
                    "Invalid group name: " + config.name() + " (must match [a-z0-9_][a-z0-9_-]*, _ prefix = abstract)");
        }

        // Reject auto-injected template names in the user's templates list
        for (String template : config.templates()) {
            if (isReservedTemplateName(template)) {
                throw new IllegalArgumentException(
                        "Template '" + template + "' is auto-injected and must not be listed explicitly");
            }
            if (template.equals(config.name())) {
                throw new IllegalArgumentException(
                        "Group template '" + template + "' is auto-injected and must not be listed explicitly");
            }
            if (!templateManager.exists(template)) {
                throw new IllegalArgumentException("Template not found: " + template);
            }
        }

        // Validate parent group
        if (config.parent() != null && !config.parent().isBlank()) {
            if (!groups.containsKey(config.parent())) {
                throw new IllegalArgumentException("Parent group not found: " + config.parent());
            }
            // Check for cycles
            var visited = new HashSet<String>();
            visited.add(config.name());
            String current = config.parent();
            while (current != null) {
                if (!visited.add(current)) {
                    throw new IllegalArgumentException(
                            "Circular group inheritance: " + config.name() + " -> ... -> " + current);
                }
                GroupConfig p = groups.get(current);
                current = p != null ? p.parent() : null;
            }
            validateRuntimeTargetConsistency(config);
        }

        // Validate Bedrock proxy target -- a Geyser group forwards to a live instance of this group,
        // so it must reference an existing (proxy) group, and not itself.
        if (!config.bedrockProxyGroup().isBlank()) {
            if (config.bedrockProxyGroup().equals(config.name())) {
                throw new IllegalArgumentException("bedrockProxyGroup cannot reference the group itself");
            }
            if (!groups.containsKey(config.bedrockProxyGroup())) {
                throw new IllegalArgumentException("bedrockProxyGroup not found: " + config.bedrockProxyGroup());
            }
        }

        // Validate dependsOn -- no self-dependency or circular dependencies
        if (!config.dependsOn().isEmpty()) {
            for (String dep : config.dependsOn()) {
                if (dep.equals(config.name())) {
                    throw new IllegalArgumentException("Group cannot depend on itself: " + config.name());
                }
            }
            // Check for circular dependencies via DFS
            Set<String> visiting = new HashSet<>();
            visiting.add(config.name());
            for (String dep : config.dependsOn()) {
                detectCircularDependency(dep, visiting, config.name());
            }
        }

        // Validate fallbackGroup exists
        if (config.fallbackGroup() != null && !config.fallbackGroup().isBlank()) {
            if (!groups.containsKey(config.fallbackGroup())
                    && !config.fallbackGroup().equals(config.name())) {
                throw new IllegalArgumentException("Fallback group not found: " + config.fallbackGroup());
            }
        }

        if (config.minInstances() > config.maxInstances()) {
            throw new IllegalArgumentException("minInstances (" + config.minInstances()
                    + ") cannot exceed maxInstances (" + config.maxInstances() + ")");
        }
        if (config.cpuReservation() > 1.0) {
            throw new IllegalArgumentException("cpuReservation must be between 0.0 and 1.0");
        }
        validateScalingPolicy(config);
        validatePlacementPolicy(config);
        if (config.portRangeStart() > config.portRangeEnd()) {
            throw new IllegalArgumentException("portRangeStart (" + config.portRangeStart()
                    + ") cannot exceed portRangeEnd (" + config.portRangeEnd() + ")");
        }
        validateDisabledFields(config);
        for (String extensionId : config.attachedExtensions()) {
            if (extensionId == null || extensionId.isBlank()) {
                throw new IllegalArgumentException("attachedExtensions must not contain blank values");
            }
        }
        for (String moduleId : config.attachedModules()) {
            if (moduleId == null || moduleId.isBlank()) {
                throw new IllegalArgumentException("attachedModules must not contain blank values");
            }
            if (config.disabledModules().contains(moduleId)) {
                throw new IllegalArgumentException("module '" + moduleId + "' cannot be both attached and disabled");
            }
        }
        for (String moduleId : config.enabledModules()) {
            if (moduleId == null || moduleId.isBlank()) {
                throw new IllegalArgumentException("enabledModules must not contain blank values");
            }
            if (config.disabledModules().contains(moduleId)) {
                throw new IllegalArgumentException("module '" + moduleId + "' cannot be both enabled and disabled");
            }
        }
        for (String moduleId : config.disabledModules()) {
            if (moduleId == null || moduleId.isBlank()) {
                throw new IllegalArgumentException("disabledModules must not contain blank values");
            }
        }
        for (String extensionId : config.enabledExtensions()) {
            if (extensionId == null || extensionId.isBlank()) {
                throw new IllegalArgumentException("enabledExtensions must not contain blank values");
            }
            if (config.disabledExtensions().contains(extensionId)) {
                throw new IllegalArgumentException(
                        "extension '" + extensionId + "' cannot be both enabled and disabled");
            }
        }
        for (String extensionId : config.disabledExtensions()) {
            if (extensionId == null || extensionId.isBlank()) {
                throw new IllegalArgumentException("disabledExtensions must not contain blank values");
            }
            if (config.attachedExtensions().contains(extensionId)) {
                throw new IllegalArgumentException(
                        "extension '" + extensionId + "' cannot be both attached and disabled");
            }
        }
        for (var fileEntry : config.configPatches().entrySet()) {
            if (fileEntry.getKey() == null || fileEntry.getKey().isBlank()) {
                throw new IllegalArgumentException("configPatches must not contain blank file names");
            }
            for (var patchEntry : fileEntry.getValue().entrySet()) {
                if (patchEntry.getKey() == null || patchEntry.getKey().isBlank()) {
                    throw new IllegalArgumentException(
                            "configPatches for '" + fileEntry.getKey() + "' must not contain blank keys");
                }
            }
        }
    }

    private static void validateScalingPolicy(GroupConfig config) {
        if (!SCALING_MODES.contains(config.scalingMode())) {
            throw new IllegalArgumentException(
                    "Unsupported scalingMode '" + config.scalingMode() + "' (expected DYNAMIC, STATIC, or MANUAL)");
        }
        if (config.scaleUpThreshold() <= 0 || config.scaleUpThreshold() > 1) {
            throw new IllegalArgumentException(
                    "scaleUpThreshold must be in the range (0, 1], got " + config.scaleUpThreshold());
        }
    }

    private static void validatePlacementPolicy(GroupConfig config) {
        if (config.portRangeStart() < MIN_PORT || config.portRangeEnd() > MAX_PORT) {
            throw new IllegalArgumentException(
                    "portRangeStart/portRangeEnd must be valid TCP ports in the range " + MIN_PORT + "-" + MAX_PORT);
        }
        validateNodeLabelConstraints("nodeAffinity", config.nodeAffinity());
        validateNodeLabelConstraints("nodeAntiAffinity", config.nodeAntiAffinity());
        for (String constraint : config.nodeAffinity()) {
            if (config.nodeAntiAffinity().contains(constraint)) {
                throw new IllegalArgumentException(
                        "Placement constraint '" + constraint + "' cannot be both nodeAffinity and nodeAntiAffinity");
            }
        }
    }

    private static void validateNodeLabelConstraints(String field, List<String> constraints) {
        for (String constraint : constraints) {
            if (constraint == null || constraint.isBlank()) {
                throw new IllegalArgumentException(field + " must not contain blank constraints");
            }
            int eq = constraint.indexOf('=');
            if (eq == 0 || eq == constraint.length() - 1) {
                throw new IllegalArgumentException(
                        field + " constraint '" + constraint + "' must be either a label key or key=value");
            }
        }
    }

    private static void validateDisabledFields(GroupConfig config) {
        // predictiveScaling, scaleUpMargin, burstCeiling, routing, drainOnShutdown
        // were dropped from the public contract under Phase 45 (M0). Any value
        // present on legacy YAML/JSON inputs is silently ignored by Jackson.
        if (config.priority() < 0) {
            throw new IllegalArgumentException("priority must be >= 0");
        }
        validateSpreadConstraint(config.spreadConstraint());
    }

    private static void validateSpreadConstraint(String spreadConstraint) {
        if (spreadConstraint == null || spreadConstraint.isBlank()) {
            return;
        }
        // Format: "<labelKey>" or "<labelKey>=<value>". Reuses node-label semantics
        // used elsewhere by nodeAffinity / nodeAntiAffinity.
        int eq = spreadConstraint.indexOf('=');
        if (eq == 0 || eq == spreadConstraint.length() - 1) {
            throw new IllegalArgumentException(
                    "spreadConstraint '" + spreadConstraint + "' must be either a label key or key=value");
        }
    }

    private static boolean isReservedTemplateName(String name) {
        return name.equals("base") || name.startsWith("base-");
    }

    private void validateRuntimeTargetConsistency(GroupConfig config) {
        GroupRuntimeTarget childTarget = config.runtimeTarget();
        if (childTarget.family() == GroupRuntimeFamily.UNKNOWN) {
            return;
        }

        String current = config.parent();
        while (current != null && !current.isBlank()) {
            GroupConfig parent = groups.get(current);
            if (parent == null) {
                return;
            }

            GroupRuntimeTarget parentTarget = parent.runtimeTarget();
            if (parentTarget.family() != GroupRuntimeFamily.UNKNOWN && parentTarget.family() != childTarget.family()) {
                throw new IllegalArgumentException("Group '" + config.name() + "' resolves to runtime family "
                        + childTarget.family()
                        + " but parent chain group '"
                        + parent.name()
                        + "' resolves to "
                        + parentTarget.family());
            }
            current = parent.parent();
        }
    }

    /**
     * Auto-create an empty template named after the group so users have a dedicated
     * layer for group-specific files (plugins, configs, etc.).
     */
    private void ensureGroupTemplate(String groupName, String platform) {
        if (templateManager.exists(groupName)) return;
        try {
            templateManager.save(
                    new TemplateConfig(groupName, "Group template for " + groupName, platform.toLowerCase(), "", 0));
            logger.info("Auto-created template for group: {}", groupName);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create template for group: " + groupName, e);
        }
    }

    private void detectCircularDependency(String groupName, Set<String> visiting, String origin) {
        if (visiting.contains(groupName)) {
            throw new IllegalArgumentException("Circular dependency detected: " + origin + " -> ... -> " + groupName);
        }
        GroupConfig group = groups.get(groupName);
        if (group == null) return;
        visiting.add(groupName);
        for (String dep : group.dependsOn()) {
            detectCircularDependency(dep, visiting, origin);
        }
        visiting.remove(groupName);
    }

    /**
     * Reload a group from the backing store (used by remote event handlers).
     * If the store is not configured or the group is not found, this is a no-op.
     */
    public void reloadGroup(String groupName) {
        if (groupStore == null) return;
        try {
            for (var config : groupStore.loadAll()) {
                if (config.name().equals(groupName)) {
                    groups.put(groupName, config);
                    logger.debug("Reloaded group from store: {}", groupName);
                    return;
                }
            }
            // Group not found in store — might have been renamed; remove from cache
            groups.remove(groupName);
        } catch (Exception e) {
            logger.warn("Failed to reload group {}: {}", groupName, e.getMessage());
        }
    }

    /**
     * Remove a group from the in-memory cache (used by remote delete events).
     */
    public void removeGroupFromCache(String groupName) {
        groups.remove(groupName);
        logger.debug("Removed group from cache: {}", groupName);
    }
}
