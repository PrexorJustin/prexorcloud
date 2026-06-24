package me.prexorjustin.prexorcloud.controller.group.spec;

import java.util.LinkedHashMap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import me.prexorjustin.prexorcloud.controller.group.GroupConfig;

/**
 * Resolves a v2 {@link GroupSpec} down to the legacy {@link GroupConfig} that the composition planner
 * and scheduler consume today (Group/Template v2, Phase 0). This is the seam that keeps the proven
 * engine -- and the {@code planHash} -- untouched while the authoring model moves to v2.
 *
 * <p>It maps via a field-name {@code Map} + {@code ObjectMapper.convertValue}, the same proven pattern
 * as {@code MongoGroupStore}: self-documenting by key name, and it naturally drops the dead
 * {@code @JsonIgnore} GroupConfig fields. v2-only inputs that today's engine cannot act on yet --
 * scaling {@code signals}/{@code aggregation}/{@code step}/{@code warmPool}/{@code predictive}, the
 * typed {@code variables}, per-layer {@code pinnedHash}/{@code vars}, and {@code lifecycle.drainOnShutdown}
 * (still {@code @JsonIgnore} on GroupConfig) -- are intentionally not carried; they take effect only
 * when Phases 1/2/5 replace the relevant engines. The result is exactly today's behaviour.
 */
public final class GroupSpecAdapter {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private GroupSpecAdapter() {}

    public static GroupConfig toGroupConfig(GroupSpec spec) {
        var m = new LinkedHashMap<String, Object>();

        GroupSpec.Identity id = spec.identity();
        m.put("name", id.name());
        m.put("parent", id.parent());
        m.put("platform", id.platform());
        m.put("platformVersion", id.platformVersion());
        m.put("jarFile", id.jarFile());
        m.put("templates", spec.templates().stream().map(GroupSpec.TemplateLayerRef::name).toList());

        GroupSpec.ScalingPolicy s = spec.scaling();
        m.put("scalingMode", s.mode() == null ? null : s.mode().name());
        m.put("minInstances", s.min());
        m.put("maxInstances", s.max());
        m.put("maxPlayers", s.maxPlayers());
        m.put("scaleUpThreshold", s.targetUtilization());
        m.put("scaleDownAfterSeconds", s.scaleDownAfterSeconds());
        m.put("scaleCooldownSeconds", s.cooldownSeconds());

        GroupSpec.Placement p = spec.placement();
        m.put("portRangeStart", p.portRangeStart());
        m.put("portRangeEnd", p.portRangeEnd());
        m.put("nodeAffinity", p.affinity());
        m.put("nodeAntiAffinity", p.antiAffinity());
        m.put("spreadConstraint", p.spreadConstraint());
        m.put("priority", p.priority());

        GroupSpec.Lifecycle lc = spec.lifecycle();
        m.put("startupTimeoutSeconds", lc.startupTimeoutSeconds());
        m.put("shutdownGraceSeconds", lc.shutdownGraceSeconds());
        m.put("maxLifetimeSeconds", lc.maxLifetimeSeconds());

        GroupSpec.Persistence pe = spec.persistence();
        m.put("static", pe.enabled());
        m.put("staticInstanceNames", pe.instanceNames());
        m.put("protectedPaths", pe.protectedPaths());

        GroupSpec.Ops ops = spec.ops();
        m.put("fallbackGroup", ops.fallbackGroup());
        m.put("defaultGroup", ops.defaultGroup());
        m.put("dependsOn", ops.dependsOn());
        m.put("startupWeight", ops.startupWeight());
        m.put("maintenance", ops.maintenance());
        m.put("maintenanceMessage", ops.maintenanceMessage());
        m.put("maintenanceBypass", ops.maintenanceBypass());
        m.put("motds", ops.motds());
        m.put("motdMode", ops.motdMode());
        m.put("motdIntervalSeconds", ops.motdIntervalSeconds());

        GroupSpec.Rollout ro = spec.rollout();
        m.put("updateStrategy", ro.strategy() == null ? null : ro.strategy().name());

        GroupSpec.Resources r = spec.resources();
        m.put("memoryMb", r.memoryMb());
        m.put("cpuReservation", r.cpuReservation());
        m.put("diskReservationMb", r.diskReservationMb());
        m.put("jvmArgs", r.jvmArgs());
        m.put("env", r.env());

        GroupSpec.ModulePolicy mod = spec.modules();
        m.put("attachedModules", mod.attachedModules());
        m.put("enabledModules", mod.enabledModules());
        m.put("disabledModules", mod.disabledModules());
        m.put("attachedExtensions", mod.attachedExtensions());
        m.put("enabledExtensions", mod.enabledExtensions());
        m.put("disabledExtensions", mod.disabledExtensions());
        m.put("configPatches", mod.configPatches());

        m.put("bedrockProxyGroup", spec.bedrockProxyGroup());

        return MAPPER.convertValue(m, GroupConfig.class);
    }
}
