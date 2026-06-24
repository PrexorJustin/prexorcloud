package me.prexorjustin.prexorcloud.controller.group.spec;

import java.util.List;
import java.util.Map;

/**
 * GroupSpec v2 -- the proposed authoring contract that replaces the flat 52-component positional
 * {@code GroupConfig} record. This is a <b>proposal in code</b> for review (Group/Template v2,
 * Phase 0); nothing consumes it yet.
 *
 * <p>Design rules, so the shape stays groundable and the migration stays mechanical:
 * <ul>
 *   <li><b>Every field maps to an existing {@code GroupConfig} field.</b> The only new shape is the
 *       grouping into nested policies and the additive scaling fields (signals / aggregation /
 *       warm pool / predictive) that Phase 1 fills in. No field is invented without a home.</li>
 *   <li><b>The five dead {@code @JsonIgnore} fields are dropped</b> (predictiveScaling, scaleUpMargin,
 *       burstCeiling, routing) -- except {@code drainOnShutdown}, which returns as a real
 *       {@link Lifecycle} field.</li>
 *   <li><b>v2 resolves down to the legacy planner input</b> via a separate, separately-verified
 *       adapter ({@code GroupSpec#toGroupConfig()}, next step), so the frozen
 *       {@code InstanceCompositionPlanner.plan()} / {@code planHash} seam is untouched.</li>
 *   <li><b>Controller-internal for now.</b> The public {@code cloud-api} contract
 *       ({@code GroupManager}/{@code GroupCreateRequest}/{@code GroupUpdateRequest}/{@code GroupView})
 *       evolves additively and separately; this record does not become public API by being written here.</li>
 * </ul>
 *
 * <p>Open shape questions worth a human call before the adapter is wired (sensible defaults chosen here):
 * nesting depth (moderate, one level); whether {@code STATIC} scaling mode and {@link Persistence#enabled()}
 * stay separate (kept separate to preserve today's two distinct knobs); and whether this should later
 * surface as the public authoring API (deferred).
 */
public record GroupSpec(
        Identity identity,
        List<TemplateLayerRef> templates,
        ScalingPolicy scaling,
        Placement placement,
        Resources resources,
        Lifecycle lifecycle,
        Persistence persistence,
        Rollout rollout,
        Ops ops,
        ModulePolicy modules,
        List<VariableDef> variables,
        String bedrockProxyGroup) {

    /** Identity & runtime selection. Maps: name, parent, platform, platformVersion, jarFile. */
    public record Identity(String name, String parent, String platform, String platformVersion, String jarFile) {}

    /**
     * One user template layer, applied after the group layer in list order. {@code pinnedHash} lets a
     * group pin a specific template version (additive over today's name-only {@code templates} list);
     * {@code vars} are attach-time variable overrides for this layer (Phase 2). Today's behaviour =
     * {@code pinnedHash} null + {@code vars} empty.
     */
    public record TemplateLayerRef(String name, String pinnedHash, Map<String, String> vars) {}

    /**
     * Scaling policy. Maps the live fields (mode/min/max/maxPlayers/scaleUpThreshold→targetUtilization/
     * scaleDownAfterSeconds/scaleCooldownSeconds) and adds the Phase-1 surface: a pluggable signal list,
     * an aggregation policy (replacing today's implicit "every instance saturated"), a {@code step}
     * (scale-by-N, today fixed at 1), a warm pool, and optional predictive scaling.
     */
    public record ScalingPolicy(
            ScalingMode mode,
            int min,
            int max,
            int maxPlayers,
            List<ScalingSignalSpec> signals,
            Aggregation aggregation,
            double targetUtilization,
            int step,
            int scaleDownAfterSeconds,
            int cooldownSeconds,
            WarmPool warmPool,
            Predictive predictive) {

        /** Keep {@code minPrepared} instances PREPARED-but-not-serving for instant join. New in v2. */
        public record WarmPool(int minPrepared, int ttlSeconds) {}

        /** Optional forecast-driven pre-scale. New in v2; {@code enabled=false} = today's reactive-only. */
        public record Predictive(boolean enabled, String model, int lookaheadSeconds) {}
    }

    /** One scaling input. {@code customKey} is only read for {@link SignalKind#CUSTOM} (a module-provided signal). */
    public record ScalingSignalSpec(SignalKind kind, String customKey) {}

    public enum SignalKind { PLAYER_LOAD, TPS, CPU, MEMORY, CUSTOM }

    /** How per-instance signal utilisations combine into one scale decision. Today's behaviour ≈ {@link #ALL}. */
    public enum Aggregation { ALL, ANY, AVG, PERCENTILE }

    public enum ScalingMode { STATIC, DYNAMIC, MANUAL }

    /** Placement constraints + port range. Maps: nodeAffinity, nodeAntiAffinity, spreadConstraint, priority, portRange*. */
    public record Placement(
            List<String> affinity,
            List<String> antiAffinity,
            String spreadConstraint,
            int priority,
            int portRangeStart,
            int portRangeEnd) {}

    /** Resource reservations. Maps: memoryMb, cpuReservation, diskReservationMb, jvmArgs, env. */
    public record Resources(
            int memoryMb,
            double cpuReservation,
            long diskReservationMb,
            List<String> jvmArgs,
            Map<String, String> env) {}

    /** Lifecycle timers. Maps: startupTimeoutSeconds, shutdownGraceSeconds, drainOnShutdown (revived), maxLifetimeSeconds. */
    public record Lifecycle(
            int startupTimeoutSeconds,
            int shutdownGraceSeconds,
            boolean drainOnShutdown,
            int maxLifetimeSeconds) {}

    /** Persistent/static instances. Maps: isStatic, staticInstanceNames, protectedPaths. Kept distinct from {@link ScalingMode#STATIC}. */
    public record Persistence(boolean enabled, List<String> instanceNames, List<String> protectedPaths) {}

    /** Deployment/rollout. Maps: updateStrategy, plus the Phase-5 surface (waveSize, healthGate, autoRollback). */
    public record Rollout(UpdateStrategy strategy, int waveSize, boolean healthGate, boolean autoRollback) {}

    public enum UpdateStrategy { ROLLING, CANARY, RECREATE }

    /** Operations + orchestration + MOTD. Maps: maintenance*, fallbackGroup, defaultGroup, dependsOn, startupWeight, motd*. */
    public record Ops(
            boolean maintenance,
            String maintenanceMessage,
            List<String> maintenanceBypass,
            String fallbackGroup,
            boolean defaultGroup,
            List<String> dependsOn,
            int startupWeight,
            List<String> motds,
            String motdMode,
            int motdIntervalSeconds) {}

    /** Module/extension policy. Maps the six attach/enable/disable lists + configPatches verbatim. */
    public record ModulePolicy(
            List<String> attachedModules,
            List<String> enabledModules,
            List<String> disabledModules,
            List<String> attachedExtensions,
            List<String> enabledExtensions,
            List<String> disabledExtensions,
            Map<String, Map<String, String>> configPatches) {}
}
