package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.HashMap;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.catalog.CatalogStore;
import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.group.GroupRuntimeResolver;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;

public final class GroupDtoMapper {

    private GroupDtoMapper() {}

    public static Map<String, Object> toDto(GroupConfig group, ClusterState clusterState) {
        return toDto(group, clusterState, null);
    }

    public static Map<String, Object> toDto(GroupConfig group, ClusterState clusterState, CatalogStore catalogStore) {
        var instances = clusterState.getInstancesByGroup(group.name());
        int totalPlayers =
                instances.stream().mapToInt(InstanceInfo::playerCount).sum();
        var runtimeResolution = GroupRuntimeResolver.resolve(group, catalogStore);

        var dto = new HashMap<String, Object>();
        dto.put("name", group.name());
        dto.put("parent", group.parent());
        dto.put("platform", group.platform());
        dto.put("platformVersion", group.platformVersion());
        dto.put(
                "runtimeTarget",
                Map.of(
                        "platform", runtimeResolution.target().platform(),
                        "platformVersion", runtimeResolution.target().platformVersion(),
                        "family", runtimeResolution.target().family().name()));
        dto.put("jarFile", group.jarFile());
        dto.put("templates", group.templates());
        dto.put("scalingMode", group.scalingMode());
        dto.put("minInstances", group.minInstances());
        dto.put("maxInstances", group.maxInstances());
        dto.put("maxPlayers", group.maxPlayers());
        dto.put("scaleUpThreshold", group.scaleUpThreshold());
        dto.put("scaleDownAfterSeconds", group.scaleDownAfterSeconds());
        dto.put("scaleCooldownSeconds", group.scaleCooldownSeconds());
        dto.put("portRangeStart", group.portRangeStart());
        dto.put("portRangeEnd", group.portRangeEnd());
        dto.put("startupTimeoutSeconds", group.startupTimeoutSeconds());
        dto.put("shutdownGraceSeconds", group.shutdownGraceSeconds());
        dto.put("maxLifetimeSeconds", group.maxLifetimeSeconds());
        dto.put("static", group.isStatic());
        dto.put("staticInstanceNames", group.staticInstanceNames());
        dto.put("protectedPaths", group.protectedPaths());
        dto.put("fallbackGroup", group.fallbackGroup());
        dto.put("defaultGroup", group.defaultGroup());
        dto.put("dependsOn", group.dependsOn());
        dto.put("startupWeight", group.startupWeight());
        dto.put("maintenance", group.maintenance());
        dto.put("maintenanceMessage", group.maintenanceMessage());
        dto.put("maintenanceBypass", group.maintenanceBypass());
        dto.put("updateStrategy", group.updateStrategy());
        dto.put("nodeAffinity", group.nodeAffinity());
        dto.put("nodeAntiAffinity", group.nodeAntiAffinity());
        dto.put("spreadConstraint", group.spreadConstraint());
        dto.put("priority", group.priority());
        dto.put("memoryMb", group.memoryMb());
        dto.put("cpuReservation", group.cpuReservation());
        dto.put("diskReservationMb", group.diskReservationMb());
        dto.put("jvmArgs", group.jvmArgs());
        dto.put("env", group.env());
        dto.put("motds", group.motds());
        dto.put("motdMode", group.motdMode());
        dto.put("motdIntervalSeconds", group.motdIntervalSeconds());
        dto.put("attachedModules", group.attachedModules());
        dto.put("enabledModules", group.enabledModules());
        dto.put("disabledModules", group.disabledModules());
        dto.put("attachedExtensions", group.attachedExtensions());
        dto.put("enabledExtensions", group.enabledExtensions());
        dto.put("disabledExtensions", group.disabledExtensions());
        dto.put("configPatches", group.configPatches());
        dto.put("bedrockProxyGroup", group.bedrockProxyGroup());
        dto.put("runningInstances", instances.size());
        dto.put("totalPlayers", totalPlayers);
        return dto;
    }
}
